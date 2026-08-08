package com.intermarche.fidelity.earn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link WarningCode}: the closed nomenclature of {@code /earn}
 * and ingestion warnings (§27.1). The type is a branchless enum — extending it is a
 * document revision, so the contract under test is the exact set, order and naming of the
 * constants plus the synthetic {@code values()}/{@code valueOf(String)} round-trip. Pure
 * logic, no Panache and no clock: nothing here reads a {@code DateTimeProvider}.
 */
class WarningCodeTest {

    /**
     * The nomenclature is closed and ordered exactly as specified (§27.1): the five codes
     * appear in declaration order, so both {@code values()} and each ordinal are pinned.
     */
    @Test
    @DisplayName("values(): closed set of five codes in the specified declaration order")
    void valuesHoldsTheClosedOrderedNomenclature() {
        WarningCode[] expected = {
                WarningCode.UNKNOWN_EAN,
                WarningCode.EARN_MISMATCH,
                WarningCode.EXPIRED_LEASE_CONFIRMED,
                WarningCode.RESILIATED_ACCOUNT,
                WarningCode.CARD_MISMATCH
        };
        assertArrayEquals(expected, WarningCode.values());
        assertEquals(5, WarningCode.values().length);
    }

    /**
     * {@code values()} returns a fresh defensive array on each call: mutating one copy does
     * not leak into the enum's internal constant table (§31.2 defensive-copy discipline).
     */
    @Test
    @DisplayName("values(): each call yields a fresh array, mutation does not leak")
    void valuesReturnsFreshDefensiveArray() {
        WarningCode[] first = WarningCode.values();
        first[0] = WarningCode.CARD_MISMATCH;
        assertSame(WarningCode.UNKNOWN_EAN, WarningCode.values()[0]);
    }

    /**
     * Each constant's {@code name()} matches its declared identifier, pinning the wire
     * spelling used in the {@code /earn} projection and ingestion warnings (§27.1, §27.4).
     */
    @Test
    @DisplayName("name(): every constant carries its specified spelling")
    void nameMatchesTheSpecifiedSpelling() {
        assertEquals("UNKNOWN_EAN", WarningCode.UNKNOWN_EAN.name());
        assertEquals("EARN_MISMATCH", WarningCode.EARN_MISMATCH.name());
        assertEquals("EXPIRED_LEASE_CONFIRMED", WarningCode.EXPIRED_LEASE_CONFIRMED.name());
        assertEquals("RESILIATED_ACCOUNT", WarningCode.RESILIATED_ACCOUNT.name());
        assertEquals("CARD_MISMATCH", WarningCode.CARD_MISMATCH.name());
    }

    /**
     * Each constant sits at its specified ordinal, fixing the declaration order the closed
     * nomenclature relies on (§27.1).
     */
    @Test
    @DisplayName("ordinal(): every constant sits at its specified position")
    void ordinalMatchesDeclarationOrder() {
        assertEquals(0, WarningCode.UNKNOWN_EAN.ordinal());
        assertEquals(1, WarningCode.EARN_MISMATCH.ordinal());
        assertEquals(2, WarningCode.EXPIRED_LEASE_CONFIRMED.ordinal());
        assertEquals(3, WarningCode.RESILIATED_ACCOUNT.ordinal());
        assertEquals(4, WarningCode.CARD_MISMATCH.ordinal());
    }

    /**
     * {@code valueOf(String)} round-trips every declared name back to the same singleton
     * constant, confirming the synthetic parser resolves the whole nomenclature.
     */
    @Test
    @DisplayName("valueOf(): every declared name round-trips to its singleton constant")
    void valueOfRoundTripsEveryConstant() {
        for (WarningCode code : WarningCode.values()) {
            WarningCode resolved = WarningCode.valueOf(code.name());
            assertNotNull(resolved);
            assertSame(code, resolved);
        }
    }

    /**
     * {@code valueOf(String)} rejects a name outside the closed nomenclature: an unknown
     * code is not silently coerced but raises {@link IllegalArgumentException} (§27.1).
     */
    @Test
    @DisplayName("valueOf(): a name outside the nomenclature throws")
    void valueOfRejectsUnknownName() {
        assertThrows(IllegalArgumentException.class, () -> WarningCode.valueOf("CARD_ABSENT"));
    }

    /**
     * {@code valueOf(String)} rejects a null name with {@link NullPointerException}, covering
     * the null arm of the synthetic parser's argument guard.
     */
    @Test
    @DisplayName("valueOf(): a null name throws NullPointerException")
    void valueOfRejectsNullName() {
        assertThrows(NullPointerException.class, () -> WarningCode.valueOf(null));
    }
}
