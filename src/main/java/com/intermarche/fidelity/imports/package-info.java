/**
 * Bulk CSV imports — same mechanism as imvaluation.
 *
 * <p>Feeds the rule catalogue, communities, program settings and the imfid
 * product reference (EAN &rarr; brand, families). Checksum-based optimisation
 * and the staged fallback 1000&rarr;100&rarr;10&rarr;1 on batch failure. Rows
 * are authenticated in HTTP Basic and answered with a status, never a redirect.</p>
 */
package com.intermarche.fidelity.imports;
