package com.intermarche.fidelity.rule;

import java.util.List;
import java.util.Set;

/**
 * The runtime interpreter of one {@link com.intermarche.fidelity.domain.FidelityRule},
 * created by its {@link EarnRuleApplierFactory} from the rule's validated JSON
 * specification (§12, I10) — the imfid counterpart of an imvaluation offer applier.
 * <p>
 * An applier is a pure, side-effect-free function of a valued basket and a card
 * context: {@code /earn} is a read (§30.2). It evaluates its eligibility predicate
 * (assiette scopes and the "not consumed by a commercial offer" trace, §15, I2),
 * sums the net assiette of the retained lines and applies its calculation, rounded
 * once per rule at the centime (I4). Plugging a new mechanic is a new factory + a
 * new applier + a new schema, with no change to the evaluation engine (§12, I10).
 * <p>
 * Orchestration — priorities, exclusivity, caps and the reading of {@code /valuation}
 * — is not an applier concern: it consumes the produced entries downstream (§15).
 */
public interface EarnRuleApplier {

    /**
     * Evaluates the rule against the valued basket and the card context, producing
     * the earn it grants.
     * <p>
     * Returns {@link EarnEntry#none(String, String)} — never null — when the
     * predicate fails or the rule is a non-producer ({@link #isProducer()} false);
     * callers filter with {@link EarnEntry#isEmpty()}.
     *
     * @param lines   The valued basket lines (§22.1); never null, may be empty.
     * @param context The dated card context (§15, §31.1); never null.
     * @return The earn produced by the rule, never null.
     */
    EarnEntry apply(List<ValuedLine> lines, CardContext context);

    /**
     * Indicates whether the rule produces earn ({@code true}) or is a pure assiette
     * filter such as {@code PROGRAM_EXCLUSION} ({@code false}), which removes its
     * scopes from every earn assiette and founds the burnable base without ever
     * granting an entry (§12, §15).
     *
     * @return true when the applier produces earn entries.
     */
    default boolean isProducer() {
        return true;
    }

    /**
     * Returns the ids of the basket lines this rule's scopes cover for the purpose of
     * removing them from every earn assiette and founding the burnable base (§15,
     * §22.3) — only {@code PROGRAM_EXCLUSION} overrides this; every producer returns
     * the empty set. Coverage ignores commercial consumption: a covered line leaves
     * every assiette and is subtracted from {@code totalPrice} even if an offer
     * already priced it.
     *
     * @param lines The valued basket lines; a null list yields an empty set.
     * @return The covered line ids, never null.
     */
    default Set<String> excludedLineIds(List<ValuedLine> lines) {
        return Set.of();
    }

    /**
     * Indicates whether the rule may run in the anonymous "had you carried the card"
     * projection (§20, RFP BO-03-03-28) — true only for mechanics that depend on the
     * basket and the date alone. A mechanic gated by the card's history, memberships
     * or activations must keep the default {@code false}: including it would inflate
     * the amount announced to a non-cardholder. The default is deliberately
     * conservative — a new mechanic is out of the anonymous projection until its
     * applier explicitly opts in.
     *
     * @return true when the applier runs in the anonymous projection.
     */
    default boolean appliesAnonymously() {
        return false;
    }
}
