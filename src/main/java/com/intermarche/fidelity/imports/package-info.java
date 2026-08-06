/**
 * Bulk CSV imports — the imvaluation import machinery, reprised bit for bit (§18).
 *
 * <p>{@link com.intermarche.fidelity.imports.ImporterCsvResource} carries the
 * shared framework: pipe-delimited streaming, chunking, programmatic transactions,
 * the JSON execution report, checksum-based optimisation and the staged fallback
 * {@code 1000 → 100 → 10 → 1} on batch failure (§18, §32).</p>
 *
 * <p>Domains, each a {@code POST /.../import} endpoint:</p>
 * <ul>
 *   <li>the imfid product reference — {@link com.intermarche.fidelity.imports.ProductCsvResource}
 *       and {@link com.intermarche.fidelity.imports.ProductFamilyCsvResource}: the
 *       same CSV files as imvaluation, one source and two importers (§18);</li>
 *   <li>{@link com.intermarche.fidelity.imports.FidelityCommunityCsvResource} — communities;</li>
 *   <li>{@link com.intermarche.fidelity.imports.FidelityAccountCsvResource} — cards,
 *       generating the reserved-prefix EAN-13 number when absent (§33.1);</li>
 *   <li>{@link com.intermarche.fidelity.imports.FidelityMembershipCsvResource} — community memberships;</li>
 *   <li>{@link com.intermarche.fidelity.imports.FidelityAdjustmentCsvResource} — {@code ADJUSTMENT}
 *       movements (seeded balances), written under the per-card lock (§30.1, §32.1);</li>
 *   <li>{@link com.intermarche.fidelity.imports.FidelityActivationCsvResource} — per-card activations;</li>
 *   <li>{@link com.intermarche.fidelity.imports.FidelityVisitCsvResource} — visits as dated
 *       {@code EarnTrace} headers (§29.1).</li>
 * </ul>
 *
 * <p>The {@code FIDELITY_RULES} import ships with the rule SPI and its JSON-Schema
 * validation, not here. The {@code fid-admin} role guard (§24.1) is attached to
 * every endpoint in the later security build step.</p>
 */
package com.intermarche.fidelity.imports;
