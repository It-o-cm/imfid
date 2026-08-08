package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link AccountStatus}: the closed lifecycle nomenclature of a
 * {@link FidelityAccount} (§14, §25.3, §30.4). The type is a branchless enum — adding a
 * status is a document revision, so the contract under test is the exact set, order and
 * naming of the three constants plus the synthetic {@code values()}/{@code valueOf(String)}
 * round-trip. Pure logic, no Panache and no clock: nothing here reads a
 * {@code DateTimeProvider}.
 */
class AccountStatusTest {

    /**
     * The nomenclature is closed and ordered exactly as specified (§14, §30.4): the three
     * statuses appear in declaration order, so both {@code values()} and each ordinal are
     * pinned.
     */
    @Test
    @DisplayName("values(): closed set of three statuses in the specified declaration order")
    void valuesHoldsTheClosedOrderedNomenclature() {
        AccountStatus[] expected = {
                AccountStatus.ACTIVE,
                AccountStatus.PENDING_ACTIVATION,
                AccountStatus.RESILIATED
        };
        assertArrayEquals(expected, AccountStatus.values());
        assertEquals(3, AccountStatus.values().length);
    }

    /**
     * {@code values()} returns a fresh defensive array on each call: mutating one copy does
     * not leak into the enum's internal constant table (§31.2 defensive-copy discipline).
     */
    @Test
    @DisplayName("values(): each call yields a fresh array, mutation does not leak")
    void valuesReturnsFreshDefensiveArray() {
        AccountStatus[] first = AccountStatus.values();
        first[0] = AccountStatus.RESILIATED;
        assertSame(AccountStatus.ACTIVE, AccountStatus.values()[0]);
    }

    /**
     * Each constant's {@code name()} matches its declared identifier, pinning the spelling
     * used by the card-creation screen and the administration history sheet (§25.3, §30.4).
     */
    @Test
    @DisplayName("name(): every constant carries its specified spelling")
    void nameMatchesTheSpecifiedSpelling() {
        assertEquals("ACTIVE", AccountStatus.ACTIVE.name());
        assertEquals("PENDING_ACTIVATION", AccountStatus.PENDING_ACTIVATION.name());
        assertEquals("RESILIATED", AccountStatus.RESILIATED.name());
    }

    /**
     * Each constant sits at its specified ordinal, fixing the declaration order the closed
     * nomenclature relies on — {@code ACTIVE} first, since it is the default status offered
     * at card creation (§30.4).
     */
    @Test
    @DisplayName("ordinal(): every constant sits at its specified position")
    void ordinalMatchesDeclarationOrder() {
        assertEquals(0, AccountStatus.ACTIVE.ordinal());
        assertEquals(1, AccountStatus.PENDING_ACTIVATION.ordinal());
        assertEquals(2, AccountStatus.RESILIATED.ordinal());
    }

    /**
     * {@code valueOf(String)} round-trips every declared name back to the same singleton
     * constant, confirming the synthetic parser resolves the whole nomenclature.
     */
    @Test
    @DisplayName("valueOf(): every declared name round-trips to its singleton constant")
    void valueOfRoundTripsEveryConstant() {
        for (AccountStatus status : AccountStatus.values()) {
            AccountStatus resolved = AccountStatus.valueOf(status.name());
            assertNotNull(resolved);
            assertSame(status, resolved);
        }
    }

    /**
     * {@code valueOf(String)} rejects a name outside the closed nomenclature: an unknown
     * status is not silently coerced but raises {@link IllegalArgumentException} (§14).
     */
    @Test
    @DisplayName("valueOf(): a name outside the nomenclature throws")
    void valueOfRejectsUnknownName() {
        assertThrows(IllegalArgumentException.class, () -> AccountStatus.valueOf("SUSPENDED"));
    }

    /**
     * {@code valueOf(String)} rejects a null name with {@link NullPointerException}, covering
     * the null arm of the synthetic parser's argument guard.
     */
    @Test
    @DisplayName("valueOf(): a null name throws NullPointerException")
    void valueOfRejectsNullName() {
        assertThrows(NullPointerException.class, () -> AccountStatus.valueOf(null));
    }
}
