package com.intermarche.e2e;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.ProductFamily;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group K — the CSV bulk imports and the Imports screen (§18, §23.5,
 * e2escenarios-imfid.md "## K. Imports CSV & écran Imports"): the common machinery — raw
 * pipe-delimited POST, header-driven column resolution, JSON report and staged fallback with its WARN line
 * (K1); the checksum idempotence, 0-update on an identical replay, 1 on a changed field (K2);
 * the resource-level ordering catch-up of the nine-domain pack (K3); the drag &amp; drop
 * preselection with its {@code famil}-before-{@code product} trap (K4 [W]); the {@code
 * NO_MOVEMENT} idempotent visit import (K5); the prod-like divergences of the CSV world (K6
 * [P]); the report being valid, escaped JSON even with quote/tab-bearing errors (K7); the two
 * diverging rule paths — the validating importer versus the unvalidated direct persistence
 * (K8); the adjustment importer writing the ledger and refreshing the balance idempotently
 * (K9); the account importer as a card generator and transfer-chain builder (K10); the
 * membership/activation dependency errors and their catch-up (K11); and the family import as a
 * complete-state replacement whose removals are proven in base (K12).
 * <p>
 * Every scenario boots the real application under {@link QuarkusTest} against the
 * DataInitializer-rebuilt world (wipe + reload at each boot, itself driven by these very
 * importers — {@code SeedLoader}). The seeded facts the catalog states are only read; every
 * mutation targets isolated {@code ZZZ_*}/{@code 29900001*} test rows, uses references and
 * ticket refs carrying a per-scenario suffix (fiscal idempotence trap, §29.4), and is undone in
 * a {@code finally} so no scenario depends on another's order. The direct import endpoints are
 * driven over Basic {@code admin} (role {@code fid-admin}, §24.1) posting raw {@code text/plain}
 * CSV; K4 drives a real headless browser ({@code [W]}) so the drag &amp; drop JS of
 * {@code fidelity.js} runs for real. Import row errors are surfaced in the JSON report's
 * {@code errors} array and asserted by parsing, never by string-matching the whole body.
 */
@QuarkusTest
@WithPlaywright
class GroupKIT {

    /**
     * Bootstrap administrator login name (SecurityBootstrap default), role {@code fid-admin}.
     */
    private static final String ADMIN_USER = "admin";

    /**
     * Bootstrap administrator password (SecurityBootstrap default in dev/test).
     */
    private static final String ADMIN_PASSWORD = "admin-password";

    /**
     * The rules import endpoint (§18).
     */
    private static final String RULES_IMPORT = "/fidelity/rules/import";

    /**
     * The adjustments import endpoint (§18, §32.1).
     */
    private static final String ADJ_IMPORT = "/fidelity/adjustments/import";

    /**
     * The accounts import endpoint (§18, §33.1).
     */
    private static final String ACCOUNTS_IMPORT = "/fidelity/accounts/import";

    /**
     * The memberships import endpoint (§18).
     */
    private static final String MEMBERSHIPS_IMPORT = "/fidelity/memberships/import";

    /**
     * The activations import endpoint (§18).
     */
    private static final String ACTIVATIONS_IMPORT = "/fidelity/activations/import";

    /**
     * The visits import endpoint (§18, §29.1).
     */
    private static final String VISITS_IMPORT = "/fidelity/visits/import";

    /**
     * The product families import endpoint (§18).
     */
    private static final String FAMILIES_IMPORT = "/product-families/import";

    /**
     * The rule CSV header line (columns resolved by name on every import).
     */
    private static final String RULE_HEADER =
            "CODE|TYPE|LABEL|VALID_FROM|VALID_TO|PRIORITY|EXCLUSIVE|MONTHLY_CAP_PER_CARD|ACTIVE|SPECIFICATION";

    /**
     * The adjustment CSV header line (columns resolved by name on every import).
     */
    private static final String ADJ_HEADER = "REFERENCE|CARD_NUMBER|AMOUNT|MOVEMENT_DATE|REASON";

    /**
     * The account CSV header line (columns resolved by name on every import).
     */
    private static final String ACCOUNT_HEADER = "CARD_NUMBER|STATUS|ACTIVATED_AT|LAST_USED_AT|TRANSFERRED_TO_CARD";

    /**
     * The membership CSV header line (columns resolved by name on every import).
     */
    private static final String MEMBERSHIP_HEADER = "CARD_NUMBER|COMMUNITY_CODE|VALID_FROM|VALID_TO";

    /**
     * The activation CSV header line (columns resolved by name on every import).
     */
    private static final String ACTIVATION_HEADER = "CARD_NUMBER|RULE_CODE|PERIOD_START|PERIOD_END|MISSION_DONE";

    /**
     * The visit CSV header line (columns resolved by name on every import).
     */
    private static final String VISIT_HEADER = "TICKET_REF|CARD_NUMBER|STORE_CODE|FISCAL_DATE";

    /**
     * The family CSV header line (columns resolved by name on every import).
     */
    private static final String FAMILY_HEADER = "CODE|DESCRIPTION|FLAGS|PRODUCT_EANS|SUBFAMILY_CODES";

    /**
     * A valid {@code BRAND_TIERED_EARN} specification, reused wherever an import needs a rule
     * whose type and schema both validate against the registry (§12).
     */
    private static final String BRAND_SPEC =
            "{\"scope\":{\"include\":{\"brands\":[\"Pâturages\"]}},\"minEligibleItems\":3,"
                    + "\"baseRate\":0.05,\"boostedRate\":0.10,\"visitThreshold\":4}";

    /**
     * A seeded ACTIVE card used as the neutral write target of the ledger scenarios (K1, K9).
     */
    private static final String CARD_A = "2990000000057";

    /**
     * A card number that is never seeded, used to force "card not found" row errors.
     */
    private static final String CARD_UNKNOWN = "9999999999999";

    /**
     * The seeded {@code F_L} family's original product EAN list, restored after K12.
     */
    private static final Set<String> F_L_SEED_EANS =
            Set.of("3300000000001", "3300000000014", "3300000000017", "3300000000018");

    /**
     * The captured runtime log messages, populated while a K1 import runs (§18).
     */
    private static final CopyOnWriteArrayList<String> LOGS = new CopyOnWriteArrayList<>();

    /**
     * The runtime log handler installed for the current scenario, or null when none.
     */
    private Handler logHandler;

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
     * Attaches a fresh root-logger capture handler before each scenario so a test may read the
     * contractual import log lines (§18) without any cross-scenario leakage.
     */
    @BeforeEach
    void attachLogCapture() {
        LOGS.clear();
        logHandler = new Handler() {
            /**
             * Captures the fully formatted message of a record into the static list.
             *
             * @param record The log record to capture.
             */
            @Override
            public void publish(LogRecord record) {
                if (record == null) {
                    return;
                }
                String formatted = record instanceof ExtLogRecord ext ? ext.getFormattedMessage() : record.getMessage();
                if (formatted != null) {
                    LOGS.add(formatted);
                }
            }

            /**
             * Flushes the handler; nothing is buffered, so this is a no-op.
             */
            @Override
            public void flush() {
                // Nothing buffered.
            }

            /**
             * Closes the handler; nothing is held, so this is a no-op.
             */
            @Override
            public void close() {
                // Nothing to release.
            }
        };
        logHandler.setLevel(Level.ALL);
        Logger.getLogger("").addHandler(logHandler);
    }

    /**
     * Detaches the runtime log capture handler after each scenario.
     */
    @AfterEach
    void detachLogCapture() {
        if (logHandler != null) {
            Logger.getLogger("").removeHandler(logHandler);
            logHandler = null;
        }
    }

    // --------------------------------------------------
    // K1 — common machinery: raw POST, header-driven parsing, report, staged fallback
    // --------------------------------------------------

    /**
     * K1 — the common import machinery: a raw {@code text/plain} pipe-delimited POST with a
     * header-driven body returns a 200 JSON report {@code {"createdCount":n,"updatedCount":m}}; a
     * chunk carrying one faulty row (an unknown card) triggers the staged fallback, logging the
     * WARN {@code Failed to process chunk of size 4 with step 1000. Retrying with step 100} and
     * emitting {@code Import finished. Created: 3, Updated: 0}, the three healthy rows passing
     * while the faulty one is isolated line by line into the report's {@code errors}.
     */
    @Test
    void k1_commonMachineryReportsAndStagesTheFallback() {
        List<String> refs = List.of("K1-1", "K1-2", "K1-3", "K1-BAD");
        try {
            String csv = csv(ADJ_HEADER,
                    "K1-1|" + CARD_A + "|0.00|2026-08-01|K1 neutral one",
                    "K1-2|" + CARD_A + "|0.00|2026-08-01|K1 neutral two",
                    "K1-3|" + CARD_A + "|0.00|2026-08-01|K1 neutral three",
                    "K1-BAD|" + CARD_UNKNOWN + "|0.00|2026-08-01|K1 doomed row");
            Response report = importCsv(ADJ_IMPORT, csv);
            assertEquals(200, report.statusCode(), "a raw CSV import returns a 200 report (§18)");
            assertEquals(3, createdCount(report), "the three healthy rows are created (K1)");
            assertEquals(0, updatedCount(report), "no row is an update (K1)");
            assertTrue(errorsContain(report, "Card '" + CARD_UNKNOWN + "' not found."),
                    "the faulty row is isolated into the errors (K1)");
            assertTrue(LOGS.stream().anyMatch(m -> m.contains(
                            "Failed to process chunk of size 4 with step 1000. Retrying with step 100")),
                    "the staged fallback logs its WARN line (§18)");
            assertTrue(LOGS.stream().anyMatch(m -> m.contains("Import finished. Created: 3, Updated: 0")),
                    "the import logs its finished line (§18)");
        } finally {
            deleteMovementsByRefs(refs);
        }
    }

    // --------------------------------------------------
    // K2 — checksum idempotence
    // --------------------------------------------------

    /**
     * K2 — checksum idempotence: creating a rule reports one creation; a byte-identical replay
     * reports zero updates and leaves {@code updated_at} untouched in base (the checksum skips
     * the write); a replay with one changed field (the label) reports one update and persists
     * the new value.
     */
    @Test
    void k2_checksumSkipsUnchangedRowsAndCountsChangedOnes() {
        String code = "ZZZ_K2";
        try {
            String create = csv(RULE_HEADER,
                    code + "|BRAND_TIERED_EARN|Label K2|2026-01-01T00:00:00||0|false||false|" + BRAND_SPEC);
            Response created = importCsv(RULES_IMPORT, create);
            assertEquals(1, createdCount(created), "the rule is created (K2)");
            LocalDateTime firstUpdatedAt = ruleUpdatedAt(code);
            assertNotNull(firstUpdatedAt, "the created rule carries an updated_at (K2)");
            Response replay = importCsv(RULES_IMPORT, create);
            assertEquals(0, createdCount(replay), "an identical replay creates nothing (K2)");
            assertEquals(0, updatedCount(replay), "an identical replay updates nothing — checksum hit (K2)");
            assertEquals(firstUpdatedAt, ruleUpdatedAt(code), "the identical replay leaves updated_at untouched (K2)");
            String changed = csv(RULE_HEADER,
                    code + "|BRAND_TIERED_EARN|Changed K2|2026-01-01T00:00:00||0|false||false|" + BRAND_SPEC);
            Response updated = importCsv(RULES_IMPORT, changed);
            assertEquals(1, updatedCount(updated), "a changed field reports one update (K2)");
            assertEquals("Changed K2", ruleLabel(code), "the changed label is persisted (K2)");
        } finally {
            deleteRule(code);
        }
    }

    // --------------------------------------------------
    // K3 — resource ordering and catch-up
    // --------------------------------------------------

    /**
     * K3 — resource-level ordering: replaying the whole seed pack (the very {@code
     * 01→09} files the DataInitializer loaded) passes with no row error, every domain being
     * idempotent; a membership played before its card exists errors with {@code Card '<n>' not
     * found.}; importing the account then replaying the membership succeeds — a catch-up with no
     * shared state.
     */
    @Test
    void k3_replayIsIdempotentAndDependenciesCatchUp() {
        String card = "2990000199001";
        try {
            replayPassesWithoutErrors("/seed/01-products.csv", "/products/import");
            replayPassesWithoutErrors("/seed/02-product-families.csv", FAMILIES_IMPORT);
            replayPassesWithoutErrors("/seed/03-communities.csv", "/fidelity/communities/import");
            replayPassesWithoutErrors("/seed/04-rules.csv", RULES_IMPORT);
            replayPassesWithoutErrors("/seed/05-accounts.csv", ACCOUNTS_IMPORT);
            replayPassesWithoutErrors("/seed/06-adjustments.csv", ADJ_IMPORT);
            replayPassesWithoutErrors("/seed/07-visits.csv", VISITS_IMPORT);
            replayPassesWithoutErrors("/seed/08-activations.csv", ACTIVATIONS_IMPORT);
            replayPassesWithoutErrors("/seed/09-memberships.csv", MEMBERSHIPS_IMPORT);
            String membershipCsv = csv(MEMBERSHIP_HEADER, card + "|BABIES|2026-02-10|");
            Response tooEarly = importCsv(MEMBERSHIPS_IMPORT, membershipCsv);
            assertEquals(0, createdCount(tooEarly), "a membership before its card creates nothing (K3)");
            assertTrue(errorsContain(tooEarly, "Card '" + card + "' not found."),
                    "a membership before its card errors on the missing card (K3)");
            Response account = importCsv(ACCOUNTS_IMPORT, csv(ACCOUNT_HEADER, card + "|ACTIVE|||"));
            assertEquals(1, createdCount(account), "the account is created on catch-up (K3)");
            Response caught = importCsv(MEMBERSHIPS_IMPORT, membershipCsv);
            assertEquals(1, createdCount(caught), "the membership passes once its card exists (K3)");
            assertTrue(membershipExists(card, "BABIES", LocalDate.of(2026, 2, 10)),
                    "the caught-up membership is persisted (K3)");
        } finally {
            deleteMembershipsOfCard(card);
            deleteAccount(card);
        }
    }

    // --------------------------------------------------
    // K4 [W] — drag & drop preselection
    // --------------------------------------------------

    /**
     * K4 [W] — the Imports drag &amp; drop, driven in a real headless browser: dragging over the
     * frame highlights it ({@code is-dragover}) and dropping a file lands it in the input while
     * preselecting the domain from the name — {@code 04-rules.csv → FIDELITY_RULES}; the graven
     * order trap holds, {@code 02-product-families.csv → PRODUCT_FAMILIES} because the {@code
     * famil} pattern is tested before {@code product}; an unrecognized name leaves the domain
     * unchanged; and a classic file selection preselects the domain the same way ({@code
     * 05-accounts.csv → FIDELITY_ACCOUNTS}).
     */
    @Test
    void k4_dragAndDropPreselectsTheDomain() {
        Page page = null;
        try {
            page = login();
            page.navigate(url("ui/imports"));
            page.waitForSelector(".import-drop");
            String combined = (String) page.evaluate(DROP_SCRIPT);
            String[] steps = combined.split("@");
            assertEquals("true|FIDELITY_RULES|1", steps[0], "dropping 04-rules.csv highlights, lands, preselects (K4)");
            assertEquals("true|PRODUCT_FAMILIES|1", steps[1], "02-product-families.csv preselects PRODUCT_FAMILIES (famil before product, K4)");
            assertEquals("true|MEMBERSHIPS|1", steps[2], "an unrecognized name leaves the domain unchanged (K4)");
            page.locator(".import-drop input[type=\"file\"]").setInputFiles(
                    new FilePayload("05-accounts.csv", "text/csv", "x".getBytes()));
            assertEquals("FIDELITY_ACCOUNTS",
                    page.locator(".import-drop select[name=\"domain\"]").inputValue(),
                    "a classic selection preselects the domain the same way (K4)");
        } finally {
            if (page != null) {
                page.close();
            }
        }
    }

    // --------------------------------------------------
    // K5 — visit import: NO_MOVEMENT, idempotent
    // --------------------------------------------------

    /**
     * K5 — the visit import writes header-only {@code NO_MOVEMENT} traces (§29.1) idempotent by
     * ticket reference: a fresh visit is created with the {@code NO_MOVEMENT} status and its
     * fiscal date; replaying the same ticket reference records nothing more (no doubled visit) —
     * the official lever arming the 4th-visit boost, exercised end to end in group C.
     */
    @Test
    void k5_visitImportIsNoMovementAndIdempotent() {
        String ref = "K5-0101-2026-900001";
        try {
            String visitCsv = csv(VISIT_HEADER, ref + "|" + CARD_A + "|0101|2026-08-03");
            Response created = importCsv(VISITS_IMPORT, visitCsv);
            assertEquals(1, createdCount(created), "a fresh visit is recorded (K5)");
            EarnTrace trace = traceByRef(ref);
            assertNotNull(trace, "the visit trace is persisted (K5)");
            assertEquals(EarnTrace.STATUS_NO_MOVEMENT, traceStatus(ref), "the visit trace carries NO_MOVEMENT (§29.1)");
            assertEquals(LocalDate.of(2026, 8, 3), traceFiscalDate(ref), "the visit trace carries its fiscal date (K5)");
            Response replay = importCsv(VISITS_IMPORT, visitCsv);
            assertEquals(0, createdCount(replay), "replaying the same ticket doubles no visit (§29.4)");
            assertEquals(1, traceCount(ref), "exactly one trace exists after the replay (K5)");
        } finally {
            deleteTrace(ref);
        }
    }

    // --------------------------------------------------
    // K6 [P] — limits of the CSV world
    // --------------------------------------------------

    /**
     * K6 [P] — the qualification pack cannot express the narrated ledger (ADJUSTMENT-only
     * movements) nor an active burn reservation (card {@code …088} showing available = balance):
     * assumed divergences with the richer DataInitializer world, not to be asserted in a
     * prod-like harness. Disabled as a justified residue until such a harness exists.
     */
    @Test
    @Disabled("[P] prod-like env required: the CSV qualif pack cannot express the narrated ledger"
            + " (ADJUSTMENT-only) nor an active burn reservation (…088 available = balance); assumed"
            + " divergences with the DataInitializer world, not asserted in prod-like (K6).")
    void k6_limitsOfTheCsvWorld() {
        // Justified residue: no prod-like harness yet (see @Disabled reason).
    }

    // --------------------------------------------------
    // K7 — the report is valid, escaped JSON
    // --------------------------------------------------

    /**
     * K7 — the import report is valid JSON even when row errors carry characters that would
     * break a string literal: rows keyed by a reference bearing a double quote and a tab, both on
     * an unknown card, produce {@code errors} entries that {@code escapeJson} renders as {@code
     * \"} and spaces, so the body {@code {"createdCount":0,"updatedCount":0,"errors":[…]}} parses
     * — asserted BY PARSING, the assumed divergence with imvaluation's D3 malformed body.
     */
    @Test
    void k7_theReportIsValidEscapedJson() {
        try {
            String csv = csv(ADJ_HEADER,
                    "K7-\"quoted\"|" + CARD_UNKNOWN + "|1.00|2026-08-01|K7 quote in key",
                    "K7-\ttabbed|" + CARD_UNKNOWN + "|1.00|2026-08-01|K7 tab in key");
            Response report = importCsv(ADJ_IMPORT, csv);
            assertEquals(200, report.statusCode(), "the import returns a 200 report (K7)");
            List<String> errors = errors(report);
            assertEquals(2, errors.size(), "both faulty rows are parsed out of the JSON report (K7)");
            assertTrue(errors.stream().anyMatch(e -> e.contains("K7-\"quoted\"")),
                    "the quote-bearing key round-trips through the parsed report (K7)");
            assertTrue(report.asString().contains("\\\""),
                    "the raw body carries the JSON-escaped quote (K7)");
        } finally {
            deleteMovementsByRefs(List.of("K7-\"quoted\"", "K7-\ttabbed"));
        }
    }

    // --------------------------------------------------
    // K8 — the two diverging rule paths (canary)
    // --------------------------------------------------

    /**
     * K8 — the two rule paths diverge: the {@code FIDELITY_RULES} import validates against the
     * registry, so a missing type, an unknown type, a schema-invalid specification and a
     * malformed {@code validFrom} each become a row error ({@code missing rule type}, {@code
     * unknown rule type '<t>'}, {@code specification invalid for type '<t>':}, {@code missing or
     * malformed validFrom (ISO date-time)}); the direct persistence path (seed, SQL) does not
     * validate, so a deformed rule (an unknown type) can exist in base — the engine degrading it
     * (C13). Both paths are proven side by side.
     */
    @Test
    void k8_importValidatesWhereDirectPersistenceDoesNot() {
        String directCode = "ZZZ_K8_DIRECT";
        try {
            String csv = csv(RULE_HEADER,
                    "ZZZ_K8_MISSTYPE||No type|2026-01-01T00:00:00||0|false||false|" + BRAND_SPEC,
                    "ZZZ_K8_UNKNOWN|ZZZ_TYPE_K8|Unknown type|2026-01-01T00:00:00||0|false||false|" + BRAND_SPEC,
                    "ZZZ_K8_BADSPEC|BRAND_TIERED_EARN|Bad spec|2026-01-01T00:00:00||0|false||false|{}",
                    "ZZZ_K8_NODATE|BRAND_TIERED_EARN|No validFrom|not-a-date||0|false||false|" + BRAND_SPEC);
            Response report = importCsv(RULES_IMPORT, csv);
            assertEquals(0, createdCount(report), "no invalid rule row is created by the import (K8)");
            assertTrue(errorsContain(report, "missing rule type"), "a missing type is refused (K8)");
            assertTrue(errorsContain(report, "unknown rule type 'ZZZ_TYPE_K8'"), "an unknown type is refused (K8)");
            assertTrue(errorsContain(report, "specification invalid for type 'BRAND_TIERED_EARN':"),
                    "a schema-invalid specification is refused (K8)");
            assertTrue(errorsContain(report, "missing or malformed validFrom (ISO date-time)"),
                    "a malformed validFrom is refused (K8)");
            persistDeformedRule(directCode);
            assertNotNull(ruleByCodeType(directCode), "a deformed rule persists through the direct path (K8)");
            assertEquals("ZZZ_TYPE_K8", ruleByCodeType(directCode), "the direct path never validates the type (C13, K8)");
        } finally {
            deleteRule(directCode);
            deleteRule("ZZZ_K8_MISSTYPE");
            deleteRule("ZZZ_K8_UNKNOWN");
            deleteRule("ZZZ_K8_BADSPEC");
            deleteRule("ZZZ_K8_NODATE");
        }
    }

    // --------------------------------------------------
    // K9 — the adjustment importer writes the ledger
    // --------------------------------------------------

    /**
     * K9 — the adjustment importer writes the ledger: a row creates an {@code ADJUSTMENT}
     * movement and refreshes the account balance by its amount; a replay on the same reference
     * credits nothing more (idempotence, I8); and the mandatory-field guards refuse a missing
     * reason, amount or movement date and an unknown card with their literals ({@code reason is
     * mandatory for an ADJUSTMENT (§32.1).}, {@code amount is mandatory.}, {@code movementDate is
     * mandatory.}, {@code Card '<n>' not found.}).
     */
    @Test
    void k9_adjustmentImporterWritesTheLedgerIdempotently() {
        String ref = "K9-ADJ-1";
        BigDecimal before = balanceOf(CARD_A);
        try {
            Response created = importCsv(ADJ_IMPORT, csv(ADJ_HEADER, ref + "|" + CARD_A + "|10.00|2026-08-01|K9 credit"));
            assertEquals(1, createdCount(created), "the adjustment row is created (K9)");
            assertTrue(adjustmentExists(ref), "the ADJUSTMENT movement is written (§32.1)");
            assertEquals(0, before.add(new BigDecimal("10.00")).compareTo(balanceOf(CARD_A)),
                    "the balance is refreshed by the amount (K9)");
            Response replay = importCsv(ADJ_IMPORT, csv(ADJ_HEADER, ref + "|" + CARD_A + "|10.00|2026-08-01|K9 credit"));
            assertEquals(0, createdCount(replay), "a replay of the reference credits nothing more (I8)");
            assertEquals(0, before.add(new BigDecimal("10.00")).compareTo(balanceOf(CARD_A)),
                    "the replay leaves the balance unchanged (K9)");
            Response refusals = importCsv(ADJ_IMPORT, csv(ADJ_HEADER,
                    "K9-NOREASON|" + CARD_A + "|5.00|2026-08-01|",
                    "K9-NOAMOUNT|" + CARD_A + "||2026-08-01|K9 no amount",
                    "K9-NODATE|" + CARD_A + "|5.00||K9 no date",
                    "K9-NOCARD|" + CARD_UNKNOWN + "|5.00|2026-08-01|K9 no card"));
            assertTrue(errorsContain(refusals, "reason is mandatory for an ADJUSTMENT (§32.1)."), "a missing reason is refused (K9)");
            assertTrue(errorsContain(refusals, "amount is mandatory."), "a missing amount is refused (K9)");
            assertTrue(errorsContain(refusals, "movementDate is mandatory."), "a missing movement date is refused (K9)");
            assertTrue(errorsContain(refusals, "Card '" + CARD_UNKNOWN + "' not found."), "an unknown card is refused (K9)");
        } finally {
            deleteMovementsByRefs(List.of(ref));
            setBalance(CARD_A, before);
        }
    }

    // --------------------------------------------------
    // K10 — the account importer as card generator and chain builder
    // --------------------------------------------------

    /**
     * K10 — the account importer's magic column: a filled {@code cardNumber} upserts (a re-import
     * changing the status counts one update); a blank {@code cardNumber} generates the number —
     * a 13-digit EAN-13 opened by the program prefix with a valid check digit (the import is a
     * card generator); and a {@code transferredToCard} value makes the transfer chain exist from
     * the import alone (E8 on a 100% CSV world).
     */
    @Test
    void k10_accountImporterGeneratesCardsAndBuildsTheChain() {
        String explicit = "2990000100019";
        String linked = "2990000100026";
        String generated = null;
        try {
            Response first = importCsv(ACCOUNTS_IMPORT, csv(ACCOUNT_HEADER, explicit + "|ACTIVE|||"));
            assertEquals(1, createdCount(first), "an explicit card is created (K10)");
            Response second = importCsv(ACCOUNTS_IMPORT, csv(ACCOUNT_HEADER, explicit + "|RESILIATED|||"));
            assertEquals(1, updatedCount(second), "a re-import changing the status upserts (K10)");
            assertEquals("RESILIATED", accountStatus(explicit), "the upsert persists the new status (K10)");
            Response chained = importCsv(ACCOUNTS_IMPORT, csv(ACCOUNT_HEADER, linked + "|ACTIVE|||" + explicit));
            assertEquals(1, createdCount(chained), "the transfer-source card is created (K10)");
            assertEquals(explicit, accountTransferredTo(linked), "the transfer chain exists from the import (E8, K10)");
            Set<String> before = allCardNumbers();
            Response gen = importCsv(ACCOUNTS_IMPORT, csv(ACCOUNT_HEADER, "|ACTIVE|||"));
            assertEquals(1, createdCount(gen), "a blank card row creates one account (K10)");
            Set<String> after = allCardNumbers();
            after.removeAll(before);
            assertEquals(1, after.size(), "exactly one card number is generated (K10)");
            generated = after.iterator().next();
            assertEquals(13, generated.length(), "the generated number is a 13-digit EAN-13 (§33.1)");
            assertTrue(generated.startsWith("29"), "the generated number is opened by the program prefix (§33.1)");
            assertTrue(isValidEan13(generated), "the generated number carries a valid EAN-13 check digit (§33.1)");
        } finally {
            deleteAccount(explicit);
            deleteAccount(linked);
            deleteAccount(generated);
        }
    }

    // --------------------------------------------------
    // K11 — membership & activation dependencies
    // --------------------------------------------------

    /**
     * K11 — the membership and activation imports guard their dependencies: an unknown card,
     * community or missing {@code validFrom} refuse a membership row ({@code Card '<n>' not
     * found.}, {@code Community '<c>' not found.}, {@code validFrom is mandatory.}); an unknown
     * card, missing {@code ruleCode} or {@code periodStart} refuse an activation row ({@code Card
     * '<n>' not found.}, {@code ruleCode is mandatory.}, {@code periodStart is mandatory.}); and
     * a membership played before its card errors, then passes once the card is imported —
     * resource-level catch-up.
     */
    @Test
    void k11_membershipAndActivationDependenciesGuardAndCatchUp() {
        String card = "2990000199011";
        try {
            Response memberships = importCsv(MEMBERSHIPS_IMPORT, csv(MEMBERSHIP_HEADER,
                    CARD_UNKNOWN + "|BABIES|2026-02-10|",
                    "2990000000019|NOPE_COMM|2026-02-10|",
                    "2990000000019|BABIES||"));
            assertTrue(errorsContain(memberships, "Card '" + CARD_UNKNOWN + "' not found."), "an unknown card is refused (K11)");
            assertTrue(errorsContain(memberships, "Community 'NOPE_COMM' not found."), "an unknown community is refused (K11)");
            assertTrue(errorsContain(memberships, "validFrom is mandatory."), "a missing validFrom is refused (K11)");
            Response activations = importCsv(ACTIVATIONS_IMPORT, csv(ACTIVATION_HEADER,
                    CARD_UNKNOWN + "|ECOUPON_DEMO|2026-08-01|2026-09-30|false",
                    "2990000000019||2026-08-01|2026-09-30|false",
                    "2990000000019|ECOUPON_DEMO|||false"));
            assertTrue(errorsContain(activations, "Card '" + CARD_UNKNOWN + "' not found."), "an unknown card is refused (K11)");
            assertTrue(errorsContain(activations, "ruleCode is mandatory."), "a missing ruleCode is refused (K11)");
            assertTrue(errorsContain(activations, "periodStart is mandatory."), "a missing periodStart is refused (K11)");
            String membershipCsv = csv(MEMBERSHIP_HEADER, card + "|BABIES|2026-02-10|");
            assertTrue(errorsContain(importCsv(MEMBERSHIPS_IMPORT, membershipCsv), "Card '" + card + "' not found."),
                    "a membership before its card errors (K11)");
            assertEquals(1, createdCount(importCsv(ACCOUNTS_IMPORT, csv(ACCOUNT_HEADER, card + "|ACTIVE|||"))),
                    "the account catches up (K11)");
            assertEquals(1, createdCount(importCsv(MEMBERSHIPS_IMPORT, membershipCsv)),
                    "the membership passes once its card exists (K11)");
        } finally {
            deleteMembershipsOfCard(card);
            deleteAccount(card);
        }
    }

    // --------------------------------------------------
    // K12 — family import as complete-state replacement
    // --------------------------------------------------

    /**
     * K12 — the family import is a complete-state replacement: re-importing {@code F_L} with an
     * amputated EAN list removes the absent product links in base (the column is the whole
     * state), which is verified by reading the real state — counter-proving imvaluation's E4 bug
     * (an update counted but not persisted). Unknown products, sub-families and self-references
     * are refused with {@code Product EAN '<e>' not found.}, {@code SubFamily code '<c>' not
     * found.} and {@code Family '<c>' cannot contain itself.}.
     */
    @Test
    void k12_familyImportReplacesTheWholeState() {
        String selfRef = "ZZZ_K12";
        try {
            assertEquals(F_L_SEED_EANS, familyEans("F_L"), "the seeded F_L carries its four EANs (K12)");
            importCsv(FAMILIES_IMPORT, csv(FAMILY_HEADER, "F_L|Fruits & Légumes frais||3300000000001,3300000000014|"));
            assertEquals(Set.of("3300000000001", "3300000000014"), familyEans("F_L"),
                    "the amputated list removes the absent links in base (E4 counter-proof, K12)");
            Response badEan = importCsv(FAMILIES_IMPORT, csv(FAMILY_HEADER, "ZZZ_K12A|x||" + CARD_UNKNOWN + "|"));
            assertTrue(errorsContain(badEan, "Product EAN '" + CARD_UNKNOWN + "' not found."), "an unknown product EAN is refused (K12)");
            Response badSub = importCsv(FAMILIES_IMPORT, csv(FAMILY_HEADER, "ZZZ_K12B|x|||NOPE_FAM"));
            assertTrue(errorsContain(badSub, "SubFamily code 'NOPE_FAM' not found."), "an unknown sub-family is refused (K12)");
            Response selfCsv = importCsv(FAMILIES_IMPORT, csv(FAMILY_HEADER, selfRef + "|x|||" + selfRef));
            assertTrue(errorsContain(selfCsv, "Family '" + selfRef + "' cannot contain itself."), "a self-reference is refused (K12)");
        } finally {
            importCsv(FAMILIES_IMPORT, csv(FAMILY_HEADER,
                    "F_L|Fruits & Légumes frais||3300000000001,3300000000014,3300000000017,3300000000018|"));
            deleteFamily(selfRef);
            deleteFamily("ZZZ_K12A");
            deleteFamily("ZZZ_K12B");
        }
    }

    // --------------------------------------------------
    // Helpers — HTTP imports (Basic admin, raw text/plain)
    // --------------------------------------------------

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
     * Builds a pipe-delimited CSV body from a header line and its data rows.
     *
     * @param header The header line (always skipped by the importer).
     * @param rows   The data rows.
     * @return The newline-joined CSV body.
     */
    private static String csv(String header, String... rows) {
        return header + "\n" + String.join("\n", rows);
    }

    /**
     * Reads a classpath seed CSV and replays it against its endpoint, asserting no row error —
     * the "the pack replays clean" leg of K3.
     *
     * @param resource The classpath seed resource path.
     * @param path     The matching import endpoint path.
     */
    private void replayPassesWithoutErrors(String resource, String path) {
        String body = seedCsv(resource);
        Response report = importCsv(path, body);
        assertEquals(200, report.statusCode(), "replaying " + resource + " returns a 200 report (K3)");
        assertTrue(errors(report).isEmpty(), "replaying " + resource + " raises no row error (K3)");
    }

    /**
     * Reads a classpath seed CSV resource into a string.
     *
     * @param resource The classpath resource path.
     * @return The resource content as UTF-8 text.
     */
    private String seedCsv(String resource) {
        try (var in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "the seed resource " + resource + " must be on the classpath");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read seed resource " + resource, e);
        }
    }

    /**
     * Reads the {@code createdCount} of a parsed import report.
     *
     * @param report The import report response.
     * @return The created count.
     */
    private static int createdCount(Response report) {
        return report.jsonPath().getInt("createdCount");
    }

    /**
     * Reads the {@code updatedCount} of a parsed import report.
     *
     * @param report The import report response.
     * @return The updated count.
     */
    private static int updatedCount(Response report) {
        return report.jsonPath().getInt("updatedCount");
    }

    /**
     * Reads the {@code errors} array of a parsed import report, empty when the report carries
     * none — the parse itself proves the report is valid JSON (K7).
     *
     * @param report The import report response.
     * @return The list of row errors, never null.
     */
    private static List<String> errors(Response report) {
        List<String> list = report.jsonPath().getList("errors");
        return list == null ? List.of() : list;
    }

    /**
     * Tells whether a parsed report carries a row error containing the given substring.
     *
     * @param report    The import report response.
     * @param substring The substring to look for.
     * @return true when at least one error contains the substring.
     */
    private static boolean errorsContain(Response report, String substring) {
        return errors(report).stream().anyMatch(e -> e.contains(substring));
    }

    // --------------------------------------------------
    // Helpers — Playwright
    // --------------------------------------------------

    /**
     * The drag &amp; drop simulation script: it drops three synthetic files onto {@code
     * .import-drop} and returns, per drop, the {@code is-dragover} highlight seen on dragover,
     * the preselected domain and the placed-file count, joined {@code over|domain|files} and
     * separated by {@code @}. The unknown-name drop is preceded by forcing a distinct domain so
     * "unchanged" is observable.
     */
    private static final String DROP_SCRIPT =
            "() => {"
                    + "const zone = document.querySelector('.import-drop');"
                    + "const sel = zone.querySelector('select[name=\"domain\"]');"
                    + "const fileInput = zone.querySelector('input[type=\"file\"]');"
                    + "const drop = (name) => {"
                    + "  const dt = new DataTransfer();"
                    + "  dt.items.add(new File(['x'], name, {type:'text/csv'}));"
                    + "  zone.dispatchEvent(new DragEvent('dragover', {bubbles:true, cancelable:true, dataTransfer:dt}));"
                    + "  const over = zone.classList.contains('is-dragover');"
                    + "  zone.dispatchEvent(new DragEvent('drop', {bubbles:true, cancelable:true, dataTransfer:dt}));"
                    + "  return over + '|' + sel.value + '|' + fileInput.files.length;"
                    + "};"
                    + "const rules = drop('04-rules.csv');"
                    + "const families = drop('02-product-families.csv');"
                    + "sel.value = 'MEMBERSHIPS';"
                    + "const unknown = drop('totally-unknown.csv');"
                    + "return rules + '@' + families + '@' + unknown;"
                    + "}";

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
    // Helpers — database reads/writes (no absolute ids)
    // --------------------------------------------------

    /**
     * Reads a rule's {@code updated_at} in a fresh transaction.
     *
     * @param code The rule code.
     * @return The last-modification timestamp, or null when absent.
     */
    private static LocalDateTime ruleUpdatedAt(String code) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityRule rule = FidelityRule.findByCode(code);
            return rule == null ? null : rule.updatedAt;
        });
    }

    /**
     * Reads a rule's label in a fresh transaction.
     *
     * @param code The rule code.
     * @return The label, or null when absent.
     */
    private static String ruleLabel(String code) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityRule rule = FidelityRule.findByCode(code);
            return rule == null ? null : rule.label;
        });
    }

    /**
     * Reads a rule's type in a fresh transaction, the DB proof of the direct persistence path.
     *
     * @param code The rule code.
     * @return The rule type, or null when absent.
     */
    private static String ruleByCodeType(String code) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityRule rule = FidelityRule.findByCode(code);
            return rule == null ? null : rule.type;
        });
    }

    /**
     * Persists a deformed rule (an unknown, unvalidated type) directly, proving the direct path
     * never validates as the importer does (K8); the rule is inactive so it never participates.
     *
     * @param code The code of the deformed rule.
     */
    private static void persistDeformedRule(String code) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityRule rule = new FidelityRule();
            rule.code = code;
            rule.type = "ZZZ_TYPE_K8";
            rule.label = "Deformed direct rule";
            rule.specification = "{\"foo\":\"bar\"}";
            rule.validFrom = LocalDateTime.of(2026, 1, 1, 0, 0);
            rule.priority = 0;
            rule.exclusive = false;
            rule.active = false;
            rule.persist();
        });
    }

    /**
     * Deletes a rule by code in a fresh transaction; an unknown code is a no-op.
     *
     * @param code The rule code.
     */
    private static void deleteRule(String code) {
        QuarkusTransaction.requiringNew().run(() -> FidelityRule.delete("code", code));
    }

    /**
     * Reads an account's balance in a fresh transaction.
     *
     * @param card The card number.
     * @return The balance at scale 2.
     */
    private static BigDecimal balanceOf(String card) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityAccount.findByCardNumber(card).balance);
    }

    /**
     * Sets an account's balance in a fresh transaction, restoring the seeded value after a
     * ledger-writing scenario.
     *
     * @param card    The card number.
     * @param balance The balance to write.
     */
    private static void setBalance(String card, BigDecimal balance) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            if (account != null) {
                account.balance = balance;
            }
        });
    }

    /**
     * Tells whether an {@code ADJUSTMENT} movement exists for a ticket reference, in a fresh
     * transaction.
     *
     * @param ref The movement ticket reference.
     * @return true when the movement exists.
     */
    private static boolean adjustmentExists(String ref) {
        return QuarkusTransaction.requiringNew().call(() ->
                FidelityMovement.findByNaturalKey(ref, MovementType.ADJUSTMENT, null) != null);
    }

    /**
     * Deletes every movement carrying one of the given ticket references, in a fresh transaction.
     *
     * @param refs The ticket references to purge.
     */
    private static void deleteMovementsByRefs(List<String> refs) {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String ref : refs) {
                FidelityMovement.delete("ticketRef", ref);
            }
        });
    }

    /**
     * Reads a visit trace's status in a fresh transaction.
     *
     * @param ref The ticket reference.
     * @return The trace status, or null when absent.
     */
    private static String traceStatus(String ref) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EarnTrace trace = EarnTrace.findByTicketRef(ref);
            return trace == null ? null : trace.status;
        });
    }

    /**
     * Reads a visit trace's fiscal date in a fresh transaction.
     *
     * @param ref The ticket reference.
     * @return The fiscal date, or null when absent.
     */
    private static LocalDate traceFiscalDate(String ref) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EarnTrace trace = EarnTrace.findByTicketRef(ref);
            return trace == null ? null : trace.fiscalDate;
        });
    }

    /**
     * Finds a visit trace by ticket reference in a fresh transaction.
     *
     * @param ref The ticket reference.
     * @return The trace, or null when absent.
     */
    private static EarnTrace traceByRef(String ref) {
        return QuarkusTransaction.requiringNew().call(() -> EarnTrace.findByTicketRef(ref));
    }

    /**
     * Counts the visit traces carrying a ticket reference, in a fresh transaction.
     *
     * @param ref The ticket reference.
     * @return The trace count.
     */
    private static long traceCount(String ref) {
        return QuarkusTransaction.requiringNew().call(() -> EarnTrace.count("ticketRef", ref));
    }

    /**
     * Deletes a visit trace by ticket reference in a fresh transaction.
     *
     * @param ref The ticket reference.
     */
    private static void deleteTrace(String ref) {
        QuarkusTransaction.requiringNew().run(() -> EarnTrace.delete("ticketRef", ref));
    }

    /**
     * Reads an account's status name in a fresh transaction.
     *
     * @param card The card number.
     * @return The status name, or null when absent.
     */
    private static String accountStatus(String card) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            return account == null ? null : account.status.name();
        });
    }

    /**
     * Reads an account's {@code transferredToCard} in a fresh transaction.
     *
     * @param card The card number.
     * @return The transfer target, or null when absent.
     */
    private static String accountTransferredTo(String card) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            return account == null ? null : account.transferredToCard;
        });
    }

    /**
     * Reads every account card number in a fresh transaction — the before/after set diff pins
     * the generated card without an absolute id (K10).
     *
     * @return The set of all card numbers.
     */
    private static Set<String> allCardNumbers() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Set<String> cards = new HashSet<>();
            for (FidelityAccount account : FidelityAccount.<FidelityAccount>listAll()) {
                cards.add(account.cardNumber);
            }
            return cards;
        });
    }

    /**
     * Deletes an account by card number in a fresh transaction; a null or unknown number is a
     * no-op.
     *
     * @param card The card number, or null.
     */
    private static void deleteAccount(String card) {
        if (card == null) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> FidelityAccount.delete("cardNumber", card));
    }

    /**
     * Tells whether a membership exists on a card/community/start triple, in a fresh transaction.
     *
     * @param card      The card number.
     * @param community The community code.
     * @param validFrom The window start.
     * @return true when the membership exists.
     */
    private static boolean membershipExists(String card, String community, LocalDate validFrom) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            FidelityCommunity comm = FidelityCommunity.findByCode(community);
            if (account == null || comm == null) {
                return false;
            }
            return FidelityMembership.count("account = ?1 and community = ?2 and validFrom = ?3",
                    account, comm, validFrom) > 0;
        });
    }

    /**
     * Deletes every membership of a card in a fresh transaction; an unknown card is a no-op.
     *
     * @param card The card number.
     */
    private static void deleteMembershipsOfCard(String card) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            if (account != null) {
                FidelityMembership.delete("account", account);
            }
        });
    }

    /**
     * Reads a family's member product EANs in a fresh transaction, the real base state K12
     * asserts against.
     *
     * @param code The family code.
     * @return The set of product EANs, empty when the family is absent.
     */
    private static Set<String> familyEans(String code) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ProductFamily family = ProductFamily.findByCode(code);
            Set<String> eans = new HashSet<>();
            if (family != null) {
                family.products.forEach(product -> eans.add(product.ean));
            }
            return eans;
        });
    }

    /**
     * Deletes a family by code in a fresh transaction, first unlinking it so no association row
     * survives; a null or unknown code is a no-op.
     *
     * @param code The family code, or null.
     */
    private static void deleteFamily(String code) {
        if (code == null) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> {
            ProductFamily family = ProductFamily.findByCode(code);
            if (family != null) {
                family.products.clear();
                family.productFamilies.clear();
                family.delete();
            }
        });
    }

    /**
     * Verifies the EAN-13 check digit of a 13-digit number (§33.1): the first twelve digits
     * weighted 1 and 3 alternately from the left must complete, with the last digit, to a
     * multiple of ten.
     *
     * @param ean The 13-digit candidate.
     * @return true when the check digit is valid.
     */
    private static boolean isValidEan13(String ean) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = ean.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int check = (10 - (sum % 10)) % 10;
        return check == (ean.charAt(12) - '0');
    }
}
