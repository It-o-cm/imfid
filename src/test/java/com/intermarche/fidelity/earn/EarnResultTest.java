package com.intermarche.fidelity.earn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.intermarche.fidelity.rule.ValuedLine;

/**
 * Plain unit coverage for {@link EarnResult}: the internal earn outcome shared by the
 * {@code /earn} projection (§27.1) and the ingestion recalc (§26.1). The class is a
 * mutable builder; its single branch is the constructor's {@code lines == null ?
 * List.of() : List.copyOf(lines)} ternary, whose both arms are exercised here (§29.6).
 * The rest is straight-line: the collection/money defaults and the {@link
 * EarnResult#toResponse()} mapping, whose defensive list copies (§31.2) are asserted.
 * Pure logic, no Panache and no clock: nothing here reads a {@code DateTimeProvider}.
 */
class EarnResultTest {

    /**
     * Builds a valued line carrier for the constructor arms, at euro scale 2 (§30.5).
     *
     * @param lineId The line id.
     * @param net    The net TTC amount.
     * @return The valued line, never null.
     */
    private static ValuedLine line(String lineId, String net) {
        return new ValuedLine(lineId, "3250391234567", BigDecimal.ONE,
                new BigDecimal(net), new BigDecimal(net), false);
    }

    /**
     * A result built from null lines takes the ternary true arm: the lines fall back to an
     * empty non-null list (§31.2) and the money/collection defaults ship at zero and empty.
     */
    @Test
    @DisplayName("null lines: ternary true arm falls back to empty lines, zero defaults")
    void nullLinesFallBackToEmptyAndShipsDefaults() {
        EarnResult result = new EarnResult(null);
        assertNotNull(result.lines);
        assertTrue(result.lines.isEmpty());
        assertNotNull(result.entries);
        assertTrue(result.entries.isEmpty());
        assertNotNull(result.capsApplied);
        assertTrue(result.capsApplied.isEmpty());
        assertNotNull(result.warnings);
        assertTrue(result.warnings.isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.total));
        assertEquals(2, result.total.scale());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.burnableBase));
        assertEquals(2, result.burnableBase.scale());
    }

    /**
     * A result built from a non-null list takes the ternary false arm: the lines are copied
     * defensively, so a later mutation of the caller's list does not leak into the result.
     */
    @Test
    @DisplayName("non-null lines: ternary false arm copies defensively (source mutation isolated)")
    void nonNullLinesAreCopiedDefensively() {
        List<ValuedLine> source = new ArrayList<>();
        source.add(line("L1", "10.00"));
        source.add(line("L2", "20.00"));
        EarnResult result = new EarnResult(source);
        assertNotSame(source, result.lines);
        assertEquals(2, result.lines.size());
        assertEquals("L1", result.lines.get(0).lineId);
        assertEquals("L2", result.lines.get(1).lineId);
        source.add(line("L3", "30.00"));
        assertEquals(2, result.lines.size());
    }

    /**
     * The copied lines list is immutable ({@code List.copyOf}): a write attempt throws,
     * confirming the false arm produces a defensive read-only carrier (§22.1, §24.2).
     */
    @Test
    @DisplayName("non-null lines: copied list is immutable")
    void copiedLinesListIsImmutable() {
        EarnResult result = new EarnResult(new ArrayList<>(List.of(line("L1", "5.00"))));
        assertThrows(UnsupportedOperationException.class, () -> result.lines.add(line("L2", "6.00")));
    }

    /**
     * {@code toResponse()} maps every field of the internal result onto the projection DTO:
     * the two money totals are carried through (compared by {@code compareTo} at scale 2),
     * and each collection is copied with identical content preserved in order.
     */
    @Test
    @DisplayName("toResponse maps totals and copies every collection with content")
    void toResponseMapsTotalsAndCopiesCollections() {
        EarnResult result = new EarnResult(List.of(line("L1", "10.00")));
        result.total = new BigDecimal("12.30");
        result.burnableBase = new BigDecimal("45.60");
        result.entries.add(new EarnResponse.Entry(
                "RULE_A", "Rule A", new BigDecimal("2.00"), new BigDecimal("40.00"), List.of("L1")));
        result.capsApplied.add(new EarnResponse.CapApplied(
                "GLOBAL", new BigDecimal("15.00"), new BigDecimal("3.50")));
        result.warnings.add(new EarnResponse.Warning("UNKNOWN_EAN", "3250391234567", "Unknown EAN"));
        EarnResponse response = result.toResponse();
        assertNotNull(response);
        assertEquals(0, new BigDecimal("12.30").compareTo(response.total));
        assertEquals(0, new BigDecimal("45.60").compareTo(response.burnableBase));
        assertEquals(1, response.entries.size());
        assertEquals("RULE_A", response.entries.get(0).ruleCode);
        assertEquals(1, response.capsApplied.size());
        assertEquals("GLOBAL", response.capsApplied.get(0).scope);
        assertEquals(1, response.warnings.size());
        assertEquals("UNKNOWN_EAN", response.warnings.get(0).code);
    }

    /**
     * {@code toResponse()} defends the DTO from later builder writes: the response holds
     * fresh collection copies, so appending to the result's lists after the mapping does not
     * grow the response (§31.2), while the money fields carry the reference assigned.
     */
    @Test
    @DisplayName("toResponse collections are fresh copies, isolated from later builder writes")
    void toResponseCollectionsAreDefensiveCopies() {
        EarnResult result = new EarnResult(List.of());
        EarnResponse response = result.toResponse();
        assertNotSame(result.entries, response.entries);
        assertNotSame(result.capsApplied, response.capsApplied);
        assertNotSame(result.warnings, response.warnings);
        assertTrue(response.entries.isEmpty());
        assertTrue(response.capsApplied.isEmpty());
        assertTrue(response.warnings.isEmpty());
        result.entries.add(new EarnResponse.Entry(
                "RULE_B", "Rule B", new BigDecimal("1.00"), new BigDecimal("10.00"), List.of()));
        result.capsApplied.add(new EarnResponse.CapApplied(
                "GLOBAL", new BigDecimal("9.00"), new BigDecimal("1.00")));
        result.warnings.add(new EarnResponse.Warning("CARD_ABSENT", null, "No card"));
        assertTrue(response.entries.isEmpty());
        assertTrue(response.capsApplied.isEmpty());
        assertTrue(response.warnings.isEmpty());
    }

    /**
     * On a freshly constructed result, {@code toResponse()} yields the empty-earn default
     * projection: zero money at scale 2 and empty non-null collections (§15, §27.1).
     */
    @Test
    @DisplayName("toResponse on default result yields the empty-earn projection")
    void toResponseOnDefaultResultYieldsEmptyProjection() {
        EarnResult result = new EarnResult(null);
        EarnResponse response = result.toResponse();
        assertEquals(0, BigDecimal.ZERO.compareTo(response.total));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.burnableBase));
        assertTrue(response.entries.isEmpty());
        assertTrue(response.capsApplied.isEmpty());
        assertTrue(response.warnings.isEmpty());
    }
}
