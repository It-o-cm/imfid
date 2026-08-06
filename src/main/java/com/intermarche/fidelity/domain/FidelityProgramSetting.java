package com.intermarche.fidelity.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A typed, administrable program parameter — key/value (§25.1).
 * <p>
 * The values that used to be literals live here: the global monthly cap
 * (400 € in 2026), the default reservation lease TTL (I11), the program
 * reference time zone (Europe/Paris) that defines the civil day of visits (I3),
 * the 28th of the month, the 1st-March expiry and rule windows — never the
 * server clock (§30.3) — and the reserved card-number prefix (§33.1). Edited in
 * the "Program" entry of the admin UI.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "fidelity_program_settings",
        indexes = @Index(name = "idx_setting_key", columnList = "setting_key")
)
@Cacheable
public class FidelityProgramSetting extends BaseEntity {

    /**
     * Key of the global per-card monthly cap in euro (400 € in 2026, §15, §25.1).
     */
    public static final String KEY_GLOBAL_MONTHLY_CAP = "program.globalMonthlyCap";

    /**
     * Key of the default reservation lease TTL, in seconds (I11, §25.1).
     */
    public static final String KEY_RESERVATION_LEASE_TTL_SECONDS = "reservation.leaseTtlSeconds";

    /**
     * Key of the program reference time zone (Europe/Paris, §25.1, §30.3).
     */
    public static final String KEY_PROGRAM_ZONE = "program.zone";

    /**
     * Key of the reserved card-number prefix, outside the product EAN ranges (§33.1).
     */
    public static final String KEY_CARD_PREFIX = "card.prefix";

    // --------------------------------------------------
    // Key / value
    // --------------------------------------------------

    /**
     * The unique setting key (one of the {@code KEY_*} constants).
     */
    @Column(name = "setting_key", unique = true, nullable = false, length = 80)
    @NotBlank(message = "Setting key is mandatory")
    public String key;

    /**
     * The setting value, stored as text and interpreted by the reading accessor
     * according to the key's type.
     */
    @Column(name = "setting_value", nullable = false, length = 255)
    @NotBlank(message = "Setting value is mandatory")
    public String value;

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a setting by its key.
     *
     * @param key The setting key.
     * @return The setting, or null if none matches.
     */
    public static FidelityProgramSetting findByKey(String key) {
        return find("key", key).firstResult();
    }

    /**
     * Reads a setting value as a string, or the fallback when the key is absent.
     *
     * @param key      The setting key.
     * @param fallback The value returned when the key is absent.
     * @return The stored value, or the fallback.
     */
    public static String getString(String key, String fallback) {
        FidelityProgramSetting setting = findByKey(key);
        return setting != null ? setting.value : fallback;
    }

    /**
     * Reads a setting value as a decimal, or the fallback when the key is absent or
     * the value is not a number (§31.2).
     *
     * @param key      The setting key.
     * @param fallback The value returned when the key is absent or unparseable.
     * @return The parsed decimal, or the fallback.
     */
    public static BigDecimal getDecimal(String key, BigDecimal fallback) {
        FidelityProgramSetting setting = findByKey(key);
        if (setting == null || setting.value == null || setting.value.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(setting.value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Reads a setting value as an integer, or the fallback when the key is absent or
     * the value is not a number (§31.2).
     *
     * @param key      The setting key.
     * @param fallback The value returned when the key is absent or unparseable.
     * @return The parsed integer, or the fallback.
     */
    public static int getInt(String key, int fallback) {
        FidelityProgramSetting setting = findByKey(key);
        if (setting == null || setting.value == null || setting.value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(setting.value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum from the setting's key and value.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(key, value);
    }
}
