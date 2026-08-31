package com.intermarche.fidelity.rule.appliers;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.rule.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Factory for {@code TICKET_THRESHOLD_EARN} — ticket-amount thresholds (§12): a
 * fixed gain unlocked by the highest threshold reached by the eligible assiette of
 * the single current ticket.
 * <p>
 * Administrable parameters: the assiette (whole store or scopes) and the tier list
 * ({@code tiers}, each {@code threshold} in € unlocking a fixed {@code reward} in
 * €). Unlike {@code CHALLENGE_EARN}, nothing cumulates across tickets and no card
 * activation is required: each ticket is evaluated alone.
 */
@ApplicationScoped
public class TicketThresholdEarnFactory extends AbstractEarnRuleApplierFactory {

    /**
     * Returns the rule type this factory interprets.
     *
     * @return {@link FidelityRule#TYPE_TICKET_THRESHOLD_EARN}.
     */
    @Override
    public String getRuleType() {
        return FidelityRule.TYPE_TICKET_THRESHOLD_EARN;
    }

    /**
     * Creates the applier bound to the given rule.
     *
     * @param rule The rule to interpret.
     * @return A new {@link TicketThresholdEarnApplier}.
     */
    @Override
    public EarnRuleApplier create(FidelityRule rule) {
        return new TicketThresholdEarnApplier(rule);
    }
}

/**
 * Applier for {@code TICKET_THRESHOLD_EARN} (§12, §15).
 * <p>
 * Sums the eligible assiette of the current ticket and grants the reward of the
 * highest tier whose threshold is reached — the reward of that tier alone, not the
 * sum of the tiers passed (20 € → 1 €, 40 € → 2 €: a 45 € assiette grants 2 €).
 * The ticket is evaluated in isolation: no cross-ticket cumulation, no card
 * activation. The reward is rounded at the centime (I4).
 */
class TicketThresholdEarnApplier extends AbstractEarnRuleApplier {

    /**
     * A ticket tier: an assiette-amount threshold unlocking a fixed reward.
     */
    private static final class Tier {

        /**
         * The eligible-assiette amount (€) at which the tier unlocks.
         */
        private final BigDecimal threshold;

        /**
         * The fixed gain (€) granted when the tier is the highest one reached.
         */
        private final BigDecimal reward;

        /**
         * Builds a tier.
         *
         * @param threshold The unlock threshold in €.
         * @param reward    The reward in €.
         */
        private Tier(BigDecimal threshold, BigDecimal reward) {
            this.threshold = threshold;
            this.reward = reward;
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
    TicketThresholdEarnApplier(FidelityRule rule) {
        super(rule);
        this.tiers = parseTiers();
    }

    /**
     * Evaluates the ticket-threshold rule against the basket (§15). The card
     * context plays no part: the mechanic depends on the ticket alone.
     *
     * @param lines   The valued basket lines.
     * @param context The dated card context; unused by this mechanic.
     * @return The earn produced, or the empty entry when no line is eligible or no
     *         tier is reached.
     */
    @Override
    public EarnEntry apply(List<ValuedLine> lines, CardContext context) {
        if (tiers.isEmpty()) {
            return none();
        }
        List<ValuedLine> eligible = eligibleLines(lines);
        if (eligible.isEmpty()) {
            return none();
        }
        BigDecimal assiette = assietteOf(eligible);
        BigDecimal granted = round(rewardReached(assiette));
        if (granted.signum() <= 0) {
            return none();
        }
        return entry(granted, assiette, eligible);
    }

    /**
     * Returns the reward of the highest tier whose threshold the assiette reaches;
     * zero when no tier is reached (§12).
     *
     * @param assiette The eligible assiette of the ticket.
     * @return The reward, euro at scale 2.
     */
    private BigDecimal rewardReached(BigDecimal assiette) {
        BigDecimal reward = BigDecimal.ZERO;
        for (Tier tier : tiers) {
            if (assiette.compareTo(tier.threshold) < 0) {
                break;
            }
            reward = tier.reward;
        }
        return round(reward);
    }

    /**
     * Parses the {@code tiers} array into ascending-threshold tiers, guarding
     * malformed entries (§31.2). Iterating a Jackson array never yields a Java null
     * ({@code isObject()} alone also rejects a JSON {@code null}, a {@code NullNode}).
     *
     * @return The tiers, never null.
     */
    private List<Tier> parseTiers() {
        List<Tier> parsed = new ArrayList<>();
        for (JsonNode node : tiersNode()) {
            if (!node.isObject()) {
                continue;
            }
            BigDecimal threshold = readDecimal(node, "threshold");
            BigDecimal reward = readDecimal(node, "reward");
            if (threshold == null || reward == null) {
                continue;
            }
            parsed.add(new Tier(threshold, reward));
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
