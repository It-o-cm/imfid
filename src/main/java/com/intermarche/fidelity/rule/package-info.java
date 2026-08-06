/**
 * Earn rule framework and extension SPI (§12, I10) — imvaluation offer-engine pattern.
 *
 * <p>CDI factories {@code EarnRuleApplierFactory { String getRuleType();
 * String getSchema(); EarnRuleApplier create(FidelityRule) }} discovered by
 * {@code Instance<>} and indexed type&rarr;factory at startup. Each rule's JSON
 * {@code specification} is validated by its factory's JSON Schema; a shared
 * registry aggregates every schema so runtime validation and the admin forms
 * cannot drift ({@code OfferSchemaRegistry} pattern).</p>
 *
 * <p>The seven mechanics of the 2026 programme are appliers here — {@code
 * BRAND_TIERED_EARN}, {@code CALENDAR_FAMILY_EARN}, {@code COMMUNITY_EARN},
 * {@code MONTHLY_DATE_EARN}, {@code CHALLENGE_EARN}, {@code ECOUPON_EARN},
 * {@code PROGRAM_EXCLUSION}. Plugging a new mechanic is a factory + an applier +
 * a schema, with no change to the evaluation engine and no base migration.</p>
 */
package com.intermarche.fidelity.rule;
