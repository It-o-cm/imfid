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
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link BatchRunLog}: the supervision record of the last batch run
 * (§23.4). The class holds no temporal logic of its own — {@code runAt} is a stored field, not
 * a clock read — so nothing here touches a {@code DateTimeProvider}; the Panache active-record
 * finders are mocked through {@link PanacheEntityBase} in a try-with-resources per the imfid
 * unit bench, and the checksum is asserted as the pure function of the business fields it is.
 */
class BatchRunLogTest {

    // --------------------------------------------------
    // lastRun()
    // --------------------------------------------------

    /**
     * The last-run finder delegates to the newest-first Panache query and returns its first
     * result when the batch type has already run.
     */
    @Test
    @DisplayName("lastRun(): returns the most recent run of the batch type")
    void lastRunFound() {
        BatchRunLog found = new BatchRunLog();
        @SuppressWarnings("unchecked")
        PanacheQuery<BatchRunLog> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "batchType = ?1 order by runAt desc", "EXPIRY")).thenReturn(query);
            assertSame(found, BatchRunLog.lastRun("EXPIRY"));
        }
    }

    /**
     * The last-run finder returns null when the batch type has never run (§23.4).
     */
    @Test
    @DisplayName("lastRun(): returns null when the batch type never ran")
    void lastRunAbsent() {
        @SuppressWarnings("unchecked")
        PanacheQuery<BatchRunLog> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "batchType = ?1 order by runAt desc", "PURGE")).thenReturn(query);
            assertNull(BatchRunLog.lastRun("PURGE"));
        }
    }

    // --------------------------------------------------
    // latest()
    // --------------------------------------------------

    /**
     * The latest finder pages the newest-first query to the requested size and returns its
     * list, newest first.
     */
    @Test
    @DisplayName("latest(): pages the newest-first query and returns its list")
    void latestReturnsPage() {
        BatchRunLog first = new BatchRunLog();
        BatchRunLog second = new BatchRunLog();
        List<BatchRunLog> runs = List.of(first, second);
        @SuppressWarnings("unchecked")
        PanacheQuery<BatchRunLog> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.page(ArgumentMatchers.any(Page.class))).thenReturn(query);
        Mockito.when(query.list()).thenReturn(runs);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("order by runAt desc")).thenReturn(query);
            assertEquals(runs, BatchRunLog.latest(5));
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The checksum is a pure function of the business fields: two logs with identical
     * attributes share it.
     */
    @Test
    @DisplayName("getChecksum(): identical business fields share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * Changing any business field — here the moved amount — changes the checksum, so any run
     * difference is detected.
     */
    @Test
    @DisplayName("getChecksum(): a different amount alters the checksum")
    void checksumChangesWithAmount() {
        BatchRunLog other = sample();
        other.totalAmount = new BigDecimal("99.99");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    // --------------------------------------------------
    // Field initializer
    // --------------------------------------------------

    /**
     * A freshly constructed log starts with a zero moved amount at scale 2, compared by
     * {@code compareTo} so {@code 0.00} and {@code 0} are treated as equal.
     */
    @Test
    @DisplayName("new: total amount defaults to zero")
    void totalAmountDefaultsToZero() {
        assertTrue(BigDecimal.ZERO.compareTo(new BatchRunLog().totalAmount) == 0);
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a fully-populated run log with fixed business fields for checksum assertions.
     *
     * @return A sample log with deterministic attributes.
     */
    private BatchRunLog sample() {
        BatchRunLog log = new BatchRunLog();
        log.batchType = "EXPIRY";
        log.runAt = LocalDateTime.of(2026, 1, 1, 3, 0, 0);
        log.accountsAffected = 42;
        log.totalAmount = new BigDecimal("12.34");
        return log;
    }
}
