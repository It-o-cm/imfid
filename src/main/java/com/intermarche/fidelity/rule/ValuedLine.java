package com.intermarche.fidelity.rule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * One valued basket line, as derived from the {@code /valuation} output the POS
 * transmits to {@code /earn} (§15, §22.1) — the internal earn-engine reading of a
 * priced line.
 * <p>
 * imfid reconstitutes the net amount of a line from {@code offers[].items[]}
 * grouped by {@code lineId}, less the re-allocated discounts (§22.1); it never
 * recomputes a "before discount" figure — the earn assiette is the net paid (I1,
 * §22.1). The {@link #consumedByCommercialOffer} predicate carries I2: a line
 * absorbed by a non-Standard offer (bundle, N+M, manual gesture) or targeted by a
 * discount is out of every earn assiette (§22.1). A negative net is clamped to
 * zero for the earn (§22.1), which {@link #earnBaseAmount()} enforces.
 * <p>
 * Both the excluding-tax (HT) and including-tax (TTC) nets are carried; the earn
 * assiette is the net paid, i.e. the TTC net (§22.1, §22.3 — {@code totalPrice} is
 * the TTC reference of the burnable base). Fields are public and final: a valued
 * line is an immutable carrier.
 */
public final class ValuedLine {

    /**
     * The valuation line id ({@code offers[].items[].lineId}); the natural key of
     * the line across offers and the unit of the earn trace and return debit (§22.1,
     * §29.4).
     */
    public final String lineId;

    /**
     * The EAN of the line, resolved against the imfid product reference to derive
     * brand and families for the assiette scopes (§13, §15); may be null when the
     * valuation carried no EAN.
     */
    public final String ean;

    /**
     * The line quantity: units for a UNIT product, kilograms or liters for a
     * WEIGHT/VOLUME product (§22.2). Used for the "N eligible items" count, where a
     * weighing counts as one product (§22.2).
     */
    public final BigDecimal quantity;

    /**
     * The net amount excluding tax (HT) of the line, euro at scale 2 (§22.1, §30.5).
     */
    public final BigDecimal netAmountExcludingTax;

    /**
     * The net amount including tax (TTC) of the line, euro at scale 2 — the net paid
     * that founds the earn assiette (§22.1, §30.5).
     */
    public final BigDecimal netAmountIncludingTax;

    /**
     * Whether the line was consumed by a commercial offer — absorbed by a
     * non-Standard offer or targeted by a discount (I2, §22.1); such a line is out
     * of every earn assiette.
     */
    public final boolean consumedByCommercialOffer;

    /**
     * Builds a valued line, normalizing the amounts to euro scale 2 HALF_UP and
     * reading a null quantity or amount as zero (§30.5, §31.2).
     *
     * @param lineId                    The valuation line id; must not be null.
     * @param ean                       The line EAN; may be null.
     * @param quantity                  The line quantity; null is read as zero.
     * @param netAmountExcludingTax     The net HT amount; null is read as zero.
     * @param netAmountIncludingTax     The net TTC amount; null is read as zero.
     * @param consumedByCommercialOffer Whether a commercial offer consumed the line.
     */
    public ValuedLine(String lineId, String ean, BigDecimal quantity,
                      BigDecimal netAmountExcludingTax, BigDecimal netAmountIncludingTax,
                      boolean consumedByCommercialOffer) {
        this.lineId = Objects.requireNonNull(lineId, "lineId");
        this.ean = ean;
        this.quantity = quantity != null ? quantity : BigDecimal.ZERO;
        this.netAmountExcludingTax = scale(netAmountExcludingTax);
        this.netAmountIncludingTax = scale(netAmountIncludingTax);
        this.consumedByCommercialOffer = consumedByCommercialOffer;
    }

    /**
     * Returns the net amount that founds the earn assiette: the TTC net paid,
     * clamped to zero when negative (§22.1 — a discount is not capped at the product
     * price, so a negative net is possible and is brought back to zero for the earn).
     *
     * @return The non-negative TTC net, euro at scale 2.
     */
    public BigDecimal earnBaseAmount() {
        return netAmountIncludingTax.signum() < 0
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : netAmountIncludingTax;
    }

    /**
     * Indicates whether the line may feed an earn assiette at all: not consumed by a
     * commercial offer (I2) and carrying a strictly positive net paid.
     *
     * @return true when the line is a candidate for an earn assiette.
     */
    public boolean isEarnCandidate() {
        return !consumedByCommercialOffer && earnBaseAmount().signum() > 0;
    }

    /**
     * Normalizes an amount to euro scale 2 HALF_UP, reading null as zero (§30.5).
     *
     * @param value The raw amount; may be null.
     * @return The amount at scale 2, never null.
     */
    private static BigDecimal scale(BigDecimal value) {
        BigDecimal base = value != null ? value : BigDecimal.ZERO;
        return base.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns a debug representation of the line.
     *
     * @return The line id, EAN, net TTC and consumption flag.
     */
    @Override
    public String toString() {
        return "ValuedLine{lineId=" + lineId + ", ean=" + ean
                + ", netTTC=" + netAmountIncludingTax
                + ", consumed=" + consumedByCommercialOffer + '}';
    }
}
