package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link ReservationState}: the closed lifecycle of a burn
 * reservation lease (§16, I11, §25.5). The type is a branchless enum — the reservation
 * to-bail-then-confirm protocol never grows a fifth state, so the contract under test is
 * the exact set, order and naming of the four constants plus the synthetic
 * {@code values()}/{@code valueOf(String)} round-trip. Pure logic, no Panache and no
 * clock: nothing here reads a {@code DateTimeProvider}.
 */
class ReservationStateTest {

    /**
     * The lifecycle is closed and ordered exactly as engraved (§16, I11): the four states
     * appear in declaration order, so both {@code values()} and each ordinal are pinned.
     */
    @Test
    @DisplayName("values(): closed set of four lease states in the specified declaration order")
    void valuesHoldsTheClosedOrderedLifecycle() {
        ReservationState[] expected = {
                ReservationState.ACTIVE,
                ReservationState.CONFIRMED,
                ReservationState.RELEASED,
                ReservationState.EXPIRED
        };
        assertArrayEquals(expected, ReservationState.values());
        assertEquals(4, ReservationState.values().length);
    }

    /**
     * {@code values()} returns a fresh defensive array on each call: mutating one copy does
     * not leak into the enum's internal constant table (§31.2 defensive-copy discipline).
     */
    @Test
    @DisplayName("values(): each call yields a fresh array, mutation does not leak")
    void valuesReturnsFreshDefensiveArray() {
        ReservationState[] first = ReservationState.values();
        first[0] = ReservationState.EXPIRED;
        assertSame(ReservationState.ACTIVE, ReservationState.values()[0]);
    }

    /**
     * Each constant's {@code name()} matches its declared identifier, pinning the spelling
     * the reservation ledger persists and the admin sheet renders (§16).
     */
    @Test
    @DisplayName("name(): every constant carries its specified spelling")
    void nameMatchesTheSpecifiedSpelling() {
        assertEquals("ACTIVE", ReservationState.ACTIVE.name());
        assertEquals("CONFIRMED", ReservationState.CONFIRMED.name());
        assertEquals("RELEASED", ReservationState.RELEASED.name());
        assertEquals("EXPIRED", ReservationState.EXPIRED.name());
    }

    /**
     * Each constant sits at its specified ordinal, fixing the declaration order the closed
     * lifecycle relies on — {@code ACTIVE} first, the live lease reserving part of the
     * balance at the register (§16).
     */
    @Test
    @DisplayName("ordinal(): every constant sits at its specified position")
    void ordinalMatchesDeclarationOrder() {
        assertEquals(0, ReservationState.ACTIVE.ordinal());
        assertEquals(1, ReservationState.CONFIRMED.ordinal());
        assertEquals(2, ReservationState.RELEASED.ordinal());
        assertEquals(3, ReservationState.EXPIRED.ordinal());
    }

    /**
     * {@code valueOf(String)} round-trips every declared name back to the same singleton
     * constant, confirming the synthetic parser resolves the whole lifecycle.
     */
    @Test
    @DisplayName("valueOf(): every declared name round-trips to its singleton constant")
    void valueOfRoundTripsEveryConstant() {
        for (ReservationState state : ReservationState.values()) {
            ReservationState resolved = ReservationState.valueOf(state.name());
            assertNotNull(resolved);
            assertSame(state, resolved);
        }
    }

    /**
     * {@code valueOf(String)} rejects a name outside the closed lifecycle: an unknown state
     * is not silently coerced but raises {@link IllegalArgumentException} (§16, I11).
     */
    @Test
    @DisplayName("valueOf(): a name outside the lifecycle throws")
    void valueOfRejectsUnknownName() {
        assertThrows(IllegalArgumentException.class, () -> ReservationState.valueOf("PENDING"));
    }

    /**
     * {@code valueOf(String)} rejects a null name with {@link NullPointerException}, covering
     * the null arm of the synthetic parser's argument guard.
     */
    @Test
    @DisplayName("valueOf(): a null name throws NullPointerException")
    void valueOfRejectsNullName() {
        assertThrows(NullPointerException.class, () -> ReservationState.valueOf(null));
    }
}
