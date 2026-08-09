package com.intermarche.fidelity.rule.appliers;

import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.rule.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.List;

/**
 * Factory for {@code BRAND_TIERED_EARN} — the "everyday brands" socle (5 %/10 %,
 * §12): a percentage of the eligible amount of a brand set, gated by an
 * N-eligible-items threshold and boosted once the card reaches a visit count.
 * <p>
 * Administrable parameters (all in the specification): the brand set (assiette
 * scope), the minimum eligible items ({@code minEligibleItems} = 3), the base rate
 * ({@code baseRate} = 0.05), the boosted rate ({@code boostedRate} = 0.10), the
 * visit threshold ({@code visitThreshold} = 4) and the promotion exclusion (carried
 * as ordinary excludes, §13).
 */
@ApplicationScoped
public class BrandTieredEarnFactory extends AbstractEarnRuleApplierFactory {

    /**
     * Returns the rule type this factory interprets.
     *
     * @return {@link FidelityRule#TYPE_BRAND_TIERED_EARN}.
     */
    @Override
    public String getRuleType() {
        return FidelityRule.TYPE_BRAND_TIERED_EARN;
    }

    /**
     * Creates the applier bound to the given rule.
     *
     * @param rule The rule to interpret.
     * @return A new {@link BrandTieredEarnApplier}.
     */
    @Override
    public EarnRuleApplier create(FidelityRule rule) {
        return new BrandTieredEarnApplier(rule);
    }
}

/**
 * Applier for {@code BRAND_TIERED_EARN} (§12, §15).
 * <p>
 * Sums the net assiette of the eligible brand lines; grants nothing below the
 * {@code minEligibleItems} threshold; applies the boosted rate when the month's
 * visits reach {@code visitThreshold}, the base rate otherwise; rounds once at the
 * centime (I4). The seuil is counted on the rule assiette, all brands of the set
 * confounded, and the rate applies to the whole eligible amount (§12).
 */
class BrandTieredEarnApplier extends AbstractEarnRuleApplier {

    /**
     * The base rate as a stored fraction (0.05 = 5 %).
     */
    private final BigDecimal baseRate;

    /**
     * The boosted rate as a stored fraction (0.10 = 10 %), reached at the visit
     * threshold.
     */
    private final BigDecimal boostedRate;

    /**
     * The visit count at or above which the boosted rate applies (§12, I3).
     */
    private final int visitThreshold;

    /**
     * The minimum number of eligible items required for the rule to grant anything
     * (§12, §22.2).
     */
    private final int minEligibleItems;

    /**
     * Builds the applier, reading the rates and thresholds from the specification.
     *
     * @param rule The rule to interpret.
     */
    BrandTieredEarnApplier(FidelityRule rule) {
        super(rule);
        this.baseRate = decimal("baseRate", BigDecimal.ZERO);
        this.boostedRate = decimal("boostedRate", baseRate);
        this.visitThreshold = integer("visitThreshold", Integer.MAX_VALUE);
        this.minEligibleItems = integer("minEligibleItems", 0);
    }

    /**
     * Evaluates the socle rule against the basket and the card context (§15).
     *
     * @param lines   The valued basket lines.
     * @param context The dated card context.
     * @return The earn produced, or the empty entry when the predicate fails.
     */
    @Override
    public EarnEntry apply(List<ValuedLine> lines, CardContext context) {
        if (context == null) {
            return none();
        }
        List<ValuedLine> eligible = eligibleLines(lines);
        if (eligible.isEmpty() || countEligibleItems(eligible) < minEligibleItems) {
            return none();
        }
        BigDecimal rate = context.monthlyVisits >= visitThreshold ? boostedRate : baseRate;
        BigDecimal assiette = assietteOf(eligible);
        BigDecimal amount = applyRate(assiette, rate);
        return entry(amount, assiette, eligible);
    }
}
