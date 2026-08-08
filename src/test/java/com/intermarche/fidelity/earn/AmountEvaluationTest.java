package com.intermarche.fidelity.earn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link AmountEvaluation}: the null-as-zero read and the
 * euro scale-2 HALF_UP normalization of the TTC and HT accessors (§22, §30.5, §31.2).
 */
class AmountEvaluationTest {

    /**
     * TTC of a non-null including-tax amount is returned scaled to euro scale 2.
     */
    @Test
    @DisplayName("ttc(): non-null amount is normalized to scale 2")
    void ttcNonNullScalesToTwoDecimals() {
        AmountEvaluation evaluation = new AmountEvaluation();
        evaluation.amountIncludingTax = new BigDecimal("12.5");
        BigDecimal result = evaluation.ttc();
        assertEquals(0, result.compareTo(new BigDecimal("12.50")));
        assertEquals(2, result.scale());
    }

    /**
     * TTC of a null including-tax amount reads as zero and is never null (§31.2).
     */
    @Test
    @DisplayName("ttc(): null amount reads as zero")
    void ttcNullReadsAsZero() {
        AmountEvaluation evaluation = new AmountEvaluation();
        evaluation.amountIncludingTax = null;
        BigDecimal result = evaluation.ttc();
        assertNotNull(result);
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
        assertEquals(2, result.scale());
    }

    /**
     * TTC rounds a half-cent up per HALF_UP at euro scale 2 (§30.5).
     */
    @Test
    @DisplayName("ttc(): rounds HALF_UP")
    void ttcRoundsHalfUp() {
        AmountEvaluation evaluation = new AmountEvaluation();
        evaluation.amountIncludingTax = new BigDecimal("1.005");
        BigDecimal result = evaluation.ttc();
        assertEquals(0, result.compareTo(new BigDecimal("1.01")));
        assertEquals(2, result.scale());
    }

    /**
     * HT of a non-null excluding-tax amount is returned scaled to euro scale 2.
     */
    @Test
    @DisplayName("ht(): non-null amount is normalized to scale 2")
    void htNonNullScalesToTwoDecimals() {
        AmountEvaluation evaluation = new AmountEvaluation();
        evaluation.amountExcludingTax = new BigDecimal("7.4");
        BigDecimal result = evaluation.ht();
        assertEquals(0, result.compareTo(new BigDecimal("7.40")));
        assertEquals(2, result.scale());
    }

    /**
     * HT of a null excluding-tax amount reads as zero and is never null (§31.2).
     */
    @Test
    @DisplayName("ht(): null amount reads as zero")
    void htNullReadsAsZero() {
        AmountEvaluation evaluation = new AmountEvaluation();
        evaluation.amountExcludingTax = null;
        BigDecimal result = evaluation.ht();
        assertNotNull(result);
        assertEquals(0, result.compareTo(BigDecimal.ZERO));
        assertEquals(2, result.scale());
    }

    /**
     * HT rounds a half-cent up per HALF_UP at euro scale 2 (§30.5).
     */
    @Test
    @DisplayName("ht(): rounds HALF_UP")
    void htRoundsHalfUp() {
        AmountEvaluation evaluation = new AmountEvaluation();
        evaluation.amountExcludingTax = new BigDecimal("2.345");
        BigDecimal result = evaluation.ht();
        assertEquals(0, result.compareTo(new BigDecimal("2.35")));
        assertEquals(2, result.scale());
    }
}
