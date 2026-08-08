package com.intermarche.fidelity.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.batch.BatchResult.Line;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link BatchResult}: the dry-run/execution carrier of a batch run
 * (§23.4, §32.3). The only branch is the null-guard ternary in {@link BatchResult#add}, so
 * both arms (null and non-null amount) are exercised, alongside the constructor, the
 * scale-2 seeding of {@code totalAmount}, accumulation across calls, and the nested
 * {@link Line}. Every {@code BigDecimal} is asserted by {@code compareTo} and carries
 * scale 2 / HALF_UP to the euro (§30.5).
 */
class BatchResultTest {

    /**
     * The constructor stores both carrier fields verbatim and seeds a fresh, non-null,
     * scale-2 zero total with an empty breakdown.
     */
    @Test
    @DisplayName("constructor seeds fields, zero total at scale 2, and empty lines")
    void constructorSeedsDefaults() {
        BatchResult result = new BatchResult("EXPIRY", true);
        assertEquals("EXPIRY", result.batch);
        assertTrue(result.dryRun);
        assertEquals(0, result.accountsAffected);
        assertNotNull(result.totalAmount);
        assertEquals(0, result.totalAmount.compareTo(BigDecimal.ZERO));
        assertEquals(2, result.totalAmount.scale());
        assertNotNull(result.lines);
        assertTrue(result.lines.isEmpty());
    }

    /**
     * The {@code dryRun} flag carries the false value too, covering the constructor's
     * boolean assignment on both settings.
     */
    @Test
    @DisplayName("constructor carries dryRun=false")
    void constructorCarriesExecution() {
        BatchResult result = new BatchResult("SWEEP", false);
        assertEquals("SWEEP", result.batch);
        assertFalse(result.dryRun);
    }

    /**
     * The non-null arm of the ternary: a supplied amount is rescaled to scale 2 with
     * HALF_UP, recorded as a line, counted, and added to the total.
     */
    @Test
    @DisplayName("add with non-null amount rescales, records, counts, and totals")
    void addNonNullAmount() {
        BatchResult result = new BatchResult("EXPIRY", false);
        result.add("C1", new BigDecimal("12.345"));
        assertEquals(1, result.accountsAffected);
        assertEquals(1, result.lines.size());
        Line line = result.lines.get(0);
        assertEquals("C1", line.cardNumber);
        assertEquals(0, line.amount.compareTo(new BigDecimal("12.35")));
        assertEquals(2, line.amount.scale());
        assertEquals(0, result.totalAmount.compareTo(new BigDecimal("12.35")));
        assertEquals(2, result.totalAmount.scale());
    }

    /**
     * The null arm of the ternary: a null amount degrades to a scale-2 zero magnitude,
     * still records a line and increments the count without moving the total.
     */
    @Test
    @DisplayName("add with null amount degrades to scale-2 zero magnitude")
    void addNullAmount() {
        BatchResult result = new BatchResult("EXPIRY", true);
        result.add("C2", null);
        assertEquals(1, result.accountsAffected);
        assertEquals(1, result.lines.size());
        Line line = result.lines.get(0);
        assertEquals("C2", line.cardNumber);
        assertEquals(0, line.amount.compareTo(BigDecimal.ZERO));
        assertEquals(2, line.amount.scale());
        assertEquals(0, result.totalAmount.compareTo(BigDecimal.ZERO));
        assertEquals(2, result.totalAmount.scale());
    }

    /**
     * Successive calls, mixing null and non-null amounts, accumulate the count and the
     * total while preserving insertion order in the breakdown.
     */
    @Test
    @DisplayName("add accumulates count and total across mixed calls")
    void addAccumulates() {
        BatchResult result = new BatchResult("SWEEP", false);
        result.add("A", new BigDecimal("10.00"));
        result.add("B", null);
        result.add("C", new BigDecimal("5.555"));
        assertEquals(3, result.accountsAffected);
        assertEquals(3, result.lines.size());
        assertEquals("A", result.lines.get(0).cardNumber);
        assertEquals("B", result.lines.get(1).cardNumber);
        assertEquals("C", result.lines.get(2).cardNumber);
        assertEquals(0, result.totalAmount.compareTo(new BigDecimal("15.56")));
        assertEquals(2, result.totalAmount.scale());
    }

    /**
     * The nested {@link Line} constructor stores its card number and amount by reference.
     */
    @Test
    @DisplayName("Line constructor carries its fields")
    void lineCarriesFields() {
        BigDecimal amount = new BigDecimal("3.00").setScale(2, RoundingMode.HALF_UP);
        Line line = new Line("Z9", amount);
        assertEquals("Z9", line.cardNumber);
        assertSame(amount, line.amount);
        assertEquals(0, line.amount.compareTo(new BigDecimal("3.00")));
    }

    /**
     * A null card number is stored as-is on the line, since {@code add} guards only the
     * amount.
     */
    @Test
    @DisplayName("add keeps a null card number on the line")
    void addNullCardNumber() {
        BatchResult result = new BatchResult("EXPIRY", true);
        result.add(null, new BigDecimal("1.00"));
        assertNull(result.lines.get(0).cardNumber);
        assertEquals(0, result.totalAmount.compareTo(new BigDecimal("1.00")));
    }
}
