package com.intermarche.fidelity.domain.util;

import com.intermarche.fidelity.domain.FidelityProgramSetting;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * The single reader of the program clock (§24.6, §25.1, §30.3): every clock-driven
 * decision — the civil day of visits (I3), rule and membership windows, reservation
 * leases (I11), the 1st-March expiry, the 24-month purge and the {@code earnYear} —
 * resolves its fiscal date here, at the program reference zone, never the server clock.
 * <p>
 * The wall time comes from the mockable {@link DateTimeProvider} (a {@code LocalDateTime}
 * held, by convention, at the program zone); the zone itself is the administrable
 * {@link FidelityProgramSetting#KEY_PROGRAM_ZONE} (Europe/Paris in 2026), read on demand
 * so an admin change takes effect without a redeploy. A malformed zone falls back to
 * Europe/Paris rather than throwing (§31.2).
 */
@ApplicationScoped
public class ProgramClock {

    /**
     * The fallback reference zone when the setting is absent or malformed (§25.1).
     */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Paris");

    /**
     * Returns the program reference zone from the settings, falling back to Europe/Paris
     * on an absent or malformed value (§25.1, §31.2).
     *
     * @return The program zone, never null.
     */
    public ZoneId zone() {
        String configured = FidelityProgramSetting.getString(FidelityProgramSetting.KEY_PROGRAM_ZONE, null);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(configured.trim());
        } catch (RuntimeException e) {
            return DEFAULT_ZONE;
        }
    }

    /**
     * Returns the current program wall time, from the mockable provider (§24.6).
     *
     * @return The current date-time at the program zone, never null.
     */
    public LocalDateTime now() {
        return DateTimeProvider.now();
    }

    /**
     * Returns the current fiscal date at the program zone (§30.3).
     *
     * @return Today's fiscal date, never null.
     */
    public LocalDate today() {
        return now().toLocalDate();
    }

    /**
     * Returns the first day of the month of the given date — the lower bound of a
     * monthly cap or visit cumulative (§15, §29.1).
     *
     * @param date The reference date; a null date uses today.
     * @return The first day of the month, never null.
     */
    public LocalDate monthStart(LocalDate date) {
        LocalDate ref = date != null ? date : today();
        return ref.with(TemporalAdjusters.firstDayOfMonth());
    }

    /**
     * Returns the last day of the month of the given date — the upper bound of a
     * monthly cap or visit cumulative (§15, §29.1).
     *
     * @param date The reference date; a null date uses today.
     * @return The last day of the month, never null.
     */
    public LocalDate monthEnd(LocalDate date) {
        LocalDate ref = date != null ? date : today();
        return ref.with(TemporalAdjusters.lastDayOfMonth());
    }
}
