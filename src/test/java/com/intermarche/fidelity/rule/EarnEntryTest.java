package com.intermarche.fidelity.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link EarnEntry}: the immutable carrier's constructor guards
 * (both arms of each {@code requireNonNull} and of the line-ids ternary), the {@code scale}
 * null-as-zero reading and its HALF_UP rounding (§30.5), the {@link EarnEntry#none} factory,
 * and each leg of the {@link EarnEntry#isEmpty} compound guard (§29, §29.6).
 * <p>
 * The entry is a side-effect-free value object (§30.2): no Panache finder and no clock read,
 * so no {@code DateTimeProvider} is involved. Every amount is asserted by {@code compareTo}
 * (never {@code equals}) to the euro cent, scale 2 (§30.5).
 */
class EarnEntryTest {

    /**
     * The full constructor normalizes both amounts to scale 2 and copies the line ids,
     * exercising the non-null arm of every guard and both ternaries.
     */
    @Test
    @DisplayName("full constructor normalizes amounts and copies line ids")
    void fullConstructorPopulatesFields() {
        EarnEntry entry = new EarnEntry("R1", "Rule one",
                new BigDecimal("2.5"), new BigDecimal("40"), List.of("L1", "L2"));
        assertEquals("R1", entry.ruleCode);
        assertEquals("Rule one", entry.label);
        assertEquals(0, new BigDecimal("2.50").compareTo(entry.amount));
        assertEquals(2, entry.amount.scale());
        assertEquals(0, new BigDecimal("40.00").compareTo(entry.baseAmount));
        assertEquals(2, entry.baseAmount.scale());
        assertEquals(List.of("L1", "L2"), entry.lineIds);
    }

    /**
     * A null rule code is rejected by the first guard (true arm), before any other field.
     */
    @Test
    @DisplayName("null rule code is rejected")
    void nullRuleCodeRejected() {
        NullPointerException error = assertThrows(NullPointerException.class,
                () -> new EarnEntry(null, "Label", BigDecimal.ONE, BigDecimal.TEN, List.of("L1")));
        assertEquals("ruleCode", error.getMessage());
    }

    /**
     * A null label is rejected by the second guard (true arm), the rule code being present
     * so the first guard passes (its false arm).
     */
    @Test
    @DisplayName("null label is rejected")
    void nullLabelRejected() {
        NullPointerException error = assertThrows(NullPointerException.class,
                () -> new EarnEntry("R1", null, BigDecimal.ONE, BigDecimal.TEN, List.of("L1")));
        assertEquals("label", error.getMessage());
    }

    /**
     * A null amount is read as zero (the {@code scale} ternary, null arm) for the amount slot.
     */
    @Test
    @DisplayName("null amount reads as zero")
    void nullAmountReadsAsZero() {
        EarnEntry entry = new EarnEntry("R1", "Label", null, new BigDecimal("5.00"), List.of("L1"));
        assertEquals(0, BigDecimal.ZERO.compareTo(entry.amount));
        assertEquals(2, entry.amount.scale());
    }

    /**
     * A null base amount is read as zero (the {@code scale} ternary, null arm) for the base slot.
     */
    @Test
    @DisplayName("null base amount reads as zero")
    void nullBaseReadsAsZero() {
        EarnEntry entry = new EarnEntry("R1", "Label", new BigDecimal("5.00"), null, List.of("L1"));
        assertEquals(0, BigDecimal.ZERO.compareTo(entry.baseAmount));
        assertEquals(2, entry.baseAmount.scale());
    }

    /**
     * The amount is rounded HALF_UP to scale 2: a third-decimal five rounds the cent up (§30.5).
     */
    @Test
    @DisplayName("amount rounds HALF_UP to the cent")
    void amountRoundsHalfUp() {
        EarnEntry entry = new EarnEntry("R1", "Label",
                new BigDecimal("2.005"), new BigDecimal("1.004"), List.of("L1"));
        assertEquals(0, new BigDecimal("2.01").compareTo(entry.amount));
        assertEquals(0, new BigDecimal("1.00").compareTo(entry.baseAmount));
    }

    /**
     * A null line-ids list is read as empty (the ternary, null arm).
     */
    @Test
    @DisplayName("null line ids read as empty")
    void nullLineIdsReadAsEmpty() {
        EarnEntry entry = new EarnEntry("R1", "Label", BigDecimal.ONE, BigDecimal.TEN, null);
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * The line-ids copy is defensive (the ternary, non-null arm): mutating the source list
     * after construction does not change the entry.
     */
    @Test
    @DisplayName("line ids are a defensive copy")
    void lineIdsAreDefensiveCopy() {
        List<String> source = new ArrayList<>();
        source.add("L1");
        EarnEntry entry = new EarnEntry("R1", "Label", BigDecimal.ONE, BigDecimal.TEN, source);
        source.add("L2");
        assertEquals(1, entry.lineIds.size());
        assertThrows(UnsupportedOperationException.class, () -> entry.lineIds.add("L3"));
    }

    /**
     * The {@code none} factory yields a zero-amount, zero-base, no-line entry keeping the codes.
     */
    @Test
    @DisplayName("none yields a zero empty entry")
    void noneYieldsZeroEntry() {
        EarnEntry entry = EarnEntry.none("R1", "Label");
        assertEquals("R1", entry.ruleCode);
        assertEquals("Label", entry.label);
        assertEquals(0, BigDecimal.ZERO.compareTo(entry.amount));
        assertEquals(0, BigDecimal.ZERO.compareTo(entry.baseAmount));
        assertTrue(entry.lineIds.isEmpty());
        assertTrue(entry.isEmpty());
    }

    /**
     * isEmpty is true for a negative amount (first leg true, {@code signum() < 0}) even with
     * contributing lines (second leg false).
     */
    @Test
    @DisplayName("isEmpty is true for a negative amount")
    void isEmptyNegativeAmount() {
        EarnEntry entry = new EarnEntry("R1", "Label", new BigDecimal("-1.00"), BigDecimal.TEN, List.of("L1"));
        assertTrue(entry.isEmpty());
    }

    /**
     * isEmpty is true for a zero amount at the boundary (first leg true, {@code signum() == 0})
     * with contributing lines (second leg false).
     */
    @Test
    @DisplayName("isEmpty is true for a zero amount")
    void isEmptyZeroAmount() {
        EarnEntry entry = new EarnEntry("R1", "Label", BigDecimal.ZERO, BigDecimal.TEN, List.of("L1"));
        assertTrue(entry.isEmpty());
    }

    /**
     * isEmpty is true for a positive amount but no contributing line (first leg false,
     * {@code signum() > 0}; second leg true).
     */
    @Test
    @DisplayName("isEmpty is true when no line contributes")
    void isEmptyNoLines() {
        EarnEntry entry = new EarnEntry("R1", "Label", new BigDecimal("1.00"), BigDecimal.TEN, List.of());
        assertTrue(entry.isEmpty());
    }

    /**
     * isEmpty is false for a positive amount with contributing lines (both legs false).
     */
    @Test
    @DisplayName("isEmpty is false for a producing entry")
    void isEmptyProducing() {
        EarnEntry entry = new EarnEntry("R1", "Label", new BigDecimal("0.01"), BigDecimal.TEN, List.of("L1"));
        assertFalse(entry.isEmpty());
    }

    /**
     * toString renders the rule code, amount, base and line ids for debugging.
     */
    @Test
    @DisplayName("toString renders the entry fields")
    void toStringRendersFields() {
        EarnEntry entry = new EarnEntry("R1", "Label", new BigDecimal("2.00"), new BigDecimal("40.00"), List.of("L1"));
        String rendered = entry.toString();
        assertTrue(rendered.contains("ruleCode=R1"));
        assertTrue(rendered.contains("amount=2.00"));
        assertTrue(rendered.contains("baseAmount=40.00"));
        assertTrue(rendered.contains("lineIds=[L1]"));
    }
}
