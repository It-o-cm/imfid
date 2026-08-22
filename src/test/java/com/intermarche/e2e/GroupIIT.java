package com.intermarche.e2e;

import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group I — the Rules administration screens (§18, §23.3,
 * e2escenarios-imfid.md "## I. UI Règles"): the list with its four state badges and its
 * per-state actions (I1), the read-only consultation as a frozen schema-driven form with a
 * live Form/JSON toggle and the "facteur cent" (I2 [W]), the schema-driven creation form
 * regenerated per type with its server-side validation and overlap notices (I3 [W]), the
 * in-place edition allowed only on a not-yet-in-force rule (I4), the end-of-application
 * editor with its set / clear / past / frozen guards (I5), and the duplication prefilled
 * form and taken-code guard (I6).
 * <p>
 * Every scenario boots the real application under {@link QuarkusTest} against the
 * DataInitializer-rebuilt world (wipe + reload at each boot): the eleven seeded rules are
 * asserted, never re-seeded. The seed carries no UPCOMING and no INACTIVE rule (every row
 * is ACTIVE or CLOSED at any campaign date past 2026-05-18), so the scenarios that need
 * those states mint isolated {@code ZZZ_*} test rules removed in a {@code finally}; the
 * seeded rules {@code SOCLE_5_MARQUES} (ACTIVE) and {@code SOCLE_4_MARQUES} (CLOSED) are
 * only read. Time is frozen through {@link DateTimeProvider} so every window is literal and
 * the campaign is never calendar-flaky: 2027 is always the future, 2020 always the past.
 * <p>
 * The {@code /ui/*} POST → 303 → notice cycle (§21.3) is driven over a form session
 * ({@code j_security_check} + {@code quarkus-credential} cookie); the redirect
 * {@code Location} is read without being followed so both the one-shot notice and the
 * target path are asserted from the header. Admin notices are the English literals the
 * catalog quotes. The two {@code [W]} scenarios drive a real headless browser so
 * {@code rules-form.js} renders the schema-driven fields, applies the consultation lock and
 * performs the percentage conversion.
 * <p>
 * Justified residue inside I5: the sixth attendu — extending a window "over the next
 * version" of the same code, expecting {@code The new window would overlap another instance
 * of code '<c>'} — is unreachable here because {@code FidelityRule.code} is
 * {@code unique=true}: no two rows can ever share a code, so {@code updateEndDate}'s
 * same-code overlap loop can never find a sibling. The guard is defensive; the other four
 * end-of-application attendus are covered.
 */
@QuarkusTest
@WithPlaywright
class GroupIIT {

    /**
     * Bootstrap administrator login name (SecurityBootstrap default), role {@code fid-admin}.
     */
    private static final String ADMIN_USER = "admin";

    /**
     * Bootstrap administrator password (SecurityBootstrap default in dev/test).
     */
    private static final String ADMIN_PASSWORD = "admin-password";

    /**
     * The form-session cookie set by {@code j_security_check} for the admin UI.
     */
    private static final String SESSION_COOKIE = "quarkus-credential";

    /**
     * The frozen instant of the whole class: every rule window is literal against it, so
     * 2027 is always the future and 2020 always the past whatever day the campaign runs.
     */
    private static final LocalDateTime FROZEN_AT = LocalDateTime.of(2026, 8, 22, 10, 0);

    /**
     * The seeded ACTIVE socle rule (validFrom 2026-05-18, open-ended) — the frozen
     * consultation of I2, the edit-refused-in-force case of I4, the overlap source of I3
     * and the duplication source of I6.
     */
    private static final String RULE_ACTIVE = "SOCLE_5_MARQUES";

    /**
     * The seeded CLOSED socle history (validTo 2026-05-18, in the past) — the CLOSED badge
     * of I1, the absent end-of-application block and the frozen-rule guard of I5.
     */
    private static final String RULE_CLOSED = "SOCLE_4_MARQUES";

    /**
     * Another seeded rule whose code the I6 taken-code guard collides with.
     */
    private static final String RULE_TAKEN = "FL_WEEKEND";

    /**
     * The rule type of the seeded socle rules and the isolated test rules.
     */
    private static final String BRAND_TYPE = "BRAND_TIERED_EARN";

    /**
     * The human label of the socle {@code baseRate}/{@code boostedRate} rate fields
     * (BRAND_TIERED_EARN.json {@code x-label}), located by text in the [W] scenarios.
     */
    private static final String RATE_LABEL = "Taux de base";

    /**
     * A valid {@code BRAND_TIERED_EARN} specification (the seeded socle spec): its stored
     * {@code baseRate} 0.05 shows as 5 through the "facteur cent" (§21.3). Used to seed the
     * isolated test rules and to feed the I4 update.
     */
    private static final String VALID_SPEC = "{\"scope\":{\"include\":{\"brands\":"
            + "[\"Pâturages\",\"Paquito\",\"Fiorini\",\"Labell\",\"Mäy\"]}},"
            + "\"minEligibleItems\":3,\"baseRate\":0.05,\"boostedRate\":0.10,\"visitThreshold\":4}";

    /**
     * The I1 isolated UPCOMING rule (future validFrom): the only row carrying the UPCOMING
     * badge and the only row exposing the {@code Éditer} link.
     */
    private static final String RULE_I1_UPCOMING = "ZZZ_I1_UPCOMING";

    /**
     * The I1 isolated INACTIVE rule (in-window but {@code active=false}): the only row
     * carrying the INACTIVE badge.
     */
    private static final String RULE_I1_INACTIVE = "ZZZ_I1_INACTIVE";

    /**
     * The I4 isolated UPCOMING rule the in-place edition is allowed on.
     */
    private static final String RULE_I4_UPCOMING = "ZZZ_I4_UPCOMING";

    /**
     * The I5 isolated ACTIVE open rule the end-of-application editor writes onto.
     */
    private static final String RULE_I5_END = "ZZZ_I5_END";

    /**
     * The I6 isolated target the direct-copy duplication mints, proving type and spec are
     * carried over end to end.
     */
    private static final String RULE_I6_COPY = "ZZZ_I6_COPY";

    /**
     * The Playwright browser context injected by quarkus-playwright (headless Chromium).
     */
    @InjectPlaywright
    BrowserContext browser;

    /**
     * The test HTTP root of the booted application, used to build absolute URLs for the
     * {@code [W]} scenarios.
     */
    @TestHTTPResource("/")
    URL baseUrl;

    /**
     * Freezes the program clock before each scenario so every window comparison is
     * deterministic (§24.6).
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
    // I1 — the list: badges, clickable codes, Éditer only on UPCOMING, no Fermer
    // --------------------------------------------------

    /**
     * I1 — the rule list carries the four state badges ({@code ACTIVE} on the open socle,
     * {@code CLOSED} on its frozen history, {@code UPCOMING} and {@code INACTIVE} on the two
     * isolated test rules), the codes are clickable links to the read-only sheet, the
     * {@code Éditer} link appears on the UPCOMING row and on no in-force row, and no
     * {@code Fermer} button exists anywhere on the screen (a closed rule is versioned, never
     * reopened, §18).
     */
    @Test
    void i1_listShowsFourBadgesClickableCodesEditerOnlyUpcomingAndNoFermer() {
        seedRule(RULE_I1_UPCOMING, LocalDateTime.of(2027, 6, 1, 0, 0), null, true);
        seedRule(RULE_I1_INACTIVE, LocalDateTime.of(2026, 1, 1, 0, 0), null, false);
        try {
            String html = getHtml("/ui/rules?page=1");
            assertTrue(html.contains("badge badge-ok\">ACTIVE<"), "the open socle carries the ACTIVE badge (I1)");
            assertTrue(html.contains("badge badge-off\">CLOSED<"), "the frozen socle history carries the CLOSED badge (I1)");
            assertTrue(html.contains(">UPCOMING<"), "the future test rule carries the UPCOMING badge (I1)");
            assertTrue(html.contains(">INACTIVE<"), "the inactive test rule carries the INACTIVE badge (I1)");
            assertTrue(html.contains("href=\"/ui/rules/" + RULE_ACTIVE + "\""), "the codes are clickable links to the sheet (I1)");
            assertTrue(html.contains("href=\"/ui/rules/" + RULE_I1_UPCOMING + "/edit\">Éditer"),
                    "the Éditer link is present on the UPCOMING row (I1)");
            assertFalse(html.contains("/ui/rules/" + RULE_ACTIVE + "/edit"), "no Éditer link on the in-force ACTIVE row (I1)");
            assertFalse(html.contains("/ui/rules/" + RULE_CLOSED + "/edit"), "no Éditer link on the CLOSED row (I1)");
            assertFalse(html.contains("Fermer"), "no Fermer button exists on the rules screen (§18)");
        } finally {
            purgeRule(RULE_I1_UPCOMING);
            purgeRule(RULE_I1_INACTIVE);
        }
    }

    // --------------------------------------------------
    // I2 — consultation = frozen form [W] (§23.3, facteur cent)
    // --------------------------------------------------

    /**
     * I2 [W] — the read-only sheet of {@code SOCLE_5_MARQUES} rendered in a real headless
     * browser: the backbone inputs and the schema-driven fields are all disabled (a frozen
     * form), the Form/JSON toggle stays usable, the rate shows 5 (stored 0.05, "facteur
     * cent") while the JSON view exposes the raw 0.05, and — the fragile point — the lock is
     * re-applied after the JSON → Form round-trip re-renders the fields.
     */
    @Test
    void i2_consultationIsAFrozenFormWithLiveToggleAndFacteurCent() {
        Page page = login();
        try {
            page.navigate(url("ui/rules/" + RULE_ACTIVE));
            assertTrue(page.locator("input[name='code']").isDisabled(), "the code field is frozen in consultation (§23.3)");
            assertTrue(page.locator("select[name='type']").isDisabled(), "the type field is frozen in consultation (§23.3)");
            assertTrue(page.locator("input[name='validFrom']").isDisabled(), "the validFrom field is frozen in consultation (§23.3)");
            assertTrue(rateField(page).isDisabled(), "the schema-driven rate field is frozen in consultation (§23.3)");
            assertEquals("5", rateField(page).inputValue(), "the rate shows 5, not the stored 0.05 (facteur cent, §21.3)");
            assertTrue(page.locator("#toggle-json").isEnabled(), "the Form/JSON toggle stays usable in consultation (I2)");
            page.locator("#toggle-json").click();
            assertTrue(page.locator("#json-editor").inputValue().contains("\"baseRate\": 0.05"),
                    "the JSON view exposes the raw stored fraction 0.05 (facteur cent, §21.3)");
            page.locator("#toggle-form").click();
            assertTrue(rateField(page).isDisabled(), "the lock is re-applied after the JSON → Form re-render (I2 fragile point)");
            assertEquals("5", rateField(page).inputValue(), "the rate still shows 5 after the round-trip (facteur cent, §21.3)");
        } finally {
            page.close();
        }
    }

    // --------------------------------------------------
    // I3 — schema-driven creation [W] (§12, §21.3, §18)
    // --------------------------------------------------

    /**
     * I3 [W] — the creation form is regenerated per type from the seven factory schemas (a
     * {@code BRAND_TIERED_EARN} carries a rate field, a {@code PROGRAM_EXCLUSION} carries
     * only its scope), a specification that fails its schema re-renders the form with the
     * notice {@code Invalid specification for type '<t>': <violations>}, and a window
     * overlapping an existing instance of the same code re-renders with {@code Rule window
     * overlaps an existing instance of code '<c>'} (§12, §18).
     */
    @Test
    void i3_creationIsSchemaDrivenPerTypeAndRejectsInvalidSpecAndOverlap() {
        Page page = login();
        try {
            page.navigate(url("ui/rules/new"));
            assertTrue(page.locator("#schema-fields").textContent().contains(RATE_LABEL),
                    "the BRAND_TIERED_EARN form is generated with its rate field (§21.3)");
            page.selectOption("#rule-type", "PROGRAM_EXCLUSION");
            assertFalse(page.locator("#schema-fields").textContent().contains(RATE_LABEL),
                    "switching type regenerates the form: the rate field is gone (§21.3)");
            assertTrue(page.locator("#schema-fields").textContent().contains("Inclusions"),
                    "the PROGRAM_EXCLUSION form is generated with its scope block (§12)");
            page.navigate(url("ui/rules/new"));
            page.fill("input[name='code']", "ZZZ_I3_INVALID");
            page.fill("input[name='label']", "x");
            page.fill("input[name='validFrom']", "2027-06-01T00:00");
            page.locator("#rule-form button[type='submit']").click();
            page.waitForURL("**/ui/rules/new**");
            assertTrue(page.locator(".alert-error").textContent().startsWith("Invalid specification for type '" + BRAND_TYPE + "':"),
                    "an incomplete specification re-renders with the Invalid specification notice (§12)");
            page.navigate(url("ui/rules/new?from=" + RULE_ACTIVE));
            page.fill("input[name='code']", RULE_ACTIVE);
            page.fill("input[name='label']", "dup");
            page.fill("input[name='validFrom']", "2027-06-01T00:00");
            page.locator("#rule-form button[type='submit']").click();
            page.waitForURL("**/ui/rules/new**");
            assertEquals("Rule window overlaps an existing instance of code '" + RULE_ACTIVE + "'",
                    page.locator(".alert-error").textContent(), "an overlapping window on an existing code is refused (§18)");
        } finally {
            page.close();
            purgeRule("ZZZ_I3_INVALID");
        }
    }

    // --------------------------------------------------
    // I4 — edition allowed on UPCOMING only (§18)
    // --------------------------------------------------

    /**
     * I4 — editing an in-force rule is refused: {@code GET /ui/rules/SOCLE_5_MARQUES/edit}
     * redirects (303) to the sheet with {@code Only a rule not yet in force can be edited;
     * duplicate then close instead (§18)}. On an UPCOMING rule the edit form opens with
     * everything modifiable but the code (frozen identity), and posting a window that starts
     * in the past is refused with {@code An edited rule cannot start in the past (§18)}.
     */
    @Test
    void i4_editionIsAllowedOnUpcomingOnlyAndForbidsAPastStart() {
        seedRule(RULE_I4_UPCOMING, LocalDateTime.of(2027, 6, 1, 0, 0), null, true);
        try {
            Response inForce = getNoFollow("/ui/rules/" + RULE_ACTIVE + "/edit");
            assertEquals(303, inForce.statusCode(), "editing an in-force rule redirects to its sheet (§18)");
            assertEquals("Only a rule not yet in force can be edited; duplicate then close instead (§18)",
                    noticeOf(inForce.header("Location")), "the in-force edit is refused with the literal (§18)");
            String form = getHtml("/ui/rules/" + RULE_I4_UPCOMING + "/edit");
            assertTrue(form.contains("data-readonly=\"false\""), "an UPCOMING rule opens a live (non-frozen) edit form (I4)");
            assertTrue(form.contains("name=\"code\"") && form.contains("readonly"), "the code stays frozen in edition (I4)");
            assertTrue(form.contains("action=\"/ui/rules/" + RULE_I4_UPCOMING + "/update\""), "the form posts to the update route (I4)");
            assertTrue(form.contains("type=\"submit\""), "the form is submittable — everything but the code is modifiable (I4)");
            String past = noticeOf(postForm("/ui/rules/" + RULE_I4_UPCOMING + "/update",
                    "type", BRAND_TYPE, "label", "x", "validFrom", "2020-01-01T00:00",
                    "specification", VALID_SPEC).header("Location"));
            assertEquals("An edited rule cannot start in the past (§18)", past,
                    "an edited rule cannot be moved to start in the past (§18)");
        } finally {
            purgeRule(RULE_I4_UPCOMING);
        }
    }

    // --------------------------------------------------
    // I5 — end-of-application editor (§18)
    // --------------------------------------------------

    /**
     * I5 — the end-of-application editor: its block is absent from a CLOSED rule's sheet and
     * present on an open one; posting a date sets it with {@code Rule <c>: end of application
     * set to <date>}, an empty date clears it with {@code …cleared (open-ended)}, a past date
     * is refused with {@code The end of application can never be set in the past (§18)}, and a
     * rule whose window has ended is frozen — {@code No rule with code '<c>' whose window is
     * still open; an ended rule is frozen — version it instead (§18)}. (The "overlap another
     * instance" attendu is unreachable under the unique-code constraint — see the class
     * Javadoc.)
     */
    @Test
    void i5_endOfApplicationEditorSetsClearsRefusesPastAndFreezesEndedRules() {
        seedRule(RULE_I5_END, LocalDateTime.of(2026, 1, 1, 0, 0), null, true);
        try {
            assertFalse(getHtml("/ui/rules/" + RULE_CLOSED).contains("action=\"/ui/rules/" + RULE_CLOSED + "/end-date\""),
                    "the end-of-application block is absent from a CLOSED rule's sheet (§18)");
            assertTrue(getHtml("/ui/rules/" + RULE_I5_END).contains("action=\"/ui/rules/" + RULE_I5_END + "/end-date\""),
                    "the end-of-application block is present on an open rule's sheet (§18)");
            String set = noticeOf(postForm("/ui/rules/" + RULE_I5_END + "/end-date", "validTo", "2027-06-01T00:00").header("Location"));
            assertEquals("Rule " + RULE_I5_END + ": end of application set to 2027-06-01T00:00", set,
                    "posting a date reports the end of application set (§18)");
            assertEquals(LocalDateTime.of(2027, 6, 1, 0, 0), validToOf(RULE_I5_END), "the posted end of application is persisted (§18)");
            String cleared = noticeOf(postForm("/ui/rules/" + RULE_I5_END + "/end-date", "validTo", "").header("Location"));
            assertEquals("Rule " + RULE_I5_END + ": end of application cleared (open-ended)", cleared,
                    "an empty date clears the end of application (§18)");
            assertNull(validToOf(RULE_I5_END), "clearing the end of application makes the rule open-ended again (§18)");
            String past = noticeOf(postForm("/ui/rules/" + RULE_I5_END + "/end-date", "validTo", "2020-01-01T00:00").header("Location"));
            assertEquals("The end of application can never be set in the past (§18)", past,
                    "the end of application cannot be posted in the past (§18)");
            String frozen = noticeOf(postForm("/ui/rules/" + RULE_CLOSED + "/end-date", "validTo", "2027-06-01T00:00").header("Location"));
            assertEquals("No rule with code '" + RULE_CLOSED + "' whose window is still open; "
                            + "an ended rule is frozen — version it instead (§18)", frozen,
                    "an ended rule is frozen forever — it is versioned, never re-ended (§18)");
        } finally {
            purgeRule(RULE_I5_END);
        }
    }

    // --------------------------------------------------
    // I6 — duplication (§23.3)
    // --------------------------------------------------

    /**
     * I6 — duplication: {@code GET /ui/rules/new?from=SOCLE_5_MARQUES} prefills the form with
     * the proposed code {@code SOCLE_5_MARQUES_V2}, the source type and its specification; the
     * direct-copy POST carries type and spec over to a fresh code; and duplicating onto an
     * already-taken code is refused with {@code A rule with code '<c>' already exists}.
     */
    @Test
    void i6_duplicationPrefillsProposedCodeTypeAndSpecAndGuardsTakenCode() {
        try {
            String prefill = getHtml("/ui/rules/new?from=" + RULE_ACTIVE);
            assertTrue(prefill.contains("value=\"" + RULE_ACTIVE + "_V2\""), "the proposed code is the source code with _V2 (I6)");
            assertTrue(prefill.contains("value=\"" + BRAND_TYPE + "\" selected"), "the source type is preselected (I6)");
            assertTrue(prefill.contains("baseRate"), "the source specification is carried into the prefilled form (I6)");
            String copied = noticeOf(postForm("/ui/rules/" + RULE_ACTIVE + "/duplicate", "newCode", RULE_I6_COPY).header("Location"));
            assertEquals("Rule " + RULE_ACTIVE + " duplicated to " + RULE_I6_COPY, copied,
                    "duplicating to a fresh code reports the source and the target (§23.3)");
            assertEquals(BRAND_TYPE, typeOf(RULE_I6_COPY), "the duplicate carries the source type (§23.3)");
            assertEquals(specificationOf(RULE_ACTIVE), specificationOf(RULE_I6_COPY), "the duplicate carries the source specification (§23.3)");
            String taken = noticeOf(postForm("/ui/rules/" + RULE_ACTIVE + "/duplicate", "newCode", RULE_TAKEN).header("Location"));
            assertEquals("A rule with code '" + RULE_TAKEN + "' already exists", taken,
                    "duplicating onto a taken code is refused with the literal (§23.3)");
        } finally {
            purgeRule(RULE_I6_COPY);
        }
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
     * Posts a form over a fresh admin session without following the 303, so both the notice
     * and the target path stay readable on the {@code Location} header (§21.3).
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
     * Performs a GET over a fresh admin session without following redirects, so a 303 guard
     * keeps its status and {@code Location} header readable.
     *
     * @param path The GET path.
     * @return The un-followed HTTP response.
     */
    private static Response getNoFollow(String path) {
        return RestAssured.given().redirects().follow(false).cookie(SESSION_COOKIE, adminSession()).get(path);
    }

    /**
     * Reads a rendered page as HTML over a fresh admin session.
     *
     * @param path The GET path (may carry a query string).
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
    // Helpers — Playwright
    // --------------------------------------------------

    /**
     * Builds an absolute URL under the booted application's test root.
     *
     * @param path The path without a leading slash.
     * @return The absolute URL string.
     */
    private String url(String path) {
        return baseUrl.toString() + path;
    }

    /**
     * Opens a browser page and signs in as the administrator through the real login form.
     *
     * @return A page authenticated for the admin UI.
     */
    private Page login() {
        Page page = browser.newPage();
        page.navigate(url("ui/login"));
        page.fill("input[name='j_username']", ADMIN_USER);
        page.fill("input[name='j_password']", ADMIN_PASSWORD);
        page.locator("button[type='submit']").click();
        page.waitForURL("**/ui/cards");
        return page;
    }

    /**
     * Locates the schema-driven {@code baseRate} number input of the current form by its
     * {@code x-label}, whatever the JSON → Form round-trip did to the DOM.
     *
     * @param page The browser page.
     * @return The rate input locator.
     */
    private static com.microsoft.playwright.Locator rateField(Page page) {
        return page.locator("#schema-fields label.field:has-text(\"" + RATE_LABEL + "\") input.input-control");
    }

    // --------------------------------------------------
    // Helpers — database seeding (isolated test rules)
    // --------------------------------------------------

    /**
     * Inserts an isolated {@code BRAND_TIERED_EARN} test rule with the valid socle
     * specification, in its own transaction — the versioning-safe way to reach an UPCOMING
     * or INACTIVE state the seed does not carry.
     *
     * @param code      The rule code.
     * @param validFrom The window start.
     * @param validTo   The window end, or null while open.
     * @param active    Whether the rule is active.
     */
    private static void seedRule(String code, LocalDateTime validFrom, LocalDateTime validTo, boolean active) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityRule rule = new FidelityRule();
            rule.code = code;
            rule.type = BRAND_TYPE;
            rule.label = "Test rule " + code;
            rule.specification = VALID_SPEC;
            rule.validFrom = validFrom;
            rule.validTo = validTo;
            rule.priority = 50;
            rule.exclusive = true;
            rule.active = active;
            rule.persist();
        });
    }

    /**
     * Removes a test rule by code in a fresh transaction, restoring the seeded world; a null
     * or unknown code is a no-op.
     *
     * @param code The test rule code, or null.
     */
    private static void purgeRule(String code) {
        if (code == null) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> FidelityRule.delete("code", code));
    }

    // --------------------------------------------------
    // Helpers — database reads (no absolute ids)
    // --------------------------------------------------

    /**
     * Reads a rule's window end in a fresh transaction.
     *
     * @param code The rule code.
     * @return The window end, or null when open-ended.
     */
    private static LocalDateTime validToOf(String code) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityRule.findByCode(code).validTo);
    }

    /**
     * Reads a rule's type in a fresh transaction.
     *
     * @param code The rule code.
     * @return The rule type.
     */
    private static String typeOf(String code) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityRule.findByCode(code).type);
    }

    /**
     * Reads a rule's specification in a fresh transaction.
     *
     * @param code The rule code.
     * @return The JSON specification.
     */
    private static String specificationOf(String code) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityRule.findByCode(code).specification);
    }
}
