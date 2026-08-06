/**
 * Domain model of imfid — administrable rules and loyalty accounts (§13, §14).
 *
 * <p>Panache entities built on the imvaluation {@code BaseEntity} pattern
 * (optimistic version, audit and per-lifecycle checksum): the rule backbone
 * ({@code FidelityRule} with its JSON {@code specification}),
 * {@code FidelityCommunity}, the account aggregate ({@code FidelityAccount},
 * {@code FidelityMembership}, {@code FidelityMovement}, {@code FidelityActivation})
 * and the administrable {@code FidelityProgramSetting} (§25.1). Amounts are
 * {@link java.math.BigDecimal} at scale 2, HALF_UP, implicit euro (§30.5).</p>
 *
 * <p>The imfid product reference (EAN &rarr; brand, families) used to resolve
 * earn baskets also lives here, fed by the same CSV imports as imvaluation.</p>
 */
package com.intermarche.fidelity.domain;
