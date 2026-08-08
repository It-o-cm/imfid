package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link FidelityRuleTier}: a materialized row of a {@link FidelityRule}'s
 * {@code tiers} array (§13). The class holds no temporal or JSON logic and no guards; its only
 * behaviour is {@link FidelityRuleTier#getChecksum()}, a pure hash of the five business fields
 * (rank, threshold, rate, rewardAmount, missionRequired). Each field is varied in isolation to prove
 * it participates in the checksum, an all-null decimal case proves {@link java.util.Objects#hash}
 * tolerates the nullable columns, and the {@code rule} back-reference is asserted as a plain
 * assignment. No Quarkus boot and no H2 are involved; every {@link BigDecimal} is compared by
 * {@code compareTo}.
 */
class FidelityRuleTierTest {

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The checksum is a pure function of the business fields: two tiers with identical fields share
     * it.
     */
    @Test
    @DisplayName("getChecksum(): identical fields share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * A different rank changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a rank change alters the checksum")
    void checksumChangesWithRank() {
        FidelityRuleTier other = sample();
        other.rank = 99;
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different threshold changes the checksum; the {@link BigDecimal} field participates in the
     * hash like any other attribute.
     */
    @Test
    @DisplayName("getChecksum(): a threshold change alters the checksum")
    void checksumChangesWithThreshold() {
        FidelityRuleTier other = sample();
        other.threshold = new BigDecimal("10.00");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different rate changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a rate change alters the checksum")
    void checksumChangesWithRate() {
        FidelityRuleTier other = sample();
        other.rate = new BigDecimal("0.2000");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different reward amount changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a reward-amount change alters the checksum")
    void checksumChangesWithRewardAmount() {
        FidelityRuleTier other = sample();
        other.rewardAmount = new BigDecimal("9.99");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Toggling the mission-required flag changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a mission-required change alters the checksum")
    void checksumChangesWithMissionRequired() {
        FidelityRuleTier other = sample();
        other.missionRequired = false;
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * The nullable decimal columns are tolerated: a tier with null threshold, rate and rewardAmount
     * still hashes and two such tiers share a checksum.
     */
    @Test
    @DisplayName("getChecksum(): null decimal fields are tolerated")
    void checksumToleratesNullDecimals() {
        FidelityRuleTier a = new FidelityRuleTier();
        FidelityRuleTier b = new FidelityRuleTier();
        assertEquals(a.getChecksum(), b.getChecksum());
    }

    // --------------------------------------------------
    // rule back-reference
    // --------------------------------------------------

    /**
     * The owning rule is held as a plain back-reference.
     */
    @Test
    @DisplayName("rule: the owning rule is held by reference")
    void ruleBackReference() {
        FidelityRule rule = new FidelityRule();
        FidelityRuleTier tier = new FidelityRuleTier();
        tier.rule = rule;
        assertSame(rule, tier.rule);
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a fully-populated tier with fixed business fields for checksum assertions.
     *
     * @return A sample tier with deterministic attributes.
     */
    private FidelityRuleTier sample() {
        FidelityRuleTier tier = new FidelityRuleTier();
        tier.rank = 0;
        tier.threshold = new BigDecimal("3.00");
        tier.rate = new BigDecimal("0.1000");
        tier.rewardAmount = new BigDecimal("5.00");
        tier.missionRequired = true;
        return tier;
    }
}
