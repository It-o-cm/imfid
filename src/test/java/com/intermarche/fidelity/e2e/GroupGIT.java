package com.intermarche.fidelity.e2e;

import com.intermarche.fidelity.batch.BatchScheduler;
import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.BatchRunLog;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.ReservationState;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group G — the account lifecycle batches (§16, e2escenarios-imfid.md
 * "## G"): the 1st-March expiry, the 24-month purge, the two-month activation void, their
 * "Simulate then Execute" contract (§23.4, §32.3), the supervision screen (§23.4), the three
 * literal cron expressions (§16) and the machine API {@code POST /api/batches/{type}}
 * (§32.3). Every scenario boots the real application under {@link QuarkusTest} with the
 * DataInitializer-rebuilt 2026 world.
 * <p>
 * The destructive scenarios never touch the seeded world: each isolates its effect to test
 * cards it inserts and removes in a {@code finally}. G1 freezes the {@link DateTimeProvider}
 * at the 1st of March so the expiry year is the previous civil year (§30.3) and only its
 * seeded card — carrying an old-year credit, a preserved current-year credit and an active
 * lease (I11) — has anything to expire; it proves the dry-run writes nothing and the
 * execution posts a negative EXPIRY bounded by the available balance, never the euros held
 * under the lease (§28.3), plus a {@link BatchRunLog} written on execution only. G2 antidates
 * two cards (a positive and a negative balance) past the 24-month cut-off and proves the
 * positive balance is debited to zero and RESILIATED, the negative one RESILIATED with no
 * movement and a zero line. G3 asserts the seeded historical proof of an activation void
 * (card {@code …095} + its {@code BatchRunLog}). G4 [W] drives the Programme screen in a real
 * browser: the last run of each batch, the literal execute-confirm dialog (dismissed, so no
 * write) and the unknown-type notice. G5 proves the three cron literals by reflection (a
 * configuration test). G6 exercises the machine API: the {@code dryRun}-defaults-true safety
 * net, the {@code fid-admin} guard ({@code pos} → 403) and the unknown-type 400. DB
 * assertions go through Panache under {@link QuarkusTransaction}, always by natural key and
 * delta, never absolute ids; BigDecimal amounts are compared by {@code compareTo} (§30.5).
 */
@QuarkusTest
@WithPlaywright
class GroupGIT {

    /**
     * Bootstrap administrator login name (SecurityBootstrap default), role {@code fid-admin}.
     */
    private static final String ADMIN_USER = "admin";

    /**
     * Bootstrap administrator password (SecurityBootstrap default in dev/test).
     */
    private static final String ADMIN_PASSWORD = "admin-password";

    /**
     * Bootstrap machine login name (SecurityBootstrap default), role {@code pos}.
     */
    private static final String POS_USER = "pos";

    /**
     * Bootstrap machine password (SecurityBootstrap default in dev/test).
     */
    private static final String POS_PASSWORD = "pos-password";

    /**
     * The seeded card voided by the two-month activation batch: RESILIATED, an
     * ACTIVATION_VOID movement of −2.40 €, balance 0.00 € — the historical proof of G3
     * (…095).
     */
    private static final String CARD_VOIDED = "2990000000095";

    /**
     * Test card of G1: an old-year credit (expirable), a current-year credit (preserved) and
     * an active lease, isolated from the seeded world by an out-of-range prefix.
     */
    private static final String CARD_EXPIRY = "9990000000011";

    /**
     * Test card of G2 carrying a positive balance past the 24-month inactivity cut-off.
     */
    private static final String CARD_PURGE_POSITIVE = "9990000000022";

    /**
     * Test card of G2 carrying a negative balance past the 24-month inactivity cut-off (I7).
     */
    private static final String CARD_PURGE_NEGATIVE = "9990000000033";

    /**
     * The instant the expiry batch is frozen at for G1 — 1 March 2026 at 03:00, distinct from
     * the seeded EXPIRY run at 02:00 so the run G1 writes is removable by its exact timestamp.
     */
    private static final LocalDateTime EXPIRY_FROZEN_AT = LocalDateTime.of(2026, 3, 1, 3, 0);

    /**
     * The literal execute-confirmation dialog of the Programme screen (§23.4, Q-E), with the
     * batch type interpolated — asserted for EXPIRY in G4.
     */
    private static final String CONFIRM_EXPIRY =
            "Exécuter le batch EXPIRY ? Cette opération détruit des avantages de façon définitive.";

    /**
     * The Playwright browser context injected by quarkus-playwright (headless Chromium).
     */
    @InjectPlaywright
    BrowserContext browser;

    /**
     * The test HTTP root of the booted application, used to build absolute URLs for the
     * real-browser G4 scenario.
     */
    @TestHTTPResource("/")
    URL baseUrl;

    // --------------------------------------------------
    // G1 — expiry dry-run vs execution (§16, §28.3)
    // --------------------------------------------------

    /**
     * G1 — {@code DateTimeProvider} frozen at 1 March so the expiry year is the previous civil
     * year: the dry-run reports the figures without any write (no EXPIRY movement, no
     * {@link BatchRunLog}), and the execution posts a negative EXPIRY consumed FIFO by
     * earnYear (the current-year credit is preserved), bounded by the available balance so the
     * euros held under the active lease are never expired (I11, §28.3), and records the run.
     */
    @Test
    void g1_expiryDryRunReportsThenExecutionWritesBoundedByAvailable() {
        seedExpiryCard();
        long runsBefore = batchRunCount("EXPIRY");
        try {
            DateTimeProvider.setFixedDateTime(EXPIRY_FROZEN_AT);
            Response dry = postBatchDefault("EXPIRY");
            assertEquals(200, dry.statusCode(), "the dry-run answers 200");
            assertEquals("EXPIRY", dry.jsonPath().getString("batch"), "the result names the batch");
            assertTrue(dry.jsonPath().getBoolean("dryRun"), "the default run is a simulation");
            assertEquals(1, dry.jsonPath().getInt("accountsAffected"), "only the isolated card qualifies at expireYear 2025");
            assertEquals(0, bd(dry.jsonPath().get("totalAmount")).compareTo(new BigDecimal("25.00")),
                    "the simulated amount is min(old residual 30.00, available 25.00) = 25.00 €");
            assertEquals(0, expiryLine(dry).compareTo(new BigDecimal("25.00")), "the card's line carries the bounded amount");
            assertNull(expiryMovement(CARD_EXPIRY), "a dry-run posts no EXPIRY movement (§30.2)");
            assertEquals(0, balanceOf(CARD_EXPIRY).compareTo(new BigDecimal("40.00")), "a dry-run leaves the balance untouched");
            assertEquals(runsBefore, batchRunCount("EXPIRY"), "a dry-run records no BatchRunLog (§23.4)");
            Response exec = postBatch("EXPIRY", false);
            assertEquals(200, exec.statusCode(), "the execution answers 200");
            assertTrue(exec.jsonPath().getBoolean("dryRun") == false, "the execution is not a simulation");
            assertEquals(1, exec.jsonPath().getInt("accountsAffected"), "the execution touches only the isolated card");
            assertEquals(0, expiryLine(exec).compareTo(new BigDecimal("25.00")), "the executed line carries the bounded amount");
            BigDecimal posted = expiryMovementAmount(CARD_EXPIRY);
            assertEquals(0, posted.compareTo(new BigDecimal("-25.00")), "the execution posts a negative EXPIRY of −25.00 €");
            assertEquals(0, balanceOf(CARD_EXPIRY).compareTo(new BigDecimal("15.00")),
                    "the balance drops to 15.00 € — the preserved current-year credit plus the lease-held residual");
            assertEquals(runsBefore + 1, batchRunCount("EXPIRY"), "the execution records exactly one BatchRunLog");
        } finally {
            DateTimeProvider.clear();
            deleteBatchRunAt("EXPIRY", EXPIRY_FROZEN_AT);
            purgeTestCard(CARD_EXPIRY);
        }
    }

    // --------------------------------------------------
    // G2 — 24-month purge (§16)
    // --------------------------------------------------

    /**
     * G2 — two antidated cards past the 24-month inactivity cut-off: the dry-run reports the
     * figures without any write, and the execution debits the positive balance to zero and
     * moves the card to RESILIATED, while the negative-balance card is resiliated with no
     * movement and a zero line (a negative balance is never a credit to purge, I7).
     */
    @Test
    void g2_purgeDebitsPositiveAndResiliatesReportingZeroForNegative() {
        seedPurgeCards();
        try {
            Response dry = postBatchDefault("PURGE");
            assertEquals(200, dry.statusCode(), "the dry-run answers 200");
            assertEquals("PURGE", dry.jsonPath().getString("batch"), "the result names the batch");
            assertTrue(dry.jsonPath().getBoolean("dryRun"), "the default run is a simulation");
            assertEquals(0, purgeLine(dry, CARD_PURGE_POSITIVE).compareTo(new BigDecimal("18.00")),
                    "the positive card's simulated line is its 18.00 € balance");
            assertEquals(0, purgeLine(dry, CARD_PURGE_NEGATIVE).compareTo(new BigDecimal("0.00")),
                    "the negative card's simulated line is 0.00 € (balance.max(0), I7)");
            assertEquals(AccountStatus.ACTIVE, statusOf(CARD_PURGE_POSITIVE), "a dry-run resiliates nothing");
            assertEquals(AccountStatus.ACTIVE, statusOf(CARD_PURGE_NEGATIVE), "a dry-run resiliates nothing");
            assertNull(purgeMovement(CARD_PURGE_POSITIVE), "a dry-run posts no PURGE movement (§30.2)");
            assertEquals(0, batchRunCount("PURGE"), "a dry-run records no BatchRunLog (§23.4)");
            Response exec = postBatch("PURGE", false);
            assertEquals(200, exec.statusCode(), "the execution answers 200");
            assertEquals(2, exec.jsonPath().getInt("accountsAffected"), "the execution touches both antidated cards");
            assertEquals(0, bd(exec.jsonPath().get("totalAmount")).compareTo(new BigDecimal("18.00")),
                    "only the positive balance contributes to the total moved");
            assertEquals(AccountStatus.RESILIATED, statusOf(CARD_PURGE_POSITIVE), "the positive card is resiliated");
            assertEquals(0, purgeMovementAmount(CARD_PURGE_POSITIVE).compareTo(new BigDecimal("-18.00")),
                    "the positive balance is debited to zero by a −18.00 € PURGE");
            assertEquals(0, balanceOf(CARD_PURGE_POSITIVE).compareTo(new BigDecimal("0.00")), "the purged balance is zero");
            assertEquals(AccountStatus.RESILIATED, statusOf(CARD_PURGE_NEGATIVE), "the negative card is resiliated");
            assertNull(purgeMovement(CARD_PURGE_NEGATIVE), "the negative balance is resiliated without a movement");
            assertEquals(0, balanceOf(CARD_PURGE_NEGATIVE).compareTo(new BigDecimal("-5.00")),
                    "the negative balance is carried forward unchanged (reported 0, moved nothing)");
            assertEquals(1, batchRunCount("PURGE"), "the execution records exactly one BatchRunLog");
        } finally {
            deleteBatchRuns("PURGE");
            purgeTestCard(CARD_PURGE_POSITIVE);
            purgeTestCard(CARD_PURGE_NEGATIVE);
        }
    }

    // --------------------------------------------------
    // G3 — activation void, historical proof (§16)
    // --------------------------------------------------

    /**
     * G3 — the seeded world already carries the proof of the two-month activation void: card
     * {@code …095}, never activated, is RESILIATED with a negative ACTIVATION_VOID movement
     * cancelling its advantages and a zero balance, and the supervision screen keeps its
     * ACTIVATION_VOID {@link BatchRunLog}.
     */
    @Test
    void g3_activationVoidHistoricalProofOnSeededCard() {
        assertEquals(AccountStatus.RESILIATED, statusOf(CARD_VOIDED), "the never-activated card …095 is RESILIATED");
        assertEquals(0, balanceOf(CARD_VOIDED).compareTo(new BigDecimal("0.00")),
                "its advantages were cancelled — balance 0.00 €");
        BigDecimal voided = movementAmount(CARD_VOIDED, MovementType.ACTIVATION_VOID);
        assertEquals(0, voided.compareTo(new BigDecimal("-2.40")), "a negative ACTIVATION_VOID cancels its 2.40 € advantage");
        BatchRunLog last = lastRun("ACTIVATION_VOID");
        assertNotNull(last, "the supervision screen keeps the ACTIVATION_VOID run (§23.4)");
        assertEquals(1, last.accountsAffected, "the seeded run touched one account");
        assertEquals(0, last.totalAmount.compareTo(new BigDecimal("2.40")), "the seeded run voided 2.40 €");
    }

    // --------------------------------------------------
    // G4 — the Programme screen, real browser [W] (§23.4)
    // --------------------------------------------------

    /**
     * G4 [W] — the Programme supervision screen in a real headless browser: the last run of
     * each batch is shown (EXPIRY and ACTIVATION_VOID ran, PURGE never), the execute button of
     * a destructive batch raises the literal confirmation dialog (dismissed here, so nothing
     * is written), and an unknown batch type yields the literal {@code Unknown batch '<t>'}
     * notice.
     */
    @Test
    void g4_programmeScreenLastRunsConfirmDialogAndUnknownNotice() {
        Page page = login();
        try {
            page.navigate(url("ui/program"));
            String content = page.content();
            assertTrue(content.contains("EXPIRY"), "the EXPIRY batch card is shown");
            assertTrue(content.contains("PURGE"), "the PURGE batch card is shown");
            assertTrue(content.contains("ACTIVATION_VOID"), "the ACTIVATION_VOID batch card is shown");
            assertTrue(content.contains("Dernier run"), "the last run of a batch that ran is shown (§23.4)");
            assertTrue(content.contains("Jamais exécuté"), "the never-run PURGE shows 'Jamais exécuté'");
            String[] captured = new String[1];
            page.onDialog(dialog -> {
                captured[0] = dialog.message();
                dialog.dismiss();
            });
            page.locator("button.btn-danger").first().click();
            assertEquals(CONFIRM_EXPIRY, captured[0], "the execute button raises the literal destructive-confirmation dialog");
            assertEquals(0, batchRunCount("PURGE"), "the dismissed dialog wrote nothing (§30.2)");
        } finally {
            page.close();
        }
        String notice = postBatchUiUnknownType("NOPE");
        assertEquals("Unknown batch 'NOPE'", notice, "an unknown batch type yields the literal notice (Q-D)");
    }

    // --------------------------------------------------
    // G5 — the three literal cron expressions (§16)
    // --------------------------------------------------

    /**
     * G5 — the scheduler proves itself by its expressions alone (a configuration test): the
     * expiry fires once a year on 1 March at 03:00 ({@code 0 0 3 1 3 ?}), the purge daily at
     * 04:00 ({@code 0 0 4 * * ?}) and the activation void daily at 05:00 ({@code 0 0 5 * * ?}).
     */
    @Test
    void g5_schedulerCronExpressionsAreTheThreeLiterals() {
        assertEquals("0 0 3 1 3 ?", cronOf("scheduledExpiry"), "the expiry cron fires once a year on 1 March at 03:00");
        assertEquals("0 0 4 * * ?", cronOf("scheduledPurge"), "the purge cron fires daily at 04:00");
        assertEquals("0 0 5 * * ?", cronOf("scheduledActivationVoid"), "the activation-void cron fires daily at 05:00");
    }

    // --------------------------------------------------
    // G6 — the machine batch API (§32.3)
    // --------------------------------------------------

    /**
     * G6 — {@code POST /api/batches/{type}}: {@code dryRun} defaults to true so a forgotten
     * parameter simulates and never destroys, the {@code fid-admin} role is required ({@code
     * pos} → 403), an unknown type is a 400 with the closed literal, and the body is the
     * {@link com.intermarche.fidelity.batch.BatchResult} JSON (batch, dryRun, accountsAffected,
     * totalAmount, lines).
     */
    @Test
    void g6_machineApiDefaultsToDryRunGuardsRoleAndRejectsUnknownType() {
        long runsBefore = batchRunCount("EXPIRY");
        Response defaulted = postBatchDefault("EXPIRY");
        assertEquals(200, defaulted.statusCode(), "a known batch answers 200");
        assertEquals("EXPIRY", defaulted.jsonPath().getString("batch"), "the body carries the batch name");
        assertTrue(defaulted.jsonPath().getBoolean("dryRun"), "a missing dryRun parameter defaults to a simulation");
        assertEquals(0, defaulted.jsonPath().getInt("accountsAffected"), "nothing qualifies at the real clock's expireYear");
        assertEquals(0, bd(defaulted.jsonPath().get("totalAmount")).compareTo(new BigDecimal("0.00")), "the total is 0.00 €");
        assertNotNull(defaulted.jsonPath().getList("lines"), "the body carries the per-account lines array");
        assertEquals(runsBefore, batchRunCount("EXPIRY"), "the defaulted simulation writes no BatchRunLog (§23.4)");
        int forbidden = RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .post("/api/batches/EXPIRY").statusCode();
        assertEquals(403, forbidden, "the pos role is refused on the fid-admin batch API");
        Response unknown = RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(ADMIN_USER, ADMIN_PASSWORD)
                .post("/api/batches/NOPE");
        assertEquals(400, unknown.statusCode(), "an unknown batch type is a 400");
        assertEquals("Unknown batch type 'NOPE'", unknown.jsonPath().getString("error"),
                "the 400 carries the closed unknown-type literal (Q-A)");
    }

    // --------------------------------------------------
    // Helpers — HTTP (machine API)
    // --------------------------------------------------

    /**
     * Triggers a batch with the default {@code dryRun} (parameter omitted) as the admin.
     *
     * @param type The batch type path segment.
     * @return The HTTP response.
     */
    private static Response postBatchDefault(String type) {
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(ADMIN_USER, ADMIN_PASSWORD)
                .post("/api/batches/" + type);
    }

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
                .queryParam("dryRun", dryRun)
                .post("/api/batches/" + type);
    }

    // --------------------------------------------------
    // Helpers — HTTP (admin UI form session)
    // --------------------------------------------------

    /**
     * Posts an unknown batch type to the Programme UI over a form session and returns the
     * one-shot notice the POST → 303 cycle carries in its redirect {@code Location} (§21.3),
     * decoded — following the redirect would drop the session cookie and land on the login.
     *
     * @param type The unknown batch type.
     * @return The decoded {@code notice} query parameter of the redirect.
     */
    private static String postBatchUiUnknownType(String type) {
        String cookie = RestAssured.given().redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("j_username", ADMIN_USER).formParam("j_password", ADMIN_PASSWORD)
                .post("/j_security_check").cookie("quarkus-credential");
        String location = RestAssured.given().redirects().follow(false)
                .cookie("quarkus-credential", cookie)
                .contentType(ContentType.URLENC)
                .formParam("type", type).formParam("dryRun", "true")
                .post("/ui/program/batch").header("Location");
        assertNotNull(location, "an unknown batch type redirects with a notice (§21.3)");
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

    // --------------------------------------------------
    // Helpers — JSON reading
    // --------------------------------------------------

    /**
     * Coerces a JSON numeric value to a scale-agnostic BigDecimal for {@code compareTo}.
     *
     * @param value The raw JSON value.
     * @return The BigDecimal of its string form.
     */
    private static BigDecimal bd(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    /**
     * Reads the amount of the expiry test card's line in a batch result.
     *
     * @param response The batch result response.
     * @return The line amount.
     */
    private static BigDecimal expiryLine(Response response) {
        return purgeLine(response, CARD_EXPIRY);
    }

    /**
     * Reads the amount of a given card's line in a batch result.
     *
     * @param response   The batch result response.
     * @param cardNumber The card number.
     * @return The line amount.
     */
    private static BigDecimal purgeLine(Response response, String cardNumber) {
        Map<String, ?> line = response.jsonPath().getMap("lines.find { it.cardNumber == '" + cardNumber + "' }");
        assertNotNull(line, "the result must carry a line for " + cardNumber);
        return bd(line.get("amount"));
    }

    // --------------------------------------------------
    // Helpers — reflection (scheduler configuration)
    // --------------------------------------------------

    /**
     * Reads the {@code cron} of a {@link BatchScheduler} scheduled method by reflection.
     *
     * @param methodName The scheduled method name.
     * @return The cron expression.
     */
    private static String cronOf(String methodName) {
        try {
            Scheduled scheduled = BatchScheduler.class.getDeclaredMethod(methodName).getAnnotation(Scheduled.class);
            assertNotNull(scheduled, methodName + " must carry a @Scheduled annotation");
            return scheduled.cron();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing scheduled method " + methodName, e);
        }
    }

    // --------------------------------------------------
    // Helpers — database seeding (isolated test cards)
    // --------------------------------------------------

    /**
     * Inserts the G1 expiry test card at the real clock: an ACTIVE account with a 2025 credit
     * of 30.00 € (expirable at expireYear 2025), a preserved 2026 credit of 10.00 € and an
     * active lease of 15.00 € holding part of the balance (I11); the balance is materialized
     * to 40.00 € from the two credits.
     */
    private static void seedExpiryCard() {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = new FidelityAccount();
            account.cardNumber = CARD_EXPIRY;
            account.status = AccountStatus.ACTIVE;
            account.balance = BigDecimal.ZERO;
            account.persist();
            postMovement(account, MovementType.EARN, "30.00", LocalDate.of(2025, 6, 1), 2025, "G1-2025", null);
            postMovement(account, MovementType.EARN, "10.00", LocalDate.of(2026, 1, 15), 2026, "G1-2026", null);
            FidelityReservation lease = new FidelityReservation();
            lease.account = account;
            lease.amount = new BigDecimal("15.00");
            lease.state = ReservationState.ACTIVE;
            lease.ticketRef = "G1-LEASE";
            lease.expiresAt = LocalDateTime.of(2999, 1, 1, 0, 0);
            lease.persist();
            account.balance = FidelityMovement.computeBalance(account);
            account.persist();
        });
    }

    /**
     * Inserts the two G2 purge test cards, both antidated past the 24-month inactivity cut-off
     * ({@code lastUsedAt} in 2023): one ACTIVE with a positive 18.00 € balance, one ACTIVE with
     * a negative −5.00 € balance (I7).
     */
    private static void seedPurgeCards() {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount positive = new FidelityAccount();
            positive.cardNumber = CARD_PURGE_POSITIVE;
            positive.status = AccountStatus.ACTIVE;
            positive.balance = BigDecimal.ZERO;
            positive.lastUsedAt = LocalDateTime.of(2023, 1, 1, 0, 0);
            positive.persist();
            postMovement(positive, MovementType.ADJUSTMENT, "18.00", LocalDate.of(2026, 6, 1), 2026, "G2-POS", "Antidated demo balance");
            positive.balance = FidelityMovement.computeBalance(positive);
            positive.persist();
            FidelityAccount negative = new FidelityAccount();
            negative.cardNumber = CARD_PURGE_NEGATIVE;
            negative.status = AccountStatus.ACTIVE;
            negative.balance = BigDecimal.ZERO;
            negative.lastUsedAt = LocalDateTime.of(2023, 1, 1, 0, 0);
            negative.persist();
            postMovement(negative, MovementType.RETURN_DEBIT, "-5.00", LocalDate.of(2026, 6, 1), 2026, "G2-NEG", null);
            negative.balance = FidelityMovement.computeBalance(negative);
            negative.persist();
        });
    }

    /**
     * Persists a movement on an already-persisted account within the current transaction.
     *
     * @param account   The owning account.
     * @param type      The movement type.
     * @param amount    The signed amount, as text.
     * @param date      The fiscal movement date.
     * @param earnYear  The civil year of acquisition.
     * @param ticketRef The ticket reference (kept unique per test movement).
     * @param reason    The reason, or null.
     */
    private static void postMovement(FidelityAccount account, MovementType type, String amount, LocalDate date,
                                     int earnYear, String ticketRef, String reason) {
        FidelityMovement movement = new FidelityMovement();
        movement.account = account;
        movement.type = type;
        movement.amount = new BigDecimal(amount);
        movement.movementDate = date;
        movement.earnYear = earnYear;
        movement.ruleCode = null;
        movement.ticketRef = ticketRef;
        movement.reason = reason;
        movement.persist();
    }

    /**
     * Removes a test card and everything hanging off it (reservations then movements then the
     * account) in a fresh transaction, restoring the seeded world.
     *
     * @param cardNumber The test card number.
     */
    private static void purgeTestCard(String cardNumber) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(cardNumber);
            if (account != null) {
                FidelityReservation.delete("account", account);
                FidelityMovement.delete("account", account);
                account.delete();
            }
        });
    }

    // --------------------------------------------------
    // Helpers — database reads and cleanups (no absolute ids)
    // --------------------------------------------------

    /**
     * Reads a card's materialized balance in a fresh transaction.
     *
     * @param cardNumber The card number.
     * @return The balance.
     */
    private static BigDecimal balanceOf(String cardNumber) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityAccount.findByCardNumber(cardNumber).balance);
    }

    /**
     * Reads a card's status in a fresh transaction.
     *
     * @param cardNumber The card number.
     * @return The account status.
     */
    private static AccountStatus statusOf(String cardNumber) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityAccount.findByCardNumber(cardNumber).status);
    }

    /**
     * Finds the single movement of a type on a card, or null, in a fresh transaction.
     *
     * @param cardNumber The card number.
     * @param type       The movement type.
     * @return The movement, or null.
     */
    private static FidelityMovement movementOf(String cardNumber, MovementType type) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(cardNumber);
            return account == null ? null : FidelityMovement.<FidelityMovement>find("account = ?1 and type = ?2", account, type).firstResult();
        });
    }

    /**
     * Reads the amount of the single movement of a type on a card, asserting it exists.
     *
     * @param cardNumber The card number.
     * @param type       The movement type.
     * @return The movement amount.
     */
    private static BigDecimal movementAmount(String cardNumber, MovementType type) {
        FidelityMovement movement = movementOf(cardNumber, type);
        assertNotNull(movement, cardNumber + " must carry a " + type + " movement");
        return movement.amount;
    }

    /**
     * Finds the EXPIRY movement of a card, or null.
     *
     * @param cardNumber The card number.
     * @return The EXPIRY movement, or null.
     */
    private static FidelityMovement expiryMovement(String cardNumber) {
        return movementOf(cardNumber, MovementType.EXPIRY);
    }

    /**
     * Reads the amount of a card's EXPIRY movement, asserting it exists.
     *
     * @param cardNumber The card number.
     * @return The EXPIRY amount.
     */
    private static BigDecimal expiryMovementAmount(String cardNumber) {
        return movementAmount(cardNumber, MovementType.EXPIRY);
    }

    /**
     * Finds the PURGE movement of a card, or null.
     *
     * @param cardNumber The card number.
     * @return The PURGE movement, or null.
     */
    private static FidelityMovement purgeMovement(String cardNumber) {
        return movementOf(cardNumber, MovementType.PURGE);
    }

    /**
     * Reads the amount of a card's PURGE movement, asserting it exists.
     *
     * @param cardNumber The card number.
     * @return The PURGE amount.
     */
    private static BigDecimal purgeMovementAmount(String cardNumber) {
        return movementAmount(cardNumber, MovementType.PURGE);
    }

    /**
     * Counts the recorded runs of a batch type in a fresh transaction.
     *
     * @param type The batch type name.
     * @return The run count.
     */
    private static long batchRunCount(String type) {
        return QuarkusTransaction.requiringNew().call(() -> BatchRunLog.count("batchType", type));
    }

    /**
     * Reads the most recent run of a batch type in a fresh transaction.
     *
     * @param type The batch type name.
     * @return The last run, or null.
     */
    private static BatchRunLog lastRun(String type) {
        return QuarkusTransaction.requiringNew().call(() -> BatchRunLog.lastRun(type));
    }

    /**
     * Deletes the batch run recorded at a precise instant, removing only the run a test wrote
     * (never a seeded run) in a fresh transaction.
     *
     * @param type   The batch type name.
     * @param runAt  The exact run timestamp.
     */
    private static void deleteBatchRunAt(String type, LocalDateTime runAt) {
        QuarkusTransaction.requiringNew().run(() -> BatchRunLog.delete("batchType = ?1 and runAt = ?2", type, runAt));
    }

    /**
     * Deletes every run of a batch type that the seeded world never records (PURGE) in a fresh
     * transaction.
     *
     * @param type The batch type name.
     */
    private static void deleteBatchRuns(String type) {
        QuarkusTransaction.requiringNew().run(() -> BatchRunLog.delete("batchType", type));
    }
}
