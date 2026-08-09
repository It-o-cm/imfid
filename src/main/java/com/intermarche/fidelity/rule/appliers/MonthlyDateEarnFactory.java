package com.intermarche.fidelity.rule.appliers;

import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.rule.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.List;

/**
 * Factory for {@code MONTHLY_DATE_EARN} — the 40 % feminine hygiene Labell on the
 * 28th for Students (§12): a percentage on a fixed day of the month, once per
 * period, capped.
 * <p>
 * Administrable parameters: the day of month ({@code dayOfMonth} = 28), the assiette
 * (scopes), the rate ({@code rate}), the required community ({@code communityCode})
 * and the monthly cap (10 €, applied by the orchestration, §15). Uniqueness per
 * period is the existence of an EARN of the rule's code in the month — the same
 * source as the cumulatives, no new counter (§15).
 */
@ApplicationScoped
public class MonthlyDateEarnFactory extends AbstractEarnRuleApplierFactory {

    /**
     * Returns the rule type this factory interprets.
     *
     * @return {@link FidelityRule#TYPE_MONTHLY_DATE_EARN}.
     */
    @Override
    public String getRuleType() {
        return FidelityRule.TYPE_MONTHLY_DATE_EARN;
    }

    /**
     * Creates the applier bound to the given rule.
     *
     * @param rule The rule to interpret.
     * @return A new {@link MonthlyDateEarnApplier}.
     */
    @Override
    public EarnRuleApplier create(FidelityRule rule) {
        return new MonthlyDateEarnApplier(rule);
    }
}

/**
 * Applier for {@code MONTHLY_DATE_EARN} (§12, §15).
 * <p>
 * Grants nothing unless the evaluation day is the configured day of month, the
 * card belongs to the required community (when set), and the rule has not already
 * earned this period (uniqueness by the existence of an EARN of the code this
 * month, §15); otherwise applies the rate to the eligible assiette, rounded once at
 * the centime (I4). The monthly cap is applied downstream (§15, I5).
 */
class MonthlyDateEarnApplier extends AbstractEarnRuleApplier {

    /**
     * The day of month the rule fires on (1-31, §12).
     */
    private final int dayOfMonth;

    /**
     * The rate as a stored fraction (§21.3).
     */
    private final BigDecimal rate;

    /**
     * The required community code, or null when the rule is not community-gated (§12).
     */
    private final String communityCode;

    /**
     * Builds the applier, reading the day of month, the rate and the community code.
     *
     * @param rule The rule to interpret.
     */
    MonthlyDateEarnApplier(FidelityRule rule) {
        super(rule);
        this.dayOfMonth = integer("dayOfMonth", 0);
        this.rate = decimal("rate", BigDecimal.ZERO);
        this.communityCode = text("communityCode");
    }

    /**
     * Evaluates the monthly-date rule against the basket and the card context (§15).
     *
     * @param lines   The valued basket lines.
     * @param context The dated card context.
     * @return The earn produced, or the empty entry when a predicate fails.
     */
    @Override
    public EarnEntry apply(List<ValuedLine> lines, CardContext context) {
        if (context == null || context.evaluationDate.getDayOfMonth() != dayOfMonth) {
            return none();
        }
        if (communityCode != null && !context.isMemberOf(communityCode)) {
            return none();
        }
        if (context.hasEarnedThisPeriod(rule.code)) {
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
