package com.intermarche.fidelity.batch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link BatchType}: the closed nomenclature of the account
 * lifecycle batches (§16). The type is a branchless enum — adding a batch is a document
 * revision, so the contract under test is the exact set, order and naming of the three
 * constants plus the synthetic {@code values()}/{@code valueOf(String)} round-trip. Pure
 * logic, no Panache and no clock: nothing here reads a {@code DateTimeProvider}.
 */
class BatchTypeTest {

    /**
     * The nomenclature is closed and ordered exactly as specified (§16): the three batches
     * appear in declaration order, so both {@code values()} and each ordinal are pinned.
     */
    @Test
    @DisplayName("values(): closed set of three batches in the specified declaration order")
    void valuesHoldsTheClosedOrderedNomenclature() {
        BatchType[] expected = {
                BatchType.EXPIRY,
                BatchType.PURGE,
                BatchType.ACTIVATION_VOID
        };
        assertArrayEquals(expected, BatchType.values());
        assertEquals(3, BatchType.values().length);
    }

    /**
     * {@code values()} returns a fresh defensive array on each call: mutating one copy does
     * not leak into the enum's internal constant table (§31.2 defensive-copy discipline).
     */
    @Test
    @DisplayName("values(): each call yields a fresh array, mutation does not leak")
    void valuesReturnsFreshDefensiveArray() {
        BatchType[] first = BatchType.values();
        first[0] = BatchType.ACTIVATION_VOID;
        assertSame(BatchType.EXPIRY, BatchType.values()[0]);
    }

    /**
     * Each constant's {@code name()} matches its declared identifier, pinning the spelling
     * used by the admin UI and GraphQL trigger nomenclature (§23.4, §32.3).
     */
    @Test
    @DisplayName("name(): every constant carries its specified spelling")
    void nameMatchesTheSpecifiedSpelling() {
        assertEquals("EXPIRY", BatchType.EXPIRY.name());
        assertEquals("PURGE", BatchType.PURGE.name());
        assertEquals("ACTIVATION_VOID", BatchType.ACTIVATION_VOID.name());
    }

    /**
     * Each constant sits at its specified ordinal, fixing the declaration order the closed
     * nomenclature relies on (§16).
     */
    @Test
    @DisplayName("ordinal(): every constant sits at its specified position")
    void ordinalMatchesDeclarationOrder() {
        assertEquals(0, BatchType.EXPIRY.ordinal());
        assertEquals(1, BatchType.PURGE.ordinal());
        assertEquals(2, BatchType.ACTIVATION_VOID.ordinal());
    }

    /**
     * {@code valueOf(String)} round-trips every declared name back to the same singleton
     * constant, confirming the synthetic parser resolves the whole nomenclature.
     */
    @Test
    @DisplayName("valueOf(): every declared name round-trips to its singleton constant")
    void valueOfRoundTripsEveryConstant() {
        for (BatchType type : BatchType.values()) {
            BatchType resolved = BatchType.valueOf(type.name());
            assertNotNull(resolved);
            assertSame(type, resolved);
        }
    }

    /**
     * {@code valueOf(String)} rejects a name outside the closed nomenclature: an unknown
     * batch is not silently coerced but raises {@link IllegalArgumentException} (§16).
     */
    @Test
    @DisplayName("valueOf(): a name outside the nomenclature throws")
    void valueOfRejectsUnknownName() {
        assertThrows(IllegalArgumentException.class, () -> BatchType.valueOf("REACTIVATION"));
    }

    /**
     * {@code valueOf(String)} rejects a null name with {@link NullPointerException}, covering
     * the null arm of the synthetic parser's argument guard.
     */
    @Test
    @DisplayName("valueOf(): a null name throws NullPointerException")
    void valueOfRejectsNullName() {
        assertThrows(NullPointerException.class, () -> BatchType.valueOf(null));
    }
}
