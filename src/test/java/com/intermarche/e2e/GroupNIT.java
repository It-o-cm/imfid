package com.intermarche.e2e;

import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.BatchRunLog;
import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.EarnTraceLine;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import com.intermarche.fidelity.earn.WarningCode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group N — the cross-cutting seams (e2escenarios-imfid.md "## N. Coutures
 * transverses"): the universal ledger invariant (N1), pseudonymity (N2), per-card
 * concurrency (N3), the closed nomenclatures (N4), the live protected-division guard (N5),
 * the inter-scenario hygiene of the campaign (N6) and the volumetry budgets (N7). Every
 * scenario boots the real application under {@link QuarkusTest} against the
 * DataInitializer-rebuilt 2026 world (wipe + reload at each boot): the seeded facts the
 * catalog states are only read, and every mutation targets isolated {@code 999…} test cards
 * or per-run suffixed ticket references, removed in a {@code finally} so the class stays
 * order-independent. None of N1–N7 is [W] or [P], so all seven are implemented at the
 * RestAssured tier.
 * <p>
 * The POS surfaces ({@code /api/earn}, {@code /api/events/*}, {@code /api/burn/*}, {@code
 * /api/accounts/*}) are driven Basic {@code pos}; the admin surfaces (the batch API, the CSV
 * imports and the card sheet) Basic {@code admin} — the {@code /ui/*} card sheet over a form
 * session ({@code j_security_check} + {@code quarkus-credential} cookie). Fiscal idempotence
 * is a trap (I8): every {@code ticketRef} carries a per-scenario unique suffix so a replay is
 * never silently absorbed (N6 proves the corollary). Temporal behaviour goes through {@link
 * DateTimeProvider}, never the wall clock (§24.6): N1's expiry leg freezes the 1st of March
 * so its expire year is the previous civil year and only its isolated card qualifies.
 * BigDecimal amounts are compared by {@code compareTo} (§30.5); DB assertions go through
 * Panache under {@link QuarkusTransaction}, always by natural key and delta, never absolute
 * ids or counters.
 */
@QuarkusTest
class GroupNIT {

    /**
     * Bootstrap machine login (SecurityBootstrap default), role {@code pos}.
     */
    private static final String POS_USER = "pos";

    /**
     * Bootstrap machine password (SecurityBootstrap default in dev/test).
     */
    private static final String POS_PASSWORD = "pos-password";

    /**
     * Bootstrap administrator login (SecurityBootstrap default), role {@code fid-admin}.
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
     * Reserved card: ACTIVE, balance 30.00 €, one ACTIVE lease of 8.00 € (…088) — the
     * summary witness of N2/N4.
     */
    private static final String CARD_RESERVED = "2990000000088";

    /**
     * Neutral successor card: ACTIVE, no visits, base 5 % socle rate (…071) — the pure-read
     * and concurrent-credit target.
     */
    private static final String CARD_PLAIN = "2990000000071";

    /**
     * Rich-history card carrying six of the nine ledger movement types (…019) — the
     * nomenclature witness of N4.
     */
    private static final String CARD_RICH = "2990000000019";

    /**
     * Babies card: ACTIVE, 18.50 € balance, no seeded lease (…026) — a scratch reservation
     * card for N3 and the DAILY_RULE leg of N4.
     */
    private static final String CARD_BABIES = "2990000000026";

    /**
     * PENDING_ACTIVATION card: accrues but cannot burn (…040) — the ACCOUNT_STATUS witness.
     */
    private static final String CARD_PENDING = "2990000000040";

    /**
     * A card number never seeded — the unknown-card probe.
     */
    private static final String CARD_UNKNOWN = "9999999999999";

    /**
     * Isolated test card of N1's H (adjustment) leg, out of the seeded range.
     */
    private static final String N1_HCARD = "9990000001401";

    /**
     * Isolated test card of N1's E (ingestion) leg, out of the seeded range.
     */
    private static final String N1_ECARD = "9990000001402";

    /**
     * Isolated test card of N1's G (expiry batch) leg, out of the seeded range.
     */
    private static final String N1_GCARD = "9990000001403";

    /**
     * Isolated test card of N7's bounded-ingestion leg, out of the seeded range.
     */
    private static final String N7_CARD = "9990000001701";

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
     * An EAN absent from the imfid product reference, for the UNKNOWN_EAN warning (§25.4).
     */
    private static final String EAN_UNKNOWN = "3400000099999";

    /**
     * The §12 socle rule code the neutral card lights at its base rate.
     */
    private static final String RULE_SOCLE = "SOCLE_5_MARQUES";

    /**
     * The products import endpoint (§18).
     */
    private static final String PRODUCTS_IMPORT = "/products/import";

    /**
     * The product CSV header line (always skipped by the importer).
     */
    private static final String PRODUCT_HEADER =
            "ean|name|description|brand|referenceWeight|referenceVolume|productType|unitName|active";

    /**
     * The brand marker stamped on the N7 bulk products, so they are removed in one delete.
     */
    private static final String N7_BULK_BRAND = "ZZZ_N7_BULK";

    /**
     * The closed nine-name movement nomenclature (§14, I10) — no earn mechanic ever extends it.
     */
    private static final Set<String> MOVEMENT_TYPES = Set.of("EARN", "REFUND_CREDIT", "ADJUSTMENT",
            "BURN", "RETURN_DEBIT", "EXPIRY", "PURGE", "ACTIVATION_VOID", "TRANSFER");

    /**
     * The closed five-name warning nomenclature (§27.1, Q-B) — extending it is a document revision.
     */
    private static final Set<String> WARNINGS = Set.of("UNKNOWN_EAN", "EARN_MISMATCH",
            "EXPIRED_LEASE_CONFIRMED", "RESILIATED_ACCOUNT", "CARD_MISMATCH");

    /**
     * The closed three reservation refusal reasons (§27.3, Q-A).
     */
    private static final Set<String> REFUSAL_REASONS = Set.of("INSUFFICIENT_BALANCE", "DAILY_RULE", "ACCOUNT_STATUS");

    /**
     * The forbidden nominative JSON keys — none may ever surface on an API response (§33.3, N2).
     */
    private static final Set<String> FORBIDDEN_KEYS = Set.of("name", "firstName", "lastName", "fullName",
            "holderName", "email", "phone", "address", "birthDate", "customerName", "surname", "givenName");

    /**
     * The frozen instant of N1's expiry leg — 1 March 2026 at 04:15, distinct from the seeded
     * EXPIRY run (02:00) and group G's runs so the run N1 writes is removable by its timestamp.
     */
    private static final LocalDateTime N1_EXPIRY_AT = LocalDateTime.of(2026, 3, 1, 4, 15);

    // --------------------------------------------------
    // N1 — balance = Σ movements after every writing surface (E, G, H, §14)
    // --------------------------------------------------

    /**
     * N1 — the universal closure invariant: after each writing surface the denormalized
     * {@code balance} equals both {@code computeBalance} and the raw signed sum of the card's
     * movements (§14). It is proven on three isolated cards, one per surface — an ADJUSTMENT
     * gesture (H), a {@code ticket-closed} ingestion (E) and an EXPIRY batch execution frozen
     * so its expire year is the previous civil year (G) — each asserted right after its write.
     */
    @Test
    void n1_balanceEqualsSumOfMovementsAfterEveryWrite() {
        String eventRef = "0101-2026-N1-E-" + suffix();
        seedPlainCard(N1_HCARD);
        seedPlainCard(N1_ECARD);
        seedExpiryCard(N1_GCARD, "20.00");
        try {
            String adjust = noticeOf(postForm("/ui/cards/" + N1_HCARD + "/adjust",
                    "amount", "7.35", "reason", "N1 goodwill").header("Location"));
            assertEquals("Adjustment posted", adjust, "the H write posts the adjustment (§32.1)");
            assertLedgerInvariant(N1_HCARD, "7.35", "after the ADJUSTMENT gesture the balance is Σ movements (§14)");
            Response ingest = postClosed(closedBody(eventRef, N1_ECARD, N1_ECARD, "2026-08-10", socleOffers(), "6.00"));
            assertEquals(202, ingest.statusCode(), "the E write is accepted (202)");
            assertLedgerInvariant(N1_ECARD, "0.30", "after the ticket-closed credit the balance is Σ movements (§14)");
            DateTimeProvider.setFixedDateTime(N1_EXPIRY_AT);
            Response exec = postBatch("EXPIRY", false);
            assertEquals(200, exec.statusCode(), "the G write (expiry execution) answers 200");
            assertNotNull(movementOf(N1_GCARD, MovementType.EXPIRY), "the frozen expiry consumes the isolated card's 2025 residual");
            assertLedgerInvariant(N1_GCARD, "0.00", "after the EXPIRY batch the balance is Σ movements (§14)");
        } finally {
            DateTimeProvider.clear();
            deleteBatchRunAt("EXPIRY", N1_EXPIRY_AT);
            deleteTicket(eventRef);
            purgeTestCard(N1_HCARD);
            purgeTestCard(N1_ECARD);
            purgeTestCard(N1_GCARD);
        }
    }

    // --------------------------------------------------
    // N2 — pseudonymity: the card is the only key (§33.3)
    // --------------------------------------------------

    /**
     * N2 — pseudonymity: no nominative datum surfaces anywhere. The account summary and the
     * movement history of {@code …088} carry the card number as their only identifier and none
     * of the forbidden nominative keys, and the admin card sheet renders no nominative field
     * label on its French screen — the card is the sole key (§33.3).
     */
    @Test
    void n2_noNominativeDataOnApiOrScreen() {
        Response summary = getAccount(CARD_RESERVED);
        assertEquals(200, summary.statusCode(), "the account summary answers 200");
        Map<String, ?> summaryMap = summary.jsonPath().getMap("");
        assertEquals(CARD_RESERVED, summaryMap.get("cardNumber"), "the card number is the summary's identifier");
        assertNoNominativeKeys(summaryMap.keySet(), "the account summary");
        Response history = getMovements(CARD_RESERVED);
        assertEquals(200, history.statusCode(), "the movement history answers 200");
        List<Map<String, ?>> items = history.jsonPath().getList("items");
        for (Map<String, ?> item : items) {
            assertNoNominativeKeys(item.keySet(), "a movement row");
        }
        String sheet = cardSheetHtml(CARD_RESERVED);
        assertTrue(sheet.contains(CARD_RESERVED), "the card sheet identifies the card by its number (§33.3)");
        for (String label : List.of("Nom du client", "Prénom", "Adresse e-mail", "Adresse email",
                "Numéro de téléphone", "Date de naissance", "Nom de famille")) {
            assertFalse(sheet.contains(label), "the card sheet must carry no nominative label but held '" + label + "'");
        }
    }

    // --------------------------------------------------
    // N3 — per-card concurrency (I11, §30.1)
    // --------------------------------------------------

    /**
     * N3 — per-card concurrency: two simultaneous reservations on the same card with distinct
     * tickets resolve to exactly one 201 and one 409 under the {@code SELECT FOR UPDATE} card
     * lock (I11); and two simultaneous {@code ticket-closed} on the same card with distinct
     * references both credit with no lost update and no double — the per-card lock serializes
     * the writes (§30.1), and under perfect simultaneity a transient optimistic conflict is
     * retried (as the register would on a 5xx) while the natural key (I8) keeps the retry
     * idempotent, so the balance grows by exactly the full sum.
     */
    @Test
    void n3_perCardConcurrencyLocksReservationsAndSerializesCredits() {
        cleanReservations(CARD_BABIES);
        String firstTicket = "0101-2026-N3-A-" + suffix();
        String secondTicket = "0101-2026-N3-B-" + suffix();
        String firstEvent = "0101-2026-N3-E1-" + suffix();
        String secondEvent = "0101-2026-N3-E2-" + suffix();
        String firstBody = closedBody(firstEvent, CARD_PLAIN, CARD_PLAIN, "2026-08-10", socleOffers(), "6.00");
        String secondBody = closedBody(secondEvent, CARD_PLAIN, CARD_PLAIN, "2026-08-11", socleOffers(), "6.00");
        BigDecimal before = accountBalance(CARD_PLAIN);
        try {
            Response[] leases = concurrently(() -> reserve(CARD_BABIES, "5.00", firstTicket),
                    () -> reserve(CARD_BABIES, "5.00", secondTicket));
            List<Integer> codes = Arrays.asList(leases[0].statusCode(), leases[1].statusCode());
            List<Integer> sorted = codes.stream().sorted().collect(Collectors.toList());
            assertEquals(List.of(201, 409), sorted,
                    "two concurrent reservations on one card yield exactly one 201 and one 409 (I11) but were " + codes);
            Response[] credits = concurrently(() -> postClosed(firstBody), () -> postClosed(secondBody));
            settleCredit(firstBody, credits[0]);
            settleCredit(secondBody, credits[1]);
            assertEquals(1, countMovements(firstEvent, MovementType.EARN), "the first credit posts exactly one EARN (no double, I8)");
            assertEquals(1, countMovements(secondEvent, MovementType.EARN), "the second credit posts exactly one EARN (no double, I8)");
            assertEquals(0, accountBalance(CARD_PLAIN).subtract(before).compareTo(new BigDecimal("0.60")),
                    "both credits land — the balance grows by 0.60 €, no lost update under the per-card lock (§30.1)");
        } finally {
            cleanReservations(CARD_BABIES);
            deleteTicket(firstEvent);
            deleteTicket(secondEvent);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // N4 — the closed nomenclatures (§14, §27.1, §27.3, N4)
    // --------------------------------------------------

    /**
     * N4 — the closed nomenclatures: the movement enum is exactly its nine names and the
     * warning enum exactly its five (extending either is a document revision, not a silent
     * change); a driven UNKNOWN_EAN projection warns with a code inside the closed five; the
     * three reservation refusals carry exactly the closed three reasons; and every cap scope of
     * an account summary stays inside {@code GLOBAL} | {@code RULE:<code>} | {@code
     * COMMUNITY:<code>} — any unexpected code is a test failure.
     */
    @Test
    void n4_closedNomenclaturesAreNeverExtendedSilently() {
        Set<String> movements = Arrays.stream(MovementType.values()).map(Enum::name).collect(Collectors.toSet());
        assertEquals(MOVEMENT_TYPES, movements, "the movement nomenclature is exactly its nine names (§14, I10)");
        Set<String> warnings = Arrays.stream(WarningCode.values()).map(Enum::name).collect(Collectors.toSet());
        assertEquals(WARNINGS, warnings, "the warning nomenclature is exactly its five names (§27.1)");
        Response earn = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00",
                List.of(standardOffer("L1", EAN_UNKNOWN, "1.0", "5.00")), "5.00"));
        String warningCode = earn.jsonPath().getString("warnings[0].code");
        assertTrue(WARNINGS.contains(warningCode), "the projected warning stays in the closed five but was " + warningCode);
        cleanReservations(CARD_BABIES);
        String burnRef = "0101-2026-N4-BURN-" + suffix();
        insertBurnToday(CARD_BABIES, burnRef);
        try {
            Response insufficient = reserve(CARD_PLAIN, "9999.00", "0101-2026-N4-INS-" + suffix());
            assertEquals(422, insufficient.statusCode(), "an over-balance reservation is a 422");
            assertEquals("INSUFFICIENT_BALANCE", insufficient.jsonPath().getString("reason"), "the over-balance reason is closed");
            Response daily = reserve(CARD_BABIES, "1.00", "0101-2026-N4-DAILY-" + suffix());
            assertEquals("DAILY_RULE", daily.jsonPath().getString("reason"), "the second burn of the day reason is closed");
            Response status = reserve(CARD_PENDING, "1.00", "0101-2026-N4-STAT-" + suffix());
            assertEquals("ACCOUNT_STATUS", status.jsonPath().getString("reason"), "the pending-account reason is closed");
            for (String reason : List.of("INSUFFICIENT_BALANCE", "DAILY_RULE", "ACCOUNT_STATUS")) {
                assertTrue(REFUSAL_REASONS.contains(reason), "every refusal reason stays inside the closed three");
            }
        } finally {
            deleteMovementsByRef(burnRef);
            cleanReservations(CARD_BABIES);
        }
        List<Map<String, ?>> caps = getAccount(CARD_RESERVED).jsonPath().getList("monthlyCaps");
        assertFalse(caps.isEmpty(), "the summary carries at least the GLOBAL cap");
        for (Map<String, ?> cap : caps) {
            String scope = String.valueOf(cap.get("scope"));
            assertTrue(scope.matches("GLOBAL|RULE:.+|COMMUNITY:.+"),
                    "every cap scope stays in the closed nomenclature but was " + scope);
        }
    }

    // --------------------------------------------------
    // N5 — the live protected-division guard (§31.2)
    // --------------------------------------------------

    /**
     * N5 — the live protected-division guard: a valued couple whose discount re-allocates over
     * an offer with a zero total tranche weight drives {@code ValuationReader.prorata} into its
     * zero-denominator branch. The engine degrades silently — a 200, never a 500 (§31.2) — and
     * the socle assiette still earns beside the degenerate discount, proving the whole engine
     * completed. The guard's null/zero jambs are, in the unit-test campaign, a deliberate
     * JaCoCo coverage exclusion (an audit decision) rather than a test hole; here the branch is
     * exercised alive end to end.
     */
    @Test
    void n5_prorataZeroDenominatorDegradesSilently() {
        String zeroPack = "ZeroPack:N5";
        String zeroOffer = offerObject(zeroPack, "0.00",
                offerItem("L4", EAN_MILK, "1.0", "0.00") + "," + offerItem("L5", EAN_WATER, "1.0", "0.00"));
        List<String> offers = new ArrayList<>(socleOffers());
        offers.add(zeroOffer);
        String advantage = "{\"type\":\"DISCOUNT\",\"offer\":\"" + zeroPack + "\",\"discountAmount\":"
                + amount("1.00") + "}";
        String body = "{\"valuationRequest\":{\"customerCode\":\"" + CARD_PLAIN + "\",\"storeCode\":\"0101\","
                + "\"createdAt\":\"2026-08-17T10:00:00\"},\"valuationResponse\":{\"offers\":["
                + String.join(",", offers) + "],\"advantages\":[" + advantage + "],\"totalPrice\":"
                + amount("5.00") + ",\"vatBreakdown\":[]}}";
        Response r = postEarn(body);
        assertEquals(200, r.statusCode(), "the zero-denominator discount couple degrades silently — a 200, never a 500 (§31.2)");
        Map<String, ?> socle = r.jsonPath().getMap("entries.find { it.ruleCode == '" + RULE_SOCLE + "' }");
        assertNotNull(socle, "the socle assiette still earns beside the degenerate discount (the engine completed)");
        assertTrue(new BigDecimal(String.valueOf(socle.get("amount"))).signum() > 0,
                "the socle earn is positive — the protected prorata never broke the projection");
    }

    // --------------------------------------------------
    // N6 — inter-scenario hygiene: fixed time and suffixed refs (§24.6, I8)
    // --------------------------------------------------

    /**
     * N6 — inter-scenario hygiene: {@link DateTimeProvider#setFixedDateTime} pins the program
     * clock to a chosen instant and {@code clear()} releases it back to the real clock (the
     * temporal lever the whole catalog rests on, §24.6); and a same-reference {@code
     * ticket-closed} replay is silently absorbed by the natural key (I8) while a per-run
     * suffixed reference is not — the operative reason every ticketRef is suffixed per run.
     */
    @Test
    void n6_fixedTimeReleasesAndSuffixedRefsAvoidAbsorption() {
        String sharedRef = "0101-2026-N6-SHARED-" + suffix();
        String suffixedRef = "0101-2026-N6-DISTINCT-" + suffix();
        LocalDateTime fixed = LocalDateTime.of(2026, 1, 2, 8, 30);
        try {
            DateTimeProvider.setFixedDateTime(fixed);
            assertEquals(fixed, DateTimeProvider.now(), "setFixedDateTime pins the program clock (§24.6)");
            DateTimeProvider.clear();
            assertFalse(fixed.equals(DateTimeProvider.now()), "clear() releases the clock back to real time (§24.6)");
            Response first = postClosed(closedBody(sharedRef, CARD_PLAIN, CARD_PLAIN, "2026-08-10", socleOffers(), "6.00"));
            assertEquals(202, first.statusCode(), "the first ingestion is accepted (202)");
            assertEquals(1, countMovements(sharedRef, MovementType.EARN), "the first ingestion posts one EARN");
            Response replay = postClosed(closedBody(sharedRef, CARD_PLAIN, CARD_PLAIN, "2026-08-10", socleOffers(), "6.00"));
            assertEquals(202, replay.statusCode(), "the same-ref replay is accepted again (202)");
            assertEquals(1, countMovements(sharedRef, MovementType.EARN),
                    "the same-ref replay is absorbed by the natural key (I8) — why refs are suffixed per run");
            Response distinct = postClosed(closedBody(suffixedRef, CARD_PLAIN, CARD_PLAIN, "2026-08-10", socleOffers(), "6.00"));
            assertEquals(202, distinct.statusCode(), "the suffixed-ref ingestion is accepted (202)");
            assertEquals(1, countMovements(suffixedRef, MovementType.EARN),
                    "a distinct suffixed reference posts its own EARN — no cross-run absorption");
        } finally {
            DateTimeProvider.clear();
            deleteTicket(sharedRef);
            deleteTicket(suffixedRef);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // N7 — volumetry budgets (§18, §16, C1 under load)
    // --------------------------------------------------

    /**
     * N7 — volumetry: a 50 000-line product import (chunks of 1000) completes inside an explicit
     * time budget with exact counters (created 50 000, updated 0, no error); a {@code
     * ticket-closed} of 50 distinct socle lines is ingested inside its budget into a single set
     * of movements (one aggregated EARN, no per-line explosion, idempotent on replay); and 200
     * consecutive {@code /earn} on the same basket are a pure read without drift — an identical
     * total each time and not a single write (accounts, movements, traces unchanged), C1 under
     * load.
     */
    @Test
    void n7_volumetryBudgetsHoldForImportIngestionAndPureRead() {
        String ingestRef = "0101-2026-N7-BULK-" + suffix();
        seedPlainCard(N7_CARD);
        try {
            String bulk = bulkProductCsv(50_000);
            Response[] holder = new Response[1];
            assertTimeout(Duration.ofSeconds(240), () -> holder[0] = importCsv(PRODUCTS_IMPORT, bulk),
                    "the 50 000-line import must complete inside its time budget (§18)");
            Response report = holder[0];
            assertEquals(200, report.statusCode(), "the mass import returns a 200 report (§18)");
            assertEquals(50_000, report.jsonPath().getInt("createdCount"), "every one of the 50 000 rows is created (N7)");
            assertEquals(0, report.jsonPath().getInt("updatedCount"), "no row is an update on a fresh import (N7)");
            List<String> importErrors = report.jsonPath().getList("errors");
            assertTrue(importErrors == null || importErrors.isEmpty(), "the mass import raises no row error (N7)");
            List<String> fifty = new ArrayList<>();
            String[] eans = {EAN_MILK, EAN_WATER, EAN_YAOURT};
            for (int i = 0; i < 50; i++) {
                fifty.add(standardOffer("L" + (i + 1), eans[i % 3], "1.0", "1.00"));
            }
            Response ingest = assertTimeout(Duration.ofSeconds(30),
                    () -> postClosed(closedBody(ingestRef, N7_CARD, N7_CARD, "2026-08-10", fifty, "50.00")),
                    "the 50-line ingestion must complete inside its time budget (§16)");
            assertEquals(202, ingest.statusCode(), "the 50-line ticket-closed is accepted (202)");
            assertEquals(1, countMovements(ingestRef, MovementType.EARN),
                    "the 50 distinct socle lines fold into a single aggregated EARN — one set of movements (N7)");
            assertEquals(0, findMovement(ingestRef, MovementType.EARN, RULE_SOCLE).amount.compareTo(new BigDecimal("2.50")),
                    "the socle earn is 5 % of the 50.00 € net = 2.50 €");
            Response replay = postClosed(closedBody(ingestRef, N7_CARD, N7_CARD, "2026-08-10", fifty, "50.00"));
            assertEquals(202, replay.statusCode(), "the replay is accepted (202)");
            assertEquals(1, countMovements(ingestRef, MovementType.EARN), "the replay folds into no extra movement (I8)");
            long accountsBefore = count(() -> FidelityAccount.count());
            long movementsBefore = count(() -> FidelityMovement.count());
            long tracesBefore = count(() -> EarnTrace.count());
            String firstTotal = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00", socleOffers(), "6.00"))
                    .jsonPath().getString("total");
            assertTimeout(Duration.ofSeconds(60), () -> {
                for (int i = 0; i < 200; i++) {
                    Response loop = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00", socleOffers(), "6.00"));
                    assertEquals(200, loop.statusCode(), "each pure-read /earn under load answers 200 (C1)");
                    assertEquals(firstTotal, loop.jsonPath().getString("total"), "the /earn total never drifts under load (C1)");
                }
            }, "the 200 consecutive projections must complete inside their time budget (C1)");
            assertEquals(accountsBefore, count(() -> FidelityAccount.count()), "200 projections write no account (§30.2)");
            assertEquals(movementsBefore, count(() -> FidelityMovement.count()), "200 projections write no movement (§30.2)");
            assertEquals(tracesBefore, count(() -> EarnTrace.count()), "200 projections write no trace or visit (§30.2)");
        } finally {
            deleteBulkProducts();
            deleteTicket(ingestRef);
            purgeTestCard(N7_CARD);
        }
    }

    // --------------------------------------------------
    // Helpers — HTTP (POS)
    // --------------------------------------------------

    /**
     * Posts a {@code /valuation} couple to {@code /api/earn} as the {@code pos} operator.
     *
     * @param body The JSON request body.
     * @return The HTTP response.
     */
    private static Response postEarn(String body) {
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .contentType("application/json").body(body).post("/api/earn");
    }

    /**
     * Posts a {@code ticket-closed} event as the {@code pos} operator.
     *
     * @param body The JSON request body.
     * @return The HTTP response.
     */
    private static Response postClosed(String body) {
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .contentType("application/json").body(body).post("/api/events/ticket-closed");
    }

    /**
     * Posts a burn reservation (or renewal) to {@code /api/burn/reservations} as {@code pos}.
     *
     * @param card      The card number.
     * @param amount    The amount to reserve, as a JSON number literal.
     * @param ticketRef The ticket reference.
     * @return The HTTP response.
     */
    private static Response reserve(String card, String amount, String ticketRef) {
        String body = "{\"card\":\"" + card + "\",\"amount\":" + amount + ",\"ticketRef\":\"" + ticketRef + "\"}";
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .contentType("application/json").body(body).post("/api/burn/reservations");
    }

    /**
     * Reads an account summary from {@code /api/accounts/{card}} as {@code pos}.
     *
     * @param card The card number.
     * @return The HTTP response.
     */
    private static Response getAccount(String card) {
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .get("/api/accounts/" + card);
    }

    /**
     * Reads the first page of an account's movement history from {@code /api/accounts/{card}/movements}.
     *
     * @param card The card number.
     * @return The HTTP response.
     */
    private static Response getMovements(String card) {
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .queryParam("page", 0).queryParam("size", 50)
                .get("/api/accounts/" + card + "/movements");
    }

    // --------------------------------------------------
    // Helpers — HTTP (admin: batch API, imports, form session)
    // --------------------------------------------------

    /**
     * Triggers a batch with an explicit {@code dryRun} flag as the admin.
     *
     * @param type   The batch type path segment.
     * @param dryRun Whether to simulate.
     * @return The HTTP response.
     */
    private static Response postBatch(String type, boolean dryRun) {
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(ADMIN_USER, ADMIN_PASSWORD)
                .queryParam("dryRun", dryRun).post("/api/batches/" + type);
    }

    /**
     * POSTs a raw pipe-delimited CSV body to an import endpoint over Basic {@code admin} (§18).
     *
     * @param path The import endpoint path.
     * @param body The CSV body (header line first, then data rows).
     * @return The JSON import report response.
     */
    private static Response importCsv(String path, String body) {
        return RestAssured.given().auth().preemptive().basic(ADMIN_USER, ADMIN_PASSWORD)
                .contentType(ContentType.TEXT).body(body).post(path);
    }

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
     * Reads a card sheet as HTML over a fresh admin session.
     *
     * @param card The card number.
     * @return The rendered sheet body.
     */
    private static String cardSheetHtml(String card) {
        return RestAssured.given().cookie(SESSION_COOKIE, adminSession())
                .get("/ui/cards/" + card).then().statusCode(200).extract().asString();
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
                return java.net.URLDecoder.decode(pair.substring("notice=".length()), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    // --------------------------------------------------
    // Helpers — concurrency
    // --------------------------------------------------

    /**
     * Runs two HTTP suppliers concurrently, released together by a two-party barrier so they
     * genuinely race, and returns their responses in submission order.
     *
     * @param first  The first HTTP call.
     * @param second The second HTTP call.
     * @return The two responses, first then second.
     */
    private static Response[] concurrently(Supplier<Response> first, Supplier<Response> second) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Response> a = pool.submit(() -> {
                barrier.await();
                return first.get();
            });
            Future<Response> b = pool.submit(() -> {
                barrier.await();
                return second.get();
            });
            return new Response[]{a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)};
        } catch (Exception e) {
            throw new IllegalStateException("concurrent execution failed", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Settles a concurrent credit: a {@code ticket-closed} accepted on its first try is done,
     * and one that met a transient per-card write conflict under perfect simultaneity (a 5xx on
     * the FOR-UPDATE re-read) is replayed — idempotently, by the natural key (I8) — until it is
     * accepted, so nothing is lost and nothing is doubled (§30.1).
     *
     * @param body  The verbatim {@code ticket-closed} body to replay on conflict.
     * @param first The response of the concurrent first attempt.
     */
    private static void settleCredit(String body, Response first) {
        if (first.statusCode() == 202) {
            return;
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            if (postClosed(body).statusCode() == 202) {
                return;
            }
        }
        throw new IllegalStateException("a concurrent credit never settled after retries");
    }

    // --------------------------------------------------
    // Helpers — a per-test unique suffix
    // --------------------------------------------------

    /**
     * Produces a per-call unique suffix for ticket references, so the idempotent upserts are
     * never silently absorbed by a replay (I8).
     *
     * @return A unique suffix string.
     */
    private static String suffix() {
        return Long.toString(System.nanoTime());
    }

    // --------------------------------------------------
    // Helpers — JSON building
    // --------------------------------------------------

    /**
     * Builds a full {@code /earn} request body with a card and an evaluation date.
     *
     * @param card      The customer card.
     * @param createdAt The ISO evaluation instant.
     * @param offers    The offer JSON fragments.
     * @param totalTtc  The reconciled total price TTC.
     * @return The request JSON.
     */
    private static String earnBody(String card, String createdAt, List<String> offers, String totalTtc) {
        String vr = "\"valuationRequest\":{\"customerCode\":\"" + card + "\",\"storeCode\":\"0101\",\"createdAt\":\""
                + createdAt + "\"}";
        return "{" + vr + ",\"valuationResponse\":" + valuationResponse(offers, totalTtc) + "}";
    }

    /**
     * Builds a {@code ticket-closed} body with an event card, a request card and a fiscal date.
     *
     * @param ticketRef    The idempotency ticket reference.
     * @param card         The event card (a coherence control).
     * @param customerCode The authoritative request card.
     * @param fiscalDate   The ISO fiscal date.
     * @param offers       The offer JSON fragments.
     * @param totalTtc     The reconciled total price TTC.
     * @return The request JSON.
     */
    private static String closedBody(String ticketRef, String card, String customerCode, String fiscalDate,
                                     List<String> offers, String totalTtc) {
        String vr = "\"valuationRequest\":{\"customerCode\":\"" + customerCode + "\",\"storeCode\":\"0101\","
                + "\"createdAt\":\"" + fiscalDate + "T10:00:00\"}";
        return "{\"ticketRef\":\"" + ticketRef + "\",\"card\":\"" + card + "\",\"fiscalDate\":\"" + fiscalDate + "\","
                + vr + ",\"valuationResponse\":" + valuationResponse(offers, totalTtc) + "}";
    }

    /**
     * Builds a {@code valuationResponse} object from offers and a total price, with no
     * advantages (so the reconciliation is total = Σ offers, at the centime).
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
     * Builds a single-item Standard offer (a non-consuming, catalogue-tariff offer whose line
     * may earn); the offer amount equals its item amount, so the §22.1 offer invariant holds.
     *
     * @param lineId The basket line id.
     * @param ean    The line EAN.
     * @param qty    The line quantity, as a JSON number literal.
     * @param ttc    The line net TTC.
     * @return The offer JSON object.
     */
    private static String standardOffer(String lineId, String ean, String qty, String ttc) {
        return offerObject("Standard: EAN=" + ean + ", Qty=" + qty, ttc, offerItem(lineId, ean, qty, ttc));
    }

    /**
     * Builds an offer object from a type, an amount and the raw items JSON.
     *
     * @param type      The descriptive offer type (its family discriminant).
     * @param amountTtc The offer amount TTC.
     * @param itemsJson The comma-joined item JSON fragments.
     * @return The offer JSON object.
     */
    private static String offerObject(String type, String amountTtc, String itemsJson) {
        return "{\"type\":\"" + type + "\",\"amount\":" + amount(amountTtc) + ",\"items\":[" + itemsJson + "]}";
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
     * Builds an {@code AmountEvaluation} JSON block, using the value for both HT and TTC at the
     * 20 % rate (the earn assiette reads the TTC net only).
     *
     * @param ttc The amount value, at scale 2.
     * @return The amount JSON object.
     */
    private static String amount(String ttc) {
        return "{\"amountExcludingTax\":" + ttc + ",\"amountIncludingTax\":" + ttc + ",\"vatRate\":0.2000}";
    }

    /**
     * The reference socle cart: milk + water + yoghurt = three socle units for a 6.00 € net
     * assiette (lines L1, L2, L3).
     *
     * @return The three Standard offer fragments.
     */
    private static List<String> socleOffers() {
        return List.of(standardOffer("L1", EAN_MILK, "1.0", "3.00"),
                standardOffer("L2", EAN_WATER, "1.0", "1.20"), standardOffer("L3", EAN_YAOURT, "1.0", "1.80"));
    }

    /**
     * Builds a bulk product CSV of the given size, every row a unique EAN under the N7 marker
     * brand so the rows are removable in one delete.
     *
     * @param rows The number of product rows.
     * @return The pipe-delimited CSV body.
     */
    private static String bulkProductCsv(int rows) {
        StringBuilder builder = new StringBuilder(PRODUCT_HEADER);
        for (int i = 0; i < rows; i++) {
            builder.append('\n').append(String.format("77%011d", i)).append("|N7 Product ").append(i)
                    .append("|bulk|").append(N7_BULK_BRAND).append("|1.000|1.000|UNIT|unit|true");
        }
        return builder.toString();
    }

    // --------------------------------------------------
    // Helpers — database seeding (isolated test cards)
    // --------------------------------------------------

    /**
     * Inserts a plain ACTIVE test account with a zero balance and no movement, in a fresh
     * transaction.
     *
     * @param card The card number.
     */
    private static void seedPlainCard(String card) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = new FidelityAccount();
            account.cardNumber = card;
            account.status = AccountStatus.ACTIVE;
            account.balance = BigDecimal.ZERO;
            account.persist();
        });
    }

    /**
     * Inserts an ACTIVE test account carrying a single 2025 EARN credit (expirable at expire
     * year 2025) with no lease, in a fresh transaction — the isolated expiry target of N1's G
     * leg.
     *
     * @param card   The card number.
     * @param amount The 2025 credit amount, as text.
     */
    private static void seedExpiryCard(String card, String amount) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = new FidelityAccount();
            account.cardNumber = card;
            account.status = AccountStatus.ACTIVE;
            account.balance = BigDecimal.ZERO;
            account.persist();
            FidelityMovement movement = new FidelityMovement();
            movement.account = account;
            movement.type = MovementType.EARN;
            movement.amount = new BigDecimal(amount);
            movement.movementDate = LocalDate.of(2025, 6, 1);
            movement.earnYear = 2025;
            movement.ruleCode = null;
            movement.ticketRef = card + "-2025";
            movement.persist();
            account.balance = FidelityMovement.computeBalance(account);
        });
    }

    /**
     * Inserts a confirmed-style BURN movement dated today, in a fresh transaction — arms the
     * once-per-day rule for the DAILY_RULE refusal.
     *
     * @param card      The card number.
     * @param ticketRef The unique ticket reference (for later removal).
     */
    private static void insertBurnToday(String card, String ticketRef) {
        LocalDate today = DateTimeProvider.now().toLocalDate();
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityMovement movement = new FidelityMovement();
            movement.account = FidelityAccount.findByCardNumber(card);
            movement.type = MovementType.BURN;
            movement.amount = new BigDecimal("-1.00");
            movement.movementDate = today;
            movement.earnYear = today.getYear();
            movement.ruleCode = null;
            movement.ticketRef = ticketRef;
            movement.persist();
        });
    }

    /**
     * Removes a test card and everything hanging off it (reservations then movements then the
     * account) in a fresh transaction, restoring the seeded world; a null or unknown card is a
     * no-op.
     *
     * @param card The test card number, or null.
     */
    private static void purgeTestCard(String card) {
        if (card == null) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            if (account != null) {
                FidelityReservation.delete("account", account);
                FidelityMovement.delete("account", account);
                account.delete();
            }
        });
    }

    /**
     * Deletes every reservation of a scratch card (one that carries no seeded lease), in a fresh
     * transaction.
     *
     * @param card The card number.
     */
    private static void cleanReservations(String card) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            FidelityReservation.delete("account", account);
        });
    }

    /**
     * Deletes the N7 bulk products by their marker brand in a fresh transaction.
     */
    private static void deleteBulkProducts() {
        QuarkusTransaction.requiringNew().run(() -> Product.delete("brand", N7_BULK_BRAND));
    }

    // --------------------------------------------------
    // Helpers — database reads and cleanups (no absolute ids)
    // --------------------------------------------------

    /**
     * Asserts the closure invariant on a card: {@code balance == computeBalance == Σ movement
     * amounts}, and that the balance equals an expected value, in a fresh transaction (§14).
     *
     * @param card     The card number.
     * @param expected The expected balance value, as text.
     * @param message  The assertion message.
     */
    private static void assertLedgerInvariant(String card, String expected, String message) {
        BigDecimal[] triple = QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            BigDecimal computed = FidelityMovement.computeBalance(account);
            BigDecimal sum = BigDecimal.ZERO;
            for (FidelityMovement movement : FidelityMovement.<FidelityMovement>list("account", account)) {
                sum = sum.add(movement.amount);
            }
            return new BigDecimal[]{account.balance, computed, sum};
        });
        assertEquals(0, triple[0].compareTo(triple[1]), message + " — balance equals computeBalance");
        assertEquals(0, triple[1].compareTo(triple[2]), message + " — computeBalance is the raw signed sum");
        assertEquals(0, triple[0].compareTo(new BigDecimal(expected)), message + " — the balance is the expected " + expected);
    }

    /**
     * Reads an account's denormalized balance in a fresh transaction.
     *
     * @param card The card number.
     * @return The balance, or null when the card is unknown.
     */
    private static BigDecimal accountBalance(String card) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            return account == null ? null : account.balance;
        });
    }

    /**
     * Finds a movement by its natural key in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @param type      The movement type.
     * @param ruleCode  The rule code, or null.
     * @return The movement, or null when none matches.
     */
    private static FidelityMovement findMovement(String ticketRef, MovementType type, String ruleCode) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityMovement.findByNaturalKey(ticketRef, type, ruleCode));
    }

    /**
     * Finds the single movement of a type on a card, or null, in a fresh transaction.
     *
     * @param card The card number.
     * @param type The movement type.
     * @return The movement, or null.
     */
    private static FidelityMovement movementOf(String card, MovementType type) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            return account == null ? null
                    : FidelityMovement.<FidelityMovement>find("account = ?1 and type = ?2", account, type).firstResult();
        });
    }

    /**
     * Counts the movements of a type for a ticket reference, in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @param type      The movement type.
     * @return The movement count.
     */
    private static long countMovements(String ticketRef, MovementType type) {
        return QuarkusTransaction.requiringNew().call(() ->
                FidelityMovement.count("ticketRef = ?1 and type = ?2", ticketRef, type));
    }

    /**
     * Runs a counting query in a fresh transaction.
     *
     * @param supplier The count supplier.
     * @return The counted value.
     */
    private static long count(Supplier<Long> supplier) {
        return QuarkusTransaction.requiringNew().call(supplier::get);
    }

    /**
     * Deletes the movements and trace of a ticket reference, in a fresh transaction.
     *
     * @param ticketRef The ticket reference to clear.
     */
    private static void deleteTicket(String ticketRef) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityMovement.delete("ticketRef", ticketRef);
            EarnTrace trace = EarnTrace.findByTicketRef(ticketRef);
            if (trace != null) {
                EarnTraceLine.delete("trace", trace);
                trace.delete();
            }
        });
    }

    /**
     * Deletes every movement of a ticket reference (with no trace attached), in a fresh
     * transaction.
     *
     * @param ticketRef The ticket reference to clear.
     */
    private static void deleteMovementsByRef(String ticketRef) {
        QuarkusTransaction.requiringNew().run(() -> FidelityMovement.delete("ticketRef", ticketRef));
    }

    /**
     * Recomputes an account's denormalized balance from its remaining movements, in a fresh
     * transaction — restoring the seeded balance after a test's rows are removed (§14).
     *
     * @param card The card number.
     */
    private static void recomputeBalance(String card) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            if (account != null) {
                account.balance = FidelityMovement.computeBalance(account);
            }
        });
    }

    /**
     * Deletes the batch run recorded at a precise instant, removing only the run a test wrote
     * (never a seeded run) in a fresh transaction.
     *
     * @param type  The batch type name.
     * @param runAt The exact run timestamp.
     */
    private static void deleteBatchRunAt(String type, LocalDateTime runAt) {
        QuarkusTransaction.requiringNew().run(() -> BatchRunLog.delete("batchType = ?1 and runAt = ?2", type, runAt));
    }

    /**
     * Asserts a key set carries none of the forbidden nominative keys (§33.3, N2).
     *
     * @param keys    The JSON object key set.
     * @param surface A description of the surface, for the message.
     */
    private static void assertNoNominativeKeys(Set<String> keys, String surface) {
        for (String key : keys) {
            assertFalse(FORBIDDEN_KEYS.contains(key), surface + " must carry no nominative key but held '" + key + "'");
        }
    }
}
