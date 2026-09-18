package com.intermarche.e2e;

import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.EarnTraceLine;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.PendingReturn;
import com.intermarche.fidelity.domain.ReservationState;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group Q — the message &amp; surface inventory "relevé PAR LE CODE"
 * (e2escenarios-imfid.md "## Q"). Q is not a behaviour group but a cross-check that every
 * contractual literal the catalog freezes is really produced by the code, on the right
 * surface, at the right HTTP status. Each of its sub-sections becomes one (or, for the
 * broad admin-guard section, one per family) {@code @Test} carrying the Q-x id in its name
 * and Javadoc; every literal is triggered end to end against the real application booted by
 * {@link QuarkusTest} with the DataInitializer-rebuilt 2026 world (wipe + reload at each
 * boot) and asserted verbatim.
 * <p>
 * Surfaces and their auth: the POS API ({@code /api/earn}, {@code /api/events/*},
 * {@code /api/burn/*}, {@code /api/accounts/*}) over Basic {@code pos}; {@code /api/batches}
 * and {@code /graphql} and the CSV imports over Basic {@code admin}; the {@code /ui/*}
 * admin screens over a form session ({@code j_security_check} + {@code quarkus-credential}
 * cookie), the notice read from the un-followed 303 {@code Location} (following it would
 * drop the session cookie). The public auth screens ({@code /ui/login}, {@code /ui/forgot},
 * {@code /ui/reset}) need no session.
 * <p>
 * Q-C (administration guards) is the load-bearing subtlety: an {@code AdminException}
 * message is the literal, surfacing verbatim as {@code errors[0].message} on GraphQL and as
 * the {@code notice} on the UI; but the two channels do not expose the same operations —
 * {@code createRule}/{@code closeRule}/{@code duplicateRule}/card/{@code upsertMembership}/
 * {@code setActivation} live on GraphQL, while {@code updateRule}/{@code updateEndDate} and
 * every community-catalog mutation are UI-only. Each guard is therefore asserted on a
 * channel that actually reaches it, and the "transits both" contract is proven explicitly on
 * two shared literals ({@code Unknown rule type '…' (no factory deployed)} and
 * {@code Unknown card '…'}). Import row errors are a different class
 * ({@code IllegalArgumentException}) with deliberately different wording than their admin
 * twins (lower-case {@code unknown rule type '…'}, {@code Card '…' not found.}), so Q-D0
 * cross-checks them separately.
 * <p>
 * The seeded world is only read; every mutation targets isolated {@code ZZZ_Q_*} codes or
 * {@code 29900019*} test cards minted per test and purged in a {@code finally}, so the class
 * stays order-independent and never disturbs the nine seeded cards, eleven rules or four
 * communities. Every guard assertion drives a refused path (no write); the few helper rows a
 * proof needs are cleaned up. Time is frozen through {@link DateTimeProvider} so the "in the
 * past / not yet in force" rule guards are deterministic whatever day the campaign runs, and
 * every ticket reference carries a per-run suffix so no idempotent upsert is silently
 * absorbed. One justified residue: the boot line {@code Empty database detected — loading the
 * 2026 program seed (§24.4)} is the prod {@code SeedLoader} path and never fires under the
 * DataInitializer wipe+reload of the dev/test boot; Q-F asserts its absence and reports it as
 * a prod-only residue rather than a flaky assertion.
 */
@QuarkusTest
class GroupQIT {

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
     * The bootstrap administrator e-mail (SecurityBootstrap default), receiving reset links.
     */
    private static final String ADMIN_EMAIL = "admin@imfid.local";

    /**
     * The form-session cookie set by {@code j_security_check} for the admin UI.
     */
    private static final String SESSION_COOKIE = "quarkus-credential";

    /**
     * The frozen instant of the whole class: the "in the past / not yet in force" rule
     * guards are literal against it (§24.6).
     */
    private static final LocalDateTime FROZEN_AT = LocalDateTime.of(2026, 8, 22, 10, 0);

    /**
     * A seeded plain ACTIVE card, used as the neutral read/refused-write subject (…071).
     */
    private static final String CARD_PLAIN = "2990000000071";

    /**
     * A seeded ACTIVE card carrying a live 8.00 € lease on ticket {@code 0101-2026-003001} —
     * the active-reservation witness (…088).
     */
    private static final String CARD_RESERVED = "2990000000088";

    /**
     * A seeded PENDING_ACTIVATION card: accrues but cannot burn (…040).
     */
    private static final String CARD_PENDING = "2990000000040";

    /**
     * A seeded RESILIATED card: cannot burn, cannot be transferred (…095).
     */
    private static final String CARD_VOIDED = "2990000000095";

    /**
     * A seeded ACTIVE scratch card with a positive balance (…026).
     */
    private static final String CARD_SCRATCH = "2990000000026";

    /**
     * A seeded ACTIVE student card, a positive burnable balance (…033).
     */
    private static final String CARD_STUDENT = "2990000000033";

    /**
     * A card number absent from the seeded world, the unknown-card probe.
     */
    private static final String CARD_UNKNOWN = "9999999999999";

    /**
     * Milk 1L (Pâturages, socle), UNIT — a socle assiette line for the reference cart.
     */
    private static final String EAN_MILK = "3300000000002";

    /**
     * Mineral water 1.5L (Paquito, socle), UNIT — a socle assiette line.
     */
    private static final String EAN_WATER = "3300000000007";

    /**
     * Yoghurt 4x125g (Pâturages, socle), UNIT — a socle assiette line.
     */
    private static final String EAN_YAOURT = "3300000000010";

    /**
     * An EAN absent from the imfid product reference, for the UNKNOWN_EAN warning.
     */
    private static final String EAN_UNKNOWN = "3300000000404";

    /**
     * A valid {@code BRAND_TIERED_EARN} specification, reused wherever a rule import or
     * administration needs a type and schema that both validate against the registry (§12).
     */
    private static final String BRAND_SPEC =
            "{\"scope\":{\"include\":{\"brands\":[\"Pâturages\"]}},\"minEligibleItems\":3,"
                    + "\"baseRate\":0.05,\"boostedRate\":0.10,\"visitThreshold\":4}";

    /**
     * The rules CSV import endpoint (§18).
     */
    private static final String RULES_IMPORT = "/fidelity/rules/import";

    /**
     * The adjustments CSV import endpoint (§18, §32.1).
     */
    private static final String ADJ_IMPORT = "/fidelity/adjustments/import";

    /**
     * The memberships CSV import endpoint (§18).
     */
    private static final String MEMBERSHIPS_IMPORT = "/fidelity/memberships/import";

    /**
     * The activations CSV import endpoint (§18).
     */
    private static final String ACTIVATIONS_IMPORT = "/fidelity/activations/import";

    /**
     * The visits CSV import endpoint (§18, §29.1).
     */
    private static final String VISITS_IMPORT = "/fidelity/visits/import";

    /**
     * The product families CSV import endpoint (§18).
     */
    private static final String FAMILIES_IMPORT = "/product-families/import";

    /**
     * The rule CSV header line (columns resolved by name on every import).
     */
    private static final String RULE_HEADER =
            "CODE|TYPE|LABEL|VALID_FROM|VALID_TO|PRIORITY|EXCLUSIVE|MONTHLY_CAP_PER_CARD|ACTIVE|ADVANTAGE_TYPE|ADVANTAGE_CATEGORY|SPECIFICATION";

    /**
     * The adjustment CSV header line (columns resolved by name on every import).
     */
    private static final String ADJ_HEADER = "REFERENCE|CARD_NUMBER|AMOUNT|MOVEMENT_DATE|REASON";

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
     * Freezes the program clock before each scenario so the temporal rule guards are literal
     * whatever day the campaign runs (§24.6).
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
    // Q-A — POS API statuses and bodies
    // --------------------------------------------------

    /**
     * Q-A — the eleven POS-API status/body couples of the inventory: {@code /earn} with no
     * valuation response is a 400 {@code {"error":"Missing valuation response in /earn
     * request"}}; a broken totals reconciliation and a broken offer reconciliation are 422
     * with the frozen {@code Incoherent totals: totalPrice …} and {@code Offer '<type>': Σ
     * items …} prefixes; an unknown card {@code /earn} is a 200 empty earn (never an error);
     * the three typed reservation refusals are 422 {@code {"reason":"INSUFFICIENT_BALANCE"}},
     * {@code DAILY_RULE}, {@code ACCOUNT_STATUS}; a lease on another ticket is a 409 with an
     * empty body; a confirm on an expired lease is a 410 with an empty body; {@code
     * ticket-closed} without a ref is a 400 {@code {"error":"Missing ticketRef"}} and {@code
     * ticket-return} missing refs a 400 {@code {"error":"Missing returnTicketRef or
     * originTicketRef"}}; an unknown {@code /api/accounts} card is a 404 {@code
     * {"error":"Unknown card"}}; and an unknown {@code /api/batches} type is a 400 {@code
     * {"error":"Unknown batch type '<t>'"}}.
     */
    @Test
    void qA_posApiStatusesAndBodies() {
        Response missing = postEarn("{\"valuationRequest\":{\"customerCode\":\"" + CARD_PLAIN + "\",\"storeCode\":\"0101\"}}");
        assertEquals(400, missing.statusCode(), "a body without a valuation response must be a 400 (Q-A)");
        assertEquals("Missing valuation response in /earn request", missing.jsonPath().getString("error"),
                "the 400 must carry the frozen missing-response literal (Q-A)");
        Response totals = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00", socleOffers(), "5.00"));
        assertEquals(422, totals.statusCode(), "an incoherent global total must be a 422 (Q-A)");
        assertTrue(totals.jsonPath().getString("error").startsWith("Incoherent totals: totalPrice "),
                "the totals 422 must carry the frozen Incoherent-totals prefix (Q-A)");
        String badOfferType = "MixedBundle: PROMO_Q x1 for 4.50€";
        String badOffer = offerObject(badOfferType, "4.50",
                offerItem("L1", EAN_MILK, "1.0", "2.86") + "," + offerItem("L2", EAN_WATER, "1.0", "0.50"));
        Response offer = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00", List.of(badOffer), "4.50"));
        assertEquals(422, offer.statusCode(), "an offer whose items do not sum to its amount must be a 422 (Q-A)");
        assertTrue(offer.jsonPath().getString("error").startsWith("Offer '" + badOfferType + "': Σ items "),
                "the offer 422 must carry the frozen Offer prefix (Q-A)");
        Response unknownCard = postEarn(earnBody(CARD_UNKNOWN, "2026-08-17T10:00:00", socleOffers(), "6.00"));
        assertEquals(200, unknownCard.statusCode(), "an unknown card /earn must be a 200 empty earn, never an error (Q-A)");
        assertTrue(unknownCard.jsonPath().getList("entries").isEmpty(), "the unknown-card earn must be empty (Q-A)");
        Response insufficient = reserve(CARD_STUDENT, "999.00", "0101-2026-QA-INS-" + suffix());
        assertEquals(422, insufficient.statusCode(), "an amount over the available balance must be a 422 (Q-A)");
        assertEquals("INSUFFICIENT_BALANCE", insufficient.jsonPath().getString("reason"),
                "the over-available refusal must carry INSUFFICIENT_BALANCE (Q-A)");
        String burnTicket = "0101-2026-QA-BURN-" + suffix();
        insertBurnToday(CARD_SCRATCH, burnTicket);
        try {
            Response daily = reserve(CARD_SCRATCH, "1.00", "0101-2026-QA-DAILY-" + suffix());
            assertEquals(422, daily.statusCode(), "a card that already burned today must be a 422 (Q-A)");
            assertEquals("DAILY_RULE", daily.jsonPath().getString("reason"),
                    "the second burn of the fiscal day must carry DAILY_RULE (Q-A)");
        } finally {
            deleteMovements(burnTicket);
        }
        Response pending = reserve(CARD_PENDING, "1.00", "0101-2026-QA-PEND-" + suffix());
        assertEquals(422, pending.statusCode(), "a PENDING_ACTIVATION card must be a 422 (Q-A)");
        assertEquals("ACCOUNT_STATUS", pending.jsonPath().getString("reason"),
                "a pending account cannot burn — ACCOUNT_STATUS (Q-A)");
        Response conflict = reserve(CARD_RESERVED, "1.00", "0101-2026-QA-CONF-" + suffix());
        assertEquals(409, conflict.statusCode(), "a live lease on another ticket must be a 409 (Q-A)");
        assertTrue(conflict.body().asString().isEmpty(), "the 409 must carry an empty body (Q-A)");
        String expiredTicket = "0101-2026-QA-EXP-" + suffix();
        cleanReservations(CARD_PLAIN);
        long expiredId = insertReservation(CARD_PLAIN, "3.00", expiredTicket,
                DateTimeProvider.now().minusMinutes(1), ReservationState.ACTIVE);
        try {
            Response gone = confirm(expiredId, "2026-08-17");
            assertEquals(410, gone.statusCode(), "confirming an expired lease must be a 410 (Q-A)");
            assertTrue(gone.body().asString().isEmpty(), "the 410 must carry an empty body (Q-A)");
        } finally {
            cleanReservations(CARD_PLAIN);
        }
        Response noRef = postClosed("{\"card\":\"" + CARD_PLAIN + "\"}");
        assertEquals(400, noRef.statusCode(), "a ticket-closed without a ref must be a 400 (Q-A)");
        assertEquals("Missing ticketRef", noRef.jsonPath().getString("error"),
                "the ticket-closed 400 must carry the Missing-ticketRef literal (Q-A)");
        Response noOrigin = postReturn("{\"returnTicketRef\":\"0101-2026-QA-RET\"}");
        assertEquals(400, noOrigin.statusCode(), "a ticket-return missing the origin must be a 400 (Q-A)");
        assertEquals("Missing returnTicketRef or originTicketRef", noOrigin.jsonPath().getString("error"),
                "the ticket-return 400 must carry the missing-refs literal (Q-A)");
        Response noReturn = postReturn("{\"originTicketRef\":\"0101-2026-QA-ORIG\"}");
        assertEquals(400, noReturn.statusCode(), "a ticket-return missing the return ref must be a 400 (Q-A)");
        assertEquals("Missing returnTicketRef or originTicketRef", noReturn.jsonPath().getString("error"),
                "the ticket-return 400 must carry the missing-refs literal for the mirror branch (Q-A)");
        Response account = getAccount(CARD_UNKNOWN);
        assertEquals(404, account.statusCode(), "an unknown /api/accounts card must be a 404 (Q-A)");
        assertEquals("Unknown card", account.jsonPath().getString("error"),
                "the account 404 must carry the Unknown-card literal (Q-A)");
        Response batch = postBatchAdmin("NOPE");
        assertEquals(400, batch.statusCode(), "an unknown /api/batches type must be a 400 (Q-A)");
        assertEquals("Unknown batch type 'NOPE'", batch.jsonPath().getString("error"),
                "the batch 400 must carry the Unknown-batch-type literal (Q-A)");
    }

    // --------------------------------------------------
    // Q-B — the closed warning nomenclature and its trace format
    // --------------------------------------------------

    /**
     * Q-B — the five-code closed warning nomenclature and its two formats: only {@code
     * UNKNOWN_EAN} is ever projected on {@code /earn} (carried with its EAN and the §25.4
     * literal message); the four lifecycle/drift codes ({@code EARN_MISMATCH}, {@code
     * EXPIRED_LEASE_CONFIRMED}, {@code RESILIATED_ACCOUNT}, {@code CARD_MISMATCH}) live only
     * on the persisted ingestion trace, as bare {@code CODE}, while a reading warning is
     * flattened as {@code CODE:<ean>}. Each of the five is triggered and read from its
     * surface: {@code UNKNOWN_EAN} on the projection response and, as {@code
     * UNKNOWN_EAN:<ean>}, on a trace; {@code CARD_MISMATCH}, {@code EARN_MISMATCH} and {@code
     * RESILIATED_ACCOUNT} on their traces; {@code EXPIRED_LEASE_CONFIRMED} on the burn
     * catch-up trace. Every trace and movement a proof creates is removed afterwards.
     */
    @Test
    void qB_warningsClosedNomenclatureAndTraceFormat() {
        Response projection = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00",
                List.of(standardOffer("L1", EAN_UNKNOWN, "1.0", "5.00")), "5.00"));
        assertEquals(200, projection.statusCode(), "an unknown EAN never fails the projection (Q-B)");
        Map<String, ?> warning = projection.jsonPath().getMap("warnings.find { it.code == 'UNKNOWN_EAN' }");
        assertNotNull(warning, "the projection must raise the UNKNOWN_EAN warning (Q-B)");
        assertEquals(EAN_UNKNOWN, warning.get("ean"), "the projection warning carries the offending EAN (Q-B)");
        assertEquals("EAN " + EAN_UNKNOWN + " unknown to the imfid product reference; line kept out of every "
                        + "earn assiette but inside the burnable base (§25.4)", warning.get("message"),
                "the projection warning carries the frozen §25.4 literal (Q-B)");
        List<Map<String, Object>> projectionWarnings = projection.jsonPath().getList("warnings");
        for (Map<String, Object> w : projectionWarnings) {
            assertEquals("UNKNOWN_EAN", w.get("code"), "the projection only ever raises UNKNOWN_EAN (Q-B)");
        }
        String eanTicket = "0101-2026-QB-EAN-" + suffix();
        try {
            assertEquals(202, postClosed(closedBody(eanTicket, CARD_PLAIN, CARD_PLAIN, "2026-08-17",
                    List.of(standardOffer("L1", EAN_UNKNOWN, "1.0", "5.00")), "5.00", null)).statusCode(),
                    "the ingestion of an unknown-EAN ticket is accepted (202, Q-B)");
            assertTrue(traceWarnings(eanTicket).contains("UNKNOWN_EAN:" + EAN_UNKNOWN),
                    "a reading warning is flattened as CODE:<ean> on the trace (Q-B)");
        } finally {
            deleteTrace(eanTicket);
            deleteMovements(eanTicket);
        }
        String mismatchTicket = "0101-2026-QB-CARD-" + suffix();
        try {
            assertEquals(202, postClosed(closedBody(mismatchTicket, CARD_STUDENT, CARD_PLAIN, "2026-08-17",
                    socleOffers(), "6.00", null)).statusCode(), "the card-mismatch ticket is accepted (202, Q-B)");
            assertTrue(traceWarnings(mismatchTicket).contains("CARD_MISMATCH"),
                    "an event card disagreeing with the request card traces a bare CARD_MISMATCH (Q-B)");
        } finally {
            deleteTrace(mismatchTicket);
            deleteMovements(mismatchTicket);
        }
        String earnTicket = "0101-2026-QB-EARN-" + suffix();
        try {
            assertEquals(202, postClosed(closedBody(earnTicket, CARD_PLAIN, CARD_PLAIN, "2026-08-17",
                    socleOffers(), "6.00", displayedEarn("SOCLE_5_MARQUES", "9.99"))).statusCode(),
                    "the earn-mismatch ticket is accepted (202, Q-B)");
            assertTrue(traceWarnings(earnTicket).contains("EARN_MISMATCH"),
                    "a displayed earn disagreeing with the recalc traces a bare EARN_MISMATCH (Q-B)");
        } finally {
            deleteTrace(earnTicket);
            deleteMovements(earnTicket);
        }
        String voidedTicket = "0101-2026-QB-RESIL-" + suffix();
        try {
            assertEquals(202, postClosed(closedBody(voidedTicket, CARD_VOIDED, CARD_VOIDED, "2026-08-17",
                    socleOffers(), "6.00", null)).statusCode(), "the resiliated-account ticket is accepted (202, Q-B)");
            assertTrue(traceWarnings(voidedTicket).contains("RESILIATED_ACCOUNT"),
                    "a ticket on a resiliated account traces a bare RESILIATED_ACCOUNT (Q-B)");
        } finally {
            deleteTrace(voidedTicket);
            deleteMovements(voidedTicket);
        }
        String leaseTicket = "0101-2026-QB-LEASE-" + suffix();
        String eventTicket = "0101-2026-QB-EXPEV-" + suffix();
        cleanReservations(CARD_PLAIN);
        long leaseId = insertReservation(CARD_PLAIN, "4.00", leaseTicket,
                DateTimeProvider.now().minusMinutes(1), ReservationState.ACTIVE);
        try {
            assertEquals(410, confirm(leaseId, "2026-08-17").statusCode(),
                    "the synchronous confirm on the expired lease is a 410 (Q-B)");
            assertEquals(202, ingestClosedWithReservation(eventTicket, CARD_PLAIN, LocalDate.of(2026, 8, 17), leaseId).statusCode(),
                    "the catch-up ticket-closed is accepted (202, Q-B)");
            assertTrue(traceWarnings(eventTicket).contains("EXPIRED_LEASE_CONFIRMED"),
                    "the burn catch-up traces a bare EXPIRED_LEASE_CONFIRMED (Q-B)");
        } finally {
            deleteTrace(eventTicket);
            deleteMovements(leaseTicket);
            deleteMovements(eventTicket);
            cleanReservations(CARD_PLAIN);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // Q-C — administration guards (rules)
    // --------------------------------------------------

    /**
     * Q-C rules — every rule {@code AdminException} literal, on a channel that reaches it.
     * On GraphQL: {@code Unknown rule type 'NOPE' (no factory deployed)}, {@code Invalid
     * specification for type 'BRAND_TIERED_EARN': …}, {@code No rule with code 'ZZZ_Q_NONE'}
     * (unknown duplicate source), {@code A rule with code 'ZZZ_Q_DUP_DST' already exists},
     * {@code No open rule with code 'ZZZ_Q_NONE'}, {@code A rule is closed with a validTo not
     * in the past (§18)}. On the UI (the channel that owns {@code updateRule}/{@code
     * updateEndDate}): {@code validFrom is mandatory}, {@code Rule window overlaps an existing
     * instance of code 'ZZZ_Q_OVL'}, {@code Only a rule not yet in force can be edited;
     * version it instead (§18)}, {@code An edited rule cannot start in the past (§18)}, {@code
     * No rule with code 'ZZZ_Q_NONE' whose window is still open; an ended rule is frozen —
     * version it instead (§18)}, {@code The end of application can never be set in the past
     * (§18)}.
     * {@code Unknown rule type} is additionally asserted on the UI to prove an {@code
     * AdminException} transits both channels verbatim. Every helper rule is isolated and
     * purged in a {@code finally}.
     * <p>
     * Justified residue: {@code updateEndDate}'s {@code The new window would overlap another
     * instance of code '<c>'} is structurally unreachable — {@code FidelityRule.code} is
     * {@code @Column(unique = true)}, so a code never carries a second instance for the
     * overlap loop to find; the branch cannot fire without a {@code src/main} change (out of
     * scope).
     */
    @Test
    void qC_ruleAdministrationGuards() {
        try {
            assertEquals("Unknown rule type 'NOPE' (no factory deployed)",
                    graphqlError("mutation { createRule(code: \"ZZZ_Q_NOPE\", type: \"NOPE\", label: \"x\","
                            + " priority: 1, exclusive: false, active: true, specification: \"{}\") { code } }"),
                    "GraphQL createRule with an unknown type surfaces its literal (Q-C)");
            assertTrue(graphqlError("mutation { createRule(code: \"ZZZ_Q_BAD\", type: \"BRAND_TIERED_EARN\", label: \"x\","
                            + " priority: 1, exclusive: false, active: true, specification: \"{}\") { code } }")
                            .startsWith("Invalid specification for type 'BRAND_TIERED_EARN': "),
                    "GraphQL createRule with a schema-invalid spec surfaces the frozen prefix (Q-C)");
            assertEquals("No rule with code 'ZZZ_Q_NONE'",
                    graphqlError("mutation { duplicateRule(sourceCode: \"ZZZ_Q_NONE\", newCode: \"ZZZ_Q_X\") { code } }"),
                    "GraphQL duplicateRule with an unknown source surfaces its literal (Q-C)");
            persistRule("ZZZ_Q_DUP_SRC", "BRAND_TIERED_EARN", LocalDateTime.of(2026, 1, 1, 0, 0), null, BRAND_SPEC);
            persistRule("ZZZ_Q_DUP_DST", "BRAND_TIERED_EARN", LocalDateTime.of(2026, 1, 1, 0, 0), null, BRAND_SPEC);
            assertEquals("A rule with code 'ZZZ_Q_DUP_DST' already exists",
                    graphqlError("mutation { duplicateRule(sourceCode: \"ZZZ_Q_DUP_SRC\", newCode: \"ZZZ_Q_DUP_DST\") { code } }"),
                    "GraphQL duplicateRule to a taken code surfaces its literal (Q-C)");
            assertEquals("No open rule with code 'ZZZ_Q_NONE'",
                    graphqlError("mutation { closeRule(code: \"ZZZ_Q_NONE\", validTo: \"2027-01-01T00:00:00\") { code } }"),
                    "GraphQL closeRule on a missing code surfaces its literal (Q-C)");
            persistRule("ZZZ_Q_CLOSE", "BRAND_TIERED_EARN", LocalDateTime.of(2026, 1, 1, 0, 0), null, BRAND_SPEC);
            assertEquals("A rule is closed with a validTo not in the past (§18)",
                    graphqlError("mutation { closeRule(code: \"ZZZ_Q_CLOSE\", validTo: \"2020-01-01T00:00:00\") { code } }"),
                    "GraphQL closeRule with a past validTo surfaces its literal (Q-C)");
            assertEquals("Unknown rule type 'NOPE' (no factory deployed)",
                    noticeOf(postForm("/ui/rules/create", "code", "ZZZ_Q_UITYPE", "type", "NOPE", "label", "x",
                            "validFrom", "2027-01-01", "specification", "{}").header("Location")),
                    "the UI create surfaces the same Unknown-rule-type literal — AdminException transits both (Q-C)");
            assertEquals("validFrom is mandatory",
                    noticeOf(postForm("/ui/rules/create", "code", "ZZZ_Q_NOVF", "type", "BRAND_TIERED_EARN", "label", "x",
                            "validFrom", "", "specification", BRAND_SPEC).header("Location")),
                    "the UI create with no validFrom surfaces the mandatory literal (Q-C)");
            persistRule("ZZZ_Q_OVL", "BRAND_TIERED_EARN", LocalDateTime.of(2026, 1, 1, 0, 0), null, BRAND_SPEC);
            assertEquals("Rule window overlaps an existing instance of code 'ZZZ_Q_OVL'",
                    noticeOf(postForm("/ui/rules/create", "code", "ZZZ_Q_OVL", "type", "BRAND_TIERED_EARN", "label", "x",
                            "validFrom", "2026-06-01", "specification", BRAND_SPEC).header("Location")),
                    "the UI create with an overlapping window surfaces the overlap literal (Q-C)");
            persistRule("ZZZ_Q_INF", "BRAND_TIERED_EARN", LocalDateTime.of(2026, 1, 1, 0, 0), null, BRAND_SPEC);
            assertEquals("Only a rule not yet in force can be edited; version it instead (§18)",
                    noticeOf(postForm("/ui/rules/ZZZ_Q_INF/update", "type", "BRAND_TIERED_EARN", "label", "x",
                            "validFrom", "2027-01-01", "specification", BRAND_SPEC).header("Location")),
                    "the UI update of an in-force rule surfaces the not-yet-in-force literal (Q-C)");
            persistRule("ZZZ_Q_UPC", "BRAND_TIERED_EARN", LocalDateTime.of(2027, 1, 1, 0, 0), null, BRAND_SPEC);
            assertEquals("An edited rule cannot start in the past (§18)",
                    noticeOf(postForm("/ui/rules/ZZZ_Q_UPC/update", "type", "BRAND_TIERED_EARN", "label", "x",
                            "validFrom", "2020-01-01", "specification", BRAND_SPEC).header("Location")),
                    "the UI update of an upcoming rule to a past start surfaces its literal (Q-C)");
            assertEquals("No rule with code 'ZZZ_Q_NONE' whose window is still open; an ended rule is frozen "
                            + "— version it instead (§18)",
                    noticeOf(postForm("/ui/rules/ZZZ_Q_NONE/end-date", "validTo", "2027-01-01").header("Location")),
                    "the UI end-date on a missing open rule surfaces the frozen literal (Q-C)");
            persistRule("ZZZ_Q_END", "BRAND_TIERED_EARN", LocalDateTime.of(2026, 1, 1, 0, 0), null, BRAND_SPEC);
            assertEquals("The end of application can never be set in the past (§18)",
                    noticeOf(postForm("/ui/rules/ZZZ_Q_END/end-date", "validTo", "2020-01-01").header("Location")),
                    "the UI end-date in the past surfaces its literal (Q-C)");
        } finally {
            deleteRulesLike("ZZZ_Q_%");
        }
    }

    // --------------------------------------------------
    // Q-C — administration guards (cards)
    // --------------------------------------------------

    /**
     * Q-C cards — every card {@code AdminException} literal: {@code An adjustment reason is
     * mandatory (§32.1)} and {@code An adjustment amount is mandatory} on {@code adjustCard};
     * {@code A resiliated card cannot be transferred} on {@code transferCard} of the seeded
     * RESILIATED card; {@code Refused: the card holds an active burn reservation (§28.1)} on
     * {@code resiliateCard} of the seeded card holding a live lease; and {@code Unknown card
     * '9999999999999'} on any card gesture. {@code Unknown card} is asserted on both channels
     * (GraphQL {@code resiliateCard} and the UI resiliate) to prove the {@code AdminException}
     * transits both. Every gesture drives a refused path — no card is written.
     */
    @Test
    void qC_cardAdministrationGuards() {
        assertEquals("An adjustment reason is mandatory (§32.1)",
                graphqlError("mutation { adjustCard(card: \"" + CARD_PLAIN + "\", amount: 5.00, reason: \"\") { cardNumber } }"),
                "GraphQL adjustCard with a blank reason surfaces its literal (Q-C)");
        assertEquals("An adjustment amount is mandatory",
                graphqlError("mutation { adjustCard(card: \"" + CARD_PLAIN + "\", amount: 0, reason: \"x\") { cardNumber } }"),
                "GraphQL adjustCard with a zero amount surfaces its literal (Q-C)");
        assertEquals("A resiliated card cannot be transferred",
                graphqlError("mutation { transferCard(fromCard: \"" + CARD_VOIDED + "\") { cardNumber } }"),
                "GraphQL transferCard on a resiliated card surfaces its literal (Q-C)");
        assertEquals("Refused: the card holds an active burn reservation (§28.1)",
                graphqlError("mutation { resiliateCard(card: \"" + CARD_RESERVED + "\") { cardNumber } }"),
                "GraphQL resiliateCard on a card holding a live lease surfaces its literal (Q-C)");
        assertEquals("Unknown card '" + CARD_UNKNOWN + "'",
                graphqlError("mutation { resiliateCard(card: \"" + CARD_UNKNOWN + "\") { cardNumber } }"),
                "GraphQL resiliateCard on an unknown card surfaces its literal (Q-C)");
        assertEquals("Unknown card '" + CARD_UNKNOWN + "'",
                noticeOf(postForm("/ui/cards/" + CARD_UNKNOWN + "/resiliate").header("Location")),
                "the UI resiliate surfaces the same Unknown-card literal — AdminException transits both (Q-C)");
        assertEquals("An adjustment reason is mandatory (§32.1)",
                noticeOf(postForm("/ui/cards/" + CARD_PLAIN + "/adjust", "amount", "5.00", "reason", "").header("Location")),
                "the UI adjust with a blank reason surfaces its literal (Q-C)");
    }

    // --------------------------------------------------
    // Q-C — administration guards (communities)
    // --------------------------------------------------

    /**
     * Q-C communities — the community-catalog guards (UI-only) and the membership/activation
     * guards (GraphQL): {@code A community with code 'BABIES' already exists}, {@code Unknown
     * community 'ZZZ_Q_NONE'}, {@code The monthly cap cannot be negative}, {@code The
     * enrollment cap cannot be negative}, {@code A renewal window needs both its start and end
     * months}, {@code A renewal month must be between 1 and 12} on the UI create/update; and,
     * on GraphQL {@code upsertMembership}/{@code setActivation}: {@code Community
     * 'ZZZ_Q_CLOSED' is closed to new enrollments (§23.2)}, {@code Enrollment cap reached for
     * community 'ZZZ_Q_CAP' (0, §28.4)}, {@code Membership validFrom is mandatory}; and, on
     * the UI card activation, {@code Activation periodStart is mandatory}. Every guard drives a
     * refused path; the two helper communities are isolated and purged.
     */
    @Test
    void qC_communityAdministrationGuards() {
        try {
            assertEquals("A community with code 'BABIES' already exists",
                    noticeOf(postForm("/ui/communities/create", "code", "BABIES", "label", "x").header("Location")),
                    "the UI create of an existing community surfaces its literal (Q-C)");
            assertEquals("Unknown community 'ZZZ_Q_NONE'",
                    noticeOf(postForm("/ui/communities/ZZZ_Q_NONE/update", "label", "x").header("Location")),
                    "the UI update of a missing community surfaces its literal (Q-C)");
            assertEquals("The monthly cap cannot be negative",
                    noticeOf(postForm("/ui/communities/create", "code", "ZZZ_Q_NEG1", "label", "x",
                            "monthlyCap", "-1").header("Location")),
                    "a negative monthly cap surfaces its literal (Q-C)");
            assertEquals("The enrollment cap cannot be negative",
                    noticeOf(postForm("/ui/communities/create", "code", "ZZZ_Q_NEG2", "label", "x",
                            "enrollmentCap", "-1").header("Location")),
                    "a negative enrollment cap surfaces its literal (Q-C)");
            assertEquals("A renewal window needs both its start and end months",
                    noticeOf(postForm("/ui/communities/create", "code", "ZZZ_Q_REN1", "label", "x",
                            "renewalStartMonth", "1", "renewalEndMonth", "").header("Location")),
                    "a half renewal window surfaces its literal (Q-C)");
            assertEquals("A renewal month must be between 1 and 12",
                    noticeOf(postForm("/ui/communities/create", "code", "ZZZ_Q_REN2", "label", "x",
                            "renewalStartMonth", "13", "renewalEndMonth", "2").header("Location")),
                    "an out-of-range renewal month surfaces its literal (Q-C)");
            persistCommunity("ZZZ_Q_CLOSED", false, null);
            assertEquals("Community 'ZZZ_Q_CLOSED' is closed to new enrollments (§23.2)",
                    graphqlError("mutation { upsertMembership(card: \"" + CARD_PLAIN + "\", community: \"ZZZ_Q_CLOSED\","
                            + " validFrom: \"2026-09-01\") { card } }"),
                    "GraphQL upsertMembership on a closed community surfaces its literal (Q-C)");
            persistCommunity("ZZZ_Q_CAP", true, 0);
            assertEquals("Enrollment cap reached for community 'ZZZ_Q_CAP' (0, §28.4)",
                    graphqlError("mutation { upsertMembership(card: \"" + CARD_PLAIN + "\", community: \"ZZZ_Q_CAP\","
                            + " validFrom: \"2026-09-01\") { card } }"),
                    "GraphQL upsertMembership over a full community surfaces its literal (Q-C)");
            assertEquals("Membership validFrom is mandatory",
                    graphqlError("mutation { upsertMembership(card: \"" + CARD_PLAIN + "\", community: \"BABIES\") { card } }"),
                    "GraphQL upsertMembership with no validFrom surfaces its literal (Q-C)");
            assertEquals("Activation periodStart is mandatory",
                    noticeOf(postForm("/ui/cards/" + CARD_PLAIN + "/activation", "ruleCode", "ZZZ_Q_RULE",
                            "periodStart", "").header("Location")),
                    "the UI activation with no periodStart surfaces its literal (Q-C)");
        } finally {
            deleteCommunity("ZZZ_Q_CLOSED");
            deleteCommunity("ZZZ_Q_CAP");
        }
    }

    // --------------------------------------------------
    // Q-D0 — import line errors per resource
    // --------------------------------------------------

    /**
     * Q-D0 — the CSV import row-error literals per resource, distinct in wording from their
     * admin twins ({@code IllegalArgumentException}, not {@code AdminException}). Rules:
     * {@code missing rule type}, {@code unknown rule type 'ZZZ_TYPE_Q'}, {@code specification
     * invalid for type 'BRAND_TIERED_EARN':}, {@code missing or malformed validFrom (ISO
     * date-time)}. Adjustments: {@code amount is mandatory.}, {@code movementDate is
     * mandatory.}, {@code reason is mandatory for an ADJUSTMENT (§32.1).}, {@code Card
     * '9999999999999' not found.}. Memberships: {@code Card '…' not found.}, {@code Community
     * 'NOPE_COMM' not found.}, {@code validFrom is mandatory.}. Activations: {@code Card '…'
     * not found.}, {@code ruleCode is mandatory.}, {@code periodStart is mandatory.}. Visits:
     * {@code cardNumber is mandatory for a visit (§29.1).}, {@code fiscalDate is mandatory.}.
     * Families: {@code Product EAN '9999999999999' not found.}, {@code SubFamily code
     * 'NOPE_FAM' not found.}, {@code Family 'ZZZ_Q_FAM_SELF' cannot contain itself.}. The
     * mechanic report is asserted to be valid JSON ({@code createdCount}/{@code
     * updatedCount}/{@code errors}) by parsing it. Every row is a refusal (nothing created);
     * the family helper codes are purged defensively.
     */
    @Test
    void qD0_importLineErrorsPerResource() {
        try {
            Response rules = importCsv(RULES_IMPORT, csv(RULE_HEADER,
                    "ZZZ_Q_MISS||No type|2026-01-01T00:00:00||0|false||false|PRODUCT||" + BRAND_SPEC,
                    "ZZZ_Q_UNK|ZZZ_TYPE_Q|x|2026-01-01T00:00:00||0|false||false|PRODUCT||" + BRAND_SPEC,
                    "ZZZ_Q_BAD|BRAND_TIERED_EARN|x|2026-01-01T00:00:00||0|false||false|PRODUCT||{}",
                    "ZZZ_Q_NODATE|BRAND_TIERED_EARN|x|not-a-date||0|false||false|PRODUCT||" + BRAND_SPEC));
            assertEquals(200, rules.statusCode(), "a raw CSV import returns a 200 report (Q-D0)");
            assertEquals(0, rules.jsonPath().getInt("createdCount"), "no invalid rule row is created (Q-D0)");
            assertTrue(errorsContain(rules, "missing rule type"), "a missing rule type is refused (Q-D0)");
            assertTrue(errorsContain(rules, "unknown rule type 'ZZZ_TYPE_Q'"), "an unknown rule type is refused (Q-D0)");
            assertTrue(errorsContain(rules, "specification invalid for type 'BRAND_TIERED_EARN':"),
                    "a schema-invalid specification is refused (Q-D0)");
            assertTrue(errorsContain(rules, "missing or malformed validFrom (ISO date-time)"),
                    "a malformed validFrom is refused (Q-D0)");
            assertNotNull(rules.jsonPath().getList("errors"), "the mechanic report parses as valid JSON (Q-D0)");
            Response adjustments = importCsv(ADJ_IMPORT, csv(ADJ_HEADER,
                    "Q-NOAMOUNT|" + CARD_PLAIN + "||2026-08-01|reason",
                    "Q-NODATE|" + CARD_PLAIN + "|5.00||reason",
                    "Q-NOREASON|" + CARD_PLAIN + "|5.00|2026-08-01|",
                    "Q-NOCARD|" + CARD_UNKNOWN + "|5.00|2026-08-01|reason"));
            assertTrue(errorsContain(adjustments, "amount is mandatory."), "a missing amount is refused (Q-D0)");
            assertTrue(errorsContain(adjustments, "movementDate is mandatory."), "a missing movement date is refused (Q-D0)");
            assertTrue(errorsContain(adjustments, "reason is mandatory for an ADJUSTMENT (§32.1)."),
                    "a missing reason is refused (Q-D0)");
            assertTrue(errorsContain(adjustments, "Card '" + CARD_UNKNOWN + "' not found."),
                    "an unknown card is refused with the import wording (Q-D0)");
            Response memberships = importCsv(MEMBERSHIPS_IMPORT, csv(MEMBERSHIP_HEADER,
                    CARD_UNKNOWN + "|BABIES|2026-02-10|",
                    CARD_PLAIN + "|NOPE_COMM|2026-02-10|",
                    CARD_PLAIN + "|BABIES||"));
            assertTrue(errorsContain(memberships, "Card '" + CARD_UNKNOWN + "' not found."), "a missing card is refused (Q-D0)");
            assertTrue(errorsContain(memberships, "Community 'NOPE_COMM' not found."), "a missing community is refused (Q-D0)");
            assertTrue(errorsContain(memberships, "validFrom is mandatory."), "a missing validFrom is refused (Q-D0)");
            Response activations = importCsv(ACTIVATIONS_IMPORT, csv(ACTIVATION_HEADER,
                    CARD_UNKNOWN + "|ZZZ_Q_RULE|2026-02-10||false",
                    CARD_PLAIN + "||2026-02-10||false",
                    CARD_PLAIN + "|ZZZ_Q_RULE|||false"));
            assertTrue(errorsContain(activations, "Card '" + CARD_UNKNOWN + "' not found."), "a missing card is refused (Q-D0)");
            assertTrue(errorsContain(activations, "ruleCode is mandatory."), "a missing ruleCode is refused (Q-D0)");
            assertTrue(errorsContain(activations, "periodStart is mandatory."), "a missing periodStart is refused (Q-D0)");
            Response visits = importCsv(VISITS_IMPORT, csv(VISIT_HEADER,
                    "Q-V1||0101|2026-08-03",
                    "Q-V2|" + CARD_PLAIN + "|0101|"));
            assertTrue(errorsContain(visits, "cardNumber is mandatory for a visit (§29.1)."), "a missing cardNumber is refused (Q-D0)");
            assertTrue(errorsContain(visits, "fiscalDate is mandatory."), "a missing fiscalDate is refused (Q-D0)");
            Response families = importCsv(FAMILIES_IMPORT, csv(FAMILY_HEADER,
                    "ZZZ_Q_FAM_A|x||" + CARD_UNKNOWN + "|",
                    "ZZZ_Q_FAM_SELF|x|||ZZZ_Q_FAM_SELF",
                    "ZZZ_Q_FAM_B|x|||NOPE_FAM"));
            assertTrue(errorsContain(families, "Product EAN '" + CARD_UNKNOWN + "' not found."), "an unknown product EAN is refused (Q-D0)");
            assertTrue(errorsContain(families, "Family 'ZZZ_Q_FAM_SELF' cannot contain itself."), "a self-reference is refused (Q-D0)");
            assertTrue(errorsContain(families, "SubFamily code 'NOPE_FAM' not found."), "an unknown sub-family is refused (Q-D0)");
        } finally {
            deleteFamily("ZZZ_Q_FAM_A");
            deleteFamily("ZZZ_Q_FAM_SELF");
            deleteFamily("ZZZ_Q_FAM_B");
            deleteRulesLike("ZZZ_Q_%");
        }
    }

    // --------------------------------------------------
    // Q-D — UI notices (English on French screens)
    // --------------------------------------------------

    /**
     * Q-D — the English UI notices, each read verbatim off the 303 {@code Location}: {@code
     * Rule <c> created}/{@code updated}/{@code duplicated to <c2>}, {@code Rule <c>: end of
     * application set to <date>}/{@code cleared (open-ended)}, {@code Community <c>
     * created}/{@code updated}/{@code closed to new enrollments}/{@code reopened to
     * enrollments}, {@code <n> membership(s) saved}, {@code Adjustment posted}, {@code Card
     * resiliated}, {@code Membership saved}, {@code Activation saved}, {@code Setting <k>
     * saved}, {@code Import failed: <msg>}, {@code Invalid submission: <msg>}, {@code Unknown
     * rule '<c>'}, {@code Unknown community '<c>'}, {@code Unknown batch '<t>'}. Every mutation
     * targets an isolated {@code ZZZ_Q_*} row or a minted {@code 29900019*} card, purged in a
     * {@code finally}; the setting save restores the seeded value.
     */
    @Test
    void qD_uiNotices() {
        String card = "2990001900011";
        try {
            assertEquals("Rule ZZZ_Q_D_NEW created",
                    noticeOf(postForm("/ui/rules/create", "code", "ZZZ_Q_D_NEW", "type", "BRAND_TIERED_EARN", "label", "x",
                            "validFrom", "2027-01-01", "specification", BRAND_SPEC,
                            "advantageType", "PRODUCT").header("Location")),
                    "creating a rule reports its created notice (Q-D)");
            persistRule("ZZZ_Q_D_UPD", "BRAND_TIERED_EARN", LocalDateTime.of(2027, 1, 1, 0, 0), null, BRAND_SPEC);
            assertEquals("Rule ZZZ_Q_D_UPD updated",
                    noticeOf(postForm("/ui/rules/ZZZ_Q_D_UPD/update", "type", "BRAND_TIERED_EARN", "label", "y",
                            "validFrom", "2027-02-01", "specification", BRAND_SPEC,
                            "advantageType", "PRODUCT").header("Location")),
                    "updating an upcoming rule reports its updated notice (Q-D)");
            assertEquals("Rule ZZZ_Q_D_UPD duplicated to ZZZ_Q_D_DUP",
                    noticeOf(postForm("/ui/rules/ZZZ_Q_D_UPD/duplicate", "newCode", "ZZZ_Q_D_DUP").header("Location")),
                    "duplicating a rule reports its duplicated notice (Q-D)");
            persistRule("ZZZ_Q_D_END", "BRAND_TIERED_EARN", LocalDateTime.of(2026, 1, 1, 0, 0), null, BRAND_SPEC);
            assertEquals("Rule ZZZ_Q_D_END: end of application set to 2027-06-01",
                    noticeOf(postForm("/ui/rules/ZZZ_Q_D_END/end-date", "validTo", "2027-06-01").header("Location")),
                    "setting a rule end reports the set notice with the date (Q-D)");
            assertEquals("Rule ZZZ_Q_D_END: end of application cleared (open-ended)",
                    noticeOf(postForm("/ui/rules/ZZZ_Q_D_END/end-date", "validTo", "").header("Location")),
                    "clearing a rule end reports the cleared notice (Q-D)");
            assertEquals("Community ZZZ_Q_D_COMM created",
                    noticeOf(postForm("/ui/communities/create", "code", "ZZZ_Q_D_COMM", "label", "Q community").header("Location")),
                    "creating a community reports its created notice (Q-D)");
            assertEquals("Community ZZZ_Q_D_COMM updated",
                    noticeOf(postForm("/ui/communities/ZZZ_Q_D_COMM/update", "label", "Q renamed").header("Location")),
                    "updating a community reports its updated notice (Q-D)");
            assertEquals("Community ZZZ_Q_D_COMM closed to new enrollments",
                    noticeOf(postForm("/ui/communities/ZZZ_Q_D_COMM/active", "active", "false").header("Location")),
                    "closing a community reports its closed notice (Q-D)");
            assertEquals("Community ZZZ_Q_D_COMM reopened to enrollments",
                    noticeOf(postForm("/ui/communities/ZZZ_Q_D_COMM/active", "active", "true").header("Location")),
                    "reopening a community reports its reopened notice (Q-D)");
            seedCard(card);
            assertEquals("1 membership(s) saved",
                    noticeOf(postForm("/ui/communities/ZZZ_Q_D_COMM/memberships", "members",
                            "[{\"card\":\"" + card + "\",\"validFrom\":\"2026-09-01\"}]").header("Location")),
                    "saving the workbench reports the membership count (Q-D)");
            assertTrue(noticeOf(postForm("/ui/communities/ZZZ_Q_D_COMM/memberships", "members", "{not json").header("Location"))
                            .startsWith("Invalid submission: "),
                    "a malformed workbench submission reports the invalid-submission notice (Q-D)");
            assertEquals("Adjustment posted",
                    noticeOf(postForm("/ui/cards/" + card + "/adjust", "amount", "5.00", "reason", "Q goodwill").header("Location")),
                    "an adjustment reports its posted notice (Q-D)");
            assertEquals("Membership saved",
                    noticeOf(postForm("/ui/cards/" + card + "/membership", "community", "BABIES",
                            "validFrom", "2026-09-01").header("Location")),
                    "a card membership reports its saved notice (Q-D)");
            assertEquals("Activation saved",
                    noticeOf(postForm("/ui/cards/" + card + "/activation", "ruleCode", "ZZZ_Q_RULE",
                            "periodStart", "2026-09-01").header("Location")),
                    "an activation reports its saved notice (Q-D)");
            assertEquals("Card resiliated",
                    noticeOf(postForm("/ui/cards/" + card + "/resiliate").header("Location")),
                    "resiliating a card reports its notice (Q-D)");
            assertEquals("Setting program.zone saved",
                    noticeOf(postForm("/ui/program/setting", "key", "program.zone", "value", "Europe/Paris").header("Location")),
                    "saving a setting reports the saved key (Q-D)");
            assertEquals("Unknown batch 'NOPE'",
                    noticeOf(postForm("/ui/program/batch", "type", "NOPE", "dryRun", "true").header("Location")),
                    "an unknown batch reports the UI unknown-batch notice (Q-D)");
            assertEquals("Unknown rule 'ZZZ_Q_NONE'",
                    noticeOf(getRedirect("/ui/rules/ZZZ_Q_NONE")),
                    "opening a missing rule sheet redirects with the unknown-rule notice (Q-D)");
            assertEquals("Unknown community 'ZZZ_Q_NONE'",
                    noticeOf(getRedirect("/ui/communities/ZZZ_Q_NONE/edit")),
                    "opening a missing community edit redirects with the unknown-community notice (Q-D)");
            String importFailed = noticeOf(importRunNoFile("PRODUCTS"));
            assertTrue(importFailed.startsWith("Import failed:"), "a broken import upload reports the import-failed notice (Q-D)");
        } finally {
            deleteRulesLike("ZZZ_Q_%");
            deleteCommunity("ZZZ_Q_D_COMM");
            deleteAccountCascade(card);
        }
    }

    // --------------------------------------------------
    // Q-E — French texts (login, reset, confirms, simulator)
    // --------------------------------------------------

    /**
     * Q-E — the French screen texts: {@code Identifiants invalides.} and {@code Votre mot de
     * passe a été modifié. Vous pouvez vous connecter.} on the login page; {@code Si un compte
     * correspond à cette adresse, un lien de réinitialisation vient de lui être envoyé. Il est
     * valable 30 minutes et à usage unique.} on the forgot page; {@code Les deux saisies ne
     * correspondent pas.} and {@code Ce lien de réinitialisation est invalide, déjà utilisé ou
     * expiré. Refaites une demande.} from the reset POST; the H4/H5/G4/J3 confirm dialogs on
     * the card sheet, program screen and community workbench; {@code Carte inconnue — earn
     * vide (§20)}, {@code Couple invalide : <msg>} and {@code Réconciliation §22.1 échouée :
     * <msg>} from the simulator; and the drag-drop hint on the imports screen. The workbench
     * confirm needs an isolated {@code ZZZ_Q_E} community, purged in a {@code finally}.
     */
    @Test
    void qE_frenchTexts() {
        String loginError = getPublic("/ui/login", "error", "true");
        assertTrue(loginError.contains("Identifiants invalides."), "the login page shows the invalid-credentials text (Q-E)");
        String loginReset = getPublic("/ui/login", "reset", "true");
        assertTrue(loginReset.contains("Votre mot de passe a été modifié. Vous pouvez vous connecter."),
                "the login page shows the reset-completed text (Q-E)");
        String forgot = getPublic("/ui/forgot", "sent", "true");
        assertTrue(forgot.contains("Si un compte correspond à cette adresse, un lien de réinitialisation vient de"),
                "the forgot page shows the neutral link-sent text (Q-E)");
        assertTrue(forgot.contains("Il est valable 30 minutes et à usage unique."),
                "the forgot page shows the link validity text (Q-E)");
        Response mismatch = postReset("some-token", "aaaaaaaa", "bbbbbbbb");
        assertTrue(decode(mismatch.header("Location")).contains("Les deux saisies ne correspondent pas."),
                "a reset with mismatched inputs carries the mismatch text (Q-E)");
        Response deadToken = postReset("deadbeef-not-a-real-token", "abcdefgh", "abcdefgh");
        assertTrue(decode(deadToken.header("Location")).contains(
                        "Ce lien de réinitialisation est invalide, déjà utilisé ou expiré. Refaites une demande."),
                "a reset with a dead token carries the invalid-link text (Q-E)");
        String cardSheet = getHtml("/ui/cards/" + CARD_PLAIN);
        assertTrue(cardSheet.contains("Transférer la carte " + CARD_PLAIN),
                "the card sheet carries the H4 transfer confirm text (Q-E)");
        assertTrue(cardSheet.contains("L\\'ancienne carte sera résiliée."),
                "the H4 transfer confirm names the resiliation consequence (Q-E)");
        assertTrue(cardSheet.contains("Résilier la carte " + CARD_PLAIN)
                        && cardSheet.contains("Le solde ne bougera plus jamais."),
                "the card sheet carries the H5 resiliate confirm text (Q-E)");
        String program = getHtml("/ui/program");
        assertTrue(program.contains("Cette opération détruit des avantages de façon définitive."),
                "the program screen carries the G4 batch-execute confirm text (Q-E)");
        try {
            persistCommunity("ZZZ_Q_E", true, null);
            String workbench = getHtml("/ui/communities/ZZZ_Q_E");
            assertTrue(workbench.contains("aux nouveaux enrôlements ? Les membres existants conservent leurs avantages."),
                    "the community workbench carries the J3 close confirm text (Q-E)");
        } finally {
            deleteCommunity("ZZZ_Q_E");
        }
        String unknownCard = simulate(valuationResponse(List.of(), "0.00"), "NOPE-UNKNOWN", "");
        assertTrue(unknownCard.contains("Carte inconnue — earn vide (§20)"),
                "the simulator shows the unknown-card note (Q-E)");
        String badCouple = simulate("{ this is not json", CARD_PLAIN, "");
        assertTrue(badCouple.contains("Couple invalide : "), "the simulator shows the invalid-couple error (Q-E)");
        String badRecon = simulate(reconciliationBreaker(), CARD_PLAIN, "2026-08-17T10:00");
        assertTrue(badRecon.contains("Réconciliation §22.1 échouée : "),
                "the simulator shows the reconciliation-failure error (Q-E)");
        String imports = getHtml("/ui/imports");
        assertTrue(imports.contains("Glissez-déposez le fichier n'importe où dans ce cadre — le domaine se "
                        + "présélectionne d'après son nom (01-products.csv → PRODUCTS, etc.)."),
                "the imports screen carries the drag-drop hint (Q-E)");
    }

    // --------------------------------------------------
    // Q-F — contractual log lines
    // --------------------------------------------------

    /**
     * Q-F — the contractual log lines, captured by the boot-installed {@link BootLogCapture}
     * root handler: the three boot lines {@code Dev/test dataset loaded: …}, {@code Bootstrap
     * users created: 'pos' (pos), 'admin' (fid-admin)} and {@code Earn rule registry started:
     * <n> schemas registered …}; and the per-request lines {@code Password reset link sent to
     * the address of account 'admin'} (a forgot request on the bootstrap admin address),
     * {@code Rejected /earn: reconciliation failed: <msg>} (a 422 earn), {@code Failed to
     * process chunk of size <x> with step <y>…} and {@code Import finished. Created: <n>,
     * Updated: <m>} (a staged import fallback), and {@code Failed to replay held return <ref>}
     * (a corrupt held return caught up by its origin). Justified residue: {@code Empty
     * database detected — loading the 2026 program seed (§24.4)} is the prod {@code SeedLoader}
     * path and never fires under the DataInitializer wipe+reload of this boot — its absence is
     * asserted and reported, not flakily awaited.
     */
    @Test
    void qF_contractualLogLines() {
        assertTrue(bootMatches(m -> m.startsWith("Dev/test dataset loaded: ") && m.contains("products,")),
                "the boot logs the dev/test dataset line (Q-F)");
        assertTrue(bootMatches(m -> m.equals("Bootstrap users created: 'pos' (pos), 'admin' (fid-admin)")),
                "the boot logs the bootstrap-users line (Q-F)");
        assertTrue(bootMatches(m -> m.startsWith("Earn rule registry started: ") && m.contains("schemas registered")),
                "the boot logs the earn-rule-registry line (Q-F)");
        assertFalse(bootMatches(m -> m.equals("Empty database detected — loading the 2026 program seed (§24.4)")),
                "the prod SeedLoader line never fires under the DataInitializer boot — a documented residue (Q-F)");
        int beforeReset = BootLogCapture.messages().size();
        RestAssured.given().redirects().follow(false).contentType(ContentType.URLENC)
                .formParam("email", ADMIN_EMAIL).post("/ui/forgot");
        assertTrue(tailMatches(beforeReset, m -> m.equals("Password reset link sent to the address of account 'admin'")),
                "a forgot request on the admin address logs the reset-link-sent line (Q-F)");
        int beforeEarn = BootLogCapture.messages().size();
        postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00", socleOffers(), "5.00"));
        assertTrue(tailMatches(beforeEarn, m -> m.startsWith("Rejected /earn: reconciliation failed: ")),
                "a broken earn reconciliation logs the rejected line (Q-F)");
        int beforeImport = BootLogCapture.messages().size();
        List<String> refs = List.of("Q-F-1", "Q-F-2", "Q-F-3", "Q-F-BAD");
        try {
            importCsv(ADJ_IMPORT, csv(ADJ_HEADER,
                    "Q-F-1|" + CARD_PLAIN + "|0.00|2026-08-01|Q neutral one",
                    "Q-F-2|" + CARD_PLAIN + "|0.00|2026-08-01|Q neutral two",
                    "Q-F-3|" + CARD_PLAIN + "|0.00|2026-08-01|Q neutral three",
                    "Q-F-BAD|" + CARD_UNKNOWN + "|0.00|2026-08-01|Q doomed row"));
            assertTrue(tailMatches(beforeImport, m -> m.contains("Failed to process chunk of size ")
                            && m.contains("Retrying with step ")),
                    "a faulty chunk logs the staged-fallback line (Q-F)");
            assertTrue(tailMatches(beforeImport, m -> m.startsWith("Import finished. Created: ")),
                    "a finished import logs its counters line (Q-F)");
        } finally {
            deleteMovementsByRefs(refs);
        }
        String failReturn = "0101-2026-QF-RET-" + suffix();
        String failOrigin = "0101-2026-QF-ORIG-" + suffix();
        int beforeReplay = BootLogCapture.messages().size();
        persistCorruptPending(failReturn, failOrigin);
        try {
            assertEquals(202, postClosed(closedBody(failOrigin, CARD_PLAIN, CARD_PLAIN, "2026-08-17",
                    socleOffers(), "6.00", null)).statusCode(), "ingesting the origin is accepted (202, Q-F)");
            assertTrue(tailMatches(beforeReplay, m -> m.equals("Failed to replay held return " + failReturn)),
                    "a corrupt held return logs the failed-replay line without breaking the origin (Q-F)");
        } finally {
            deleteTrace(failOrigin);
            deleteMovements(failOrigin);
            deletePending(failReturn);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // Helpers — POS API (Basic pos)
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
     * Posts a reservation (or renewal) to {@code /api/burn/reservations} as {@code pos}.
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
     * Confirms a reservation into a BURN at a fiscal date, as {@code pos}.
     *
     * @param id         The reservation id.
     * @param fiscalDate The fiscal date as an ISO string.
     * @return The HTTP response.
     */
    private static Response confirm(long id, String fiscalDate) {
        String body = "{\"fiscalDate\":\"" + fiscalDate + "\"}";
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .contentType("application/json").body(body).post("/api/burn/reservations/" + id + "/confirm");
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
     * Posts a {@code ticket-return} event as the {@code pos} operator.
     *
     * @param body The JSON request body.
     * @return The HTTP response.
     */
    private static Response postReturn(String body) {
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .contentType("application/json").body(body).post("/api/events/ticket-return");
    }

    /**
     * Ingests a {@code ticket-closed} carrying a {@code reservationId} over an empty basket,
     * as {@code pos} — the burn catch-up path (§29.2).
     *
     * @param eventTicket   The unique event ticket reference.
     * @param card          The authoritative card number.
     * @param fiscalDate    The fiscal date of the closure.
     * @param reservationId The reservation to confirm at ingestion.
     * @return The HTTP response.
     */
    private static Response ingestClosedWithReservation(String eventTicket, String card, LocalDate fiscalDate,
                                                        long reservationId) {
        String vr = "\"valuationRequest\":{\"customerCode\":\"" + card + "\",\"storeCode\":\"0101\",\"createdAt\":\""
                + fiscalDate.atTime(10, 0) + "\"}";
        String amount = "{\"amountExcludingTax\":0.00,\"amountIncludingTax\":0.00,\"vatRate\":0.2000}";
        String resp = "\"valuationResponse\":{\"offers\":[],\"advantages\":[],\"totalPrice\":" + amount + ",\"vatBreakdown\":[]}";
        String body = "{\"ticketRef\":\"" + eventTicket + "\",\"card\":\"" + card + "\",\"fiscalDate\":\""
                + fiscalDate + "\",\"reservationId\":" + reservationId + "," + vr + "," + resp + "}";
        return postClosed(body);
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
     * Triggers a batch by type as {@code admin} (the {@code fid-admin}-guarded trigger).
     *
     * @param type The raw batch type path segment.
     * @return The HTTP response.
     */
    private static Response postBatchAdmin(String type) {
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(ADMIN_USER, ADMIN_PASSWORD)
                .post("/api/batches/" + type);
    }

    // --------------------------------------------------
    // Helpers — GraphQL and imports (Basic admin)
    // --------------------------------------------------

    /**
     * Posts a GraphQL operation over Basic {@code fid-admin} and returns the first error
     * message, the verbatim rendering of an {@code AdminException} (§18, §26.5).
     *
     * @param query The GraphQL mutation document.
     * @return The {@code errors[0].message} of the response.
     */
    private static String graphqlError(String query) {
        return RestAssured.given().auth().preemptive().basic(ADMIN_USER, ADMIN_PASSWORD)
                .contentType(ContentType.JSON).body(Map.of("query", query)).post("/graphql")
                .jsonPath().getString("errors[0].message");
    }

    /**
     * POSTs a raw pipe-delimited CSV body to an import endpoint over Basic {@code admin}.
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
     * Tells whether a parsed import report carries a row error containing the given substring.
     *
     * @param report    The import report response.
     * @param substring The substring to look for.
     * @return true when at least one error contains the substring.
     */
    private static boolean errorsContain(Response report, String substring) {
        List<String> errors = report.jsonPath().getList("errors");
        return errors != null && errors.stream().anyMatch(e -> e.contains(substring));
    }

    /**
     * Runs a broken import upload over the admin session — a multipart carrying a domain but
     * no file, so {@code /ui/imports/run} raises and reports the {@code Import failed:} notice.
     *
     * @param domain The import domain sent without a file.
     * @return The redirect {@code Location} of the failed run.
     */
    private static String importRunNoFile(String domain) {
        return RestAssured.given().redirects().follow(false).cookie(SESSION_COOKIE, adminSession())
                .multiPart("domain", domain).post("/ui/imports/run").header("Location");
    }

    // --------------------------------------------------
    // Helpers — admin UI (form session)
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
     * Performs an un-followed GET over a fresh admin session and returns the {@code Location}
     * header — the way a redirecting GET (a missing-code sheet) carries its notice.
     *
     * @param path The GET path.
     * @return The redirect {@code Location} header.
     */
    private static String getRedirect(String path) {
        return RestAssured.given().redirects().follow(false).cookie(SESSION_COOKIE, adminSession())
                .get(path).header("Location");
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
     * Simulates a pasted couple over the admin session — the simulator POST returns the
     * rendered HTML directly (200), not a redirect.
     *
     * @param response The pasted valuation-response JSON.
     * @param card     The card number.
     * @param date     The forced evaluation date-time.
     * @return The rendered simulator HTML.
     */
    private static String simulate(String response, String card, String date) {
        return RestAssured.given().cookie(SESSION_COOKIE, adminSession()).contentType(ContentType.URLENC)
                .formParam("response", response).formParam("card", card).formParam("date", date)
                .post("/ui/simulator").then().statusCode(200).extract().asString();
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
    // Helpers — public auth screens and reset
    // --------------------------------------------------

    /**
     * Reads a public ({@code @PermitAll}) screen with one query parameter, no session needed.
     *
     * @param path  The GET path.
     * @param param The query parameter name.
     * @param value The query parameter value.
     * @return The rendered body.
     */
    private static String getPublic(String path, String param, String value) {
        return RestAssured.given().queryParam(param, value).get(path).then().statusCode(200).extract().asString();
    }

    /**
     * Posts the reset form without following the 303, so the error carried back on the reset
     * page is readable off the {@code Location}.
     *
     * @param token    The reset token.
     * @param password The new password.
     * @param confirm  The confirmation.
     * @return The un-followed HTTP response.
     */
    private static Response postReset(String token, String password, String confirm) {
        return RestAssured.given().redirects().follow(false).contentType(ContentType.URLENC)
                .formParam("token", token).formParam("password", password).formParam("confirm", confirm)
                .post("/ui/reset");
    }

    /**
     * URL-decodes a redirect {@code Location} whole, so an inline {@code error=} message can
     * be substring-matched.
     *
     * @param location The redirect Location header.
     * @return The decoded location.
     */
    private static String decode(String location) {
        assertNotNull(location, "the reset POST must redirect");
        return URLDecoder.decode(location, StandardCharsets.UTF_8);
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
     * Builds a {@code ticket-closed} body with an event card, a request card and a fiscal
     * date, optionally carrying a displayed-earn block.
     *
     * @param ticketRef    The idempotency ticket reference.
     * @param card         The event card (a coherence control).
     * @param customerCode The authoritative request card.
     * @param fiscalDate   The ISO fiscal date.
     * @param offers       The offer JSON fragments.
     * @param totalTtc     The reconciled total price TTC.
     * @param displayed    The displayed-earn JSON fragment, or null to omit it.
     * @return The request JSON.
     */
    private static String closedBody(String ticketRef, String card, String customerCode, String fiscalDate,
                                     List<String> offers, String totalTtc, String displayed) {
        String vr = "\"valuationRequest\":{\"customerCode\":\"" + customerCode + "\",\"storeCode\":\"0101\","
                + "\"createdAt\":\"" + fiscalDate + "T10:00:00\"}";
        String disp = displayed != null ? ",\"displayedEarn\":[" + displayed + "]" : "";
        return "{\"ticketRef\":\"" + ticketRef + "\",\"card\":\"" + card + "\",\"fiscalDate\":\"" + fiscalDate + "\","
                + vr + ",\"valuationResponse\":" + valuationResponse(offers, totalTtc) + disp + "}";
    }

    /**
     * Builds one displayed-earn entry JSON fragment carried for trace (§27.4).
     *
     * @param ruleCode The displayed rule code.
     * @param amount   The displayed amount value at scale 2.
     * @return The displayed-earn JSON object.
     */
    private static String displayedEarn(String ruleCode, String amount) {
        return "{\"ruleCode\":\"" + ruleCode + "\",\"amount\":" + amount + "}";
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
     * Builds a single-item Standard offer whose amount equals its item, so the §22.1 offer
     * invariant holds by construction.
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
     * @param type      The descriptive offer type.
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
     * Builds an {@code AmountEvaluation} JSON block, using the value for both HT and TTC at
     * the 20 % rate (the earn assiette reads the TTC net only).
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
     * A valuation response whose declared total (5.00 €) disagrees with the socle offers'
     * sum (6.00 €) by more than a centime — a §22.1 reconciliation breaker for the simulator.
     *
     * @return The reconciliation-breaking valuation-response JSON.
     */
    private static String reconciliationBreaker() {
        return valuationResponse(socleOffers(), "5.00");
    }

    // --------------------------------------------------
    // Helpers — a per-test unique suffix
    // --------------------------------------------------

    /**
     * Produces a per-call unique suffix for ticket references, so an idempotent upsert is
     * never silently absorbed by a replay.
     *
     * @return A unique suffix string.
     */
    private static String suffix() {
        return Long.toString(System.nanoTime());
    }

    // --------------------------------------------------
    // Helpers — boot/runtime log reading
    // --------------------------------------------------

    /**
     * Tells whether any captured log message satisfies the predicate.
     *
     * @param predicate The message predicate.
     * @return true when a captured message matches.
     */
    private static boolean bootMatches(Predicate<String> predicate) {
        return BootLogCapture.messages().stream().anyMatch(predicate);
    }

    /**
     * Tells whether any message captured since a marker index satisfies the predicate — the
     * slice added by a just-triggered action.
     *
     * @param from      The message-count marker taken before the action.
     * @param predicate The message predicate.
     * @return true when a message added since the marker matches.
     */
    private static boolean tailMatches(int from, Predicate<String> predicate) {
        List<String> messages = BootLogCapture.messages();
        return messages.subList(Math.min(from, messages.size()), messages.size()).stream().anyMatch(predicate);
    }

    // --------------------------------------------------
    // Helpers — database (fresh transactions, no absolute ids)
    // --------------------------------------------------

    /**
     * Persists an isolated earn rule directly (bypassing the import schema validation), in a
     * fresh transaction — the controlled subject of the guard/notice proofs.
     *
     * @param code      The rule code.
     * @param type      The rule type.
     * @param validFrom The window start.
     * @param validTo   The window end, or null while open.
     * @param spec      The JSON specification.
     */
    private static void persistRule(String code, String type, LocalDateTime validFrom, LocalDateTime validTo, String spec) {
        QuarkusTransaction.requiringNew().run(() -> {
            com.intermarche.fidelity.domain.FidelityRule rule = new com.intermarche.fidelity.domain.FidelityRule();
            rule.code = code;
            rule.type = type;
            rule.label = "Q test " + code;
            rule.validFrom = validFrom;
            rule.validTo = validTo;
            rule.priority = 0;
            rule.exclusive = false;
            rule.active = true;
            rule.specification = spec;
            rule.persist();
        });
    }

    /**
     * Deletes every rule whose code matches a LIKE pattern, in a fresh transaction — the
     * catch-all cleanup for a test's isolated rules.
     *
     * @param pattern The SQL LIKE pattern.
     */
    private static void deleteRulesLike(String pattern) {
        QuarkusTransaction.requiringNew().run(() ->
                com.intermarche.fidelity.domain.FidelityRule.delete("code like ?1", pattern));
    }

    /**
     * Persists an isolated community directly, in a fresh transaction.
     *
     * @param code          The community code.
     * @param active        Whether the community is open to enrollments.
     * @param enrollmentCap The enrollment cap, or null.
     */
    private static void persistCommunity(String code, boolean active, Integer enrollmentCap) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityCommunity community = new FidelityCommunity();
            community.code = code;
            community.label = "Q community " + code;
            community.active = active;
            community.enrollmentCap = enrollmentCap;
            community.persist();
        });
    }

    /**
     * Deletes a community and its memberships by code, in a fresh transaction; a null or
     * unknown code is a no-op.
     *
     * @param code The community code, or null.
     */
    private static void deleteCommunity(String code) {
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

    /**
     * Persists an isolated ACTIVE card directly, in a fresh transaction — the mutable subject
     * of the card notice proofs, kept away from the seeded cards.
     *
     * @param card The card number.
     */
    private static void seedCard(String card) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = new FidelityAccount();
            account.cardNumber = card;
            account.status = AccountStatus.ACTIVE;
            account.activatedAt = DateTimeProvider.now();
            account.persist();
        });
    }

    /**
     * Deletes an isolated card and all its dependents (movements, memberships, activations,
     * reservations), in a fresh transaction; a null or unknown card is a no-op.
     *
     * @param card The card number, or null.
     */
    private static void deleteAccountCascade(String card) {
        if (card == null) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            if (account != null) {
                FidelityMovement.delete("account", account);
                FidelityMembership.delete("account", account);
                FidelityActivation.delete("account", account);
                FidelityReservation.delete("account", account);
                account.delete();
            }
        });
    }

    /**
     * Inserts a reservation in a given state directly, in a fresh transaction.
     *
     * @param card      The card number.
     * @param amount    The reserved amount in euro.
     * @param ticketRef The ticket reference.
     * @param expiresAt The lease expiry instant.
     * @param state     The lease state.
     * @return The inserted reservation id.
     */
    private static long insertReservation(String card, String amount, String ticketRef,
                                          LocalDateTime expiresAt, ReservationState state) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityReservation reservation = new FidelityReservation();
            reservation.account = FidelityAccount.findByCardNumber(card);
            reservation.amount = new BigDecimal(amount);
            reservation.ticketRef = ticketRef;
            reservation.expiresAt = expiresAt;
            reservation.state = state;
            reservation.persist();
            return reservation.id;
        });
    }

    /**
     * Deletes every reservation of a scratch card, in a fresh transaction.
     *
     * @param card The card number.
     */
    private static void cleanReservations(String card) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            if (account != null) {
                FidelityReservation.delete("account", account);
            }
        });
    }

    /**
     * Inserts a BURN movement dated today, in a fresh transaction — arms the once-per-day
     * rule for the DAILY_RULE branch.
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
            movement.ticketRef = ticketRef;
            movement.persist();
        });
    }

    /**
     * Reads a copy of the warnings persisted on a ticket's earn trace, in a fresh
     * transaction.
     *
     * @param ticketRef The ticket reference.
     * @return The trace warnings, or an empty list when the trace is absent.
     */
    private static List<String> traceWarnings(String ticketRef) {
        List<String> warnings = QuarkusTransaction.requiringNew().call(() -> {
            EarnTrace trace = EarnTrace.findByTicketRef(ticketRef);
            return trace == null ? null : new java.util.ArrayList<>(trace.warnings);
        });
        return warnings == null ? List.of() : warnings;
    }

    /**
     * Deletes a ticket's earn trace and its lines, in a fresh transaction.
     *
     * @param ticketRef The ticket reference to clear.
     */
    private static void deleteTrace(String ticketRef) {
        QuarkusTransaction.requiringNew().run(() -> {
            EarnTrace trace = EarnTrace.findByTicketRef(ticketRef);
            if (trace != null) {
                EarnTraceLine.delete("trace", trace);
                trace.delete();
            }
        });
    }

    /**
     * Deletes every movement of a ticket reference, in a fresh transaction.
     *
     * @param ticketRef The ticket reference to clear.
     */
    private static void deleteMovements(String ticketRef) {
        QuarkusTransaction.requiringNew().run(() -> FidelityMovement.delete("ticketRef", ticketRef));
    }

    /**
     * Deletes every movement carrying one of the given ticket references, in a fresh
     * transaction.
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
     * Persists a held return whose payload is deliberately corrupt, so its replay fails at
     * deserialization, in a fresh transaction (§31.2).
     *
     * @param returnTicketRef The return ticket reference.
     * @param originTicketRef The awaited origin ticket reference.
     */
    private static void persistCorruptPending(String returnTicketRef, String originTicketRef) {
        QuarkusTransaction.requiringNew().run(() -> {
            PendingReturn pending = new PendingReturn();
            pending.returnTicketRef = returnTicketRef;
            pending.originTicketRef = originTicketRef;
            pending.payload = "{ this is not valid json";
            pending.persist();
        });
    }

    /**
     * Deletes a held return by its return reference, in a fresh transaction.
     *
     * @param returnTicketRef The return ticket reference to clear.
     */
    private static void deletePending(String returnTicketRef) {
        QuarkusTransaction.requiringNew().run(() -> PendingReturn.delete("returnTicketRef", returnTicketRef));
    }

    /**
     * Deletes a product family by code, first unlinking it so no association row survives, in
     * a fresh transaction; a null or unknown code is a no-op.
     *
     * @param code The family code, or null.
     */
    private static void deleteFamily(String code) {
        if (code == null) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> {
            com.intermarche.fidelity.domain.ProductFamily family =
                    com.intermarche.fidelity.domain.ProductFamily.findByCode(code);
            if (family != null) {
                family.products.clear();
                family.productFamilies.clear();
                family.delete();
            }
        });
    }

    /**
     * Recomputes and stores a card's denormalized balance from its movements, in a fresh
     * transaction — restores the "balance = signed sum of movements" invariant after a test
     * removed movements it had posted (§14).
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
}
