package com.intermarche.fidelity.earn;

import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.rule.ValuedLine;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a {@code /valuation} response into the valued lines the earn engine consumes
 * (§15, §22) — the connector between imvaluation's output and imfid's rule model.
 * <p>
 * It reconstitutes the net amount of each basket line from {@code offers[].items[]}
 * grouped by {@code lineId}, less the discounts re-allocated pro-rata through the
 * {@code offer} chain (§22.1); it carries I2 as the {@code consumedByCommercialOffer}
 * predicate — a line absorbed by a non-Standard offer (bundle, N+M, manual gesture)
 * or targeted by a discount is out of every earn assiette (§22.1). Delivery and
 * Deposit Basket offers (empty items) and the MEAL_VOUCHER / upsell advantages are
 * ignored (§22.1). It verifies the reconciliation invariants before producing a
 * reading (§22.1, §25.2) and warns on an EAN unknown to the imfid reference (§25.4).
 * <p>
 * A read has no side effect: {@code /earn} is a read (§30.2).
 */
@ApplicationScoped
public class ValuationReader {

    private static final Logger LOGGER = Logger.getLogger(ValuationReader.class);

    /**
     * The centime tolerance of the reconciliation invariants (§22.1).
     */
    private static final BigDecimal CENTIME = new BigDecimal("0.01");

    /**
     * Reads the couple into a {@link ValuationReading}, enforcing the reconciliation
     * invariants of §22.1 (§25.2).
     *
     * @param response The {@code /valuation} response; must not be null.
     * @return The reading (valued lines, total price, warnings), never null.
     * @throws ValuationReconciliationException when an invariant of §22.1 is violated.
     */
    public ValuationReading read(ValuationResponse response) {
        if (response == null) {
            throw new ValuationReconciliationException("Missing valuation response");
        }
        Map<String, LineAccumulator> byLine = new LinkedHashMap<>();
        BigDecimal offersTtc = BigDecimal.ZERO;

        // 1. Accumulate the offer tranches per line and check the per-offer invariant.
        for (ValuationResponse.Offer offer : response.offers) {
            if (offer == null || offer.amount == null) {
                continue;
            }
            offersTtc = offersTtc.add(offer.amount.ttc());
            boolean consuming = !isStandard(offer.type);
            List<ValuationResponse.OfferItem> items = offer.items != null ? offer.items : List.of();
            if (!items.isEmpty()) {
                verifyOfferInvariant(offer, items);
            }
            for (ValuationResponse.OfferItem item : items) {
                if (item == null || item.lineId == null) {
                    continue;
                }
                LineAccumulator acc = byLine.computeIfAbsent(item.lineId, LineAccumulator::new);
                acc.addTranche(item);
                if (consuming) {
                    acc.consumed = true;
                }
            }
        }

        // 2. Re-allocate discounts to the lines of their targeted offer (§22.1).
        BigDecimal discountsTtc = BigDecimal.ZERO;
        for (ValuationResponse.Advantage advantage : response.advantages) {
            if (advantage == null || !advantage.isDiscount()) {
                continue;
            }
            discountsTtc = discountsTtc.add(advantage.discountAmount.ttc());
            reallocateDiscount(advantage, response.offers, byLine);
        }

        // 3. Global reconciliation: totalPrice = Σ offers − Σ discounts, at the centime.
        BigDecimal totalTtc = response.totalPrice != null ? response.totalPrice.ttc() : BigDecimal.ZERO;
        BigDecimal expected = offersTtc.subtract(discountsTtc).setScale(2, RoundingMode.HALF_UP);
        if (totalTtc.subtract(expected).abs().compareTo(CENTIME) > 0) {
            throw new ValuationReconciliationException("Incoherent totals: totalPrice " + totalTtc
                    + " ≠ Σoffers " + offersTtc + " − Σdiscounts " + discountsTtc + " = " + expected);
        }

        // 4. Materialize the valued lines and warn on unknown EANs (§25.4).
        List<ValuedLine> lines = new ArrayList<>(byLine.size());
        List<EarnResponse.Warning> warnings = new ArrayList<>();
        Map<String, Boolean> eanKnown = new LinkedHashMap<>();
        for (LineAccumulator acc : byLine.values()) {
            lines.add(acc.toValuedLine());
            if (acc.ean != null && !eanKnown.containsKey(acc.ean)) {
                boolean known = Product.findByEan(acc.ean) != null;
                eanKnown.put(acc.ean, known);
                if (!known) {
                    warnings.add(new EarnResponse.Warning(WarningCode.UNKNOWN_EAN.name(), acc.ean,
                            "EAN " + acc.ean + " unknown to the imfid product reference; line kept out of every "
                                    + "earn assiette but inside the burnable base (§25.4)"));
                }
            }
        }
        return new ValuationReading(lines, totalTtc, warnings);
    }

    /**
     * Verifies the per-offer invariant Σ items.TTC = amount.TTC at the centime (§22.1),
     * throwing a reconciliation exception on violation (§25.2).
     *
     * @param offer The offer.
     * @param items The offer items (non-empty).
     */
    private void verifyOfferInvariant(ValuationResponse.Offer offer, List<ValuationResponse.OfferItem> items) {
        BigDecimal sum = BigDecimal.ZERO;
        for (ValuationResponse.OfferItem item : items) {
            if (item != null && item.amount != null) {
                sum = sum.add(item.amount.ttc());
            }
        }
        BigDecimal amount = offer.amount.ttc();
        if (sum.subtract(amount).abs().compareTo(CENTIME) > 0) {
            throw new ValuationReconciliationException("Offer '" + offer.type + "': Σ items " + sum
                    + " ≠ amount " + amount + " (§22.1)");
        }
    }

    /**
     * Re-allocates a discount to the lines of the offer(s) it targets, pro-rata by the
     * tranche TTC amount, and marks those lines consumed (§22.1). A discount whose
     * targeted offer carries no line (Delivery threshold) re-allocates to nothing —
     * the line is not in any assiette anyway.
     *
     * @param advantage The discount advantage.
     * @param offers    The offers to resolve the target from.
     * @param byLine    The per-line accumulators to subtract from.
     */
    private void reallocateDiscount(ValuationResponse.Advantage advantage,
                                    List<ValuationResponse.Offer> offers,
                                    Map<String, LineAccumulator> byLine) {
        // Collect the tranches of every offer whose type matches the discount target.
        List<ValuationResponse.OfferItem> targets = new ArrayList<>();
        for (ValuationResponse.Offer offer : offers) {
            if (offer != null && offer.type != null && offer.type.equals(advantage.offer)) {
                if (offer.items != null) {
                    for (ValuationResponse.OfferItem item : offer.items) {
                        if (item != null && item.lineId != null) {
                            targets.add(item);
                        }
                    }
                }
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        BigDecimal totalTtc = BigDecimal.ZERO;
        BigDecimal totalHt = BigDecimal.ZERO;
        for (ValuationResponse.OfferItem item : targets) {
            if (item.amount != null) {
                totalTtc = totalTtc.add(item.amount.ttc());
                totalHt = totalHt.add(item.amount.ht());
            }
        }
        BigDecimal discountTtc = advantage.discountAmount.ttc();
        BigDecimal discountHt = advantage.discountAmount.ht();

        // Pro-rata split with a last-line remainder so the parts sum back exactly.
        BigDecimal allocatedTtc = BigDecimal.ZERO;
        BigDecimal allocatedHt = BigDecimal.ZERO;
        for (int i = 0; i < targets.size(); i++) {
            ValuationResponse.OfferItem item = targets.get(i);
            LineAccumulator acc = byLine.get(item.lineId);
            if (acc == null) {
                continue;
            }
            BigDecimal shareTtc;
            BigDecimal shareHt;
            if (i == targets.size() - 1) {
                shareTtc = discountTtc.subtract(allocatedTtc);
                shareHt = discountHt.subtract(allocatedHt);
            } else {
                shareTtc = prorata(item.amount != null ? item.amount.ttc() : BigDecimal.ZERO, totalTtc, discountTtc);
                shareHt = prorata(item.amount != null ? item.amount.ht() : BigDecimal.ZERO, totalHt, discountHt);
                allocatedTtc = allocatedTtc.add(shareTtc);
                allocatedHt = allocatedHt.add(shareHt);
            }
            acc.netTtc = acc.netTtc.subtract(shareTtc);
            acc.netHt = acc.netHt.subtract(shareHt);
            acc.consumed = true;
        }
    }

    /**
     * Computes a pro-rata share {@code weight / total * amount}, protected against a
     * null or zero total (§31.2), rounded to euro scale 2 HALF_UP.
     *
     * @param weight The line weight.
     * @param total  The total weight; a zero total yields zero.
     * @param amount The amount to split.
     * @return The share, euro at scale 2.
     */
    private static BigDecimal prorata(BigDecimal weight, BigDecimal total, BigDecimal amount) {
        if (weight == null || total == null || total.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return weight.multiply(amount).divide(total, 2, RoundingMode.HALF_UP);
    }

    /**
     * Indicates whether an offer type is a Standard (catalogue-tariff) offer — the
     * only offer family whose lines may earn (§22.1). A null type is not Standard.
     *
     * @param type The offer type string.
     * @return true when the type is a Standard offer.
     */
    private static boolean isStandard(String type) {
        return type != null && type.startsWith("Standard:");
    }

    /**
     * A mutable per-line accumulator gathering the net of a {@code lineId} across the
     * offer tranches, then the discount re-allocation (§22.1).
     */
    private static final class LineAccumulator {

        /**
         * The line id.
         */
        private final String lineId;

        /**
         * The EAN of the line (last non-null tranche EAN).
         */
        private String ean;

        /**
         * The running net TTC of the line, euro at scale 2.
         */
        private BigDecimal netTtc = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        /**
         * The running net HT of the line, euro at scale 2.
         */
        private BigDecimal netHt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        /**
         * The running Standard-tranche quantity of the line (units or one per weighing).
         */
        private BigDecimal quantity = BigDecimal.ZERO;

        /**
         * Whether a commercial offer consumed the line (§22.1, I2).
         */
        private boolean consumed;

        /**
         * Builds an accumulator for a line id.
         *
         * @param lineId The line id.
         */
        private LineAccumulator(String lineId) {
            this.lineId = lineId;
        }

        /**
         * Adds an offer tranche to the accumulator: its net, its quantity and its EAN.
         *
         * @param item The offer item.
         */
        private void addTranche(ValuationResponse.OfferItem item) {
            if (item.amount != null) {
                netTtc = netTtc.add(item.amount.ttc());
                netHt = netHt.add(item.amount.ht());
            }
            if (item.produceEan != null) {
                ean = item.produceEan;
            }
            if (item.quantity != null) {
                quantity = quantity.add(BigDecimal.valueOf(item.quantity));
            }
        }

        /**
         * Materializes the accumulator into an immutable {@link ValuedLine}.
         *
         * @return The valued line.
         */
        private ValuedLine toValuedLine() {
            return new ValuedLine(lineId, ean, quantity, netHt, netTtc, consumed);
        }
    }
}
