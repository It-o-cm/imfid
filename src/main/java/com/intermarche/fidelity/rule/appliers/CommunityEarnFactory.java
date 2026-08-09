package com.intermarche.fidelity.rule.appliers;

import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.rule.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.List;

/**
 * Factory for {@code COMMUNITY_EARN} — a community advantage (Babies, Large
 * Families, Small Budgets, Students, §12): a percentage on an assiette, reserved to
 * a community's members, capped per month and per card.
 * <p>
 * Administrable parameters: the community code ({@code communityCode}), the assiette
 * (brands, families or whole store), the exclusions (first-age milk, variable
 * weight, Top Budget/Merci, alcohol — ordinary excludes, §13), the rate
 * ({@code rate}) and the monthly cap (30/20 €, carried by the rule's
 * {@code monthlyCapPerCard} or the community, applied by the orchestration, §15).
 */
@ApplicationScoped
public class CommunityEarnFactory extends AbstractEarnRuleApplierFactory {

    /**
     * Returns the rule type this factory interprets.
     *
     * @return {@link FidelityRule#TYPE_COMMUNITY_EARN}.
     */
    @Override
    public String getRuleType() {
        return FidelityRule.TYPE_COMMUNITY_EARN;
    }

    /**
     * Creates the applier bound to the given rule.
     *
     * @param rule The rule to interpret.
     * @return A new {@link CommunityEarnApplier}.
     */
    @Override
    public EarnRuleApplier create(FidelityRule rule) {
        return new CommunityEarnApplier(rule);
    }
}

/**
 * Applier for {@code COMMUNITY_EARN} (§12, §15).
 * <p>
 * Grants nothing unless the card is a member of the required community as of the
 * evaluation date; otherwise sums the net assiette of the eligible lines and
 * applies the rate, rounded once at the centime (I4). The monthly caps are applied
 * downstream by the orchestration (§15, I5).
 */
class CommunityEarnApplier extends AbstractEarnRuleApplier {

    /**
     * The required community code (§13).
     */
    private final String communityCode;

    /**
     * The rate as a stored fraction (§21.3).
     */
    private final BigDecimal rate;

    /**
     * Builds the applier, reading the community code and the rate.
     *
     * @param rule The rule to interpret.
     */
    CommunityEarnApplier(FidelityRule rule) {
        super(rule);
        this.communityCode = text("communityCode");
        this.rate = decimal("rate", BigDecimal.ZERO);
    }

    /**
     * Evaluates the community rule against the basket and the card context (§15).
     *
     * @param lines   The valued basket lines.
     * @param context The dated card context.
     * @return The earn produced, or the empty entry when the card is not a member or
     *         no line is eligible.
     */
    @Override
    public EarnEntry apply(List<ValuedLine> lines, CardContext context) {
        if (context == null || !context.isMemberOf(communityCode)) {
            return none();
        }
        List<ValuedLine> eligible = eligibleLines(lines);
        if (eligible.isEmpty()) {
            return none();
        }
        BigDecimal assiette = assietteOf(eligible);
        BigDecimal amount = applyRate(assiette, rate);
        return entry(amount, assiette, eligible);
    }
}
