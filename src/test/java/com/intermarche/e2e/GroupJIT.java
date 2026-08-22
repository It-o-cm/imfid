package com.intermarche.e2e;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
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

import java.math.BigDecimal;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group J — the Communities administration screens (§23.2,
 * e2escenarios-imfid.md "## J. UI Communautés"): the catalog creation form with its ordered
 * guards and its 303-to-workbench success (J1), the frozen-code parameter edition (J2), the
 * close-to-enrollments toggle with its confirm text, closed banner, enrollment refusals and
 * reopen (J3), and the direct-editing membership workbench as the complete source of truth —
 * deletion by omission, silently-ignored unknown cards and the enrollment cap (J4 [W]).
 * <p>
 * Every scenario boots the real application under {@link QuarkusTest} against the
 * DataInitializer-rebuilt world (wipe + reload at each boot). The seed carries four
 * communities ({@code BABIES}, {@code LARGE_FAMILIES}, {@code STUDENTS}, {@code SMALL_BUDGETS})
 * that are only read; every mutation is done on isolated {@code ZZZ_*} test communities minted
 * per scenario and removed in a {@code finally}, so no test depends on another's order and the
 * seeded catalog is never disturbed. Time is frozen through {@link DateTimeProvider} so the
 * active-member window is deterministic whatever day the campaign runs.
 * <p>
 * The {@code /ui/*} POST → 303 → notice cycle (§21.3) is driven over a form session
 * ({@code j_security_check} + {@code quarkus-credential} cookie); the redirect {@code Location}
 * is read without being followed so both the one-shot notice and the target path are asserted
 * from the header. The J3 enrollment refusal crosses the {@code /graphql} surface (Basic
 * admin) where {@code upsertMembership} surfaces the {@code AdminException} message verbatim.
 * Admin notices are the English literals the catalog quotes even on the French screens. J4 is
 * {@code [W]}: it drives a real headless browser so {@code workbench.html}'s membership table
 * JS serializes the whole set, letting deletion-by-omission and the cap be proven end to end.
 * <p>
 * Cross-reference: the "existing members keep earning" half of J3 (§23.2) is proven at the
 * earn surface by C10; here it is asserted structurally — closing a community leaves its
 * existing membership active, which is the mechanism by which those members keep cagnotting.
 */
@QuarkusTest
@WithPlaywright
class GroupJIT {

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
     * The frozen instant of the whole class: the active-member window is literal against it.
     */
    private static final LocalDateTime FROZEN_AT = LocalDateTime.of(2026, 8, 22, 10, 0);

    /**
     * A seeded community code whose existence the J1 taken-code guard collides with.
     */
    private static final String SEEDED_COMMUNITY = "BABIES";

    /**
     * A seeded ACTIVE card used as an existing member and as a would-be new enrollee.
     */
    private static final String CARD_MEMBER = "2990000000026";

    /**
     * A seeded ACTIVE card with no seeded membership, used as the refused new enrollee.
     */
    private static final String CARD_OUTSIDER = "2990000000019";

    /**
     * A second seeded ACTIVE card, used to fill the J4 workbench toward the cap.
     */
    private static final String CARD_B = "2990000000057";

    /**
     * A third seeded ACTIVE card, used to push the J4 workbench past the cap.
     */
    private static final String CARD_C = "2990000000071";

    /**
     * The Playwright browser context injected by quarkus-playwright (headless Chromium).
     */
    @InjectPlaywright
    BrowserContext browser;

    /**
     * The test HTTP root of the booted application, used to build absolute URLs for {@code [W]}.
     */
    @TestHTTPResource("/")
    URL baseUrl;

    /**
     * Freezes the program clock before each scenario so the active-member window is
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
    // J1 — creation form: ordered guards then 303-to-workbench success
    // --------------------------------------------------

    /**
     * J1 — the community list exposes the {@code Nouvelle communauté} button, and the creation
     * form applies its guards in order: an already-taken code is refused with {@code A
     * community with code '<c>' already exists}; a negative monthly cap with {@code The monthly
     * cap cannot be negative} and a negative enrollment cap with {@code The enrollment cap
     * cannot be negative}; a half-specified renewal window with {@code A renewal window needs
     * both its start and end months}; a month outside 1-12 with {@code A renewal month must be
     * between 1 and 12}. A valid submission redirects (303) to the freshly-created community's
     * workbench with {@code Community <c> created} and persists it open to enrollments.
     */
    @Test
    void j1_creationFormAppliesOrderedGuardsThenRedirectsToWorkbench() {
        String created = "ZZZ_J1_OK";
        try {
            String list = getHtml("/ui/communities");
            assertTrue(list.contains("href=\"/ui/communities/new\""), "the list links to the creation form (J1)");
            assertTrue(list.contains("Nouvelle communauté"), "the list carries the Nouvelle communauté button (J1)");
            assertEquals("A community with code '" + SEEDED_COMMUNITY + "' already exists",
                    noticeOf(create(SEEDED_COMMUNITY, "dup", "", "", "", "").header("Location")),
                    "a taken code is refused with the literal (J1)");
            assertEquals("The monthly cap cannot be negative",
                    noticeOf(create("ZZZ_J1_A", "x", "-1", "", "", "").header("Location")),
                    "a negative monthly cap is refused (J1)");
            assertEquals("The enrollment cap cannot be negative",
                    noticeOf(create("ZZZ_J1_B", "x", "", "-5", "", "").header("Location")),
                    "a negative enrollment cap is refused (J1)");
            assertEquals("A renewal window needs both its start and end months",
                    noticeOf(create("ZZZ_J1_C", "x", "", "", "3", "").header("Location")),
                    "a half-specified renewal window is refused (J1)");
            assertEquals("A renewal month must be between 1 and 12",
                    noticeOf(create("ZZZ_J1_D", "x", "", "", "0", "12").header("Location")),
                    "a renewal month outside 1-12 is refused (J1)");
            Response ok = create(created, "Test community", "20.00", "", "", "");
            assertEquals(303, ok.statusCode(), "a valid creation redirects (§21.3)");
            assertTrue(ok.header("Location").contains("/ui/communities/" + created),
                    "the valid creation lands on the new community's workbench (J1)");
            assertEquals("Community " + created + " created", noticeOf(ok.header("Location")),
                    "the valid creation reports the created community (J1)");
            assertTrue(communityIsActive(created), "a created community is open to enrollments (§23.2)");
        } finally {
            purgeCommunity(created);
        }
    }

    // --------------------------------------------------
    // J2 — parameter edition: frozen code, updated notice
    // --------------------------------------------------

    /**
     * J2 — the parameter edition form freezes the identity: the code field is rendered
     * {@code readonly} carrying the community code, the form posts to the update route, and a
     * valid update redirects to the workbench with {@code Community <c> updated} and persists
     * the new label.
     */
    @Test
    void j2_editionFreezesTheCodeAndReportsUpdated() {
        String code = "ZZZ_J2";
        try {
            seedCommunity(code, "Before", new BigDecimal("20.00"), null, null, null, true);
            String form = getHtml("/ui/communities/" + code + "/edit");
            assertTrue(form.contains("name=\"code\""), "the edit form carries the code field (J2)");
            assertTrue(form.contains("value=\"" + code + "\" required readonly"), "the code field is frozen readonly (J2)");
            assertTrue(form.contains("action=\"/ui/communities/" + code + "/update\""), "the form posts to the update route (J2)");
            Response updated = postForm("/ui/communities/" + code + "/update",
                    "label", "After", "monthlyCap", "25.00", "enrollmentCap", "", "renewalStartMonth", "",
                    "renewalEndMonth", "", "eligibilityCriteria", "");
            assertEquals(303, updated.statusCode(), "a valid update redirects (§21.3)");
            assertEquals("Community " + code + " updated", noticeOf(updated.header("Location")),
                    "the update reports the updated community (J2)");
            assertEquals("After", communityLabel(code), "the edited label is persisted (J2)");
        } finally {
            purgeCommunity(code);
        }
    }

    // --------------------------------------------------
    // J3 — close to enrollments: confirm, banner, refusals, reopen
    // --------------------------------------------------

    /**
     * J3 — closing a community to new enrollments: the open workbench carries the confirm text
     * {@code Fermer la communauté <c> aux nouveaux enrôlements ? Les membres existants
     * conservent leurs avantages.}; closing flips the badge to {@code FERMÉ} and shows the
     * explanatory banner; the existing member's membership stays active (they keep earning —
     * cross C10); enrolling a new card over {@code /graphql} is refused with {@code Community
     * '<c>' is closed to new enrollments (§23.2)}; the workbench refuses adding a non-member
     * with {@code Community '<c>' is closed to new enrollments: card <n> cannot be added
     * (§23.2)} while re-saving the existing members alone passes with {@code <n> membership(s)
     * saved}; and reopening reports {@code Community <c> reopened to enrollments}.
     */
    @Test
    void j3_closureBlocksEnrollmentsPreservesExistingMembersAndReopens() {
        String code = "ZZZ_J3";
        try {
            seedCommunity(code, "Closable", new BigDecimal("20.00"), null, null, null, true);
            seedMembership(CARD_MEMBER, code, LocalDate.of(2026, 2, 10), null);
            String open = getHtml("/ui/communities/" + code);
            assertTrue(open.contains("Fermer la communauté " + code + " aux nouveaux enrôlements ?"
                            + " Les membres existants conservent leurs avantages."),
                    "the open workbench carries the closure confirm text (J3)");
            assertTrue(open.contains(">OUVERT<"), "the open community carries the OUVERT badge (J3)");
            Response closed = postForm("/ui/communities/" + code + "/active", "active", "false");
            assertEquals(303, closed.statusCode(), "closing redirects to the workbench (§21.3)");
            assertEquals("Community " + code + " closed to new enrollments", noticeOf(closed.header("Location")),
                    "closing reports the closed community (J3)");
            String shut = getHtml("/ui/communities/" + code);
            assertTrue(shut.contains(">FERMÉ<"), "the closed community carries the FERMÉ badge (J3)");
            assertTrue(shut.contains("Communauté fermée aux nouveaux enrôlements"), "the closed banner is shown (J3)");
            assertTrue(membershipIsActive(CARD_MEMBER, code), "the existing member keeps an active membership — keeps earning (cross C10, §23.2)");
            assertEquals("Community '" + code + "' is closed to new enrollments (§23.2)",
                    graphqlEnrollError(CARD_OUTSIDER, code), "enrolling a new card over GraphQL is refused (§23.2)");
            String addNonMember = "[" + memberJson(CARD_MEMBER, "2026-02-10", null) + ","
                    + memberJson(CARD_OUTSIDER, "2026-08-01", null) + "]";
            assertEquals("Community '" + code + "' is closed to new enrollments: card " + CARD_OUTSIDER
                            + " cannot be added (§23.2)",
                    noticeOf(postForm("/ui/communities/" + code + "/memberships", "members", addNonMember).header("Location")),
                    "the workbench refuses adding a non-member to a closed community (§23.2)");
            String keepExisting = "[" + memberJson(CARD_MEMBER, "2026-02-10", null) + "]";
            assertEquals("1 membership(s) saved",
                    noticeOf(postForm("/ui/communities/" + code + "/memberships", "members", keepExisting).header("Location")),
                    "re-saving the existing members alone passes on a closed community (§23.2)");
            assertEquals("Community " + code + " reopened to enrollments",
                    noticeOf(postForm("/ui/communities/" + code + "/active", "active", "true").header("Location")),
                    "reopening reports the reopened community (J3)");
        } finally {
            purgeCommunity(code);
        }
    }

    // --------------------------------------------------
    // J4 [W] — the workbench is the complete source of truth
    // --------------------------------------------------

    /**
     * J4 [W] — the membership workbench driven in a real headless browser is the complete
     * source of truth (§23.2): removing a row and clicking {@code Enregistrer la structure}
     * deletes that membership in base (deletion by omission) and reports {@code 1 membership(s)
     * saved}; a row carrying an unknown card is silently ignored on the next save; and a
     * submission exceeding the enrollment cap is refused with {@code Enrollment cap reached for
     * community '<c>' (<n>, §28.4)} while nothing is written (the cap guard runs before the
     * delete).
     * <p>
     * Observed app behaviour (residue, not worked around in source): the workbench serializes
     * the initial set with a mapper that writes {@code LocalDate} as a numeric array, so seeded
     * {@code validFrom} values do not populate the {@code type=date} inputs on load; the dates
     * are therefore re-affirmed in the browser before each save, exactly as an operator would.
     */
    @Test
    void j4_workbenchIsTheCompleteSourceOfTruth() {
        String code = "ZZZ_J4";
        Page page = null;
        try {
            seedCommunity(code, "Workbench", new BigDecimal("20.00"), 2, null, null, true);
            seedMembership(CARD_OUTSIDER, code, LocalDate.of(2026, 1, 1), null);
            seedMembership(CARD_B, code, LocalDate.of(2026, 1, 1), null);
            page = login();
            page.navigate(url("ui/communities/" + code));
            assertEquals(2, page.locator("#members-body tr").count(), "the workbench loads the whole membership set (J4)");
            removeMemberRow(page, CARD_B);
            reaffirmDates(page, "2026-01-01");
            saveStructure(page, code);
            assertEquals("1 membership(s) saved", page.locator(".alert-ok").textContent(),
                    "deletion by omission saves the surviving members (J4)");
            assertEquals(1, membershipCount(code), "the omitted membership is deleted in base (deletion by omission, §23.2)");
            assertTrue(membershipIsActive(CARD_OUTSIDER, code), "the kept membership survives (J4)");
            assertFalse(membershipIsActive(CARD_B, code), "the removed membership is gone from base (J4)");
            page.navigate(url("ui/communities/" + code));
            reaffirmDates(page, "2026-01-01");
            page.locator("#add-member").click();
            fillNewMemberRow(page, "9999999999999", "2026-03-01");
            saveStructure(page, code);
            assertEquals("1 membership(s) saved", page.locator(".alert-ok").textContent(),
                    "an unknown card in the submission is silently ignored (J4)");
            assertEquals(1, membershipCount(code), "the unknown card writes no membership (J4)");
            page.navigate(url("ui/communities/" + code));
            reaffirmDates(page, "2026-01-01");
            page.locator("#add-member").click();
            fillNewMemberRow(page, CARD_B, "2026-04-01");
            page.locator("#add-member").click();
            fillNewMemberRow(page, CARD_C, "2026-04-01");
            saveStructure(page, code);
            assertEquals("Enrollment cap reached for community '" + code + "' (2, §28.4)",
                    page.locator(".alert-error").textContent(), "a submission over the enrollment cap is refused (§28.4)");
            assertEquals(1, membershipCount(code), "nothing is written when the cap is exceeded (§28.4)");
        } finally {
            if (page != null) {
                page.close();
            }
            purgeCommunity(code);
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
     * Posts the community creation form with its seven fields over a fresh admin session,
     * without following the 303 so the guard notice stays on the {@code Location} header.
     *
     * @param code          The community code.
     * @param label         The human label.
     * @param monthlyCap    The monthly cap field, or blank.
     * @param enrollmentCap The enrollment cap field, or blank.
     * @param startMonth    The renewal window start month field, or blank.
     * @param endMonth      The renewal window end month field, or blank.
     * @return The un-followed HTTP response.
     */
    private static Response create(String code, String label, String monthlyCap, String enrollmentCap,
                                   String startMonth, String endMonth) {
        return postForm("/ui/communities/create", "code", code, "label", label, "monthlyCap", monthlyCap,
                "enrollmentCap", enrollmentCap, "renewalStartMonth", startMonth, "renewalEndMonth", endMonth,
                "eligibilityCriteria", "");
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

    /**
     * Builds a single workbench membership JSON object from its raw fields.
     *
     * @param card      The card number.
     * @param validFrom The window start as an ISO date.
     * @param validTo   The window end as an ISO date, or null.
     * @return The JSON object string.
     */
    private static String memberJson(String card, String validFrom, String validTo) {
        return "{\"card\":\"" + card + "\",\"validFrom\":\"" + validFrom + "\",\"validTo\":"
                + (validTo == null ? "null" : "\"" + validTo + "\"") + "}";
    }

    // --------------------------------------------------
    // Helpers — GraphQL (Basic admin)
    // --------------------------------------------------

    /**
     * Enrolls a card into a community through the {@code upsertMembership} mutation over
     * {@code /graphql} (Basic admin) and returns the first surfaced error message, or null when
     * the mutation succeeds.
     *
     * @param card The card number to enroll.
     * @param code The community code.
     * @return The first GraphQL error message, or null.
     */
    private static String graphqlEnrollError(String card, String code) {
        String query = "mutation { upsertMembership(card: \"" + card + "\", community: \"" + code
                + "\", validFrom: \"2026-09-01\") { card } }";
        return RestAssured.given().auth().preemptive().basic(ADMIN_USER, ADMIN_PASSWORD)
                .contentType(ContentType.JSON).body(Map.of("query", query))
                .post("/graphql").jsonPath().getString("errors[0].message");
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
     * Clicks the {@code Retirer} button of the workbench row whose card input holds the given
     * value, removing that membership from the submission (deletion by omission).
     *
     * @param page The browser page.
     * @param card The card number of the row to remove.
     */
    private static void removeMemberRow(Page page, String card) {
        Locator rows = page.locator("#members-body tr");
        int count = rows.count();
        for (int i = 0; i < count; i++) {
            Locator row = rows.nth(i);
            if (card.equals(row.locator("input").first().inputValue())) {
                row.locator("button:has-text(\"Retirer\")").click();
                return;
            }
        }
        throw new IllegalStateException("no workbench row for card " + card);
    }

    /**
     * Fills the last (freshly-added) workbench row with a card and a start date.
     *
     * @param page      The browser page.
     * @param card      The card number to type.
     * @param validFrom The window start as an ISO date.
     */
    private static void fillNewMemberRow(Page page, String card, String validFrom) {
        Locator row = page.locator("#members-body tr").last();
        row.locator("input").nth(0).fill(card);
        row.locator("input").nth(1).fill(validFrom);
    }

    /**
     * Re-affirms the start date of every currently-loaded workbench row (§23.2). The workbench
     * serializes the initial membership set with a mapper that writes {@code LocalDate} as a
     * numeric array, so the seeded {@code validFrom} does not populate the {@code type=date}
     * inputs on load and must be re-entered — as a real operator would — for a member to survive
     * the save; observed app behaviour, reported as a residue, not worked around in source.
     *
     * @param page      The browser page.
     * @param validFrom The window start to (re-)enter, as an ISO date.
     */
    private static void reaffirmDates(Page page, String validFrom) {
        Locator rows = page.locator("#members-body tr");
        int count = rows.count();
        for (int i = 0; i < count; i++) {
            rows.nth(i).locator("input").nth(1).fill(validFrom);
        }
    }

    /**
     * Clicks {@code Enregistrer la structure} and waits for the workbench to re-render with its
     * one-shot notice.
     *
     * @param page The browser page.
     * @param code The community code the workbench redirects back to.
     */
    private static void saveStructure(Page page, String code) {
        page.locator("#members-form button[type='submit']").click();
        page.waitForURL("**/ui/communities/" + code + "?**");
    }

    // --------------------------------------------------
    // Helpers — database seeding (isolated test communities)
    // --------------------------------------------------

    /**
     * Inserts an isolated test community in its own transaction — the way to reach a controlled
     * state (open/closed, capped) without touching the seeded catalog.
     *
     * @param code          The community code.
     * @param label         The human label.
     * @param monthlyCap    The per-card monthly cap, or null.
     * @param enrollmentCap The enrollment cap, or null.
     * @param startMonth    The renewal window start month, or null.
     * @param endMonth      The renewal window end month, or null.
     * @param active        Whether the community is open to enrollments.
     */
    private static void seedCommunity(String code, String label, BigDecimal monthlyCap, Integer enrollmentCap,
                                      Integer startMonth, Integer endMonth, boolean active) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityCommunity community = new FidelityCommunity();
            community.code = code;
            community.label = label;
            community.monthlyCap = monthlyCap;
            community.enrollmentCap = enrollmentCap;
            community.renewalStartMonth = startMonth;
            community.renewalEndMonth = endMonth;
            community.active = active;
            community.persist();
        });
    }

    /**
     * Inserts a membership of a seeded card in a test community, in its own transaction.
     *
     * @param card      The card number.
     * @param code      The community code.
     * @param validFrom The window start.
     * @param validTo   The window end, or null while open.
     */
    private static void seedMembership(String card, String code, LocalDate validFrom, LocalDate validTo) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityMembership membership = new FidelityMembership();
            membership.account = FidelityAccount.findByCardNumber(card);
            membership.community = FidelityCommunity.findByCode(code);
            membership.validFrom = validFrom;
            membership.validTo = validTo;
            membership.persist();
        });
    }

    /**
     * Removes a test community and its memberships in a fresh transaction, restoring the seeded
     * world; a null or unknown code is a no-op.
     *
     * @param code The test community code, or null.
     */
    private static void purgeCommunity(String code) {
        if (code == null) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityCommunity community = FidelityCommunity.findByCode(code);
            if (community != null) {
                FidelityMembership.delete("community", community);
                community.delete();
            }
        });
    }

    // --------------------------------------------------
    // Helpers — database reads (no absolute ids)
    // --------------------------------------------------

    /**
     * Reads whether a community is open to enrollments in a fresh transaction.
     *
     * @param code The community code.
     * @return true when the community is active.
     */
    private static boolean communityIsActive(String code) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityCommunity.findByCode(code).active);
    }

    /**
     * Reads a community's label in a fresh transaction.
     *
     * @param code The community code.
     * @return The label.
     */
    private static String communityLabel(String code) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityCommunity.findByCode(code).label);
    }

    /**
     * Counts the memberships of a community in a fresh transaction.
     *
     * @param code The community code.
     * @return The membership count.
     */
    private static long membershipCount(String code) {
        return QuarkusTransaction.requiringNew().call(() ->
                FidelityMembership.count("community = ?1", FidelityCommunity.findByCode(code)));
    }

    /**
     * Reads whether a card holds a membership of a community active on the frozen day, in a
     * fresh transaction.
     *
     * @param card The card number.
     * @param code The community code.
     * @return true when an active membership exists.
     */
    private static boolean membershipIsActive(String card, String code) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            FidelityCommunity community = FidelityCommunity.findByCode(code);
            LocalDate today = FROZEN_AT.toLocalDate();
            return FidelityMembership.count(
                    "account = ?1 and community = ?2 and validFrom <= ?3 and (validTo is null or validTo >= ?3)",
                    account, community, today) > 0;
        });
    }
}
