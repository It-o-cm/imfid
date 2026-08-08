package com.intermarche.fidelity.domain.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.intermarche.fidelity.domain.FidelityProgramSetting;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link ProgramClock}: the single reader of the program clock (§24.6,
 * §25.1, §30.3) that resolves every clock-driven decision at the administrable program zone
 * rather than the server clock. All four legs of {@code zone()} are exercised — absent, blank,
 * valid and malformed setting — and both arms of the {@code date != null} guard in
 * {@code monthStart}/{@code monthEnd} (an explicit date vs. the null arm falling back to
 * {@code today()}), plus the fiscal-day resolution across the year border (§30.3). The zone
 * setting is a Panache static read, so it is stubbed with {@code mockStatic} in
 * try-with-resources; the wall time is pinned through the mockable {@link DateTimeProvider} on
 * fixed instants straddling each boundary, never the day the campaign runs (§29, §29.6). The
 * fixed instant is reset before and after each test so the cases stay isolated and order-free.
 */
class ProgramClockTest {

    /**
     * A fixed instant on the eve of the fiscal year border (§30.3): the 31st of December just
     * before midnight, pinning the near side of the {@code today()} boundary.
     */
    private static final LocalDateTime FISCAL_EVE = LocalDateTime.of(2026, 12, 31, 23, 59, 59);

    /**
     * The instant straddling the same border on the far side: the 1st of January at the first
     * second, proving the fiscal date follows the injected instant, not the campaign day.
     */
    private static final LocalDateTime FISCAL_MORROW = LocalDateTime.of(2027, 1, 1, 0, 0, 1);

    /**
     * The clock under test — a plain instance, no CDI, no Panache bootstrap.
     */
    private final ProgramClock clock = new ProgramClock();

    /**
     * Clears any fixed instant before each test so no case inherits another's static state.
     */
    @BeforeEach
    void resetBefore() {
        DateTimeProvider.clear();
    }

    /**
     * Clears the fixed instant after each test, leaving the shared static provider on its
     * system-time default for any later suite.
     */
    @AfterEach
    void resetAfter() {
        DateTimeProvider.clear();
    }

    /**
     * {@code zone()}: an absent setting drives the {@code configured == null} true leg of the
     * {@code ||} guard, falling back to Europe/Paris (§25.1, §31.2).
     */
    @Test
    @DisplayName("zone(): falls back to Europe/Paris when the setting is absent")
    void zoneFallsBackWhenSettingAbsent() {
        try (MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class)) {
            settings.when(() -> FidelityProgramSetting.getString(FidelityProgramSetting.KEY_PROGRAM_ZONE, null))
                    .thenReturn(null);
            assertEquals(ZoneId.of("Europe/Paris"), clock.zone());
        }
    }

    /**
     * {@code zone()}: a blank setting leaves the {@code configured == null} leg false and drives
     * the {@code isBlank()} true leg of the {@code ||} guard, still falling back to Europe/Paris.
     */
    @Test
    @DisplayName("zone(): falls back to Europe/Paris when the setting is blank")
    void zoneFallsBackWhenSettingBlank() {
        try (MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class)) {
            settings.when(() -> FidelityProgramSetting.getString(FidelityProgramSetting.KEY_PROGRAM_ZONE, null))
                    .thenReturn("   ");
            assertEquals(ZoneId.of("Europe/Paris"), clock.zone());
        }
    }

    /**
     * {@code zone()}: a valid, whitespace-padded setting takes both {@code ||} legs false and the
     * {@code try} success path, returning the trimmed zone rather than the default.
     */
    @Test
    @DisplayName("zone(): returns the configured zone, trimmed, when valid")
    void zoneReturnsConfiguredZoneTrimmed() {
        try (MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class)) {
            settings.when(() -> FidelityProgramSetting.getString(FidelityProgramSetting.KEY_PROGRAM_ZONE, null))
                    .thenReturn("  America/New_York  ");
            assertEquals(ZoneId.of("America/New_York"), clock.zone());
        }
    }

    /**
     * {@code zone()}: a malformed setting reaches the {@code ZoneId.of} throw and drives the
     * {@code catch} arm, falling back to Europe/Paris rather than propagating (§31.2).
     */
    @Test
    @DisplayName("zone(): falls back to Europe/Paris when the setting is malformed")
    void zoneFallsBackWhenSettingMalformed() {
        try (MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class)) {
            settings.when(() -> FidelityProgramSetting.getString(FidelityProgramSetting.KEY_PROGRAM_ZONE, null))
                    .thenReturn("Not/AZone");
            assertEquals(ZoneId.of("Europe/Paris"), clock.zone());
        }
    }

    /**
     * {@code now()}: echoes the injected instant verbatim, reading the mockable provider and never
     * the system clock (§24.6).
     */
    @Test
    @DisplayName("now(): returns the fixed program wall time")
    void nowReturnsFixedWallTime() {
        DateTimeProvider.setFixedDateTime(FISCAL_EVE);
        assertSame(FISCAL_EVE, clock.now());
    }

    /**
     * {@code today()}: the eve instant resolves to the near-side fiscal date, 31 December 2026,
     * proving the fiscal day follows the pinned instant (§30.3).
     */
    @Test
    @DisplayName("today(): resolves the near side of the fiscal-year border")
    void todayResolvesFiscalEve() {
        DateTimeProvider.setFixedDateTime(FISCAL_EVE);
        assertEquals(LocalDate.of(2026, 12, 31), clock.today());
    }

    /**
     * {@code today()}: the morrow instant one second past midnight resolves to the far-side fiscal
     * date, 1 January 2027 — the boundary tested by both straddling instants, not the campaign day.
     */
    @Test
    @DisplayName("today(): resolves the far side of the fiscal-year border")
    void todayResolvesFiscalMorrow() {
        DateTimeProvider.setFixedDateTime(FISCAL_MORROW);
        assertEquals(LocalDate.of(2027, 1, 1), clock.today());
    }

    /**
     * {@code monthStart(date)}: with an explicit date it takes the {@code date != null} true arm
     * and returns the first day of that month, ignoring the pinned clock.
     */
    @Test
    @DisplayName("monthStart(date): returns the first of the month for an explicit date")
    void monthStartForExplicitDate() {
        DateTimeProvider.setFixedDateTime(FISCAL_EVE);
        assertEquals(LocalDate.of(2026, 3, 1), clock.monthStart(LocalDate.of(2026, 3, 17)));
    }

    /**
     * {@code monthStart(null)}: takes the {@code date != null} false arm, falling back to the
     * fiscal {@code today()} — the eve instant yields the first of December 2026.
     */
    @Test
    @DisplayName("monthStart(null): falls back to the fiscal today")
    void monthStartFallsBackToToday() {
        DateTimeProvider.setFixedDateTime(FISCAL_EVE);
        assertEquals(LocalDate.of(2026, 12, 1), clock.monthStart(null));
    }

    /**
     * {@code monthEnd(date)}: with an explicit date it takes the {@code date != null} true arm and
     * returns the last day of a 31-day month.
     */
    @Test
    @DisplayName("monthEnd(date): returns the last day of a 31-day month")
    void monthEndForThirtyOneDayMonth() {
        assertEquals(LocalDate.of(2026, 1, 31), clock.monthEnd(LocalDate.of(2026, 1, 10)));
    }

    /**
     * {@code monthEnd(date)}: a non-leap February resolves to the 28th, pinning the calendar
     * arithmetic on the shortest month.
     */
    @Test
    @DisplayName("monthEnd(date): resolves the 28th for a non-leap February")
    void monthEndForNonLeapFebruary() {
        assertEquals(LocalDate.of(2026, 2, 28), clock.monthEnd(LocalDate.of(2026, 2, 15)));
    }

    /**
     * {@code monthEnd(date)}: a leap February resolves to the 29th, distinguishing the leap-year
     * arithmetic from the non-leap case.
     */
    @Test
    @DisplayName("monthEnd(date): resolves the 29th for a leap February")
    void monthEndForLeapFebruary() {
        assertEquals(LocalDate.of(2024, 2, 29), clock.monthEnd(LocalDate.of(2024, 2, 15)));
    }

    /**
     * {@code monthEnd(null)}: takes the {@code date != null} false arm, falling back to the fiscal
     * {@code today()} — the morrow instant yields the last day of January 2027.
     */
    @Test
    @DisplayName("monthEnd(null): falls back to the fiscal today")
    void monthEndFallsBackToToday() {
        DateTimeProvider.setFixedDateTime(FISCAL_MORROW);
        LocalDate end = clock.monthEnd(null);
        assertNotNull(end);
        assertEquals(LocalDate.of(2027, 1, 31), end);
    }
}
