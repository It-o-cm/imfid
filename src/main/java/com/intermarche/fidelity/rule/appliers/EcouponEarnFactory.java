package com.intermarche.fidelity.rule.appliers;

import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.rule.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.List;

/**
 * Factory for {@code ECOUPON_EARN} — the weekly e-coupons (≥ 20 %, §12): a
 * percentage on a department, subject to prior card activation.
 * <p>
 * Administrable parameters: the department/family (assiette scopes), the rate
 * ({@code rate}), the week (the rule's validity window, applied by the
 * orchestration) and the per-card activation flag (§14).
 */
@ApplicationScoped
public class EcouponEarnFactory extends AbstractEarnRuleApplierFactory {

    /**
     * Returns the rule type this factory interprets.
     *
     * @return {@link FidelityRule#TYPE_ECOUPON_EARN}.
     */
    @Override
    public String getRuleType() {
        return FidelityRule.TYPE_ECOUPON_EARN;
    }

    /**
     * Creates the applier bound to the given rule.
     *
     * @param rule The rule to interpret.
     * @return A new {@link EcouponEarnApplier}.
     */
    @Override
    public EarnRuleApplier create(FidelityRule rule) {
        return new EcouponEarnApplier(rule);
    }
}

/**
 * Applier for {@code ECOUPON_EARN} (§12, §15).
 * <p>
 * Grants nothing unless the card carries an activation of the rule active on the
 * evaluation date (§14, §31.1); otherwise sums the net assiette of the eligible
 * lines and applies the rate, rounded once at the centime (I4).
 */
class EcouponEarnApplier extends AbstractEarnRuleApplier {

    /**
     * The rate as a stored fraction (§21.3).
     */
    private final BigDecimal rate;

    /**
     * Builds the applier, reading the rate.
     *
     * @param rule The rule to interpret.
     */
    EcouponEarnApplier(FidelityRule rule) {
        super(rule);
        this.rate = decimal("rate", BigDecimal.ZERO);
    }

    /**
     * Evaluates the e-coupon rule against the basket and the card context (§15).
     *
     * @param lines   The valued basket lines.
     * @param context The dated card context.
     * @return The earn produced, or the empty entry when not activated or no line is
     *         eligible.
     */
    @Override
    public EarnEntry apply(List<ValuedLine> lines, CardContext context) {
        if (context == null || context.activationFor(rule.code) == null) {
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
