package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link PendingReturn}: a return event held until its origin ticket arrives
 * (§29.3). The class carries no temporal or compound guards — only two Panache active-record finders
 * and a checksum. The finders are driven through a {@link PanacheEntityBase} static mock in a
 * try-with-resources per the imfid unit bench, both the found and absent outcomes of the
 * {@code firstResult} lookup exercised, and the checksum is asserted as the pure function of the two
 * business references it is.
 */
class PendingReturnTest {

    // --------------------------------------------------
    // findByReturnTicketRef()
    // --------------------------------------------------

    /**
     * The finder queries the {@code returnTicketRef} column and returns its first result — the held
     * return whose idempotency key matches the replayed return ticket reference (§29.4).
     */
    @Test
    @DisplayName("findByReturnTicketRef(): returns the held return matching the reference")
    void findByReturnTicketRefFound() {
        PendingReturn match = new PendingReturn();
        @SuppressWarnings("unchecked")
        PanacheQuery<PendingReturn> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(match);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("returnTicketRef", "R-42")).thenReturn(query);
            assertSame(match, PendingReturn.findByReturnTicketRef("R-42"));
        }
    }

    /**
     * An unknown return ticket reference yields a null first result, so the caller distinguishes the
     * absence of any held return for that key (§29.3).
     */
    @Test
    @DisplayName("findByReturnTicketRef(): returns null when no held return matches")
    void findByReturnTicketRefAbsent() {
        @SuppressWarnings("unchecked")
        PanacheQuery<PendingReturn> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("returnTicketRef", "unknown")).thenReturn(query);
            assertNull(PendingReturn.findByReturnTicketRef("unknown"));
        }
    }

    // --------------------------------------------------
    // listAwaitingOrigin()
    // --------------------------------------------------

    /**
     * The listing queries the {@code originTicketRef} column and returns every held return awaiting
     * that origin ticket, so ingestion can replay them all when the origin arrives (§29.3).
     */
    @Test
    @DisplayName("listAwaitingOrigin(): returns the held returns awaiting the origin ticket")
    void listAwaitingOriginFound() {
        PendingReturn first = new PendingReturn();
        PendingReturn second = new PendingReturn();
        List<PendingReturn> held = List.of(first, second);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("originTicketRef", "O-7")).thenReturn(held);
            assertSame(held, PendingReturn.listAwaitingOrigin("O-7"));
        }
    }

    /**
     * An origin ticket with nothing held awaiting it yields the empty list Panache returns, never
     * null, so the caller iterates without a nullity guard (§29.3).
     */
    @Test
    @DisplayName("listAwaitingOrigin(): returns an empty list when nothing awaits the origin")
    void listAwaitingOriginEmpty() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("originTicketRef", "O-none"))
                    .thenReturn(List.of());
            List<PendingReturn> result = PendingReturn.listAwaitingOrigin("O-none");
            assertTrue(result.isEmpty());
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * Two held returns with identical references share a checksum, so the staged import fallback and
     * idempotent hold detect an unchanged row without a field-by-field comparison.
     */
    @Test
    @DisplayName("getChecksum(): identical references share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * Changing the return ticket reference changes the checksum, so a divergent held return is
     * detected (§29.4).
     */
    @Test
    @DisplayName("getChecksum(): a different return ticket reference alters the checksum")
    void checksumChangesWithReturnRef() {
        PendingReturn other = sample();
        other.returnTicketRef = "R-99";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Changing the origin ticket reference changes the checksum, so a held return awaiting a
     * different origin is detected (§29.3).
     */
    @Test
    @DisplayName("getChecksum(): a different origin ticket reference alters the checksum")
    void checksumChangesWithOriginRef() {
        PendingReturn other = sample();
        other.originTicketRef = "O-99";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a fully-populated held return with fixed business references for checksum assertions.
     *
     * @return A sample held return with deterministic references.
     */
    private PendingReturn sample() {
        PendingReturn held = new PendingReturn();
        held.returnTicketRef = "R-42";
        held.originTicketRef = "O-7";
        held.payload = "{\"event\":\"ticket-return\"}";
        return held;
    }
}
