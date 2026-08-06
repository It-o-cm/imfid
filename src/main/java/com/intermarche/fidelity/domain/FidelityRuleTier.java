package com.intermarche.fidelity.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One tier of a {@link FidelityRule}, materialized from the specification's
 * {@code tiers} array (§13).
 * <p>
 * A tier models both the visit-boosted rates of {@code BRAND_TIERED_EARN} (a
 * visit-count {@link #threshold} raising the {@link #rate}) and the amount tiers
 * of {@code CHALLENGE_EARN} (a purchase-amount {@link #threshold} unlocking a
 * fixed {@link #rewardAmount}, with an optional {@link #missionRequired} flag at
 * the last tier). The JSON specification remains the source of truth; these rows
 * exist so tiers are queryable and rendered by the schema-driven admin forms.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "fidelity_rule_tiers",
        indexes = @Index(name = "idx_tier_rule", columnList = "rule_id")
)
public class FidelityRuleTier extends BaseEntity {

    /**
     * The rule this tier belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rule_id", nullable = false)
    public FidelityRule rule;

    /**
     * Zero-based ordinal of the tier within its rule, ascending by threshold.
     */
    @Column(name = "rank", nullable = false)
    public int rank;

    /**
     * The tier threshold — a visit count (BRAND_TIERED) or a purchase amount in
     * euro (CHALLENGE). Stored as a decimal so both readings share one column.
     */
    @Column(precision = 19, scale = 2)
    public BigDecimal threshold;

    /**
     * The rate reached at this tier, as a stored fraction (0.10 meaning 10 %);
     * null for tiers that grant a fixed reward instead of a rate.
     */
    @Column(precision = 6, scale = 4)
    public BigDecimal rate;

    /**
     * The fixed gain granted when the tier is reached, in euro; null for tiers that
     * express a rate instead of a fixed reward.
     */
    @Column(name = "reward_amount", precision = 19, scale = 2)
    public BigDecimal rewardAmount;

    /**
     * Whether reaching this tier also requires a completed mission (last tier of a
     * challenge, §12).
     */
    @Column(name = "mission_required", nullable = false)
    public boolean missionRequired;

    /**
     * Calculates a checksum from the tier's business fields.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(rank, threshold, rate, rewardAmount, missionRequired);
    }
}
