package com.intermarche.fidelity.earn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link ValuationResponse}: the DTO default wiring and the sole
 * branchy accessor {@link ValuationResponse.Advantage#isDiscount()}, whose three-legged
 * {@code &&} guard discriminates a true discount from a null/zero one (§22.2, §31.2).
 */
class ValuationResponseTest {

    /**
     * A freshly constructed response wires its collections to empty non-null lists and
     * leaves its nullable fields null (§31.2).
     */
    @Test
    @DisplayName("ctor: collections non-null and empty, nullable fields null")
    void constructorInitializesDefaults() {
        ValuationResponse response = new ValuationResponse();
        assertNotNull(response.offers);
        assertTrue(response.offers.isEmpty());
        assertNotNull(response.advantages);
        assertTrue(response.advantages.isEmpty());
        assertNotNull(response.vatBreakdown);
        assertTrue(response.vatBreakdown.isEmpty());
        assertNull(response.totalPrice);
        assertNull(response.availableToUpcell);
    }

    /**
     * A freshly constructed offer wires its items to an empty non-null list and leaves its
     * descriptive fields null (§22.1, §31.2).
     */
    @Test
    @DisplayName("Offer ctor: items non-null and empty")
    void offerInitializesDefaults() {
        ValuationResponse.Offer offer = new ValuationResponse.Offer();
        assertNotNull(offer.items);
        assertTrue(offer.items.isEmpty());
        assertNull(offer.type);
        assertNull(offer.amount);
    }

    /**
     * A freshly constructed offer item leaves all its fields null (§22.1).
     */
    @Test
    @DisplayName("OfferItem ctor: fields null")
    void offerItemInitializesDefaults() {
        ValuationResponse.OfferItem item = new ValuationResponse.OfferItem();
        assertNull(item.lineId);
        assertNull(item.produceEan);
        assertNull(item.quantity);
        assertNull(item.amount);
    }

    /**
     * A freshly constructed VAT breakdown row leaves all its amounts null (§22.2).
     */
    @Test
    @DisplayName("VatBreakdown ctor: fields null")
    void vatBreakdownInitializesDefaults() {
        ValuationResponse.VatBreakdown row = new ValuationResponse.VatBreakdown();
        assertNull(row.vatRate);
        assertNull(row.amountExcludingTax);
        assertNull(row.vatAmount);
        assertNull(row.amountIncludingTax);
    }

    /**
     * First leg false: a null {@code discountAmount} is not a discount — short-circuits
     * before the amount is read (§22.2).
     */
    @Test
    @DisplayName("isDiscount(): null discountAmount is not a discount")
    void isDiscountNullDiscountAmount() {
        ValuationResponse.Advantage advantage = new ValuationResponse.Advantage();
        advantage.discountAmount = null;
        assertFalse(advantage.isDiscount());
    }

    /**
     * Second leg false: a discount block whose including-tax amount is null is not a
     * discount (§22.2, §31.2).
     */
    @Test
    @DisplayName("isDiscount(): null amountIncludingTax is not a discount")
    void isDiscountNullAmountIncludingTax() {
        ValuationResponse.Advantage advantage = new ValuationResponse.Advantage();
        advantage.discountAmount = new AmountEvaluation();
        advantage.discountAmount.amountIncludingTax = null;
        assertFalse(advantage.isDiscount());
    }

    /**
     * Third leg false: a present but zero including-tax amount is not a discount — signum
     * zero fails the last leg (§22.2).
     */
    @Test
    @DisplayName("isDiscount(): zero amountIncludingTax is not a discount")
    void isDiscountZeroAmountIncludingTax() {
        ValuationResponse.Advantage advantage = new ValuationResponse.Advantage();
        advantage.discountAmount = new AmountEvaluation();
        advantage.discountAmount.amountIncludingTax = BigDecimal.ZERO;
        assertFalse(advantage.isDiscount());
    }

    /**
     * All legs true: a present, positive including-tax amount marks a true discount
     * (§22.1, §22.2).
     */
    @Test
    @DisplayName("isDiscount(): positive amountIncludingTax is a discount")
    void isDiscountPositiveAmountIncludingTax() {
        ValuationResponse.Advantage advantage = new ValuationResponse.Advantage();
        advantage.discountAmount = new AmountEvaluation();
        advantage.discountAmount.amountIncludingTax = new BigDecimal("2.50");
        assertTrue(advantage.isDiscount());
    }

    /**
     * All legs true with a negative signum: a non-zero including-tax amount still marks a
     * discount — {@code signum() != 0} accepts either sign (§22.2).
     */
    @Test
    @DisplayName("isDiscount(): negative amountIncludingTax is a discount")
    void isDiscountNegativeAmountIncludingTax() {
        ValuationResponse.Advantage advantage = new ValuationResponse.Advantage();
        advantage.discountAmount = new AmountEvaluation();
        advantage.discountAmount.amountIncludingTax = new BigDecimal("-2.50");
        assertTrue(advantage.isDiscount());
    }

    /**
     * A freshly constructed advantage leaves its discriminant fields null so it reads as a
     * non-discount by default (§22.2).
     */
    @Test
    @DisplayName("Advantage ctor: discriminant fields null, not a discount")
    void advantageInitializesDefaults() {
        ValuationResponse.Advantage advantage = new ValuationResponse.Advantage();
        assertNull(advantage.type);
        assertNull(advantage.offer);
        assertNull(advantage.discountAmount);
        assertNull(advantage.suggestion);
        assertNull(advantage.offerCode);
        assertFalse(advantage.isDiscount());
    }
}
