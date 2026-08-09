package com.intermarche.fidelity.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A loyalty community — Babies, Large Families, Small Budgets, Students (§13).
 * <p>
 * {@code COMMUNITY_EARN} and {@code MONTHLY_DATE_EARN} rules reference a
 * community by its {@link #code}. Memberships are administered as data (§19);
 * the community carries the monthly cap, the enrollment cap and the annual
 * renewal window that govern them.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "fidelity_communities",
        indexes = @Index(name = "idx_community_code", columnList = "code")
)
@Cacheable
public class FidelityCommunity extends BaseEntity {

    /**
     * The unique business code of the community, referenced by rules.
     */
    @Column(unique = true, nullable = false, length = 50)
    @NotBlank(message = "Community code is mandatory")
    public String code;

    /**
     * The human label of the community.
     */
    @Column(nullable = false, length = 120)
    @NotBlank(message = "Community label is mandatory")
    public String label;

    /**
     * The per-card monthly cap of the community (e.g. 30/20 €), applied by
     * truncation between the rule cap and the global cap (§15, I5); null when the
     * community carries no cap of its own.
     */
    @Column(name = "monthly_cap", precision = 19, scale = 2)
    public BigDecimal monthlyCap;

    /**
     * The maximum number of memberships accepted for the community; null when
     * unbounded.
     */
    @Column(name = "enrollment_cap")
    public Integer enrollmentCap;

    /**
     * First month (1-12) of the annual renewal window (e.g. October/February
     * renewals); null when the community does not renew on a fixed window.
     */
    @Column(name = "renewal_start_month")
    public Integer renewalStartMonth;

    /**
     * Last month (1-12) of the annual renewal window; null when unbounded.
     */
    @Column(name = "renewal_end_month")
    public Integer renewalEndMonth;

    /**
     * A descriptive, non-executable eligibility criterion for operators (§13);
     * eligibility itself is administered as data, not evaluated from this text.
     */
    @Column(name = "eligibility_criteria", length = 500)
    public String eligibilityCriteria;

    /**
     * Whether the community is open to new enrollments (§23.2): an inactive community
     * refuses any new membership, while the existing members keep earning as long as
     * their memberships and the referencing rules run — extinction is driven by the
     * rules' end of application, never retroactively.
     */
    @Column(name = "is_active", nullable = false)
    public boolean active = true;

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a community by its unique code.
     *
     * @param code The community code.
     * @return The community, or null if none matches.
     */
    public static FidelityCommunity findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Lists every community ordered by code.
     *
     * @return The communities, never null.
     */
    public static List<FidelityCommunity> listAllByCode() {
        return list("order by code");
    }

    /**
     * Calculates a checksum from the community's business fields.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, label, monthlyCap, enrollmentCap,
                renewalStartMonth, renewalEndMonth, eligibilityCriteria, active);
    }
}
