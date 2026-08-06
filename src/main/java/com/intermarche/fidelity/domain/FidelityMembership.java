package com.intermarche.fidelity.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The membership of an account in a community over a validity window (§14).
 * <p>
 * The window carries the annual October/February renewals; the recalc at
 * ingestion evaluates membership at the ticket fiscal date (§31.1), so a
 * membership created today does not make a ticket replayed three weeks ago earn.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "fidelity_memberships",
        indexes = {
                @Index(name = "idx_membership_account", columnList = "account_id"),
                @Index(name = "idx_membership_community", columnList = "community_id")
        }
)
public class FidelityMembership extends BaseEntity {

    /**
     * The account holding the membership.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    public FidelityAccount account;

    /**
     * The community the account belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    public FidelityCommunity community;

    /**
     * Start of the membership window (inclusive), at the program zone.
     */
    @Column(name = "valid_from", nullable = false)
    public LocalDate validFrom;

    /**
     * End of the membership window (inclusive); null while open.
     */
    @Column(name = "valid_to")
    public LocalDate validTo;

    /**
     * Indicates whether the membership is active on the given fiscal date
     * (validFrom inclusive, validTo inclusive when set).
     *
     * @param date The fiscal date to test; when null returns false.
     * @return true when the membership window contains the date.
     */
    public boolean isActiveOn(LocalDate date) {
        if (date == null || validFrom == null || date.isBefore(validFrom)) {
            return false;
        }
        return validTo == null || !date.isAfter(validTo);
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Lists every membership of an account.
     *
     * @param account The account.
     * @return The memberships, never null.
     */
    public static List<FidelityMembership> listForAccount(FidelityAccount account) {
        return list("account", account);
    }

    /**
     * Lists the memberships of an account active on the given fiscal date.
     *
     * @param account The account.
     * @param date    The fiscal date.
     * @return The active memberships, never null.
     */
    public static List<FidelityMembership> listActiveForAccount(FidelityAccount account, LocalDate date) {
        return list("account = ?1 and validFrom <= ?2 and (validTo is null or validTo >= ?2)", account, date);
    }

    /**
     * Calculates a checksum from the membership's business fields, using the
     * account and community business codes.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        String cardNumber = account != null ? account.cardNumber : null;
        String communityCode = community != null ? community.code : null;
        return Objects.hash(cardNumber, communityCode, validFrom, validTo);
    }
}
