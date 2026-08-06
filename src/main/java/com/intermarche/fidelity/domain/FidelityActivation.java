package com.intermarche.fidelity.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A per-card activation flag read by the engine (§14).
 * <p>
 * Carries the e-coupon activations, the engaged challenges and the completed
 * "gold level" missions, over a period. Fed by administration or a future
 * customer UI; a rule that requires activation (ECOUPON_EARN, CHALLENGE_EARN)
 * only earns when a matching activation is active at the fiscal date (§31.1).
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "fidelity_activations",
        indexes = {
                @Index(name = "idx_activation_account", columnList = "account_id"),
                @Index(name = "idx_activation_rule", columnList = "rule_code")
        }
)
public class FidelityActivation extends BaseEntity {

    /**
     * The account the activation belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    public FidelityAccount account;

    /**
     * The code of the rule this activation enables.
     */
    @Column(name = "rule_code", nullable = false, length = 50)
    @NotBlank(message = "Activation rule code is mandatory")
    public String ruleCode;

    /**
     * Start of the activation period (inclusive), at the program zone.
     */
    @Column(name = "period_start", nullable = false)
    public LocalDate periodStart;

    /**
     * End of the activation period (inclusive); null while open.
     */
    @Column(name = "period_end")
    public LocalDate periodEnd;

    /**
     * Whether the optional mission of the activated challenge is completed (§14).
     */
    @Column(name = "mission_done", nullable = false)
    public boolean missionDone;

    /**
     * Indicates whether the activation is active on the given fiscal date
     * (periodStart inclusive, periodEnd inclusive when set).
     *
     * @param date The fiscal date to test; when null returns false.
     * @return true when the activation period contains the date.
     */
    public boolean isActiveOn(LocalDate date) {
        if (date == null || periodStart == null || date.isBefore(periodStart)) {
            return false;
        }
        return periodEnd == null || !date.isAfter(periodEnd);
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Lists every activation of an account.
     *
     * @param account The account.
     * @return The activations, never null.
     */
    public static List<FidelityActivation> listForAccount(FidelityAccount account) {
        return list("account", account);
    }

    /**
     * Finds the activation of an account for a rule active on the given fiscal date.
     *
     * @param account  The account.
     * @param ruleCode The rule code.
     * @param date     The fiscal date.
     * @return The active activation, or null.
     */
    public static FidelityActivation findActive(FidelityAccount account, String ruleCode, LocalDate date) {
        return find("account = ?1 and ruleCode = ?2 and periodStart <= ?3 and (periodEnd is null or periodEnd >= ?3)",
                account, ruleCode, date).firstResult();
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum from the activation's business fields, using the
     * account business code.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        String cardNumber = account != null ? account.cardNumber : null;
        return Objects.hash(cardNumber, ruleCode, periodStart, periodEnd, missionDone);
    }
}
