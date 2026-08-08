package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link FidelityProgramSetting}: the typed, administrable key/value
 * program parameters (§25.1). Every guard, ternary and compound predicate is exercised on both
 * arms and every leg (§29, §29.6): the three-leg blank guards of {@link
 * FidelityProgramSetting#getDecimal} and {@link FidelityProgramSetting#getInt}, their parse
 * {@code catch} arms, and the fallback ternary of {@link FidelityProgramSetting#getString}. The
 * Panache active-record finder is mocked through {@link PanacheEntityBase} in a
 * try-with-resources per the imfid unit bench, and every decimal is asserted by {@code compareTo}.
 * The class holds no temporal logic, so nothing here reads a {@code DateTimeProvider}.
 */
class FidelityProgramSettingTest {

    // --------------------------------------------------
    // findByKey()
    // --------------------------------------------------

    /**
     * The key finder delegates to the Panache query and returns its first result — the found arm.
     */
    @Test
    @DisplayName("findByKey(): returns the matching setting")
    void findByKeyFound() {
        FidelityProgramSetting found = new FidelityProgramSetting();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, FidelityProgramSetting.KEY_PROGRAM_ZONE, found);
            assertSame(found, FidelityProgramSetting.findByKey(FidelityProgramSetting.KEY_PROGRAM_ZONE));
        }
    }

    /**
     * The key finder returns null when the query yields nothing — the absent arm.
     */
    @Test
    @DisplayName("findByKey(): returns null when absent")
    void findByKeyAbsent() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "ghost.key", null);
            assertNull(FidelityProgramSetting.findByKey("ghost.key"));
        }
    }

    // --------------------------------------------------
    // getString()
    // --------------------------------------------------

    /**
     * A present setting returns its stored value — the ternary's non-null (true) arm.
     */
    @Test
    @DisplayName("getString(): a present setting returns its value (true arm)")
    void getStringPresent() {
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.value = "Europe/Paris";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, FidelityProgramSetting.KEY_PROGRAM_ZONE, setting);
            assertEquals("Europe/Paris",
                    FidelityProgramSetting.getString(FidelityProgramSetting.KEY_PROGRAM_ZONE, "UTC"));
        }
    }

    /**
     * An absent setting yields the caller's fallback — the ternary's null (false) arm.
     */
    @Test
    @DisplayName("getString(): an absent setting yields the fallback (false arm)")
    void getStringFallback() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, FidelityProgramSetting.KEY_PROGRAM_ZONE, null);
            assertEquals("UTC",
                    FidelityProgramSetting.getString(FidelityProgramSetting.KEY_PROGRAM_ZONE, "UTC"));
        }
    }

    // --------------------------------------------------
    // getDecimal()
    // --------------------------------------------------

    /**
     * An absent setting yields the fallback — the first leg ({@code setting == null}) of the guard.
     */
    @Test
    @DisplayName("getDecimal(): absent setting yields the fallback (leg 1)")
    void getDecimalAbsent() {
        BigDecimal fallback = new BigDecimal("400.00");
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, null);
            assertEquals(0, fallback.compareTo(
                    FidelityProgramSetting.getDecimal(FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, fallback)));
        }
    }

    /**
     * A present setting with a null value yields the fallback — the second leg
     * ({@code setting.value == null}) of the guard.
     */
    @Test
    @DisplayName("getDecimal(): null value yields the fallback (leg 2)")
    void getDecimalNullValue() {
        BigDecimal fallback = new BigDecimal("400.00");
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.value = null;
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, setting);
            assertEquals(0, fallback.compareTo(
                    FidelityProgramSetting.getDecimal(FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, fallback)));
        }
    }

    /**
     * A present setting with a blank value yields the fallback — the third leg
     * ({@code setting.value.isBlank()}) of the guard.
     */
    @Test
    @DisplayName("getDecimal(): blank value yields the fallback (leg 3)")
    void getDecimalBlankValue() {
        BigDecimal fallback = new BigDecimal("400.00");
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.value = "   ";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, setting);
            assertEquals(0, fallback.compareTo(
                    FidelityProgramSetting.getDecimal(FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, fallback)));
        }
    }

    /**
     * A well-formed decimal value is trimmed and parsed — every guard leg false, the nominal arm.
     */
    @Test
    @DisplayName("getDecimal(): a well-formed value is trimmed and parsed (all legs false)")
    void getDecimalParsed() {
        BigDecimal fallback = new BigDecimal("400.00");
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.value = "  250.50  ";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, setting);
            assertEquals(0, new BigDecimal("250.50").compareTo(
                    FidelityProgramSetting.getDecimal(FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, fallback)));
        }
    }

    /**
     * An unparseable value yields the fallback — the {@code NumberFormatException} catch arm.
     */
    @Test
    @DisplayName("getDecimal(): an unparseable value yields the fallback (catch arm)")
    void getDecimalUnparseable() {
        BigDecimal fallback = new BigDecimal("400.00");
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.value = "not-a-number";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, setting);
            assertEquals(0, fallback.compareTo(
                    FidelityProgramSetting.getDecimal(FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, fallback)));
        }
    }

    // --------------------------------------------------
    // getInt()
    // --------------------------------------------------

    /**
     * An absent setting yields the fallback — the first leg ({@code setting == null}) of the guard.
     */
    @Test
    @DisplayName("getInt(): absent setting yields the fallback (leg 1)")
    void getIntAbsent() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, null);
            assertEquals(900,
                    FidelityProgramSetting.getInt(FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, 900));
        }
    }

    /**
     * A present setting with a null value yields the fallback — the second leg
     * ({@code setting.value == null}) of the guard.
     */
    @Test
    @DisplayName("getInt(): null value yields the fallback (leg 2)")
    void getIntNullValue() {
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.value = null;
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, setting);
            assertEquals(900,
                    FidelityProgramSetting.getInt(FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, 900));
        }
    }

    /**
     * A present setting with a blank value yields the fallback — the third leg
     * ({@code setting.value.isBlank()}) of the guard.
     */
    @Test
    @DisplayName("getInt(): blank value yields the fallback (leg 3)")
    void getIntBlankValue() {
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.value = "  ";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, setting);
            assertEquals(900,
                    FidelityProgramSetting.getInt(FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, 900));
        }
    }

    /**
     * A well-formed integer value is trimmed and parsed — every guard leg false, the nominal arm.
     */
    @Test
    @DisplayName("getInt(): a well-formed value is trimmed and parsed (all legs false)")
    void getIntParsed() {
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.value = "  1800  ";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, setting);
            assertEquals(1800,
                    FidelityProgramSetting.getInt(FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, 900));
        }
    }

    /**
     * An unparseable value yields the fallback — the {@code NumberFormatException} catch arm.
     */
    @Test
    @DisplayName("getInt(): an unparseable value yields the fallback (catch arm)")
    void getIntUnparseable() {
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.value = "twelve";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, setting);
            assertEquals(900,
                    FidelityProgramSetting.getInt(FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, 900));
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The checksum is a pure function of the key and value: two settings with identical
     * attributes share it.
     */
    @Test
    @DisplayName("getChecksum(): identical key/value share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * Changing the value changes the checksum, so an edit is detected like any other modification.
     */
    @Test
    @DisplayName("getChecksum(): a value change alters the checksum")
    void checksumChangesWithValue() {
        FidelityProgramSetting other = sample();
        other.value = "500.00";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Registers the Panache {@code find("key", key)} static call so its {@code firstResult()}
     * yields the supplied setting, emulating the active-record finder without a Quarkus boot.
     *
     * @param panache The static mock of {@link PanacheEntityBase}.
     * @param key     The setting key expected by the finder.
     * @param result  The setting the query resolves to, or null when absent.
     */
    private void stubFind(MockedStatic<PanacheEntityBase> panache, String key, FidelityProgramSetting result) {
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityProgramSetting> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(result);
        panache.when(() -> PanacheEntityBase.find("key", key)).thenReturn(query);
    }

    /**
     * Builds a fully-populated setting with fixed key and value for checksum assertions.
     *
     * @return A sample setting with deterministic attributes.
     */
    private FidelityProgramSetting sample() {
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.key = FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP;
        setting.value = "400.00";
        return setting;
    }
}
