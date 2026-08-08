package com.intermarche.fidelity.earn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.rule.ValuedLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit campaign for {@link ValuationReader} (§22.1, §25.2, §25.4). The reader is
 * pure computation over the {@code /valuation} DTO — no clock, so nothing goes through
 * {@link com.intermarche.fidelity.domain.DateTimeProvider} — and its only collaborator is
 * the static finder {@link Product#findByEan(String)}, mocked with {@code mockStatic} in
 * try-with-resources per the imfid bench. Baskets are built in memory and the arithmetic
 * is asserted to the centime; every {@link BigDecimal} is compared by {@code compareTo}.
 */
class ValuationReaderTest {

    /**
     * The class under test; stateless, so a fresh instance per call is enough.
     */
    private final ValuationReader reader = new ValuationReader();

    /**
     * Builds an {@link AmountEvaluation} from HT and TTC decimal strings.
     *
     * @param ht  The excluding-tax amount.
     * @param ttc The including-tax amount.
     * @return The amount block.
     */
    private static AmountEvaluation amount(String ht, String ttc) {
        AmountEvaluation a = new AmountEvaluation();
        a.amountExcludingTax = new BigDecimal(ht);
        a.amountIncludingTax = new BigDecimal(ttc);
        return a;
    }

    /**
     * Builds an offer item (one offer tranche of a basket line).
     *
     * @param lineId   The original basket line id.
     * @param ean      The tranche EAN, or null.
     * @param quantity The tranche quantity, or null.
     * @param amt      The tranche amount, or null.
     * @return The offer item.
     */
    private static ValuationResponse.OfferItem item(String lineId, String ean, Double quantity,
            AmountEvaluation amt) {
        ValuationResponse.OfferItem i = new ValuationResponse.OfferItem();
        i.lineId = lineId;
        i.produceEan = ean;
        i.quantity = quantity;
        i.amount = amt;
        return i;
    }

    /**
     * Builds an offer with a mutable item list (accepting null elements).
     *
     * @param type  The offer type discriminant.
     * @param amt   The offer amount, or null.
     * @param items The offer items.
     * @return The offer.
     */
    private static ValuationResponse.Offer offer(String type, AmountEvaluation amt,
            ValuationResponse.OfferItem... items) {
        ValuationResponse.Offer o = new ValuationResponse.Offer();
        o.type = type;
        o.amount = amt;
        o.items = new ArrayList<>(Arrays.asList(items));
        return o;
    }

    /**
     * Builds a discount advantage targeting an offer type.
     *
     * @param targetOffer The targeted offer type.
     * @param disc        The discount amount (positive).
     * @return The advantage.
     */
    private static ValuationResponse.Advantage discount(String targetOffer, AmountEvaluation disc) {
        ValuationResponse.Advantage a = new ValuationResponse.Advantage();
        a.type = "DISCOUNT";
        a.offer = targetOffer;
        a.discountAmount = disc;
        return a;
    }

    /**
     * Assembles a valuation response from its total, offers and advantages.
     *
     * @param total      The total price, or null.
     * @param offers     The offers (mutable list, may hold nulls).
     * @param advantages The advantages (mutable list, may hold nulls).
     * @return The response.
     */
    private static ValuationResponse response(AmountEvaluation total,
            List<ValuationResponse.Offer> offers, List<ValuationResponse.Advantage> advantages) {
        ValuationResponse r = new ValuationResponse();
        r.totalPrice = total;
        r.offers = offers;
        r.advantages = advantages;
        return r;
    }

    /**
     * Finds a valued line by its line id in a reading.
     *
     * @param reading The reading.
     * @param lineId  The line id.
     * @return The valued line, or null when absent.
     */
    private static ValuedLine line(ValuationReading reading, String lineId) {
        for (ValuedLine l : reading.lines) {
            if (l.lineId.equals(lineId)) {
                return l;
            }
        }
        return null;
    }

    /**
     * A null response is rejected with a reconciliation exception (response==null arm).
     */
    @Test
    @DisplayName("read(null) throws a reconciliation exception")
    void readNullResponseThrows() {
        assertThrows(ValuationReconciliationException.class, () -> reader.read(null));
    }

    /**
     * A single Standard offer with a known-EAN line and a null-EAN line reads cleanly:
     * net kept, not consumed, no warning — the guard both-false arms, the items non-null
     * ternary arm, verify pass, consuming false, totalPrice non-null arm, ean==null arm,
     * ean-not-null/unseen arm with a known EAN, and every non-null addTranche arm.
     */
    @Test
    @DisplayName("read: a Standard offer keeps net, no warning for a known EAN")
    void readsSingleStandardOfferKnownAndNullEan() {
        ValuationResponse.Offer o = offer("Standard: catalogue", amount("16.00", "20.00"),
                item("L1", "111", 1.0, amount("12.00", "15.00")),
                item("L2", null, 2.0, amount("4.00", "5.00")));
        ValuationResponse resp = response(amount("16.00", "20.00"), new ArrayList<>(List.of(o)),
                new ArrayList<>());
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("111")).thenReturn(new Product());
            ValuationReading reading = reader.read(resp);
            assertEquals(2, reading.lines.size());
            assertEquals(0, new BigDecimal("15.00").compareTo(line(reading, "L1").netAmountIncludingTax));
            assertEquals(0, new BigDecimal("12.00").compareTo(line(reading, "L1").netAmountExcludingTax));
            assertEquals(0, BigDecimal.ONE.compareTo(line(reading, "L1").quantity));
            assertEquals("111", line(reading, "L1").ean);
            assertNull(line(reading, "L2").ean);
            assertFalse(line(reading, "L1").consumedByCommercialOffer);
            assertTrue(reading.warnings.isEmpty());
            assertEquals(0, new BigDecimal("20.00").compareTo(reading.totalPriceTtc));
        }
    }

    /**
     * A null offer and an offer with a null amount are both skipped (offer==null leg and
     * offer.amount==null leg); only the valid offer feeds the reading.
     */
    @Test
    @DisplayName("read: null offer and null-amount offer are skipped")
    void skipsNullOfferAndNullAmountOffer() {
        ValuationResponse.Offer noAmount = offer("Standard: x", null,
                item("LX", null, 1.0, amount("1.00", "1.00")));
        ValuationResponse.Offer valid = offer("Standard: y", amount("8.00", "10.00"),
                item("L1", null, 1.0, amount("8.00", "10.00")));
        ValuationResponse resp = response(amount("8.00", "10.00"),
                new ArrayList<>(Arrays.asList(null, noAmount, valid)), new ArrayList<>());
        ValuationReading reading = reader.read(resp);
        assertEquals(1, reading.lines.size());
        assertEquals(0, new BigDecimal("10.00").compareTo(line(reading, "L1").netAmountIncludingTax));
    }

    /**
     * A non-Standard offer (bundle) consumes its lines (consuming true arm; isStandard
     * type-not-null-but-not-prefixed false leg).
     */
    @Test
    @DisplayName("read: a non-Standard offer consumes its lines")
    void nonStandardOfferConsumesLines() {
        ValuationResponse.Offer bundle = offer("Mixed Bundle Promo: 2+1", amount("8.00", "10.00"),
                item("L1", null, 1.0, amount("8.00", "10.00")));
        ValuationResponse resp = response(amount("8.00", "10.00"), new ArrayList<>(List.of(bundle)),
                new ArrayList<>());
        ValuationReading reading = reader.read(resp);
        assertTrue(line(reading, "L1").consumedByCommercialOffer);
    }

    /**
     * A null offer type is not Standard, so the line is consumed (isStandard type==null
     * leg reaching the consuming true arm).
     */
    @Test
    @DisplayName("read: a null-type offer is not Standard and consumes its line")
    void nullTypeOfferIsNotStandard() {
        ValuationResponse.Offer o = offer(null, amount("8.00", "10.00"),
                item("L1", null, 1.0, amount("8.00", "10.00")));
        ValuationResponse resp = response(amount("8.00", "10.00"), new ArrayList<>(List.of(o)),
                new ArrayList<>());
        ValuationReading reading = reader.read(resp);
        assertTrue(line(reading, "L1").consumedByCommercialOffer);
    }

    /**
     * A null item and an item with a null line id are skipped inside an offer (item==null
     * leg and item.lineId==null leg); the valid item alone produces a line.
     */
    @Test
    @DisplayName("read: null item and null-lineId item are skipped")
    void skipsNullItemAndNullLineIdItem() {
        ValuationResponse.Offer o = offer("Standard: z", amount("12.00", "15.00"),
                null,
                item(null, null, 1.0, amount("4.00", "5.00")),
                item("L1", null, 1.0, amount("8.00", "10.00")));
        ValuationResponse resp = response(amount("12.00", "15.00"), new ArrayList<>(List.of(o)),
                new ArrayList<>());
        ValuationReading reading = reader.read(resp);
        assertEquals(1, reading.lines.size());
        assertEquals(0, new BigDecimal("10.00").compareTo(line(reading, "L1").netAmountIncludingTax));
    }

    /**
     * A null item list is read as empty, skipping the per-offer invariant (items==null
     * ternary arm and items.isEmpty true arm); the offer still counts in the totals.
     */
    @Test
    @DisplayName("read: null items list is treated as empty")
    void offerItemsNullTreatedAsEmpty() {
        ValuationResponse.Offer o = offer("Standard: threshold", amount("8.00", "10.00"));
        o.items = null;
        ValuationResponse resp = response(amount("8.00", "10.00"), new ArrayList<>(List.of(o)),
                new ArrayList<>());
        ValuationReading reading = reader.read(resp);
        assertTrue(reading.lines.isEmpty());
        assertEquals(0, new BigDecimal("10.00").compareTo(reading.totalPriceTtc));
    }

    /**
     * The per-offer invariant Σ items ≠ amount beyond the centime is rejected (verify
     * {@code >0} true arm).
     */
    @Test
    @DisplayName("read: per-offer Σ items ≠ amount throws")
    void verifyOfferInvariantViolationThrows() {
        ValuationResponse.Offer o = offer("Standard: bad", amount("8.00", "10.00"),
                item("L1", null, 1.0, amount("8.00", "10.50")));
        ValuationResponse resp = response(amount("8.00", "10.00"), new ArrayList<>(List.of(o)),
                new ArrayList<>());
        assertThrows(ValuationReconciliationException.class, () -> reader.read(resp));
    }

    /**
     * A per-offer discrepancy of exactly one centime is tolerated (verify {@code >0}
     * false boundary arm).
     */
    @Test
    @DisplayName("read: per-offer Σ items within one centime passes")
    void verifyOfferInvariantWithinCentimePasses() {
        ValuationResponse.Offer o = offer("Standard: tol", amount("8.00", "10.00"),
                item("L1", null, 1.0, amount("8.00", "10.01")));
        ValuationResponse resp = response(amount("8.00", "10.00"), new ArrayList<>(List.of(o)),
                new ArrayList<>());
        ValuationReading reading = reader.read(resp);
        assertEquals(0, new BigDecimal("10.01").compareTo(line(reading, "L1").netAmountIncludingTax));
    }

    /**
     * A global total incoherent by more than a centime is rejected (global reconciliation
     * {@code >0} true arm).
     */
    @Test
    @DisplayName("read: incoherent global total throws")
    void globalReconciliationMismatchThrows() {
        ValuationResponse.Offer o = offer("Standard: g", amount("8.00", "10.00"),
                item("L1", null, 1.0, amount("8.00", "10.00")));
        ValuationResponse resp = response(amount("8.00", "10.05"), new ArrayList<>(List.of(o)),
                new ArrayList<>());
        assertThrows(ValuationReconciliationException.class, () -> reader.read(resp));
    }

    /**
     * A global total off by exactly one centime is tolerated (global reconciliation
     * {@code >0} false boundary arm).
     */
    @Test
    @DisplayName("read: global total within one centime passes")
    void globalReconciliationWithinCentimePasses() {
        ValuationResponse.Offer o = offer("Standard: gb", amount("8.00", "10.00"),
                item("L1", null, 1.0, amount("8.00", "10.00")));
        ValuationResponse resp = response(amount("8.00", "10.01"), new ArrayList<>(List.of(o)),
                new ArrayList<>());
        ValuationReading reading = reader.read(resp);
        assertEquals(0, new BigDecimal("10.01").compareTo(reading.totalPriceTtc));
    }

    /**
     * A null total price is read as zero (totalPrice==null ternary arm); an empty basket
     * reconciles to zero.
     */
    @Test
    @DisplayName("read: null total price is read as zero")
    void nullTotalPriceTreatedAsZero() {
        ValuationResponse resp = response(null, new ArrayList<>(), new ArrayList<>());
        ValuationReading reading = reader.read(resp);
        assertTrue(reading.lines.isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(reading.totalPriceTtc));
    }

    /**
     * A discount is re-allocated pro-rata across the targeted lines with a last-line
     * remainder, marking them consumed; a null advantage and a non-discount advantage are
     * skipped first (advantage==null leg, !isDiscount leg, real-discount both-false arm,
     * non-zero prorata denominator, item.amount non-null arms, i≠last and i==last arms).
     */
    @Test
    @DisplayName("read: a discount is re-allocated pro-rata with a remainder")
    void discountReallocatedProRataAcrossLines() {
        ValuationResponse.Offer o = offer("Standard: A", amount("25.00", "30.00"),
                null,
                item(null, null, 1.0, null),
                item("L1", null, 1.0, amount("16.67", "20.00")),
                item("L2", null, 1.0, amount("8.33", "10.00")));
        ValuationResponse.Advantage mealVoucher = new ValuationResponse.Advantage();
        mealVoucher.type = "MEAL_VOUCHER";
        List<ValuationResponse.Advantage> advantages = new ArrayList<>(
                Arrays.asList(null, mealVoucher, discount("Standard: A", amount("5.00", "6.00"))));
        ValuationResponse resp = response(amount("20.00", "24.00"), new ArrayList<>(List.of(o)),
                advantages);
        ValuationReading reading = reader.read(resp);
        assertEquals(0, new BigDecimal("16.00").compareTo(line(reading, "L1").netAmountIncludingTax));
        assertEquals(0, new BigDecimal("13.34").compareTo(line(reading, "L1").netAmountExcludingTax));
        assertEquals(0, new BigDecimal("8.00").compareTo(line(reading, "L2").netAmountIncludingTax));
        assertEquals(0, new BigDecimal("6.66").compareTo(line(reading, "L2").netAmountExcludingTax));
        assertTrue(line(reading, "L1").consumedByCommercialOffer);
        assertTrue(line(reading, "L2").consumedByCommercialOffer);
    }

    /**
     * A discount whose target carries no line re-allocates to nothing (targets empty
     * arm), exercising the target-collection legs: a null offer, a null-type offer, a
     * non-matching offer, and the matching offer whose items list is null.
     */
    @Test
    @DisplayName("read: a discount targeting a line-less offer is a no-op")
    void discountTargetsOfferWithoutLinesNoOp() {
        ValuationResponse.Offer nullType = offer(null, amount("0.00", "0.00"));
        ValuationResponse.Offer standardA = offer("Standard: A", amount("16.00", "20.00"),
                item("L1", null, 1.0, amount("16.00", "20.00")));
        ValuationResponse.Offer deliveryZ = offer("Delivery: Z", amount("4.00", "5.00"));
        deliveryZ.items = null;
        List<ValuationResponse.Offer> offers = new ArrayList<>(
                Arrays.asList(null, nullType, standardA, deliveryZ));
        ValuationResponse resp = response(amount("16.00", "20.00"), offers,
                new ArrayList<>(List.of(discount("Delivery: Z", amount("4.00", "5.00")))));
        ValuationReading reading = reader.read(resp);
        assertEquals(1, reading.lines.size());
        assertFalse(line(reading, "L1").consumedByCommercialOffer);
        assertEquals(0, new BigDecimal("20.00").compareTo(line(reading, "L1").netAmountIncludingTax));
    }

    /**
     * A discount targeting an offer whose amount was null (thus dropped from the basket)
     * finds no accumulator for its line and skips it (acc==null continue arm); the other
     * line is left intact.
     */
    @Test
    @DisplayName("read: a discount on a dropped offer's line finds no accumulator")
    void discountTargetLineNotInBasket() {
        ValuationResponse.Offer standardA = offer("Standard: A", amount("16.00", "20.00"),
                item("L1", null, 1.0, amount("16.00", "20.00")));
        ValuationResponse.Offer standardB = offer("Standard: B", null,
                item("L2", null, 1.0, amount("8.00", "10.00")));
        List<ValuationResponse.Offer> offers = new ArrayList<>(Arrays.asList(standardA, standardB));
        ValuationResponse resp = response(amount("13.00", "16.00"), offers,
                new ArrayList<>(List.of(discount("Standard: B", amount("3.00", "4.00")))));
        ValuationReading reading = reader.read(resp);
        assertEquals(1, reading.lines.size());
        assertFalse(line(reading, "L1").consumedByCommercialOffer);
        assertEquals(0, new BigDecimal("20.00").compareTo(line(reading, "L1").netAmountIncludingTax));
    }

    /**
     * A pro-rata over a zero total splits every non-last share to zero and hands the whole
     * discount to the last line (prorata total.signum()==0 zero-denominator leg, the
     * item.amount==null ternary arm and the item.amount non-null accumulation arm).
     */
    @Test
    @DisplayName("read: a discount over a zero-valued target lands entirely on the last line")
    void discountZeroDenominatorProRata() {
        ValuationResponse.Offer o = offer("Standard: Z", amount("0.00", "0.00"),
                item("L1", null, 1.0, null),
                item("L2", null, 1.0, amount("0.00", "0.00")));
        ValuationResponse resp = response(amount("-3.00", "-4.00"), new ArrayList<>(List.of(o)),
                new ArrayList<>(List.of(discount("Standard: Z", amount("3.00", "4.00")))));
        ValuationReading reading = reader.read(resp);
        assertEquals(0, new BigDecimal("0.00").compareTo(line(reading, "L1").netAmountIncludingTax));
        assertEquals(0, new BigDecimal("-4.00").compareTo(line(reading, "L2").netAmountIncludingTax));
        assertEquals(0, new BigDecimal("-3.00").compareTo(line(reading, "L2").netAmountExcludingTax));
        assertTrue(line(reading, "L1").consumedByCommercialOffer);
        assertTrue(line(reading, "L2").consumedByCommercialOffer);
    }

    /**
     * An EAN unknown to the reference raises exactly one warning even when repeated across
     * lines (known false arm, !known true arm, ean-not-null/unseen enter arm, and the
     * ean-not-null/already-seen skip arm on the second line).
     */
    @Test
    @DisplayName("read: an unknown EAN warns once even when repeated")
    void unknownEanRaisesWarningOnce() {
        ValuationResponse.Offer o = offer("Standard: u", amount("16.00", "20.00"),
                item("L1", "999", 1.0, amount("8.00", "10.00")),
                item("L2", "999", 1.0, amount("8.00", "10.00")));
        ValuationResponse resp = response(amount("16.00", "20.00"), new ArrayList<>(List.of(o)),
                new ArrayList<>());
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan(ArgumentMatchers.anyString())).thenReturn(null);
            ValuationReading reading = reader.read(resp);
            assertEquals(1, reading.warnings.size());
            assertEquals(WarningCode.UNKNOWN_EAN.name(), reading.warnings.get(0).code);
            assertEquals("999", reading.warnings.get(0).ean);
        }
    }

    /**
     * A tranche with null amount, quantity and EAN contributes nothing yet is folded in
     * beside a full tranche on the same line (addTranche amount/produceEan/quantity null
     * arms alongside their non-null arms, plus the verify item.amount==null leg).
     */
    @Test
    @DisplayName("read: null tranche fields fold in without effect")
    void accumulatorHandlesNullTrancheFields() {
        ValuationResponse.Offer o = offer("Standard: acc", amount("8.00", "10.00"),
                item("L1", null, null, null),
                item("L1", null, 2.0, amount("8.00", "10.00")));
        ValuationResponse resp = response(amount("8.00", "10.00"), new ArrayList<>(List.of(o)),
                new ArrayList<>());
        ValuationReading reading = reader.read(resp);
        assertEquals(1, reading.lines.size());
        assertEquals(0, new BigDecimal("10.00").compareTo(line(reading, "L1").netAmountIncludingTax));
        assertEquals(0, new BigDecimal("2").compareTo(line(reading, "L1").quantity));
        assertNull(line(reading, "L1").ean);
    }
}
