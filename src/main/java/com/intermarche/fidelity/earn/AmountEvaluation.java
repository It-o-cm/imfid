package com.intermarche.fidelity.earn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The common monetary block of the {@code /valuation} output (§22 — {@code AmountEvaluation}):
 * an excluding-tax amount, an including-tax amount and a VAT rate as a fraction.
 * <p>
 * HT and TTC are euro at scale 2, HALF_UP; {@code vatRate} is a fraction at scale 4
 * (0.2000 = 20 %). On {@code totalPrice} the rate is always 0.0000 and must never be
 * used (§22.2). Fields are public to keep the DTO a plain Jackson carrier.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AmountEvaluation {

    /**
     * The amount excluding tax, euro at scale 2 (§22).
     */
    public BigDecimal amountExcludingTax;

    /**
     * The amount including tax, euro at scale 2 (§22).
     */
    public BigDecimal amountIncludingTax;

    /**
     * The VAT rate as a fraction, scale 4 (§22).
     */
    public BigDecimal vatRate;

    /**
     * Returns the including-tax amount at euro scale 2, reading a null as zero (§31.2).
     *
     * @return The TTC amount, never null.
     */
    public BigDecimal ttc() {
        return scale(amountIncludingTax);
    }

    /**
     * Returns the excluding-tax amount at euro scale 2, reading a null as zero (§31.2).
     *
     * @return The HT amount, never null.
     */
    public BigDecimal ht() {
        return scale(amountExcludingTax);
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
}
