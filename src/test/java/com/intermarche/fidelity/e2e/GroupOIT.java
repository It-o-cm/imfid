package com.intermarche.fidelity.e2e;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group O — the administration list surfaces: filters, sort whitelist,
 * pagination clamping and empty states (§23.3, §23.2, §31.3, guide §5.1,
 * e2escenarios-imfid.md "## O. Listes, filtres &amp; pagination UI"). Every scenario boots
 * the real application under {@link QuarkusTest} against the DataInitializer-rebuilt 2026
 * world (wipe + reload at each boot) and drives the {@code /ui/*} screens over a form session
 * ({@code j_security_check} + {@code quarkus-credential} cookie), reading back the rendered
 * HTML of the shared {@code ListView} (§21).
 * <p>
 * The four scenarios: the rule list filters and sort — {@code code} contains-insensitive,
 * {@code type} strict-exact, a sort whitelist of {@code code}/{@code type}/{@code validFrom}/
 * {@code priority} with a silent fallback to {@code code} on an injected key and no arbitrary
 * order, and {@code dir=desc} reversing the order (O1); the pagination clamp — {@code page=999}
 * to the last real page, {@code page=-3} to the first, 25 rows a page, and the three frozen
 * {@code ListView} literals {@code No <label>}, {@code Showing <a>–<b> of <n>} and
 * {@code — page <i> of <m>} (garde-fou n°4, O2); the two empty states — the filtered rule list
 * and the community list, each carrying its French message and hint (O3); and the flat,
 * unpaged community list with its per-line active-member counter excluding expired memberships
 * and its {@code OUVERT}/{@code FERMÉ} badge (crossing M4, O4).
 * <p>
 * The seed carries eleven rules and four communities that are only read; the pagination proof
 * mints twenty isolated {@code ZZZ_O2_*} filler rules removed in a {@code finally}, and the
 * community-empty proof snapshots the whole seeded catalog, empties it, asserts the empty
 * state and restores it byte-for-byte in a {@code finally}, so no test depends on another's
 * order and the seeded world is never left disturbed. Time is frozen through
 * {@link DateTimeProvider} so the active-member window of O4 is literal whatever day the
 * campaign runs, and the seeded {@code SMALL_BUDGETS} membership (window closed 2026-02-28) is
 * a deterministically-expired member proving the counter exclusion at the frozen 2026-08-22.
 */
@QuarkusTest
class GroupOIT {

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
     * The frozen instant of the whole class: the O4 active-member window is literal against it.
     */
    private static final LocalDateTime FROZEN_AT = LocalDateTime.of(2026, 8, 22, 10, 0);

    /**
     * The en dash {@code ListView} inserts inside a range label ({@code Showing 1–25}).
     */
    private static final String EN_DASH = "–";

    /**
     * The em dash {@code ListView} inserts before the page counter ({@code — page 1 of 2}).
     */
    private static final String EM_DASH = "—";

    /**
     * Freezes the program clock before each scenario so the active-member window and the
     * default rule states are deterministic (§24.6).
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
    // O1 — rule list filters and sort whitelist
    // --------------------------------------------------

    /**
     * O1 — the rule list filters and orders (§23.3, guide §5.1): the {@code code} filter is a
     * case-insensitive contains ({@code ?code=socle} and {@code ?code=SoClE} both surface
     * {@code SOCLE_5_MARQUES} and {@code SOCLE_4_MARQUES} while hiding the rest); the
     * {@code type} filter is strict-exact ({@code ?type=COMMUNITY_EARN} surfaces the community
     * rules, but the prefix {@code ?type=COMMUNITY} matches nothing — never a contains); the
     * sort key is whitelisted ({@code ?sort=priority} is honoured, but the injected
     * {@code ?sort=specification} silently falls back to {@code code} — no error, no arbitrary
     * order); and {@code dir=desc} reverses the order.
     */
    @Test
    void o1_ruleListFiltersAreContainsInsensitiveTypeStrictAndSortWhitelisted() {
        String socleLower = getHtml("/ui/rules?code=socle");
        assertTrue(socleLower.contains("/ui/rules/SOCLE_5_MARQUES"), "code contains matches SOCLE_5_MARQUES (O1)");
        assertTrue(socleLower.contains("/ui/rules/SOCLE_4_MARQUES"), "code contains matches SOCLE_4_MARQUES (O1)");
        assertFalse(socleLower.contains("/ui/rules/FL_WEEKEND"), "code contains excludes the non-matching rules (O1)");
        String socleMixed = getHtml("/ui/rules?code=SoClE");
        assertTrue(socleMixed.contains("/ui/rules/SOCLE_5_MARQUES"), "the code filter is case-insensitive (O1)");
        assertTrue(socleMixed.contains("/ui/rules/SOCLE_4_MARQUES"), "the code filter is case-insensitive (O1)");
        String typeExact = getHtml("/ui/rules?type=COMMUNITY_EARN");
        assertTrue(typeExact.contains("/ui/rules/COMMUNITY_BABIES"), "the type filter surfaces the exact type (O1)");
        assertFalse(typeExact.contains("/ui/rules/SOCLE_5_MARQUES"), "the type filter excludes other types (O1)");
        String typePrefix = getHtml("/ui/rules?type=COMMUNITY");
        assertFalse(typePrefix.contains("/ui/rules/COMMUNITY_BABIES"), "the type filter is strict-exact, never a contains (O1)");
        assertTrue(typePrefix.contains("Aucune règle ne correspond au filtre."), "a strict miss yields the empty state (O1)");
        String honoured = getHtml("/ui/rules?sort=priority");
        assertTrue(honoured.contains("name=\"sort\" value=\"priority\""), "a whitelisted sort key is honoured (O1)");
        String injected = getHtml("/ui/rules?sort=specification");
        assertTrue(injected.contains("name=\"sort\" value=\"code\""), "an injected sort key silently falls back to code (O1)");
        assertTrue(injected.indexOf("/ui/rules/CGU_EXCLUSION") < injected.indexOf("/ui/rules/STUDENTS_HYGIENE_28"),
                "the fallback orders by code ascending, never arbitrarily (O1)");
        String desc = getHtml("/ui/rules?sort=code&dir=desc");
        assertTrue(desc.indexOf("/ui/rules/STUDENTS_HYGIENE_28") < desc.indexOf("/ui/rules/CGU_EXCLUSION"),
                "dir=desc reverses the order (O1)");
    }

    // --------------------------------------------------
    // O2 — pagination clamp and the frozen ListView literals
    // --------------------------------------------------

    /**
     * O2 — the pagination clamps to the real bounds and states its position with the frozen
     * {@code ListView} literals (§31.3, guide §5.1): with thirty-one rules over two pages of
     * twenty-five, {@code ?page=999} clamps to the last page (page 2 of 2, {@code Showing
     * 26–31 of 31}) and {@code ?page=-3} clamps to the first (page 1 of 2, {@code Showing 1–25
     * of 31}); a filter matching nothing renders the {@code No rule} summary. The three
     * literals — {@code No <label>}, {@code Showing <a>–<b> of <n>} and {@code — page <i> of
     * <m>} — are asserted verbatim (garde-fou n°4).
     */
    @Test
    void o2_paginationClampsToRealBoundsWithFrozenLiterals() {
        try {
            for (int i = 0; i < 20; i++) {
                seedFillerRule(String.format("ZZZ_O2_%02d", i));
            }
            String last = getHtml("/ui/rules?page=999");
            assertTrue(last.contains("31 rules " + EM_DASH + " page 2 of 2"), "page=999 clamps to the last page (O2)");
            assertTrue(last.contains("Showing 26" + EN_DASH + "31 of 31"), "the last page states its range (O2)");
            String first = getHtml("/ui/rules?page=-3");
            assertTrue(first.contains("31 rules " + EM_DASH + " page 1 of 2"), "page=-3 clamps to the first page (O2)");
            assertTrue(first.contains("Showing 1" + EN_DASH + "25 of 31"), "the first page shows twenty-five rows (O2)");
            String empty = getHtml("/ui/rules?code=NOSUCHRULEZZZ");
            assertTrue(empty.contains(">No rule<"), "a filter matching nothing renders the No rule summary (O2)");
        } finally {
            purgeFillerRules();
        }
    }

    // --------------------------------------------------
    // O3 — empty states of the two lists
    // --------------------------------------------------

    /**
     * O3 — the two empty states carry their French message and hint (§23.3, §23.2): a rule list
     * filtered to nothing renders {@code Aucune règle ne correspond au filtre.} with the hint
     * {@code Créez une règle, ou importez le domaine FIDELITY_RULES depuis l'écran Imports.};
     * an empty community list renders {@code Aucune communauté.} with the hint {@code Importez
     * le domaine FIDELITY_COMMUNITIES depuis l'écran Imports.} The seeded catalog is snapshotted,
     * emptied to reach the community empty state, then restored byte-for-byte in a
     * {@code finally}, so the assertion is deterministic and no later test is disturbed.
     */
    @Test
    void o3_filteredRuleListAndEmptyCommunityListRenderTheirFrenchEmptyStates() {
        String filtered = getHtml("/ui/rules?code=NOSUCHRULEZZZ");
        assertTrue(filtered.contains("Aucune règle ne correspond au filtre."),
                "the filtered rule list renders its empty message (O3)");
        assertTrue(filtered.contains("Créez une règle, ou importez le domaine FIDELITY_RULES depuis l'écran Imports."),
                "the filtered rule list renders its empty hint (O3)");
        List<CommunitySnap> communities = snapshotCommunities();
        List<MembershipSnap> memberships = snapshotMemberships();
        try {
            emptyCommunityCatalog();
            String emptyList = getHtml("/ui/communities");
            assertTrue(emptyList.contains("Aucune communauté."), "the empty community list renders its message (O3)");
            assertTrue(emptyList.contains("Importez le domaine FIDELITY_COMMUNITIES depuis l'écran Imports."),
                    "the empty community list renders its hint (O3)");
        } finally {
            restoreCommunityCatalog(communities, memberships);
        }
    }

    // --------------------------------------------------
    // O4 — the flat, unpaged community list and its counters
    // --------------------------------------------------

    /**
     * O4 — the community list is a single flat page (§23.2): it carries no pager, and each line
     * shows its active-member counter and an {@code OUVERT}/{@code FERMÉ} badge. The counter
     * excludes expired memberships (crossing M4): at the frozen 2026-08-22, {@code BABIES} and
     * {@code STUDENTS} each count their one open membership while {@code SMALL_BUDGETS} counts
     * zero — its only membership closed on 2026-02-28. An isolated closed community carrying one
     * open and one expired membership proves both facets at once: its badge reads {@code FERMÉ}
     * and its counter reads one, the expired row excluded.
     */
    @Test
    void o4_communityListIsFlatWithActiveMemberCountersAndBadges() {
        String probe = "ZZZ_O4";
        try {
            seedCommunity(probe, "Closed probe", null, false);
            seedMembership("2990000000019", probe, LocalDate.of(2026, 1, 1), null);
            seedMembership("2990000000026", probe, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1));
            String html = getHtml("/ui/communities");
            assertFalse(html.contains("class=\"pager\""), "the community list carries no pager (O4)");
            assertTrue(rowOf(html, "BABIES").contains("<td>1</td>"), "BABIES counts its one open member (O4)");
            assertTrue(rowOf(html, "STUDENTS").contains("<td>1</td>"), "STUDENTS counts its one open member (O4)");
            String smallBudgets = rowOf(html, "SMALL_BUDGETS");
            assertTrue(smallBudgets.contains("<td>0</td>"), "SMALL_BUDGETS excludes its expired member (crossing M4, O4)");
            assertFalse(smallBudgets.contains("<td>1</td>"), "SMALL_BUDGETS counts no active member (O4)");
            assertTrue(rowOf(html, "BABIES").contains(">OUVERT<"), "an open community carries the OUVERT badge (O4)");
            String probeRow = rowOf(html, probe);
            assertTrue(probeRow.contains(">FERMÉ<"), "a closed community carries the FERMÉ badge (O4)");
            assertTrue(probeRow.contains("<td>1</td>"), "the closed probe counts only its open member (crossing M4, O4)");
        } finally {
            purgeCommunity(probe);
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
     * Reads a rendered page as HTML over a fresh admin session.
     *
     * @param path The GET path, possibly carrying a query string.
     * @return The rendered body.
     */
    private static String getHtml(String path) {
        return RestAssured.given().cookie(SESSION_COOKIE, adminSession())
                .get(path).then().statusCode(200).extract().asString();
    }

    /**
     * Extracts the table-row markup of a community list line, from its code link to the row
     * close, so a per-line assertion never leaks into a neighbouring row.
     *
     * @param html The rendered community list.
     * @param code The community code of the row to isolate.
     * @return The row markup.
     */
    private static String rowOf(String html, String code) {
        int start = html.indexOf("/ui/communities/" + code + "\"");
        assertTrue(start >= 0, "the community list must carry a row for " + code);
        int end = html.indexOf("</tr>", start);
        assertTrue(end >= 0, "the community row for " + code + " must be closed");
        return html.substring(start, end);
    }

    // --------------------------------------------------
    // Helpers — pagination filler rules (isolated)
    // --------------------------------------------------

    /**
     * Inserts an isolated filler rule with a minimal valid specification, in its own
     * transaction — the way to push the rule count over a page boundary without touching the
     * seeded catalog. The direct persist bypasses the schema validation the administration
     * applies, which is immaterial to a pagination count.
     *
     * @param code The filler rule code.
     */
    private static void seedFillerRule(String code) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityRule rule = new FidelityRule();
            rule.code = code;
            rule.type = FidelityRule.TYPE_BRAND_TIERED_EARN;
            rule.label = "Pagination filler";
            rule.specification = "{\"scope\":{\"wholeStore\":true},\"rate\":0.05}";
            rule.validFrom = LocalDateTime.of(2026, 1, 1, 0, 0);
            rule.priority = 0;
            rule.exclusive = false;
            rule.active = true;
            rule.persist();
        });
    }

    /**
     * Removes every filler rule minted for the pagination proof, restoring the seeded count.
     */
    private static void purgeFillerRules() {
        QuarkusTransaction.requiringNew().run(() -> FidelityRule.delete("code like ?1", "ZZZ_O2_%"));
    }

    // --------------------------------------------------
    // Helpers — community seeding (isolated)
    // --------------------------------------------------

    /**
     * Inserts an isolated test community in its own transaction.
     *
     * @param code       The community code.
     * @param label      The human label.
     * @param monthlyCap The monthly cap, or null.
     * @param active     Whether the community is open to enrollments.
     */
    private static void seedCommunity(String code, String label, BigDecimal monthlyCap, boolean active) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityCommunity community = new FidelityCommunity();
            community.code = code;
            community.label = label;
            community.monthlyCap = monthlyCap;
            community.active = active;
            community.persist();
        });
    }

    /**
     * Inserts a membership of a seeded card in a community, in its own transaction.
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
     * Removes a test community and its memberships in a fresh transaction; a null or unknown
     * code is a no-op.
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
    // Helpers — snapshot / restore of the seeded community catalog (O3)
    // --------------------------------------------------

    /**
     * Snapshots every seeded community into detached value objects, in a fresh transaction.
     *
     * @return The community snapshots.
     */
    private static List<CommunitySnap> snapshotCommunities() {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<CommunitySnap> snaps = new ArrayList<>();
            for (FidelityCommunity community : FidelityCommunity.<FidelityCommunity>listAll()) {
                snaps.add(new CommunitySnap(community.code, community.label, community.monthlyCap,
                        community.enrollmentCap, community.renewalStartMonth, community.renewalEndMonth,
                        community.eligibilityCriteria, community.active));
            }
            return snaps;
        });
    }

    /**
     * Snapshots every seeded membership into detached value objects, in a fresh transaction.
     *
     * @return The membership snapshots.
     */
    private static List<MembershipSnap> snapshotMemberships() {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<MembershipSnap> snaps = new ArrayList<>();
            for (FidelityMembership membership : FidelityMembership.<FidelityMembership>listAll()) {
                snaps.add(new MembershipSnap(membership.account.cardNumber, membership.community.code,
                        membership.validFrom, membership.validTo));
            }
            return snaps;
        });
    }

    /**
     * Empties the community catalog — memberships first for the foreign key, then communities —
     * in a fresh transaction, to reach the community empty state (O3).
     */
    private static void emptyCommunityCatalog() {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityMembership.deleteAll();
            FidelityCommunity.deleteAll();
        });
    }

    /**
     * Restores the seeded community catalog from its snapshots — communities then memberships —
     * in a fresh transaction, leaving the seeded world byte-for-byte as it was (O3).
     *
     * @param communities The community snapshots.
     * @param memberships The membership snapshots.
     */
    private static void restoreCommunityCatalog(List<CommunitySnap> communities, List<MembershipSnap> memberships) {
        QuarkusTransaction.requiringNew().run(() -> {
            for (CommunitySnap snap : communities) {
                FidelityCommunity community = new FidelityCommunity();
                community.code = snap.code();
                community.label = snap.label();
                community.monthlyCap = snap.monthlyCap();
                community.enrollmentCap = snap.enrollmentCap();
                community.renewalStartMonth = snap.renewalStartMonth();
                community.renewalEndMonth = snap.renewalEndMonth();
                community.eligibilityCriteria = snap.eligibilityCriteria();
                community.active = snap.active();
                community.persist();
            }
            for (MembershipSnap snap : memberships) {
                FidelityMembership membership = new FidelityMembership();
                membership.account = FidelityAccount.findByCardNumber(snap.card());
                membership.community = FidelityCommunity.findByCode(snap.community());
                membership.validFrom = snap.validFrom();
                membership.validTo = snap.validTo();
                membership.persist();
            }
        });
    }

    /**
     * A detached snapshot of a seeded community, used to restore the catalog after the O3
     * community empty-state assertion.
     *
     * @param code                The community code.
     * @param label               The human label.
     * @param monthlyCap          The monthly cap, or null.
     * @param enrollmentCap       The enrollment cap, or null.
     * @param renewalStartMonth   The renewal window start month, or null.
     * @param renewalEndMonth     The renewal window end month, or null.
     * @param eligibilityCriteria The eligibility criterion, or null.
     * @param active              Whether the community is open to enrollments.
     */
    private record CommunitySnap(String code, String label, BigDecimal monthlyCap, Integer enrollmentCap,
                                 Integer renewalStartMonth, Integer renewalEndMonth, String eligibilityCriteria,
                                 boolean active) {
    }

    /**
     * A detached snapshot of a seeded membership, used to restore the catalog after the O3
     * community empty-state assertion.
     *
     * @param card      The card number.
     * @param community The community code.
     * @param validFrom The window start.
     * @param validTo   The window end, or null.
     */
    private record MembershipSnap(String card, String community, LocalDate validFrom, LocalDate validTo) {
    }
}
