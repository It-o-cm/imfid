/**
 * GraphQL administration API — by business codes, never by IDs (imvaluation pattern).
 *
 * <p>Administrable surface over rules, communities, program settings and the
 * product reference, backing the admin IHM and the CSV channels. Mutations
 * refuse any rule whose type has no deployed factory or whose specification
 * fails its schema (I10): a rule in base with no interpreter is a configuration
 * error, not a silent case.</p>
 */
package com.intermarche.fidelity.graphql;
