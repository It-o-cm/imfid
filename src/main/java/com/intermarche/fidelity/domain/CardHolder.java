package com.intermarche.fidelity.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.text.Normalizer;
import java.util.List;
import java.util.Objects;

/**
 * The card holder's identity — the local fallback directory (§33.3).
 * <p>
 * imfid stays pseudonymous when a CRM is configured: identity resolution then
 * belongs to the CRM and this table stays empty. When no CRM access is
 * configured, this entity holds the holder's last name, first name, phone and
 * e-mail so the POS can find a card by phone or name. Search columns are
 * normalized copies ({@link #searchName(String)}, {@link #searchPhone(String)})
 * so lookups are exact-match and indexed.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "card_holders",
        indexes = {
                @Index(name = "idx_holder_phone", columnList = "phone_search"),
                @Index(name = "idx_holder_email", columnList = "email"),
                @Index(name = "idx_holder_last_name", columnList = "last_name_search")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_holder_account", columnNames = "account_id"))
public class CardHolder extends BaseEntity {

    /**
     * The account this identity belongs to; one holder at most per account.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    @NotNull(message = "Holder account is mandatory")
    public FidelityAccount account;

    /**
     * The holder's last name, as keyed in.
     */
    @Column(name = "last_name", nullable = false, length = 80)
    public String lastName;

    /**
     * The normalized last name used by the name lookup: uppercased, diacritics
     * stripped (§33.3).
     */
    @Column(name = "last_name_search", nullable = false, length = 80)
    public String lastNameSearch;

    /**
     * The holder's first name, as keyed in; may be null.
     */
    @Column(name = "first_name", length = 80)
    public String firstName;

    /**
     * The normalized first name used by the name matching: uppercased, diacritics
     * stripped (§33.3); null when no first name.
     */
    @Column(name = "first_name_search", length = 80)
    public String firstNameSearch;

    /**
     * The holder's phone number, as keyed in; may be null.
     */
    @Column(length = 30)
    public String phone;

    /**
     * The normalized phone used by the phone lookup: digits only, +33/0033
     * folded to the leading 0 (§33.3); null when no phone.
     */
    @Column(name = "phone_search", length = 30)
    public String phoneSearch;

    /**
     * The holder's e-mail, stored lowercased and trimmed; may be null.
     */
    @Column(length = 160)
    public String email;

    // --------------------------------------------------
    // Normalization helpers (§33.3)
    // --------------------------------------------------

    /**
     * Normalizes a name for the search column: trimmed, diacritics stripped,
     * uppercased.
     *
     * @param name The raw name; null or blank yields null.
     * @return The normalized name, or null.
     */
    public static String searchName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String stripped = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return stripped.toUpperCase();
    }

    /**
     * Normalizes a phone number for the search column: every non-digit removed,
     * a leading international French prefix (+33 or 0033) folded to 0.
     *
     * @param phone The raw phone; null or blank yields null.
     * @return The normalized phone, or null.
     */
    public static String searchPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.startsWith("0033")) {
            digits = "0" + digits.substring(4);
        } else if (digits.startsWith("33") && digits.length() > 9) {
            digits = "0" + digits.substring(2);
        }
        return digits.isEmpty() ? null : digits;
    }

    /**
     * Normalizes an e-mail: trimmed and lowercased.
     *
     * @param email The raw e-mail; null or blank yields null.
     * @return The normalized e-mail, or null.
     */
    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds the holder of an account, or null when none is recorded.
     *
     * @param account The account.
     * @return The holder, or null.
     */
    public static CardHolder findByAccount(FidelityAccount account) {
        return find("account", account).firstResult();
    }

    /**
     * Finds the holders matching a normalized phone (§33.3).
     *
     * @param phoneSearch The normalized phone, as {@link #searchPhone(String)}.
     * @param limit       The maximum number of rows returned.
     * @return The matching holders, never null.
     */
    public static List<CardHolder> listByPhone(String phoneSearch, int limit) {
        return find("phoneSearch", phoneSearch).page(0, limit).list();
    }

    /**
     * Finds the holders matching a normalized e-mail (§33.3).
     *
     * @param email The normalized e-mail, as {@link #normalizeEmail(String)}.
     * @param limit The maximum number of rows returned.
     * @return The matching holders, never null.
     */
    public static List<CardHolder> listByEmail(String email, int limit) {
        return find("email", email).page(0, limit).list();
    }

    /**
     * Finds the holders whose last name starts with the given normalized prefix,
     * optionally narrowed by a normalized first-name prefix (§33.3). Prefix
     * matching keeps the search index-friendly ({@code LIKE 'X%'}) while letting
     * the operator type only the first letters.
     *
     * @param lastNamePrefix  The normalized last-name prefix, as {@link #searchName(String)}.
     * @param firstNamePrefix The normalized first-name prefix, or null for no narrowing.
     * @param limit           The maximum number of rows returned.
     * @return The matching holders, ordered by last then first name, never null.
     */
    public static List<CardHolder> listByName(String lastNamePrefix, String firstNamePrefix, int limit) {
        if (firstNamePrefix == null) {
            return find("lastNameSearch like ?1 order by lastNameSearch, firstNameSearch",
                    lastNamePrefix + "%").page(0, limit).list();
        }
        return find("lastNameSearch like ?1 and firstNameSearch like ?2 "
                        + "order by lastNameSearch, firstNameSearch",
                lastNamePrefix + "%", firstNamePrefix + "%").page(0, limit).list();
    }

    // --------------------------------------------------
    // Auditing
    // --------------------------------------------------

    /**
     * Computes the change-detection checksum over the business fields.
     *
     * @return The checksum.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(lastName, firstName, phone, email);
    }
}
