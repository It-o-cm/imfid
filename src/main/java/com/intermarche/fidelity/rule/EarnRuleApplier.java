package com.intermarche.fidelity.rule;

import java.util.List;

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
}
