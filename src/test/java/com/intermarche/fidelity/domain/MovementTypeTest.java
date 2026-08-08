package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link MovementType}: the closed nomenclature of loyalty ledger
 * movements (§14, §32.1, §19, I10). The type is a branchless enum — plugging a new earn
 * mechanic never adds a movement type, so the contract under test is the exact set, order
 * and naming of the nine constants plus the synthetic {@code values()}/{@code valueOf(String)}
 * round-trip. Pure logic, no Panache and no clock: nothing here reads a
 * {@code DateTimeProvider}.
 */
class MovementTypeTest {

    /**
     * The nomenclature is closed and ordered exactly as engraved (§14, §32.1, I10): the nine
     * movement types appear in declaration order, so both {@code values()} and each ordinal
     * are pinned.
     */
    @Test
    @DisplayName("values(): closed set of nine movement types in the specified declaration order")
    void valuesHoldsTheClosedOrderedNomenclature() {
        MovementType[] expected = {
                MovementType.EARN,
                MovementType.REFUND_CREDIT,
                MovementType.ADJUSTMENT,
                MovementType.BURN,
                MovementType.RETURN_DEBIT,
                MovementType.EXPIRY,
                MovementType.PURGE,
                MovementType.ACTIVATION_VOID,
                MovementType.TRANSFER
        };
        assertArrayEquals(expected, MovementType.values());
        assertEquals(9, MovementType.values().length);
    }

    /**
     * {@code values()} returns a fresh defensive array on each call: mutating one copy does
     * not leak into the enum's internal constant table (§31.2 defensive-copy discipline).
     */
    @Test
    @DisplayName("values(): each call yields a fresh array, mutation does not leak")
    void valuesReturnsFreshDefensiveArray() {
        MovementType[] first = MovementType.values();
        first[0] = MovementType.TRANSFER;
        assertSame(MovementType.EARN, MovementType.values()[0]);
    }

    /**
     * Each constant's {@code name()} matches its declared identifier, pinning the spelling
     * the ledger and the card sheet persist and render (§14, §32.1).
     */
    @Test
    @DisplayName("name(): every constant carries its specified spelling")
    void nameMatchesTheSpecifiedSpelling() {
        assertEquals("EARN", MovementType.EARN.name());
        assertEquals("REFUND_CREDIT", MovementType.REFUND_CREDIT.name());
        assertEquals("ADJUSTMENT", MovementType.ADJUSTMENT.name());
        assertEquals("BURN", MovementType.BURN.name());
        assertEquals("RETURN_DEBIT", MovementType.RETURN_DEBIT.name());
        assertEquals("EXPIRY", MovementType.EXPIRY.name());
        assertEquals("PURGE", MovementType.PURGE.name());
        assertEquals("ACTIVATION_VOID", MovementType.ACTIVATION_VOID.name());
        assertEquals("TRANSFER", MovementType.TRANSFER.name());
    }

    /**
     * Each constant sits at its specified ordinal, fixing the declaration order the closed
     * nomenclature relies on — {@code EARN} first, the canonical credit posted at the ticket
     * fiscal moment (§16, I8).
     */
    @Test
    @DisplayName("ordinal(): every constant sits at its specified position")
    void ordinalMatchesDeclarationOrder() {
        assertEquals(0, MovementType.EARN.ordinal());
        assertEquals(1, MovementType.REFUND_CREDIT.ordinal());
        assertEquals(2, MovementType.ADJUSTMENT.ordinal());
        assertEquals(3, MovementType.BURN.ordinal());
        assertEquals(4, MovementType.RETURN_DEBIT.ordinal());
        assertEquals(5, MovementType.EXPIRY.ordinal());
        assertEquals(6, MovementType.PURGE.ordinal());
        assertEquals(7, MovementType.ACTIVATION_VOID.ordinal());
        assertEquals(8, MovementType.TRANSFER.ordinal());
    }

    /**
     * {@code valueOf(String)} round-trips every declared name back to the same singleton
     * constant, confirming the synthetic parser resolves the whole nomenclature.
     */
    @Test
    @DisplayName("valueOf(): every declared name round-trips to its singleton constant")
    void valueOfRoundTripsEveryConstant() {
        for (MovementType type : MovementType.values()) {
            MovementType resolved = MovementType.valueOf(type.name());
            assertNotNull(resolved);
            assertSame(type, resolved);
        }
    }

    /**
     * {@code valueOf(String)} rejects a name outside the closed nomenclature: an unknown
     * movement is not silently coerced but raises {@link IllegalArgumentException} (§14, I10).
     */
    @Test
    @DisplayName("valueOf(): a name outside the nomenclature throws")
    void valueOfRejectsUnknownName() {
        assertThrows(IllegalArgumentException.class, () -> MovementType.valueOf("BONUS"));
    }

    /**
     * {@code valueOf(String)} rejects a null name with {@link NullPointerException}, covering
     * the null arm of the synthetic parser's argument guard.
     */
    @Test
    @DisplayName("valueOf(): a null name throws NullPointerException")
    void valueOfRejectsNullName() {
        assertThrows(NullPointerException.class, () -> MovementType.valueOf(null));
    }
}
