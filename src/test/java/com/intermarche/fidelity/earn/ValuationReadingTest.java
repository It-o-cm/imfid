package com.intermarche.fidelity.earn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.rule.ValuedLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link ValuationReading}: the immutable earn reading carrier
 * (§22). The whole surface is the three-argument constructor, whose every branch is a
 * null / non-null ternary over the lines, the total price and the warnings; the cases
 * cover both arms of each, the HALF_UP normalization of the total price (§30.5) up and
 * down the borderline, and the defensive-copy / immutability contract of the collections.
 */
class ValuationReadingTest {

    /**
     * Builds a valued line carrying the given TTC net, with fixed neutral values for the
     * other coordinates, so the collection cases have a concrete element to hold.
     *
     * @param lineId The valuation line id.
     * @param netTtc The TTC net amount.
     * @return A valued line for use as a reading element.
     */
    private static ValuedLine line(String lineId, String netTtc) {
        return new ValuedLine(lineId, "3250391234567", BigDecimal.ONE,
                new BigDecimal(netTtc), new BigDecimal(netTtc), false);
    }

    /**
     * Null lines are read as an empty list rather than a null, honouring the never-null
     * collection guard (§31.2).
     */
    @Test
    @DisplayName("null lines are read as an empty list")
    void nullLinesReadAsEmpty() {
        ValuationReading reading = new ValuationReading(null, BigDecimal.ONE, List.of());
        assertTrue(reading.lines.isEmpty());
    }

    /**
     * Non-null lines are carried element-for-element, in encounter order, as a defensive
     * copy: mutating the source list after construction does not alter the reading.
     */
    @Test
    @DisplayName("non-null lines are carried as an ordered defensive copy")
    void nonNullLinesCarriedAsDefensiveCopy() {
        List<ValuedLine> source = new ArrayList<>();
        source.add(line("L1", "10.00"));
        source.add(line("L2", "20.00"));
        ValuationReading reading = new ValuationReading(source, BigDecimal.ONE, List.of());
        source.add(line("L3", "30.00"));
        assertEquals(2, reading.lines.size());
        assertEquals("L1", reading.lines.get(0).lineId);
        assertEquals("L2", reading.lines.get(1).lineId);
    }

    /**
     * The lines list is unmodifiable: the reading is an immutable carrier (§30.2), so a
     * mutation attempt is rejected.
     */
    @Test
    @DisplayName("the lines list is unmodifiable")
    void linesListIsUnmodifiable() {
        ValuationReading reading = new ValuationReading(List.of(line("L1", "10.00")),
                BigDecimal.ONE, List.of());
        assertThrows(UnsupportedOperationException.class,
                () -> reading.lines.add(line("L2", "20.00")));
    }

    /**
     * A null total price is read as zero and normalized to euro scale 2 (§30.5).
     */
    @Test
    @DisplayName("null total price is read as zero at scale 2")
    void nullTotalReadAsZero() {
        ValuationReading reading = new ValuationReading(List.of(), null, List.of());
        assertEquals(0, reading.totalPriceTtc.compareTo(BigDecimal.ZERO));
        assertEquals(2, reading.totalPriceTtc.scale());
    }

    /**
     * A non-null total price is carried and normalized to euro scale 2, preserving its
     * value to the cent.
     */
    @Test
    @DisplayName("non-null total price is normalized to scale 2")
    void nonNullTotalNormalizedToScaleTwo() {
        ValuationReading reading = new ValuationReading(List.of(), new BigDecimal("2.5"), List.of());
        assertEquals(0, reading.totalPriceTtc.compareTo(new BigDecimal("2.50")));
        assertEquals(2, reading.totalPriceTtc.scale());
    }

    /**
     * A total price on the up side of the rounding border is rounded away from zero
     * (HALF_UP), so a trailing five climbs to the next cent (§30.5).
     */
    @Test
    @DisplayName("total price is rounded HALF_UP on the up side of the border")
    void totalRoundedHalfUpAwayFromZero() {
        ValuationReading reading = new ValuationReading(List.of(), new BigDecimal("1.005"), List.of());
        assertEquals(0, reading.totalPriceTtc.compareTo(new BigDecimal("1.01")));
        assertEquals(2, reading.totalPriceTtc.scale());
    }

    /**
     * A total price below the rounding border is truncated to the current cent, the down
     * side of HALF_UP (§30.5).
     */
    @Test
    @DisplayName("total price below the border stays at the current cent")
    void totalRoundedDownBelowBorder() {
        ValuationReading reading = new ValuationReading(List.of(), new BigDecimal("1.004"), List.of());
        assertEquals(0, reading.totalPriceTtc.compareTo(new BigDecimal("1.00")));
        assertEquals(2, reading.totalPriceTtc.scale());
    }

    /**
     * Null warnings are read as an empty, mutable list: the reader appends unknown-EAN
     * warnings after construction (§25.4), so the list must accept additions.
     */
    @Test
    @DisplayName("null warnings are read as an empty mutable list")
    void nullWarningsReadAsEmptyMutable() {
        ValuationReading reading = new ValuationReading(List.of(), BigDecimal.ONE, null);
        assertTrue(reading.warnings.isEmpty());
        reading.warnings.add(new EarnResponse.Warning("UNKNOWN_EAN", "000", "unknown"));
        assertEquals(1, reading.warnings.size());
    }

    /**
     * Non-null warnings are carried as a defensive, still-mutable copy: the reading holds
     * a distinct list whose later mutation of the source leaves it unchanged, yet which
     * still accepts appends of its own.
     */
    @Test
    @DisplayName("non-null warnings are carried as a mutable defensive copy")
    void nonNullWarningsCarriedAsMutableCopy() {
        List<EarnResponse.Warning> source = new ArrayList<>();
        source.add(new EarnResponse.Warning("W1", "111", "one"));
        ValuationReading reading = new ValuationReading(List.of(), BigDecimal.ONE, source);
        source.add(new EarnResponse.Warning("W2", "222", "two"));
        assertNotSame(source, reading.warnings);
        assertEquals(1, reading.warnings.size());
        assertEquals("W1", reading.warnings.get(0).code);
        reading.warnings.add(new EarnResponse.Warning("W3", "333", "three"));
        assertEquals(2, reading.warnings.size());
    }

    /**
     * The lines list is a distinct instance from the source even when the source is an
     * empty mutable list, closing the reference-identity leg of the defensive copy.
     */
    @Test
    @DisplayName("lines are copied to a distinct instance from the source")
    void linesCopiedToDistinctInstance() {
        List<ValuedLine> source = new ArrayList<>();
        ValuationReading reading = new ValuationReading(source, BigDecimal.ONE, List.of());
        assertNotSame(source, reading.lines);
    }
}
