package com.intermarche.fidelity.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link ValuedLine}: the normalizing constructor, the
 * null-as-zero readings of quantity and amounts (§30.5, §31.2), euro scale-2 HALF_UP
 * rounding, the negative-net clamp of {@link ValuedLine#earnBaseAmount()} (§22.1) and
 * each leg of the {@link ValuedLine#isEarnCandidate()} compound guard (§29, §29.6).
 * <p>
 * A valued line is pure logic: no Panache finder, no clock read. Every {@code BigDecimal}
 * is asserted by {@code compareTo} (never {@code equals}, so {@code 0.00} matches
 * {@code 0}) to the euro cent.
 */
class ValuedLineTest {

    /**
     * The constructor rejects a null line id (the {@code requireNonNull} guard, null arm).
     */
    @Test
    @DisplayName("null lineId is rejected")
    void nullLineIdRejected() {
        NullPointerException error = assertThrows(NullPointerException.class,
                () -> new ValuedLine(null, "EAN", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, false));
        assertEquals("lineId", error.getMessage());
    }

    /**
     * The constructor copies every field verbatim when all arguments are present: the
     * non-null arms of the lineId guard, the quantity ternary and both {@code scale}
     * ternaries, with a null EAN kept as null.
     */
    @Test
    @DisplayName("full constructor keeps every field")
    void fullConstructorKeepsFields() {
        ValuedLine line = new ValuedLine("L1", null, new BigDecimal("2.000"),
                new BigDecimal("8.30"), new BigDecimal("9.96"), true);
        assertEquals("L1", line.lineId);
        assertNull(line.ean);
        assertEquals(0, new BigDecimal("2.000").compareTo(line.quantity));
        assertEquals(0, new BigDecimal("8.30").compareTo(line.netAmountExcludingTax));
        assertEquals(0, new BigDecimal("9.96").compareTo(line.netAmountIncludingTax));
        assertTrue(line.consumedByCommercialOffer);
    }

    /**
     * The EAN is carried verbatim when supplied (the non-null EAN case).
     */
    @Test
    @DisplayName("EAN is carried verbatim")
    void eanCarriedVerbatim() {
        ValuedLine line = new ValuedLine("L1", "3250390000001", BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, false);
        assertEquals("3250390000001", line.ean);
        assertFalse(line.consumedByCommercialOffer);
    }

    /**
     * A null quantity is read as zero (the quantity ternary, null arm).
     */
    @Test
    @DisplayName("null quantity reads as zero")
    void nullQuantityReadsAsZero() {
        ValuedLine line = new ValuedLine("L1", "EAN", null,
                new BigDecimal("1.00"), new BigDecimal("1.00"), false);
        assertEquals(0, BigDecimal.ZERO.compareTo(line.quantity));
    }

    /**
     * A null HT and a null TTC amount are both read as zero at scale 2 (the {@code scale}
     * ternary, null arm, exercised on each amount).
     */
    @Test
    @DisplayName("null amounts read as zero at scale 2")
    void nullAmountsReadAsZero() {
        ValuedLine line = new ValuedLine("L1", "EAN", BigDecimal.ONE, null, null, false);
        assertEquals(0, BigDecimal.ZERO.compareTo(line.netAmountExcludingTax));
        assertEquals(2, line.netAmountExcludingTax.scale());
        assertEquals(0, BigDecimal.ZERO.compareTo(line.netAmountIncludingTax));
        assertEquals(2, line.netAmountIncludingTax.scale());
    }

    /**
     * The amounts are normalized to euro scale 2 HALF_UP (the {@code scale} ternary,
     * non-null arm, rounding half up on each amount).
     */
    @Test
    @DisplayName("amounts round to scale 2 HALF_UP")
    void amountsRoundHalfUp() {
        ValuedLine line = new ValuedLine("L1", "EAN", BigDecimal.ONE,
                new BigDecimal("1.005"), new BigDecimal("2.344"), false);
        assertEquals(0, new BigDecimal("1.01").compareTo(line.netAmountExcludingTax));
        assertEquals(2, line.netAmountExcludingTax.scale());
        assertEquals(0, new BigDecimal("2.34").compareTo(line.netAmountIncludingTax));
        assertEquals(2, line.netAmountIncludingTax.scale());
    }

    /**
     * earnBaseAmount clamps a negative net paid to zero at scale 2 (the ternary, negative
     * arm — a discount is not capped at the product price, §22.1).
     */
    @Test
    @DisplayName("earnBaseAmount clamps a negative net to zero")
    void earnBaseAmountClampsNegative() {
        ValuedLine line = new ValuedLine("L1", "EAN", BigDecimal.ONE,
                new BigDecimal("-1.00"), new BigDecimal("-3.50"), false);
        BigDecimal base = line.earnBaseAmount();
        assertEquals(0, BigDecimal.ZERO.compareTo(base));
        assertEquals(2, base.scale());
    }

    /**
     * earnBaseAmount returns the TTC net verbatim when it is positive (the ternary,
     * non-negative arm).
     */
    @Test
    @DisplayName("earnBaseAmount returns a positive net verbatim")
    void earnBaseAmountReturnsPositive() {
        ValuedLine line = new ValuedLine("L1", "EAN", BigDecimal.ONE,
                new BigDecimal("4.00"), new BigDecimal("4.80"), false);
        assertSame(line.netAmountIncludingTax, line.earnBaseAmount());
        assertEquals(0, new BigDecimal("4.80").compareTo(line.earnBaseAmount()));
    }

    /**
     * earnBaseAmount returns a zero net verbatim: zero is not negative, so the non-negative
     * arm is taken (the boundary between the two arms).
     */
    @Test
    @DisplayName("earnBaseAmount returns a zero net verbatim")
    void earnBaseAmountReturnsZero() {
        ValuedLine line = new ValuedLine("L1", "EAN", BigDecimal.ONE,
                new BigDecimal("0.00"), new BigDecimal("0.00"), false);
        assertSame(line.netAmountIncludingTax, line.earnBaseAmount());
        assertEquals(0, BigDecimal.ZERO.compareTo(line.earnBaseAmount()));
    }

    /**
     * isEarnCandidate is true when the line is not consumed and carries a strictly positive
     * net paid (both legs of the {@code &&} true).
     */
    @Test
    @DisplayName("isEarnCandidate is true when unconsumed and positive")
    void isEarnCandidateWhenUnconsumedPositive() {
        ValuedLine line = new ValuedLine("L1", "EAN", BigDecimal.ONE,
                new BigDecimal("1.00"), new BigDecimal("1.20"), false);
        assertTrue(line.isEarnCandidate());
    }

    /**
     * isEarnCandidate is false when a commercial offer consumed the line (first leg false;
     * the {@code &&} short-circuits, I2, §22.1) even though the net paid is positive.
     */
    @Test
    @DisplayName("isEarnCandidate is false when consumed")
    void isEarnCandidateWhenConsumed() {
        ValuedLine line = new ValuedLine("L1", "EAN", BigDecimal.ONE,
                new BigDecimal("1.00"), new BigDecimal("1.20"), true);
        assertFalse(line.isEarnCandidate());
    }

    /**
     * isEarnCandidate is false for an unconsumed line whose net paid is zero (first leg
     * true, second leg false).
     */
    @Test
    @DisplayName("isEarnCandidate is false when the net is zero")
    void isEarnCandidateWhenZeroNet() {
        ValuedLine line = new ValuedLine("L1", "EAN", BigDecimal.ONE,
                new BigDecimal("0.00"), new BigDecimal("0.00"), false);
        assertFalse(line.isEarnCandidate());
    }

    /**
     * toString renders the line id, EAN, TTC net and consumption flag.
     */
    @Test
    @DisplayName("toString renders the line fields")
    void toStringRendersFields() {
        ValuedLine line = new ValuedLine("L1", "EAN", BigDecimal.ONE,
                new BigDecimal("1.00"), new BigDecimal("1.20"), true);
        String text = line.toString();
        assertTrue(text.contains("lineId=L1"));
        assertTrue(text.contains("ean=EAN"));
        assertTrue(text.contains("netTTC=1.20"));
        assertTrue(text.contains("consumed=true"));
    }
}
