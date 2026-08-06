package com.intermarche.fidelity.rule;

import com.intermarche.fidelity.domain.FidelityRule;

/**
 * A CDI factory that owns one earn mechanic — reprising imvaluation's offer-engine
 * pattern bit for bit (§12, I10): factories are discovered by {@code Instance<>}
 * and indexed {@code type → factory} at startup by {@link EarnRuleRegistry}.
 * <p>
 * Each factory declares the {@link #getRuleType() rule type} it interprets,
 * publishes the JSON Schema its rules' specifications are validated against and
 * rendered from ({@link #getSchema()} — the same schema instance validates at
 * runtime and drives the admin forms, so engine and UI cannot drift, §12, §21.3),
 * and {@link #create(FidelityRule) creates} an {@link EarnRuleApplier} bound to a
 * concrete rule. A rule whose type has no deployed factory, or whose specification
 * does not validate its schema, is refused at administration (§12, I10).
 */
public interface EarnRuleApplierFactory {

    /**
     * Returns the rule type this factory interprets — one of the
     * {@code FidelityRule.TYPE_*} codes; the key under which the factory is indexed.
     * Two factories claiming the same type fail the startup (§12).
     *
     * @return The rule type code, never null.
     */
    String getRuleType();

    /**
     * Returns the JSON Schema of this factory's rule specification, as a JSON string.
     * <p>
     * The schema is enriched with the {@code x-widget}/{@code x-label}/
     * {@code x-item-label}/{@code x-unit-from} display annotations that pilot the
     * schema-driven admin forms (§21.3); the very same schema instance validates the
     * specification at runtime and at import (§12).
     *
     * @return The JSON Schema as a string, never null.
     */
    String getSchema();

    /**
     * Creates an applier bound to the given rule, parsing its validated
     * specification once (§12).
     *
     * @param rule The rule to interpret; must carry a specification valid for this
     *             factory's schema.
     * @return A ready-to-evaluate applier, never null.
     */
    EarnRuleApplier create(FidelityRule rule);
}
