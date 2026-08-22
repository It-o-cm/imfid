package com.intermarche.e2e;

import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group L — the Simulator screen (§23.5,
 * e2escenarios-imfid.md "## L. Simulateur [W]"): the acceptance test of §22 turned into an
 * admin screen and the product demonstration support. The whole group is {@code [W]}, so
 * every scenario drives a real headless Chromium ({@link WithPlaywright}) against the
 * application booted by {@link QuarkusTest} with the DataInitializer-rebuilt 2026 world; a
 * pasted {@code /valuation} response, a card and a forced evaluation date are entered in the
 * real form and the rule-by-rule earn, the burnable base, the notice or the error are read
 * back from the rendered page.
 * <p>
 * The four scenarios: the nominal run of the reference milk+water+yoghurt cart on the
 * rich card {@code …019} proving the earn is rendered rule by rule with its burnable base
 * and — the contract with C1 — that a simulation writes nothing (L1); the fossil prefill
 * trap, the GET still preloading the retired CSV card {@code LOYALTY-DEMO-001} which in the
 * {@code 299…} world resolves to the {@code Carte inconnue — earn vide (§20)} note (L2); the
 * two error surfaces, an unparsable couple yielding {@code Couple invalide : <msg>} and a
 * reconciliation breach yielding {@code Réconciliation §22.1 échouée : <msg>} (L3); and the
 * three demonstration levers — the 28th lighting the Students advantage, a Saturday lighting
 * F&amp;L, and a date before 2026-05-18 answering with the four-brand socle version (L4).
 * <p>
 * The forced date is entered in the {@code datetime-local} field and passed to the engine as
 * the evaluation instant (§26.3), never applied to the global clock; the program clock is
 * additionally frozen through {@link DateTimeProvider} so the seeded windows are literal and
 * the campaign is never calendar-flaky. The rich card {@code …019} carries three seeded
 * August-2026 visit days, so a nominal evaluation dated inside August 2026 is the fourth
 * visit and the socle boosts to 10 % deterministically (§24.4) — the visit rows are read as
 * seeded, never aged, because the forced date lands in their very month.
 * <p>
 * Justified residue: the L1 "plafonds" facet renders only when a cap actually bites; the
 * nominal reference cart saturates none of the seeded caps, so {@code capsApplied} is empty
 * and the caps table is absent by design — the cap-truncation rendering itself is exercised
 * by group C (C10), not reachable from the nominal L1 basket without saturating a community
 * cap the simulator is not meant to force.
 */
@QuarkusTest
@WithPlaywright
class GroupLIT {

    /**
     * Bootstrap administrator login name (SecurityBootstrap default), role {@code fid-admin}.
     */
    private static final String ADMIN_USER = "admin";

    /**
     * Bootstrap administrator password (SecurityBootstrap default in dev/test).
     */
    private static final String ADMIN_PASSWORD = "admin-password";

    /**
     * The frozen program instant of the class; the forced form date overrides it per
     * scenario, so this only fixes the {@code createdAt}-less defaults deterministically.
     */
    private static final LocalDateTime FROZEN_AT = LocalDateTime.of(2026, 8, 22, 10, 0);

    /**
     * The retired CSV demo card the GET still preloads — the L2 fossil trap; absent from the
     * {@code 299…} seeded world (§20, §23.5).
     */
    private static final String FOSSIL_CARD = "LOYALTY-DEMO-001";

    /**
     * Rich-history card (…019): three seeded August-2026 visit days, so a nominal August
     * evaluation is the boosting fourth visit.
     */
    private static final String CARD_RICH = "2990000000019";

    /**
     * Students-community card (…033): the STUDENTS membership covers the L4 28th lever.
     */
    private static final String CARD_STUDENT = "2990000000033";

    /**
     * Neutral ACTIVE card (…071): no visit, no membership — the bare-socle L4 levers.
     */
    private static final String CARD_PLAIN = "2990000000071";

    /**
     * Milk 1L, brand Pâturages (socle), UNIT — a socle assiette line.
     */
    private static final String EAN_MILK = "3300000000002";

    /**
     * Mineral water 1.5L, brand Paquito (socle), UNIT — a socle assiette line.
     */
    private static final String EAN_WATER = "3300000000007";

    /**
     * Yoghurt 4x125g, brand Pâturages (socle), UNIT — a socle assiette line.
     */
    private static final String EAN_YAOURT = "3300000000010";

    /**
     * Crisps 150g, family F_L, UNIT — the L4 weekend F&amp;L line.
     */
    private static final String EAN_CHIPS = "3300000000014";

    /**
     * Feminine-hygiene pads, brand Labell, family HYGIENE_FEM, UNIT — the L4 28th target.
     */
    private static final String EAN_HYGIENE = "3400000000030";

    /**
     * The Playwright browser context injected by quarkus-playwright (headless Chromium).
     */
    @InjectPlaywright
    BrowserContext browser;

    /**
     * The test HTTP root of the booted application, used to build absolute URLs.
     */
    @TestHTTPResource("/")
    URL baseUrl;

    /**
     * Freezes the program clock before each scenario so the {@code createdAt}-less defaults
     * are deterministic (§24.6).
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
    // L1 — nominal run, rule by rule, and zero side effect (C1)
    // --------------------------------------------------

    /**
     * L1 — nominal: pasting the reference milk+water+yoghurt {@code /valuation} response on
     * the rich card {@code …019} at a forced August-2026 date renders the earn rule by rule
     * (the {@code SOCLE_5_MARQUES} entry, boosted to 10 % on the fourth seeded visit = 0.60 €
     * on the 6.00 € net) with its burnable base of 6.00 €, and — the contract with C1 — the
     * simulation writes nothing: accounts, movements and traces are byte-for-byte unchanged
     * (§30.2, §23.5).
     */
    @Test
    void l1_nominalRendersEarnRuleByRuleWithBurnableBaseAndNoSideEffect() {
        long accountsBefore = count(() -> FidelityAccount.count());
        long movementsBefore = count(() -> FidelityMovement.count());
        long tracesBefore = count(() -> EarnTrace.count());
        Page page = login();
        try {
            simulate(page, CARD_RICH, "2026-08-17T10:00", valuationResponse(socleOffers(), "6.00"));
            assertTrue(page.locator(".sim-total").textContent().contains("0.60"),
                    "the total earn is the boosted 10 % of 6.00 € = 0.60 € (L1)");
            String entries = page.locator("table.data-table").textContent();
            assertTrue(entries.contains("SOCLE_5_MARQUES"), "the socle rule is rendered rule by rule (L1)");
            assertTrue(entries.contains("0.60"), "the socle entry carries its 0.60 € earn (L1)");
            assertTrue(page.locator("p.field-hint:has-text(\"Base décagnottable\")").textContent().contains("6.00 €"),
                    "the burnable base is rendered at 6.00 € (L1)");
        } finally {
            page.close();
        }
        assertEquals(accountsBefore, count(() -> FidelityAccount.count()), "a simulation writes no account (C1, §30.2)");
        assertEquals(movementsBefore, count(() -> FidelityMovement.count()), "a simulation writes no movement (C1, §30.2)");
        assertEquals(tracesBefore, count(() -> EarnTrace.count()), "a simulation writes no earn-trace or visit (C1, §30.2)");
    }

    // --------------------------------------------------
    // L2 — the fossil prefill trap (§20, §23.5)
    // --------------------------------------------------

    /**
     * L2 — fossil prefill: the GET preloads the retired CSV card {@code LOYALTY-DEMO-001}
     * (the trap engraved until the one-line correction in {@code SimulatorUiResource}), and
     * simulating a valid couple as-is in the {@code 299…} world resolves the card to nothing —
     * the earn is empty and the screen carries the {@code Carte inconnue — earn vide (§20)}
     * note while still rendering the burnable base (§20, §23.5).
     */
    @Test
    void l2_fossilPrefillResolvesToUnknownCardNote() {
        Page page = login();
        try {
            page.navigate(url("ui/simulator"));
            assertEquals(FOSSIL_CARD, page.locator("input[name='card']").inputValue(),
                    "the GET still preloads the fossil demo card (L2 trap)");
            page.fill("input[name='date']", "2026-08-17T10:00");
            page.fill("textarea[name='response']", valuationResponse(socleOffers(), "6.00"));
            submit(page);
            assertTrue(page.locator("p.field-hint:has-text(\"Carte inconnue\")").textContent()
                            .contains("Carte inconnue — earn vide (§20)"),
                    "the fossil card resolves to the unknown-card note (§20)");
            assertTrue(page.locator(".placeholder-note").textContent().contains("Aucune entrée earn"),
                    "an unknown card yields an empty earn (§20)");
            assertTrue(page.locator("p.field-hint:has-text(\"Base décagnottable\")").textContent().contains("6.00 €"),
                    "the burnable base is still rendered for an unknown card (§20)");
        } finally {
            page.close();
        }
    }

    // --------------------------------------------------
    // L3 — the two error surfaces (§22, §22.1)
    // --------------------------------------------------

    /**
     * L3 — errors: an unparsable pasted couple renders the {@code Couple invalide : <msg>}
     * alert, while a couple whose global total does not reconcile with its offers renders the
     * {@code Réconciliation §22.1 échouée : <msg>} alert — the two distinct failure surfaces
     * of the connector acceptance test (§22, §22.1, §23.5).
     */
    @Test
    void l3_invalidCoupleAndReconciliationBreachRenderTheirAlerts() {
        Page page = login();
        try {
            simulate(page, CARD_PLAIN, "2026-08-17T10:00", "{ this is not valid json");
            assertTrue(page.locator(".alert-error").textContent().startsWith("Couple invalide : "),
                    "an unparsable couple renders the Couple invalide alert (§22)");
            simulate(page, CARD_PLAIN, "2026-08-17T10:00", valuationResponse(socleOffers(), "5.00"));
            assertTrue(page.locator(".alert-error").textContent().startsWith("Réconciliation §22.1 échouée : "),
                    "a total that does not reconcile renders the Réconciliation §22.1 alert (§22.1)");
        } finally {
            page.close();
        }
    }

    // --------------------------------------------------
    // L4 — the three demonstration levers (§26.3)
    // --------------------------------------------------

    /**
     * L4 — demonstration levers: forcing the date to the 28th lights the Students advantage
     * ({@code STUDENTS_HYGIENE_28} on the student card {@code …033} with a Labell hygiene
     * line), forcing a Saturday lights F&amp;L ({@code FL_WEEKEND} on a crisps line), and
     * forcing a date before 2026-05-18 answers with the four-brand socle version
     * ({@code SOCLE_4_MARQUES}, the five-brand one not yet in force) — the simulator as the
     * hand-checkable oracle of the C scenarios (§26.3, §23.5).
     */
    @Test
    void l4_demonstrationLeversLightStudentsWeekendAndSocleVersion() {
        assertEquals(DayOfWeek.SATURDAY, LocalDate.of(2026, 8, 8).getDayOfWeek(), "2026-08-08 must be a Saturday");
        Page page = login();
        try {
            simulate(page, CARD_STUDENT, "2026-08-28T10:00",
                    valuationResponse(List.of(standardOffer("L1", EAN_HYGIENE, "1.0", "10.00")), "10.00"));
            assertTrue(page.locator("table.data-table").textContent().contains("STUDENTS_HYGIENE_28"),
                    "the 28th lever lights the Students advantage (L4)");
            simulate(page, CARD_PLAIN, "2026-08-08T10:00",
                    valuationResponse(List.of(standardOffer("L1", EAN_CHIPS, "1.0", "1.80")), "1.80"));
            assertTrue(page.locator("table.data-table").textContent().contains("FL_WEEKEND"),
                    "the Saturday lever lights the F&L advantage (L4)");
            simulate(page, CARD_PLAIN, "2026-05-17T10:00", valuationResponse(socleOffers(), "6.00"));
            String versioned = page.locator("table.data-table").textContent();
            assertTrue(versioned.contains("SOCLE_4_MARQUES"),
                    "a date before 2026-05-18 answers with the four-brand socle version (L4)");
            assertFalse(versioned.contains("SOCLE_5_MARQUES"),
                    "the five-brand socle version is not yet in force before 2026-05-18 (L4)");
        } finally {
            page.close();
        }
    }

    // --------------------------------------------------
    // Helpers — Playwright
    // --------------------------------------------------

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
     * Loads the simulator, enters the card, forced date and pasted couple, and submits.
     *
     * @param page         The authenticated page.
     * @param card         The card number to enter.
     * @param date         The forced evaluation date-time (ISO local, minute precision).
     * @param responseJson The pasted {@code /valuation} response JSON.
     */
    private void simulate(Page page, String card, String date, String responseJson) {
        page.navigate(url("ui/simulator"));
        page.fill("input[name='card']", card);
        page.fill("input[name='date']", date);
        page.fill("textarea[name='response']", responseJson);
        submit(page);
    }

    /**
     * Clicks the Simuler button and waits for the result page to load.
     *
     * @param page The simulator page.
     */
    private void submit(Page page) {
        page.locator("form[action='/ui/simulator'] button[type='submit']").click();
        page.waitForLoadState();
    }

    /**
     * Builds an absolute URL under the booted application's test root.
     *
     * @param path The path without a leading slash.
     * @return The absolute URL string.
     */
    private String url(String path) {
        return baseUrl.toString() + path;
    }

    // --------------------------------------------------
    // Helpers — /valuation response JSON building
    // --------------------------------------------------

    /**
     * The reference socle cart: milk + water + yoghurt = three socle units for a 6.00 € net
     * assiette (lines L1, L2, L3).
     *
     * @return The three Standard offer fragments.
     */
    private static List<String> socleOffers() {
        return List.of(
                standardOffer("L1", EAN_MILK, "1.0", "3.00"),
                standardOffer("L2", EAN_WATER, "1.0", "1.20"),
                standardOffer("L3", EAN_YAOURT, "1.0", "1.80"));
    }

    /**
     * Builds a {@code /valuation} response object (what the simulator textarea takes) from
     * offers and a total price, with no advantages so the §22.1 reconciliation is
     * total = Σ offers at the centime — unless the caller deliberately mismatches the total.
     *
     * @param offers   The offer JSON fragments.
     * @param totalTtc The total price TTC.
     * @return The valuation-response JSON object.
     */
    private static String valuationResponse(List<String> offers, String totalTtc) {
        return "{\"offers\":[" + String.join(",", offers) + "],\"advantages\":[],\"totalPrice\":"
                + amount(totalTtc) + ",\"vatBreakdown\":[]}";
    }

    /**
     * Builds a single-item Standard offer whose amount equals its item, so the §22.1 offer
     * invariant holds.
     *
     * @param lineId The basket line id.
     * @param ean    The line EAN.
     * @param qty    The line quantity, as a JSON number literal.
     * @param ttc    The line net TTC.
     * @return The offer JSON object.
     */
    private static String standardOffer(String lineId, String ean, String qty, String ttc) {
        return "{\"type\":\"Standard: EAN=" + ean + ", Qty=" + qty + "\",\"amount\":" + amount(ttc)
                + ",\"items\":[" + offerItem(lineId, ean, qty, ttc) + "]}";
    }

    /**
     * Builds one offer item (tranche) JSON fragment.
     *
     * @param lineId The original basket line id.
     * @param ean    The tranche EAN.
     * @param qty    The tranche quantity, as a JSON number literal.
     * @param ttc    The tranche amount TTC.
     * @return The item JSON object.
     */
    private static String offerItem(String lineId, String ean, String qty, String ttc) {
        return "{\"lineId\":\"" + lineId + "\",\"produceEan\":\"" + ean + "\",\"quantity\":" + qty
                + ",\"amount\":" + amount(ttc) + "}";
    }

    /**
     * Builds an {@code AmountEvaluation} JSON block using the value for both HT and TTC at the
     * 20 % rate (the earn assiette reads the TTC net only).
     *
     * @param ttc The amount value, at scale 2.
     * @return The amount JSON object.
     */
    private static String amount(String ttc) {
        return "{\"amountExcludingTax\":" + ttc + ",\"amountIncludingTax\":" + ttc + ",\"vatRate\":0.2000}";
    }

    // --------------------------------------------------
    // Helpers — database (fresh transactions, no absolute ids)
    // --------------------------------------------------

    /**
     * Runs a counting query in a fresh transaction.
     *
     * @param supplier The count supplier.
     * @return The counted value.
     */
    private static long count(Supplier<Long> supplier) {
        return QuarkusTransaction.requiringNew().call(supplier::get);
    }
}
