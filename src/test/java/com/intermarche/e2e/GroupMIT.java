package com.intermarche.e2e;

import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group M — the administration GraphQL surface (§26.5,
 * e2escenarios-imfid.md "## M. GraphQL d'administration") plus the Programme screen guards
 * (§23.4, §25.1): the single-admin-role security of {@code /graphql} with no cross-role trap
 * (M1), the {@code AdminException}-through-{@code GraphQLException} rendering of the rule
 * mutations (M2), the machine-stable {@code closeRule} semantics that survive the UI's move to
 * the end-of-application editor (M3), the queries with their exact exposed field inventory and
 * the active-member counter that excludes an expired membership window (M4), and the program
 * settings screen with its default placeholders, save notice, manual-batch result and its
 * deliberate absence of write-side type validation (M5).
 * <p>
 * Every scenario boots the real application under {@link QuarkusTest} against the
 * DataInitializer-rebuilt world (wipe + reload at each boot). The seeded catalog is only read;
 * the mutation scenarios (M2, M3) mint isolated {@code ZZZ_*} rules per test and drop them in a
 * {@code finally}, so no test disturbs the seeded rules and none depends on another's order.
 * Time is frozen through {@link DateTimeProvider} so the active-member window (M4) and the
 * {@code closeRule} default-today (M3) are deterministic whatever day the campaign runs. The
 * M4 active-member exclusion is proven with the seeded card {@code …033}, which holds a
 * STUDENTS membership still open and a SMALL_BUDGETS membership whose window has expired: only
 * the first is counted.
 * <p>
 * The {@code /graphql} surface is reached with Basic {@code fid-admin} for the authorized runs
 * and Basic {@code pos} / no auth for the refusals (§24.1); the M5 program screen is driven
 * over a form session ({@code j_security_check} + {@code quarkus-credential} cookie) with the
 * POST → 303 → notice cycle read from the {@code Location} header without following it. Admin
 * notices are the English literals the catalog quotes even on the French screens.
 */
@QuarkusTest
class GroupMIT {

    /**
     * Bootstrap administrator login name (SecurityBootstrap default), role {@code fid-admin}.
     */
    private static final String ADMIN_USER = "admin";

    /**
     * Bootstrap administrator password (SecurityBootstrap default in dev/test).
     */
    private static final String ADMIN_PASSWORD = "admin-password";

    /**
     * Bootstrap POS machine login name (SecurityBootstrap default), role {@code pos}.
     */
    private static final String POS_USER = "pos";

    /**
     * Bootstrap POS machine password (SecurityBootstrap default in dev/test).
     */
    private static final String POS_PASSWORD = "pos-password";

    /**
     * The form-session cookie set by {@code j_security_check} for the admin UI.
     */
    private static final String SESSION_COOKIE = "quarkus-credential";

    /**
     * The frozen instant of the whole class: the active-member window and the {@code closeRule}
     * default-today are literal against it.
     */
    private static final LocalDateTime FROZEN_AT = LocalDateTime.of(2026, 8, 22, 10, 0);

    /**
     * The seeded card holding a STUDENTS membership still open and a SMALL_BUDGETS membership
     * whose window expired — the M4 witness that an expired window is excluded from the count.
     */
    private static final String CARD_STUDENT = "2990000000033";

    /**
     * A seeded ACTIVE card used to assert the exposed account query fields (M4).
     */
    private static final String CARD_RICH = "2990000000019";

    /**
     * Freezes the program clock before each scenario so the active-member window and the
     * default-today of {@code closeRule} are deterministic (§24.6).
     */
    @BeforeEach
    void freezeClock() {
        DateTimeProvider.setFixedDateTime(FROZEN_AT);
    }

    /**
     * Restores the real clock after each scenario.
     */
    @AfterEach
    void unfreezeClock() {
        DateTimeProvider.clear();
    }

    // --------------------------------------------------
    // M1 — security: one admin role, no cross-role trap
    // --------------------------------------------------

    /**
     * M1 — the {@code /graphql} surface answers a 401 Basic challenge with no authentication;
     * an authenticated {@code pos} lacks the {@code fid-admin} role, so the class-level
     * {@code @RolesAllowed} raises a {@code ForbiddenException} inside the data fetcher which
     * SmallRye renders as an HTTP 200 carrying a GraphQL {@code errors} payload with null data
     * (the calibrated-and-frozen rendering: authorization is enforced at the GraphQL layer, not
     * as a transport 403). A single {@code fid-admin} role gates the surface — no imvaluation-
     * style cross-role trap — and {@code fid-admin} clears both a query (200 with data) and a
     * mutation (200, the business refusal surfaces as a GraphQL error, never a 401/403), proving
     * queries and mutations share the one admin role.
     */
    @Test
    void m1_graphqlIsGatedByTheSingleAdminRoleForQueriesAndMutations() {
        String query = "{ programSettings { key } }";
        Response anon = RestAssured.given().contentType(ContentType.JSON).body(Map.of("query", query)).post("/graphql");
        assertEquals(401, anon.statusCode(), "unauthenticated /graphql must challenge with 401 (M1)");
        Response pos = RestAssured.given().auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .contentType(ContentType.JSON).body(Map.of("query", query)).post("/graphql");
        assertEquals(200, pos.statusCode(), "an authenticated pos is refused at the GraphQL layer, not the transport (M1)");
        assertNull(pos.jsonPath().getString("data.programSettings"),
                "the forbidden pos gets no data (M1)");
        assertNotNull(pos.jsonPath().getString("errors[0].message"),
                "the forbidden pos gets a GraphQL authorization error (M1)");
        Response adminQuery = graphql(query);
        assertEquals(200, adminQuery.statusCode(), "fid-admin clears a query (M1)");
        assertNotNull(adminQuery.jsonPath().getList("data.programSettings"),
                "the admin query returns data, not an authorization error (M1)");
        Response adminMutation = graphql("mutation { createRule(code: \"ZZZ_M1_NOPE\", type: \"NOPE\", label: \"x\","
                + " priority: 1, exclusive: false, active: true, specification: \"{}\") { code } }");
        assertEquals(200, adminMutation.statusCode(), "fid-admin clears a mutation — no 401/403 cross-role trap (M1)");
        assertNotNull(adminMutation.jsonPath().getString("errors[0].message"),
                "the mutation reaches the engine and surfaces a business error, proving authorization (M1)");
    }

    // --------------------------------------------------
    // M2 — AdminException transits through GraphQLException
    // --------------------------------------------------

    /**
     * M2 — the rule mutations translate an {@code AdminException} into a {@code GraphQLException}
     * whose message reaches {@code errors[0].message} verbatim: {@code createRule} with an
     * unknown type renders {@code Unknown rule type 'NOPE' (no factory deployed)}, and
     * {@code closeRule} with a {@code validTo} in the past renders {@code A rule is closed with a
     * validTo not in the past (§18)} while leaving the rule open (a refused mutation writes
     * nothing).
     */
    @Test
    void m2_ruleMutationErrorsTransitThroughGraphQLException() {
        String code = "ZZZ_M2";
        try {
            assertEquals("Unknown rule type 'NOPE' (no factory deployed)",
                    graphql("mutation { createRule(code: \"ZZZ_M2_NOPE\", type: \"NOPE\", label: \"x\","
                            + " priority: 1, exclusive: false, active: true, specification: \"{}\") { code } }")
                            .jsonPath().getString("errors[0].message"),
                    "an unknown rule type surfaces its AdminException message (M2)");
            seedOpenRule(code);
            assertEquals("A rule is closed with a validTo not in the past (§18)",
                    graphql("mutation { closeRule(code: \"" + code + "\", validTo: \"2020-01-01T00:00:00\") { code } }")
                            .jsonPath().getString("errors[0].message"),
                    "a past validTo surfaces its AdminException message (M2)");
            assertNull(ruleValidTo(code), "the refused closeRule leaves the rule open — nothing is written (M2)");
        } finally {
            purgeRule(code);
        }
    }

    // --------------------------------------------------
    // M3 — closeRule survives the UI, machine-stable
    // --------------------------------------------------

    /**
     * M3 — {@code closeRule} keeps its historic semantics independently of the UI's end-of-
     * application editor (I5): an explicit future {@code validTo} is posted verbatim onto the
     * rule, and a {@code closeRule} with no {@code validTo} defaults to today's start of day
     * (the frozen day), both persisted — a machine-stable contract proven without touching the
     * seeded rules.
     */
    @Test
    void m3_closeRuleKeepsItsHistoricSemantics() {
        String explicit = "ZZZ_M3A";
        String defaulted = "ZZZ_M3B";
        try {
            seedOpenRule(explicit);
            Response closed = graphql("mutation { closeRule(code: \"" + explicit
                    + "\", validTo: \"2030-06-30T00:00:00\") { code validTo } }");
            assertEquals("2030-06-30T00:00:00", closed.jsonPath().getString("data.closeRule.validTo"),
                    "an explicit validTo is posted verbatim (M3)");
            assertEquals(LocalDateTime.of(2030, 6, 30, 0, 0), ruleValidTo(explicit),
                    "the explicit validTo is persisted (M3)");
            seedOpenRule(defaulted);
            Response defaultClosed = graphql("mutation { closeRule(code: \"" + defaulted + "\") { code validTo } }");
            assertEquals("2026-08-22T00:00:00", defaultClosed.jsonPath().getString("data.closeRule.validTo"),
                    "a missing validTo defaults to today's start of day (M3)");
            assertEquals(FROZEN_AT.toLocalDate().atStartOfDay(), ruleValidTo(defaulted),
                    "the default-today validTo is persisted (M3)");
        } finally {
            purgeRule(explicit);
            purgeRule(defaulted);
        }
    }

    // --------------------------------------------------
    // M4 — queries: counts, settings, exposed field inventory
    // --------------------------------------------------

    /**
     * M4 — the queries restore the exact administration inventory: {@code communities} returns
     * each seeded community with its active-member count, an expired membership window excluded
     * (BABIES 1, LARGE_FAMILIES 0, STUDENTS 1, SMALL_BUDGETS 0 — the {@code …033} card counts
     * for STUDENTS but its expired SMALL_BUDGETS membership does not); {@code programSettings}
     * restores the four parameters keyed by their business key; the {@code RuleType} exposes
     * exactly its ten backbone fields (frozen by introspection); and the account query exposes
     * the summary fields keyed by card, never by id.
     */
    @Test
    void m4_queriesRestoreCountsSettingsAndTheExposedFieldInventory() {
        Response communities = graphql("{ communities { code activeMembers } }");
        assertEquals(1L, activeMembers(communities, "BABIES"), "BABIES counts its one open member (M4)");
        assertEquals(0L, activeMembers(communities, "LARGE_FAMILIES"), "LARGE_FAMILIES has no member (M4)");
        assertEquals(1L, activeMembers(communities, "STUDENTS"), "STUDENTS counts …033's open membership (M4)");
        assertEquals(0L, activeMembers(communities, "SMALL_BUDGETS"),
                "SMALL_BUDGETS excludes …033's expired membership window (M4)");
        Response settings = graphql("{ programSettings { key value } }");
        assertEquals("299", settingValue(settings, "card.prefix"), "the card prefix is restored (M4)");
        assertEquals("400.00", settingValue(settings, "program.globalMonthlyCap"),
                "the global monthly cap is restored (M4)");
        assertEquals("Europe/Paris", settingValue(settings, "program.zone"), "the program zone is restored (M4)");
        assertEquals("900", settingValue(settings, "reservation.leaseTtlSeconds"),
                "the reservation lease TTL is restored (M4)");
        assertEquals(4, settings.jsonPath().getList("data.programSettings").size(),
                "exactly the four program settings are exposed (M4)");
        Set<String> ruleFields = new HashSet<>(
                graphql("{ __type(name: \"RuleType\") { fields { name } } }").jsonPath()
                        .getList("data.__type.fields.name"));
        assertEquals(Set.of("code", "type", "label", "validFrom", "validTo", "priority", "exclusive",
                        "monthlyCapPerCard", "active", "specification"), ruleFields,
                "the RuleType exposes exactly its ten backbone fields (M4)");
        Response account = graphql("{ account(card: \"" + CARD_RICH
                + "\") { cardNumber status balance availableBalance monthVisits } }");
        assertEquals(CARD_RICH, account.jsonPath().getString("data.account.cardNumber"),
                "the account query is keyed by card, never by id (M4)");
        assertEquals("ACTIVE", account.jsonPath().getString("data.account.status"),
                "the account query exposes the status (M4)");
        assertNotNull(account.jsonPath().getString("data.account.availableBalance"),
                "the account query exposes the available balance (M4)");
        assertNotNull(account.jsonPath().getString("data.account.balance"),
                "the account query exposes the balance (M4)");
    }

    // --------------------------------------------------
    // M5 — Programme screen: defaults, save notice, batch, no write validation
    // --------------------------------------------------

    /**
     * M5 — the Programme screen shows the four settings pre-filled with their default values
     * ({@code 400.00}, {@code 900}, {@code Europe/Paris}, {@code 299}); saving a setting reports
     * {@code Setting <key> saved}; a manual batch execution shows its result (fragments
     * {@code <TYPE> — Exécution : a traité … € sur … compte(s)}); and there is no write-side type
     * validation — a non-numeric cap is accepted and stored verbatim (the robustness is on the
     * read side through {@code getDecimal}, §31.2), then restored so the setting inventory is
     * left untouched.
     */
    @Test
    void m5_programmeScreenGuardsDefaultsSaveBatchAndNoWriteValidation() {
        String screen = getHtml("/ui/program");
        assertTrue(screen.contains("value=\"400.00\""), "the global monthly cap default is shown (M5)");
        assertTrue(screen.contains("value=\"900\""), "the reservation lease TTL default is shown (M5)");
        assertTrue(screen.contains("value=\"Europe/Paris\""), "the program zone default is shown (M5)");
        assertTrue(screen.contains("value=\"299\""), "the card prefix default is shown (M5)");
        assertEquals("Setting program.zone saved",
                noticeOf(postForm("/ui/program/setting", "key", "program.zone", "value", "Europe/Paris").header("Location")),
                "saving a setting reports the saved key (M5)");
        Response batch = postForm("/ui/program/batch", "type", "ACTIVATION_VOID", "dryRun", "false");
        String batchNotice = noticeOf(batch.header("Location"));
        assertTrue(batchNotice.startsWith("ACTIVATION_VOID — Exécution : a traité"),
                "a manual execution shows its result header (M5)");
        assertTrue(batchNotice.contains(" € sur ") && batchNotice.endsWith(" compte(s)"),
                "the execution result carries the amount and account fragments (M5)");
        try {
            assertEquals("Setting program.globalMonthlyCap saved",
                    noticeOf(postForm("/ui/program/setting", "key", "program.globalMonthlyCap", "value", "not-a-number")
                            .header("Location")),
                    "a non-numeric cap is accepted — no write-side type validation (M5, §31.2)");
            assertTrue(getHtml("/ui/program").contains("value=\"not-a-number\""),
                    "the non-numeric cap is stored verbatim (the read side falls back, M5)");
        } finally {
            postForm("/ui/program/setting", "key", "program.globalMonthlyCap", "value", "400.00");
        }
        assertTrue(getHtml("/ui/program").contains("value=\"400.00\""),
                "the global monthly cap is restored, leaving the inventory untouched (M5)");
    }

    // --------------------------------------------------
    // Helpers — GraphQL (Basic admin)
    // --------------------------------------------------

    /**
     * Posts a GraphQL operation over Basic {@code fid-admin} and returns the raw response.
     *
     * @param query The GraphQL query or mutation document.
     * @return The HTTP response.
     */
    private static Response graphql(String query) {
        return RestAssured.given().auth().preemptive().basic(ADMIN_USER, ADMIN_PASSWORD)
                .contentType(ContentType.JSON).body(Map.of("query", query)).post("/graphql");
    }

    /**
     * Reads a community's active-member count out of a {@code communities} query response.
     *
     * @param response The GraphQL response.
     * @param code     The community code to find.
     * @return The active-member count, or -1 when the community is absent.
     */
    private static long activeMembers(Response response, String code) {
        List<Map<String, Object>> list = response.jsonPath().getList("data.communities");
        for (Map<String, Object> community : list) {
            if (code.equals(community.get("code"))) {
                return ((Number) community.get("activeMembers")).longValue();
            }
        }
        return -1L;
    }

    /**
     * Reads a program setting's value out of a {@code programSettings} query response.
     *
     * @param response The GraphQL response.
     * @param key      The setting key to find.
     * @return The setting value, or null when the key is absent.
     */
    private static String settingValue(Response response, String key) {
        List<Map<String, Object>> list = response.jsonPath().getList("data.programSettings");
        for (Map<String, Object> setting : list) {
            if (key.equals(setting.get("key"))) {
                return (String) setting.get("value");
            }
        }
        return null;
    }

    // --------------------------------------------------
    // Helpers — HTTP (form session)
    // --------------------------------------------------

    /**
     * Logs in over {@code j_security_check} as the admin and returns the session cookie value.
     *
     * @return The {@code quarkus-credential} cookie value.
     */
    private static String adminSession() {
        String cookie = RestAssured.given().redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("j_username", ADMIN_USER).formParam("j_password", ADMIN_PASSWORD)
                .post("/j_security_check").cookie(SESSION_COOKIE);
        assertNotNull(cookie, "the admin login must set the session cookie");
        return cookie;
    }

    /**
     * Posts a form over a fresh admin session without following the 303, so both the notice and
     * the target path stay readable on the {@code Location} header (§21.3).
     *
     * @param path       The POST path.
     * @param formParams The alternating form parameter name/value pairs.
     * @return The un-followed HTTP response.
     */
    private static Response postForm(String path, String... formParams) {
        io.restassured.specification.RequestSpecification request = RestAssured.given().redirects().follow(false)
                .cookie(SESSION_COOKIE, adminSession()).contentType(ContentType.URLENC);
        for (int i = 0; i + 1 < formParams.length; i += 2) {
            request = request.formParam(formParams[i], formParams[i + 1]);
        }
        return request.post(path);
    }

    /**
     * Reads a rendered page as HTML over a fresh admin session.
     *
     * @param path The GET path.
     * @return The rendered body.
     */
    private static String getHtml(String path) {
        return RestAssured.given().cookie(SESSION_COOKIE, adminSession())
                .get(path).then().statusCode(200).extract().asString();
    }

    /**
     * Extracts the decoded {@code notice} query parameter from a redirect {@code Location}.
     *
     * @param location The redirect Location header.
     * @return The decoded notice, or the empty string when absent.
     */
    private static String noticeOf(String location) {
        assertNotNull(location, "the POST must redirect with a notice (§21.3)");
        String query = location.substring(location.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            if (pair.startsWith("notice=")) {
                return URLDecoder.decode(pair.substring("notice=".length()), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    // --------------------------------------------------
    // Helpers — database seeding (isolated test rules)
    // --------------------------------------------------

    /**
     * Inserts an isolated open test rule (window start in the far past, {@code validTo} null) in
     * its own transaction — the controlled subject for the {@code closeRule} scenarios, kept
     * away from the seeded catalog. Its type and specification are immaterial to
     * {@code closeRule}, which only reads the window.
     *
     * @param code The rule code.
     */
    private static void seedOpenRule(String code) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityRule rule = new FidelityRule();
            rule.code = code;
            rule.type = FidelityRule.TYPE_COMMUNITY_EARN;
            rule.label = "Test rule " + code;
            rule.validFrom = LocalDateTime.of(2020, 1, 1, 0, 0);
            rule.validTo = null;
            rule.priority = 1;
            rule.exclusive = false;
            rule.monthlyCapPerCard = null;
            rule.active = true;
            rule.specification = "{\"communityCode\":\"BABIES\",\"scope\":{\"wholeStore\":true},\"rate\":0.01}";
            rule.persist();
        });
    }

    /**
     * Reads a rule's {@code validTo} in a fresh transaction.
     *
     * @param code The rule code.
     * @return The window end, or null while open.
     */
    private static LocalDateTime ruleValidTo(String code) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityRule.findByCode(code).validTo);
    }

    /**
     * Removes a test rule in a fresh transaction, restoring the seeded world; a null or unknown
     * code is a no-op.
     *
     * @param code The test rule code, or null.
     */
    private static void purgeRule(String code) {
        if (code == null) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> FidelityRule.delete("code", code));
    }
}
