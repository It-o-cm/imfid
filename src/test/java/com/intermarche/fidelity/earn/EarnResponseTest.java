package com.intermarche.fidelity.earn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link EarnResponse} and its nested carriers {@link
 * EarnResponse.Entry}, {@link EarnResponse.CapApplied} and {@link EarnResponse.Warning}:
 * the {@code POST /api/earn} projection response (§15, §27.1). The outer DTO is a
 * branch-free Jackson carrier whose only logic is the collection defaults never being
 * null (§31.2); the single branch of the whole surface is the {@code lineIds} defensive
 * copy ternary of {@link EarnResponse.Entry}, whose both arms are exercised here.
 */
class EarnResponseTest {

    /**
     * A freshly constructed response ships the money defaults at zero and every collection
     * as an empty, non-null list (§31.2), so an empty earn is a valid default projection.
     */
    @Test
    @DisplayName("default construction: zero money, empty non-null collections")
    void defaultConstructionShipsEmptyProjection() {
        EarnResponse response = new EarnResponse();
        assertEquals(0, BigDecimal.ZERO.compareTo(response.total));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.burnableBase));
        assertNotNull(response.entries);
        assertTrue(response.entries.isEmpty());
        assertNotNull(response.capsApplied);
        assertTrue(response.capsApplied.isEmpty());
        assertNotNull(response.warnings);
        assertTrue(response.warnings.isEmpty());
    }

    /**
     * The outer fields are plain carriers: each holds the exact reference or value
     * assigned, money compared by {@code compareTo} at scale 2 (§30.5).
     */
    @Test
    @DisplayName("outer fields carry the exact values assigned")
    void outerFieldsCarryAssignedValues() {
        EarnResponse response = new EarnResponse();
        List<EarnResponse.Entry> entries = new ArrayList<>();
        List<EarnResponse.CapApplied> caps = new ArrayList<>();
        List<EarnResponse.Warning> warnings = new ArrayList<>();
        response.total = new BigDecimal("12.30");
        response.burnableBase = new BigDecimal("45.60");
        response.entries = entries;
        response.capsApplied = caps;
        response.warnings = warnings;
        assertEquals(0, new BigDecimal("12.30").compareTo(response.total));
        assertEquals(0, new BigDecimal("45.60").compareTo(response.burnableBase));
        assertSame(entries, response.entries);
        assertSame(caps, response.capsApplied);
        assertSame(warnings, response.warnings);
    }

    /**
     * Entry with a non-null lineIds takes the true arm of the ternary: the ids are copied
     * defensively (a fresh list, not the caller's reference) with the same content, and the
     * money fields are compared by {@code compareTo}.
     */
    @Test
    @DisplayName("Entry copies non-null lineIds defensively (ternary true arm)")
    void entryCopiesNonNullLineIdsDefensively() {
        List<String> lineIds = new ArrayList<>(List.of("L1", "L2"));
        EarnResponse.Entry entry =
                new EarnResponse.Entry("RULE_A", "Rule A", new BigDecimal("2.00"), new BigDecimal("40.00"), lineIds);
        assertEquals("RULE_A", entry.ruleCode);
        assertEquals("Rule A", entry.label);
        assertEquals(0, new BigDecimal("2.00").compareTo(entry.amount));
        assertEquals(0, new BigDecimal("40.00").compareTo(entry.baseAmount));
        assertNotNull(entry.lineIds);
        assertNotSame(lineIds, entry.lineIds);
        assertEquals(List.of("L1", "L2"), entry.lineIds);
        lineIds.add("L3");
        assertEquals(List.of("L1", "L2"), entry.lineIds);
    }

    /**
     * Entry with a null lineIds takes the false arm of the ternary: the field falls back to
     * a fresh empty non-null list (§31.2) rather than propagating the null.
     */
    @Test
    @DisplayName("Entry with null lineIds falls back to empty list (ternary false arm)")
    void entryWithNullLineIdsFallsBackToEmptyList() {
        EarnResponse.Entry entry =
                new EarnResponse.Entry("RULE_B", "Rule B", new BigDecimal("0.00"), new BigDecimal("0.00"), null);
        assertNotNull(entry.lineIds);
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * The Entry default constructor leaves the scalar fields null (Jackson binds them) and
     * still guarantees a non-null empty lineIds (§31.2).
     */
    @Test
    @DisplayName("Entry default constructor: null scalars, non-null empty lineIds")
    void entryDefaultConstructorShipsEmptyLineIds() {
        EarnResponse.Entry entry = new EarnResponse.Entry();
        assertNull(entry.ruleCode);
        assertNull(entry.label);
        assertNull(entry.amount);
        assertNull(entry.baseAmount);
        assertNotNull(entry.lineIds);
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * CapApplied is a plain trace carrier: it holds the scope and the two money values
     * assigned, compared by {@code compareTo} at scale 2 (§30.5).
     */
    @Test
    @DisplayName("CapApplied carries scope, cap and truncation")
    void capAppliedCarriesAssignedValues() {
        EarnResponse.CapApplied cap =
                new EarnResponse.CapApplied("GLOBAL", new BigDecimal("15.00"), new BigDecimal("3.50"));
        assertEquals("GLOBAL", cap.scope);
        assertEquals(0, new BigDecimal("15.00").compareTo(cap.capAmount));
        assertEquals(0, new BigDecimal("3.50").compareTo(cap.truncatedBy));
    }

    /**
     * The CapApplied default constructor leaves every field null for Jackson to bind.
     */
    @Test
    @DisplayName("CapApplied default constructor leaves fields null")
    void capAppliedDefaultConstructorLeavesFieldsNull() {
        EarnResponse.CapApplied cap = new EarnResponse.CapApplied();
        assertNull(cap.scope);
        assertNull(cap.capAmount);
        assertNull(cap.truncatedBy);
    }

    /**
     * Warning is a plain carrier: with a concrete EAN it holds the code, the EAN and the
     * message exactly as assigned (§25.4).
     */
    @Test
    @DisplayName("Warning carries code, EAN and message")
    void warningCarriesAssignedValuesWithEan() {
        EarnResponse.Warning warning =
                new EarnResponse.Warning("UNKNOWN_EAN", "3250391234567", "Unknown EAN");
        assertEquals("UNKNOWN_EAN", warning.code);
        assertEquals("3250391234567", warning.ean);
        assertEquals("Unknown EAN", warning.message);
    }

    /**
     * A Warning that concerns no line carries a null EAN (§25.4) while still holding its
     * code and message, covering the EAN-absent shape of the carrier.
     */
    @Test
    @DisplayName("Warning carries a null EAN when not line-scoped")
    void warningCarriesNullEan() {
        EarnResponse.Warning warning = new EarnResponse.Warning("CARD_ABSENT", null, "No card");
        assertEquals("CARD_ABSENT", warning.code);
        assertNull(warning.ean);
        assertEquals("No card", warning.message);
    }

    /**
     * The Warning default constructor leaves every field null for Jackson to bind.
     */
    @Test
    @DisplayName("Warning default constructor leaves fields null")
    void warningDefaultConstructorLeavesFieldsNull() {
        EarnResponse.Warning warning = new EarnResponse.Warning();
        assertNull(warning.code);
        assertNull(warning.ean);
        assertNull(warning.message);
    }
}
