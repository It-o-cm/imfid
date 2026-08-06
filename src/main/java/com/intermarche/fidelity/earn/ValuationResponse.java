package com.intermarche.fidelity.earn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code /valuation} response the POS transmits verbatim to {@code /earn} — the
 * priced basket imfid reads to derive earn assiettes and the burnable base (§15,
 * §22). This is a read model of imvaluation's output, not a re-implementation:
 * imfid never recomputes a discount, it reads them from this structure (§34.1).
 * <p>
 * Every nested type ignores unknown properties so a forward-compatible valuation
 * output (§26.4) never breaks the read. {@code offers} and {@code advantages} are
 * unordered sets on the engine side (§22.2): they are discriminated by content,
 * never by index. Fields are public to keep the DTO a plain Jackson carrier.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ValuationResponse {

    /**
     * What the customer pays: each offer application with its amount and its per-line
     * breakdown (§22.1). Never null (§31.2).
     */
    public List<Offer> offers = new ArrayList<>();

    /**
     * What is deducted or signalled: discounts, the meal-voucher assiette and upsell
     * suggestions — discriminated by field presence (§22.2). Never null (§31.2).
     */
    public List<Advantage> advantages = new ArrayList<>();

    /**
     * The net total of the basket; its TTC is the reference of the maximal burn of the
     * ticket (§22, §22.3).
     */
    public AmountEvaluation totalPrice;

    /**
     * The VAT breakdown by rate — the reliable VAT source, never {@code totalPrice.vatRate}
     * (§22.2). Never null (§31.2).
     */
    public List<VatBreakdown> vatBreakdown = new ArrayList<>();

    /**
     * Map EAN &rarr; residual that stayed at the standard tariff (§22.1); read only as a
     * cross-check of the "kept full price" lines. May be null.
     */
    public Map<String, Object> availableToUpcell;

    /**
     * One offer application: a descriptive {@link #type}, its {@link #amount} and the
     * per-original-line {@link #items} (§22.1).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Offer {

        /**
         * The descriptive offer type — the sole discriminant of the offer family
         * ("Standard: …", "Mixed Bundle Promo: …" = N+M, "Manual Gesture: …",
         * "Delivery: …", "Deposit Basket: …", §22.1); never parsed for formatted
         * numbers (§22.2).
         */
        public String type;

        /**
         * The offer amount; the invariant Σ items = amount holds at the centime for an
         * offer that carries items (§22.1).
         */
        public AmountEvaluation amount;

        /**
         * The per-line breakdown; empty for Delivery and Deposit Basket offers (§22.1).
         * Never null (§31.2).
         */
        public List<OfferItem> items = new ArrayList<>();
    }

    /**
     * One tranche of an offer, restituted per original basket line (§22.1).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OfferItem {

        /**
         * The original basket line id (that of the request) — the natural key across
         * offers (§22.1).
         */
        public String lineId;

        /**
         * The EAN of the tranche, resolved against the imfid product reference to derive
         * brand and families (§13, §15); may be null.
         */
        public String produceEan;

        /**
         * The tranche quantity: units for a UNIT product, kilograms or liters for a
         * WEIGHT/VOLUME product (§22.2).
         */
        public Double quantity;

        /**
         * The tranche amount, at the real VAT rate of the product (§22.1).
         */
        public AmountEvaluation amount;
    }

    /**
     * One advantage — a discount, the meal-voucher assiette or an upsell suggestion,
     * discriminated by field presence (§22.2).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Advantage {

        /**
         * The descriptive advantage type; MEAL_VOUCHER marks the meal-voucher assiette
         * (§22.2), otherwise it is a discount or an upsell label.
         */
        public String type;

        /**
         * The type string of the targeted offer — the link discount &rarr; line(s) (§22.1);
         * null on a non-discount advantage.
         */
        public String offer;

        /**
         * The discount amount, stored positive and already deducted from the total
         * (§22.1); its presence marks a true discount (§22.2). Null otherwise.
         */
        public AmountEvaluation discountAmount;

        /**
         * The upsell suggestion structure; its presence marks an informative upsell,
         * ignored by imfid (§22.1, §22.2). Null otherwise.
         */
        public Map<String, Object> suggestion;

        /**
         * The meal-voucher offer code; carried by a MEAL_VOUCHER advantage, ignored by
         * imfid (§22.1). Null otherwise.
         */
        public String offerCode;

        /**
         * Indicates whether this advantage is a true discount — a positive
         * {@link #discountAmount} present (§22.2).
         *
         * @return true when the advantage bears a positive discount.
         */
        public boolean isDiscount() {
            return discountAmount != null
                    && discountAmount.amountIncludingTax != null
                    && discountAmount.amountIncludingTax.signum() != 0;
        }
    }

    /**
     * One VAT breakdown row (§22.2).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VatBreakdown {

        /**
         * The VAT rate as a fraction (0.2000 = 20 %).
         */
        public BigDecimal vatRate;

        /**
         * The excluding-tax amount at this rate.
         */
        public BigDecimal amountExcludingTax;

        /**
         * The VAT amount at this rate.
         */
        public BigDecimal vatAmount;

        /**
         * The including-tax amount at this rate.
         */
        public BigDecimal amountIncludingTax;
    }
}
