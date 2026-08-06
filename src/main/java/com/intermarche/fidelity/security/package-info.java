/**
 * Security — DB-backed admin accounts and the auth mechanisms (§24).
 *
 * <p>Admin accounts are entities annotated {@code @UserDefinition}. The browser
 * authenticates by form; the CSV, GraphQL and POS clients authenticate in HTTP
 * Basic and expect a status, never a redirect. The session key and the
 * first-boot bootstrap admin password come from the environment, with no
 * versioned fallback, so a misconfigured start fails rather than running on a
 * known secret.</p>
 */
package com.intermarche.fidelity.security;
