package com.intermarche.fidelity.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityRule;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Factory for {@code CHALLENGE_EARN} — "my winning challenges" (§12): purchase-amount
 * tiers to a gain over a period, with an optional mission at the last tier.
 * <p>
 * Administrable parameters: the assiette (a brand, scopes), the tier list
 * ({@code tiers}, each {@code threshold} in € unlocking a {@code reward} in €), the
 * period (the rule's validity window, applied by the orchestration), the mission
 * flag ({@code missionRequired} at the last tier) and the per-card activation (§14).
 */
@ApplicationScoped
public class ChallengeEarnFactory extends AbstractEarnRuleApplierFactory {

    /**
     * Returns the rule type this factory interprets.
     *
     * @return {@link FidelityRule#TYPE_CHALLENGE_EARN}.
     */
    @Override
    public String getRuleType() {
        return FidelityRule.TYPE_CHALLENGE_EARN;
    }

    /**
     * Creates the applier bound to the given rule.
     *
     * @param rule The rule to interpret.
     * @return A new {@link ChallengeEarnApplier}.
     */
    @Override
    public EarnRuleApplier create(FidelityRule rule) {
        return new ChallengeEarnApplier(rule);
    }
}

/**
 * Applier for {@code CHALLENGE_EARN} (§12, §15).
 * <p>
 * The challenge accumulates the eligible purchase base over its period. This
 * basket adds its eligible assiette to the running base carried by the context
 * ({@link CardContext#challengeBaseSoFar(String)}); the earn granted is the reward
 * of the highest tier reached by the new cumulative minus the reward already
 * granted by the prior cumulative — so re-crossing an already-passed tier grants
 * nothing. The last tier is granted only when its mission is done (§12); the reward
 * is rounded at the centime (I4). A rule without an active card activation grants
 * nothing (§14).
 */
class ChallengeEarnApplier extends AbstractEarnRuleApplier {

    /**
     * A challenge tier: a purchase-amount threshold unlocking a fixed reward, with
     * an optional mission requirement.
     */
    private static final class Tier {

        /**
         * The cumulative purchase amount (€) at which the tier unlocks.
         */
        private final BigDecimal threshold;

        /**
         * The fixed gain (€) granted when the tier is reached.
         */
        private final BigDecimal reward;

        /**
         * Whether reaching this tier also requires a completed mission (§12).
         */
        private final boolean missionRequired;

        /**
         * Builds a tier.
         *
         * @param threshold       The unlock threshold in €.
         * @param reward          The reward in €.
         * @param missionRequired Whether a mission is required.
         */
        private Tier(BigDecimal threshold, BigDecimal reward, boolean missionRequired) {
            this.threshold = threshold;
            this.reward = reward;
            this.missionRequired = missionRequired;
        }
    }

    /**
     * The tiers, ascending by threshold (§12).
     */
    private final List<Tier> tiers;

    /**
     * Builds the applier, reading the tier ladder from the specification.
     *
     * @param rule The rule to interpret.
     */
    ChallengeEarnApplier(FidelityRule rule) {
        super(rule);
        this.tiers = parseTiers();
    }

    /**
     * Evaluates the challenge against the basket and the card context (§15).
     *
     * @param lines   The valued basket lines.
     * @param context The dated card context.
     * @return The earn produced, or the empty entry when not activated, no line is
     *         eligible, or no new tier is crossed.
     */
    @Override
    public EarnEntry apply(List<ValuedLine> lines, CardContext context) {
        if (context == null || tiers.isEmpty()) {
            return none();
        }
        FidelityActivation activation = context.activationFor(rule.code);
        if (activation == null) {
            return none();
        }
        List<ValuedLine> eligible = eligibleLines(lines);
        if (eligible.isEmpty()) {
            return none();
        }
        BigDecimal ticketBase = assietteOf(eligible);
        BigDecimal priorBase = context.challengeBaseSoFar(rule.code);
        BigDecimal newBase = priorBase.add(ticketBase);
        boolean missionDone = activation.missionDone;

        BigDecimal rewardBefore = rewardReached(priorBase, missionDone);
        BigDecimal rewardAfter = rewardReached(newBase, missionDone);
        BigDecimal granted = round(rewardAfter.subtract(rewardBefore));
        if (granted.signum() <= 0) {
            return none();
        }
        return entry(granted, ticketBase, eligible);
    }

    /**
     * Returns the cumulative reward reached at the given purchase base: the reward of
     * the highest tier whose threshold is met, honouring the mission requirement of a
     * mission-gated tier (a tier requiring a mission that is not done is skipped, and
     * the reward falls back to the highest reachable lower tier, §12).
     *
     * @param base        The cumulative purchase base.
     * @param missionDone Whether the card's challenge mission is completed.
     * @return The cumulative reward, euro at scale 2.
     */
    private BigDecimal rewardReached(BigDecimal base, boolean missionDone) {
        BigDecimal reward = BigDecimal.ZERO;
        for (Tier tier : tiers) {
            if (base.compareTo(tier.threshold) < 0) {
                break;
            }
            if (tier.missionRequired && !missionDone) {
                continue;
            }
            reward = tier.reward;
        }
        return round(reward);
    }

    /**
     * Parses the {@code tiers} array into ascending-threshold tiers, guarding
     * malformed entries (§31.2).
     *
     * @return The tiers, never null.
     */
    private List<Tier> parseTiers() {
        List<Tier> parsed = new ArrayList<>();
        for (JsonNode node : tiersNode()) {
            if (node == null || !node.isObject()) {
                continue;
            }
            BigDecimal threshold = readDecimal(node, "threshold");
            BigDecimal reward = readDecimal(node, "reward");
            if (threshold == null || reward == null) {
                continue;
            }
            boolean mission = node.path("missionRequired").asBoolean(false);
            parsed.add(new Tier(threshold, reward, mission));
        }
        parsed.sort(Comparator.comparing(t -> t.threshold));
        return parsed;
    }

    /**
     * Reads a decimal field from a tier node, returning null when absent or
     * non-numeric (§31.2).
     *
     * @param node  The tier object node.
     * @param field The field name.
     * @return The decimal value, or null.
     */
    private static BigDecimal readDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.decimalValue();
    }
}
