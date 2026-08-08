package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link EarnTrace}: the ticket header of the earn trace (§24.2, §29.1).
 * The class holds no temporal logic of its own — {@code fiscalDate} is a stored field, not a
 * clock read — so nothing here touches a {@code DateTimeProvider}; the Panache active-record
 * finders are mocked through {@link PanacheEntityBase} in a try-with-resources per the imfid
 * unit bench, and the checksum is asserted as the pure function of the business fields it is.
 */
class EarnTraceTest {

    // --------------------------------------------------
    // addLine() — null guard, both arms
    // --------------------------------------------------

    /**
     * The non-null arm wires the line's back-reference to this trace and appends it to the
     * detail list, growing it by one.
     */
    @Test
    @DisplayName("addLine(): a non-null line is wired back and appended")
    void addLineNonNull() {
        EarnTrace trace = new EarnTrace();
        EarnTraceLine line = new EarnTraceLine();
        trace.addLine(line);
        assertEquals(1, trace.lines.size());
        assertSame(line, trace.lines.get(0));
        assertSame(trace, line.trace);
    }

    /**
     * The null arm is a no-op: a null line is ignored, the back-reference untouched and the
     * detail list left empty (§31.2).
     */
    @Test
    @DisplayName("addLine(): a null line is ignored")
    void addLineNull() {
        EarnTrace trace = new EarnTrace();
        trace.addLine(null);
        assertTrue(trace.lines.isEmpty());
    }

    // --------------------------------------------------
    // findByTicketRef()
    // --------------------------------------------------

    /**
     * The ticket finder delegates to the {@code ticketRef} equality query and returns its first
     * result when a trace matches the reference.
     */
    @Test
    @DisplayName("findByTicketRef(): returns the matching trace")
    void findByTicketRefFound() {
        EarnTrace found = new EarnTrace();
        @SuppressWarnings("unchecked")
        PanacheQuery<EarnTrace> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("ticketRef", "STORE-42#1001")).thenReturn(query);
            assertSame(found, EarnTrace.findByTicketRef("STORE-42#1001"));
        }
    }

    /**
     * The ticket finder returns null when no trace bears the reference.
     */
    @Test
    @DisplayName("findByTicketRef(): returns null when no trace matches")
    void findByTicketRefAbsent() {
        @SuppressWarnings("unchecked")
        PanacheQuery<EarnTrace> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("ticketRef", "STORE-42#9999")).thenReturn(query);
            assertNull(EarnTrace.findByTicketRef("STORE-42#9999"));
        }
    }

    // --------------------------------------------------
    // countVisits()
    // --------------------------------------------------

    /**
     * The visit count projects the distinct-fiscal-day query to a {@code Long} and returns its
     * first result, the number of distinct visit days over the inclusive range (§29.1).
     */
    @Test
    @DisplayName("countVisits(): projects the distinct-day query and returns the count")
    void countVisitsReturnsCount() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        @SuppressWarnings("unchecked")
        PanacheQuery<EarnTrace> query = Mockito.mock(PanacheQuery.class);
        @SuppressWarnings("unchecked")
        PanacheQuery<Long> projected = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.project(Long.class)).thenReturn(projected);
        Mockito.when(projected.firstResult()).thenReturn(3L);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "select count(distinct t.fiscalDate) from EarnTrace t "
                            + "where t.cardNumber = ?1 and t.fiscalDate >= ?2 and t.fiscalDate <= ?3",
                    "CARD-1", from, to)).thenReturn(query);
            assertEquals(3L, EarnTrace.countVisits("CARD-1", from, to));
        }
    }

    // --------------------------------------------------
    // findLatest()
    // --------------------------------------------------

    /**
     * The latest finder pages the newest-first query to the requested size and returns its
     * list, newest first.
     */
    @Test
    @DisplayName("findLatest(): pages the newest-first query and returns its list")
    void findLatestReturnsPage() {
        EarnTrace first = new EarnTrace();
        EarnTrace second = new EarnTrace();
        List<EarnTrace> traces = List.of(first, second);
        @SuppressWarnings("unchecked")
        PanacheQuery<EarnTrace> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.page(ArgumentMatchers.any(Page.class))).thenReturn(query);
        Mockito.when(query.list()).thenReturn(traces);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("order by createdAt desc")).thenReturn(query);
            assertEquals(traces, EarnTrace.findLatest(5));
        }
    }

    // --------------------------------------------------
    // deleteOlderThan()
    // --------------------------------------------------

    /**
     * The retention purge delegates to the {@code createdAt < ?1} bulk delete and returns the
     * number of removed traces (§25.5).
     */
    @Test
    @DisplayName("deleteOlderThan(): deletes traces older than the threshold and returns the count")
    void deleteOlderThanReturnsCount() {
        LocalDateTime threshold = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.delete("createdAt < ?1", threshold)).thenReturn(7L);
            assertEquals(7L, EarnTrace.deleteOlderThan(threshold));
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The checksum is a pure function of the business fields: two traces with identical
     * attributes share it.
     */
    @Test
    @DisplayName("getChecksum(): identical business fields share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * Changing the ticket reference — a business field — changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a different ticket reference alters the checksum")
    void checksumChangesWithTicketRef() {
        EarnTrace other = sample();
        other.ticketRef = "STORE-9#2";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Changing the card number — a business field — changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a different card number alters the checksum")
    void checksumChangesWithCardNumber() {
        EarnTrace other = sample();
        other.cardNumber = "CARD-2";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Changing the store code — a business field — changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a different store code alters the checksum")
    void checksumChangesWithStoreCode() {
        EarnTrace other = sample();
        other.storeCode = "S99";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Changing the fiscal date — a business field — changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a different fiscal date alters the checksum")
    void checksumChangesWithFiscalDate() {
        EarnTrace other = sample();
        other.fiscalDate = LocalDate.of(2026, 12, 31);
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Changing the status — a business field — changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a different status alters the checksum")
    void checksumChangesWithStatus() {
        EarnTrace other = sample();
        other.status = EarnTrace.STATUS_FAILED;
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Changing the displayed earn — a business field — changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a different displayed earn alters the checksum")
    void checksumChangesWithDisplayedEarn() {
        EarnTrace other = sample();
        other.displayedEarn = new BigDecimal("9.99");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Changing the recalculated earn — the authoritative business field — changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a different recalculated earn alters the checksum")
    void checksumChangesWithRecalculatedEarn() {
        EarnTrace other = sample();
        other.recalculatedEarn = new BigDecimal("8.88");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    // --------------------------------------------------
    // Field initializers
    // --------------------------------------------------

    /**
     * A freshly constructed trace starts with empty, non-null warning and line collections so
     * the connector never returns null for a collection (§31.2).
     */
    @Test
    @DisplayName("new: warnings and lines default to empty non-null lists")
    void collectionsDefaultToEmpty() {
        EarnTrace trace = new EarnTrace();
        assertTrue(trace.warnings.isEmpty());
        assertTrue(trace.lines.isEmpty());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a fully-populated trace with fixed business fields for checksum assertions.
     *
     * @return A sample trace with deterministic attributes.
     */
    private EarnTrace sample() {
        EarnTrace trace = new EarnTrace();
        trace.ticketRef = "STORE-42#1001";
        trace.cardNumber = "CARD-1";
        trace.storeCode = "S42";
        trace.fiscalDate = LocalDate.of(2026, 1, 15);
        trace.status = EarnTrace.STATUS_SUCCESS;
        trace.displayedEarn = new BigDecimal("12.34");
        trace.recalculatedEarn = new BigDecimal("12.34");
        return trace;
    }
}
