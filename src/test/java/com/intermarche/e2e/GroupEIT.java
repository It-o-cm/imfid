package com.intermarche.e2e;

import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.EarnTraceLine;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.PendingReturn;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group E — the fiscal event ingestion {@code POST /api/events/*}, the
 * credit that makes faith (e2escenarios-imfid.md "## E"). Every scenario is plain HTTP:
 * RestAssured over the real application booted by {@link QuarkusTest} with the
 * DataInitializer-rebuilt 2026 world, authenticated Basic {@code pos} (the
 * {@code @RolesAllowed(pos)} guard of the ingestion surface). No scenario in this group is
 * [W] or [P], so all twelve are implemented at the RestAssured tier.
 * <p>
 * Ingestion is the authority of the credit — the recalc at ingestion prevails (§26.1) —
 * so every scenario asserts the persisted ledger and trace state, never a response body
 * beyond the always-202 (or 400 for the two guard scenarios). Every crafted
 * {@code /valuation} couple satisfies the §22.1 reconciliation invariants (total = Σ
 * offers, one Standard offer per line whose {@code amount} equals its single item) unless
 * a scenario deliberately consumes lines through a non-Standard offer (E4). Fiscal
 * idempotence is a trap (§27.4, I8): every {@code ticketRef} carries a per-scenario unique
 * suffix so a replay is never silently absorbed, and each test removes the rows it created
 * (and recomputes the touched balance) so the class is order-independent. Earn-sensitive
 * scenarios pin their {@code fiscalDate} to distinct 2026 months so no single card ever
 * accrues the fourth visit of a month, keeping the socle at its 5 % base rate on every
 * calendar day the campaign runs. DB assertions go through Panache under
 * {@link QuarkusTransaction}, always by natural key and delta, never absolute ids or
 * counters. BigDecimal amounts are compared by {@code compareTo} (§30.5).
 */
@QuarkusTest
class GroupEIT {

    /**
     * Bootstrap machine login (SecurityBootstrap default), role {@code pos}.
     */
    private static final String POS_USER = "pos";

    /**
     * Bootstrap machine password (SecurityBootstrap default in dev/test).
     */
    private static final String POS_PASSWORD = "pos-password";

    /**
     * Rich-history card: ACTIVE with e-coupon and challenge activations (…019).
     */
    private static final String CARD_RICH = "2990000000019";

    /**
     * Students-community member card (…033) — a distinct existing card for the mismatch
     * counter-check.
     */
    private static final String CARD_STUDENT = "2990000000033";

    /**
     * Lost card, RESILIATED, balance transferred to the successor (…064) — the head of
     * the transfer chain resolved by {@code resolveActive} (§32.2).
     */
    private static final String CARD_LOST = "2990000000064";

    /**
     * Successor card: ACTIVE, no visits, no memberships, no activations — the neutral
     * earning card used where a bare 5 % socle rate is wanted, and the tail of the
     * transfer chain (…071).
     */
    private static final String CARD_PLAIN = "2990000000071";

    /**
     * Card left permanently negative by a seeded return debit (I7) — the standing witness
     * that a negative balance is tolerated (…057).
     */
    private static final String CARD_NEGATIVE = "2990000000057";

    /**
     * PENDING_ACTIVATION card voided by the two-month CGU batch, RESILIATED, balance
     * 0.00 € — an event on it must never move the balance (…095, §34.2).
     */
    private static final String CARD_VOIDED = "2990000000095";

    /**
     * Milk 1L, brand Pâturages (socle), UNIT — a socle assiette line.
     */
    private static final String EAN_MILK = "3300000000002";

    /**
     * Coffee 500g, brand Mäy (socle), UNIT — a socle assiette line and a bundle line.
     */
    private static final String EAN_COFFEE = "3300000000004";

    /**
     * Mineral water 1.5L, brand Paquito (socle), UNIT — a socle assiette line.
     */
    private static final String EAN_WATER = "3300000000007";

    /**
     * Yoghurt 4x125g, brand Pâturages (socle), UNIT — a socle assiette line.
     */
    private static final String EAN_YAOURT = "3300000000010";

    /**
     * Chocolate biscuits, brand Fiorini (socle), UNIT — a bundle line.
     */
    private static final String EAN_BISCUITS = "3300000000013";

    /**
     * The §12 socle rule code the neutral card lights at its base rate.
     */
    private static final String RULE_SOCLE = "SOCLE_5_MARQUES";

    /**
     * The unknown EAN of E12, absent from the imfid product reference (§25.4).
     */
    private static final String EAN_UNKNOWN = "3400000099999";

    // --------------------------------------------------
    // E1 — ticket-closed nominal: the credit that makes faith (§26.1)
    // --------------------------------------------------

    /**
     * E1 — ticket-closed nominal: a socle basket on the neutral card is accepted (202),
     * posts one EARN movement per rule keyed by {@code ruleCode}/{@code ticketRef} with
     * {@code earnYear} = the civil year of {@code fiscalDate}, recomputes the balance, and
     * writes a SUCCESS trace carrying both {@code displayedEarn} and {@code
     * recalculatedEarn} plus the verbatim request and response payloads (§26.1).
     */
    @Test
    void e1_ticketClosedNominal() {
        String ticketRef = "0101-2026-E1-CLOSED";
        BigDecimal before = accountBalance(CARD_PLAIN);
        try {
            Response r = postClosed(closedBody(ticketRef, CARD_PLAIN, CARD_PLAIN, "2026-08-10",
                    socleOffers(), "6.00", displayedEarn(RULE_SOCLE, "0.30")));
            assertEquals(202, r.statusCode(), "a nominal ticket-closed is always accepted (202)");
            FidelityMovement earn = findMovement(ticketRef, MovementType.EARN, RULE_SOCLE);
            assertNotNull(earn, "the closure must post one EARN movement for the socle rule");
            assertEquals(0, earn.amount.compareTo(new BigDecimal("0.30")), "the socle earn is 5 % of 6.00 € = 0.30 €");
            assertEquals(RULE_SOCLE, earn.ruleCode, "the movement must carry its rule code");
            assertEquals(ticketRef, earn.ticketRef, "the movement must carry its ticket reference");
            assertEquals(2026, earn.earnYear, "earnYear is the civil year of the fiscal date 2026-08-10");
            assertEquals(0, accountBalance(CARD_PLAIN).subtract(before).compareTo(new BigDecimal("0.30")),
                    "the balance must be recomputed by exactly the credited 0.30 €");
            assertEquals(EarnTrace.STATUS_SUCCESS, traceStatus(ticketRef), "the trace status must be SUCCESS");
            assertEquals(0, traceRecalc(ticketRef).compareTo(new BigDecimal("0.30")), "the recalculated earn is 0.30 €");
            assertEquals(0, traceDisplayed(ticketRef).compareTo(new BigDecimal("0.30")), "the displayed earn 0.30 € is kept");
            assertTrue(tracePayload(ticketRef, true).contains(CARD_PLAIN), "the request payload is stored verbatim");
            assertTrue(tracePayload(ticketRef, false).contains("totalPrice"), "the response payload is stored verbatim");
        } finally {
            deleteTicket(ticketRef);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // E2 — the two 400 guards (§27.4, Q-A)
    // --------------------------------------------------

    /**
     * E2 — 400: a {@code ticket-closed} missing or blank on {@code ticketRef} answers
     * {@code {"error":"Missing ticketRef"}}; a {@code ticket-return} missing either the
     * return or the origin reference answers {@code {"error":"Missing returnTicketRef or
     * originTicketRef"}} — each jamb of the composed guard covered (§29.6, Q-A).
     */
    @Test
    void e2_missingReferencesAre400() {
        Response missing = postClosed("{\"card\":\"" + CARD_PLAIN + "\"}");
        assertEquals(400, missing.statusCode(), "a ticket-closed without a ticketRef must be a 400");
        assertEquals("Missing ticketRef", missing.jsonPath().getString("error"), "the 400 carries the missing-ticketRef literal");
        Response blank = postClosed("{\"ticketRef\":\"   \",\"card\":\"" + CARD_PLAIN + "\"}");
        assertEquals(400, blank.statusCode(), "a blank ticketRef is treated as missing");
        assertEquals("Missing ticketRef", blank.jsonPath().getString("error"), "the blank ticketRef 400 carries the same literal");
        Response noOrigin = postReturn("{\"returnTicketRef\":\"0101-2026-E2-RET\"}");
        assertEquals(400, noOrigin.statusCode(), "a ticket-return without an origin reference must be a 400");
        assertEquals("Missing returnTicketRef or originTicketRef", noOrigin.jsonPath().getString("error"),
                "the missing-origin 400 carries the two-references literal");
        Response noReturn = postReturn("{\"originTicketRef\":\"0101-2026-E2-ORIG\"}");
        assertEquals(400, noReturn.statusCode(), "a ticket-return without a return reference must be a 400");
        assertEquals("Missing returnTicketRef or originTicketRef", noReturn.jsonPath().getString("error"),
                "the missing-return 400 carries the same two-references literal");
    }

    // --------------------------------------------------
    // E3 — idempotence, the trap of the campaigns (I8, §27.4)
    // --------------------------------------------------

    /**
     * E3 — idempotence: replaying the very same {@code ticket-closed} answers 202 again and
     * creates no new movement — the natural key is {@code ticketRef+type+ruleCode} (I8).
     * The corollary engraved in the pre-flight is operational: a register that renumbers
     * from {@code C04-000001} at every boot sees its events absorbed, so refs are suffixed
     * per run; this test proves the absorption by replaying deliberately.
     */
    @Test
    void e3_replayCreatesNoNewMovement() {
        String ticketRef = "0101-2026-E3-CLOSED";
        try {
            Response first = postClosed(closedBody(ticketRef, CARD_PLAIN, CARD_PLAIN, "2026-08-12",
                    socleOffers(), "6.00", null));
            assertEquals(202, first.statusCode(), "the first ingestion is accepted");
            assertEquals(1, countMovements(ticketRef, MovementType.EARN), "the first ingestion posts one EARN movement");
            Response replay = postClosed(closedBody(ticketRef, CARD_PLAIN, CARD_PLAIN, "2026-08-12",
                    socleOffers(), "6.00", null));
            assertEquals(202, replay.statusCode(), "the replay is accepted again (202)");
            assertEquals(1, countMovements(ticketRef, MovementType.EARN),
                    "the replay adds no movement — the natural key ticketRef+type+ruleCode absorbs it");
        } finally {
            deleteTicket(ticketRef);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // E4 — the recalc prevails (§26.1)
    // --------------------------------------------------

    /**
     * E4 — the recalc prevails: a lying {@code displayedEarn} of 2.10 € against a socle
     * basket that recomputes to 2.05 € credits 2.05 € and traces an {@code EARN_MISMATCH}
     * warning; and a bundle basket whose recalc is 0 (the coffee/biscuits consumed) posts
     * no movement and traces a SUCCESS header with no drama, even under a lying displayed
     * earn (§26.1).
     */
    @Test
    void e4_theRecalcPrevails() {
        String mismatchRef = "0101-2026-E4-MISMATCH";
        String bundleRef = "0101-2026-E4-BUNDLE";
        try {
            List<String> big = List.of(standardOffer("L1", EAN_MILK, "1.0", "20.00"),
                    standardOffer("L2", EAN_WATER, "1.0", "11.00"), standardOffer("L3", EAN_YAOURT, "1.0", "10.00"));
            Response mismatch = postClosed(closedBody(mismatchRef, CARD_PLAIN, CARD_PLAIN, "2026-09-10",
                    big, "41.00", displayedEarn(RULE_SOCLE, "2.10")));
            assertEquals(202, mismatch.statusCode(), "a displayed/recalc drift never fails the ingestion");
            FidelityMovement earn = findMovement(mismatchRef, MovementType.EARN, RULE_SOCLE);
            assertNotNull(earn, "the recomputed socle earn is credited");
            assertEquals(0, earn.amount.compareTo(new BigDecimal("2.05")),
                    "the credit is the recomputed 5 % of 41.00 € = 2.05 €, not the displayed 2.10 €");
            assertTrue(traceWarnings(mismatchRef).contains("EARN_MISMATCH"), "the drift traces an EARN_MISMATCH warning");
            String bundle = offerObject("MixedBundle: PROMO_COFFEE_PACK x1 for 4.50€", "4.50",
                    offerItem("L1", EAN_COFFEE, "1.0", "2.86") + "," + offerItem("L2", EAN_BISCUITS, "1.0", "1.64"));
            Response consumed = postClosed(closedBody(bundleRef, CARD_PLAIN, CARD_PLAIN, "2026-09-11",
                    List.of(bundle), "4.50", displayedEarn(RULE_SOCLE, "2.10")));
            assertEquals(202, consumed.statusCode(), "a zero-recalc closure is still accepted (202)");
            assertEquals(0, countMovements(bundleRef, MovementType.EARN), "a zero recalc posts no movement — zero drama");
            assertEquals(EarnTrace.STATUS_SUCCESS, traceStatus(bundleRef), "the zero-recalc trace is a SUCCESS header");
        } finally {
            deleteTicket(mismatchRef);
            deleteTicket(bundleRef);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // E5 — CARD_MISMATCH: the request makes faith (§27.4)
    // --------------------------------------------------

    /**
     * E5 — CARD_MISMATCH: when the event {@code card} disagrees with {@code
     * valuationRequest.customerCode}, the request makes faith — the credit lands on the
     * request card, the divergent event card is only a warning traced as {@code
     * CARD_MISMATCH} (§27.4).
     */
    @Test
    void e5_cardMismatchCreditsTheRequestCard() {
        String ticketRef = "0101-2026-E5-CLOSED";
        try {
            Response r = postClosed(closedBody(ticketRef, CARD_STUDENT, CARD_PLAIN, "2026-10-10",
                    socleOffers(), "6.00", null));
            assertEquals(202, r.statusCode(), "a card mismatch never fails the ingestion");
            FidelityMovement earn = findMovement(ticketRef, MovementType.EARN, RULE_SOCLE);
            assertNotNull(earn, "the recomputed earn is credited");
            assertEquals(CARD_PLAIN, movementCard(ticketRef, MovementType.EARN, RULE_SOCLE),
                    "the credit lands on the request card, not the divergent event card");
            assertTrue(traceWarnings(ticketRef).contains("CARD_MISMATCH"), "the divergence traces a CARD_MISMATCH warning");
        } finally {
            deleteTicket(ticketRef);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // E6 — RESILIATED_ACCOUNT: the balance is frozen (§34.2)
    // --------------------------------------------------

    /**
     * E6 — RESILIATED_ACCOUNT: an event addressed to a resiliated card traces a header and
     * a {@code RESILIATED_ACCOUNT} warning, posts no movement and leaves the balance frozen
     * — the trace status is NO_MOVEMENT (§34.2).
     */
    @Test
    void e6_resiliatedAccountTracesButNeverMoves() {
        String ticketRef = "0101-2026-E6-CLOSED";
        BigDecimal before = accountBalance(CARD_VOIDED);
        try {
            Response r = postClosed(closedBody(ticketRef, CARD_VOIDED, CARD_VOIDED, "2026-08-05",
                    socleOffers(), "6.00", null));
            assertEquals(202, r.statusCode(), "an event on a resiliated account is still accepted (202)");
            assertEquals(EarnTrace.STATUS_NO_MOVEMENT, traceStatus(ticketRef), "the resiliated event traces NO_MOVEMENT");
            assertTrue(traceWarnings(ticketRef).contains("RESILIATED_ACCOUNT"), "the event traces a RESILIATED_ACCOUNT warning");
            assertEquals(0, countMovements(ticketRef, MovementType.EARN), "a resiliated account earns nothing");
            assertEquals(0, accountBalance(CARD_VOIDED).compareTo(before), "the resiliated balance is frozen");
        } finally {
            deleteTicket(ticketRef);
        }
    }

    // --------------------------------------------------
    // E7 — the systematic visit (I3, §29.1)
    // --------------------------------------------------

    /**
     * E7 — systematic visit: a card-carrying ticket that earns nothing still writes an
     * {@code earn_traces} header, so the month's visit count grows by one (I3, §29.1); a
     * ticket carrying no card is traced without a movement and never counts as a visit.
     */
    @Test
    void e7_systematicVisit() {
        String withCard = "0101-2026-E7-VISIT";
        String noCard = "0101-2026-E7-NOCARD";
        try {
            List<String> single = List.of(standardOffer("L1", EAN_COFFEE, "1.0", "3.00"));
            long before = countVisits(CARD_PLAIN, "2026-11-10");
            Response r = postClosed(closedBody(withCard, CARD_PLAIN, CARD_PLAIN, "2026-11-10", single, "3.00", null));
            assertEquals(202, r.statusCode(), "a non-earning card ticket is still accepted (202)");
            assertEquals(0, countMovements(withCard, MovementType.EARN), "a single socle item is below the threshold — no earn");
            assertEquals(1, countVisits(CARD_PLAIN, "2026-11-10") - before, "the header still counts the visit (+1)");
            Response bare = postClosed(closedBodyNoCard(noCard, "2026-11-11", single, "3.00"));
            assertEquals(202, bare.statusCode(), "a ticket with no card is still accepted (202)");
            assertTrue(traceExists(noCard), "a cardless ticket is still traced");
            assertNull(traceCardNumber(noCard), "the cardless trace carries no card number");
            assertEquals(0, countMovements(noCard, MovementType.EARN), "a cardless ticket posts no movement");
        } finally {
            deleteTicket(withCard);
            deleteTicket(noCard);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // E8 — the transfer chain (§32.2)
    // --------------------------------------------------

    /**
     * E8 — transfer chain: an event addressed to the lost card (…064) is credited on its
     * live successor (…071) through {@code resolveActive} — the register has nothing to
     * know (§32.2).
     */
    @Test
    void e8_transferChainCreditsTheSuccessor() {
        String ticketRef = "0101-2026-E8-CLOSED";
        try {
            Response r = postClosed(closedBody(ticketRef, CARD_LOST, CARD_LOST, "2026-08-05",
                    socleOffers(), "6.00", null));
            assertEquals(202, r.statusCode(), "an event on a transferred card is accepted (202)");
            FidelityMovement earn = findMovement(ticketRef, MovementType.EARN, RULE_SOCLE);
            assertNotNull(earn, "the closure earns on the resolved live account");
            assertEquals(CARD_PLAIN, movementCard(ticketRef, MovementType.EARN, RULE_SOCLE),
                    "the credit is routed to the successor card …071, not the resiliated …064");
        } finally {
            deleteTicket(ticketRef);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // E9 — a return before its origin (§29.3)
    // --------------------------------------------------

    /**
     * E9 — return before origin: an orphan {@code ticket-return} is accepted (202) and held
     * in {@code pending_returns} uniquely by its return reference; ingesting the origin
     * replays the held return by itself, then clears it. A held return whose payload cannot
     * be replayed logs {@code Failed to replay held return %s} and never throws out.
     */
    @Test
    void e9_returnBeforeOriginIsHeldAndReplayed() {
        String originRef = "0101-2026-E9-ORIG";
        String returnRef = "0101-2026-E9-RET";
        String failOriginRef = "0101-2026-E9F-ORIG";
        String failReturnRef = "0101-2026-E9F-RET";
        try {
            Response held = postReturn(returnBody(returnRef, originRef, "2026-06-10",
                    "{\"lineId\":\"L1\",\"quantity\":1.0}", null));
            assertEquals(202, held.statusCode(), "an orphan return is accepted (202)");
            assertTrue(pendingExists(returnRef), "the orphan return is held in pending_returns");
            Response replay = postReturn(returnBody(returnRef, originRef, "2026-06-10",
                    "{\"lineId\":\"L1\",\"quantity\":1.0}", null));
            assertEquals(202, replay.statusCode(), "a second orphan copy is accepted (202)");
            assertEquals(1, countPending(returnRef), "the hold is unique by return reference (I8)");
            Response origin = postClosed(closedBody(originRef, CARD_PLAIN, CARD_PLAIN, "2026-06-10",
                    List.of(standardOffer("L1", EAN_COFFEE, "3.0", "6.00")), "6.00", null));
            assertEquals(202, origin.statusCode(), "ingesting the origin is accepted (202)");
            assertFalse(pendingExists(returnRef), "the held return is replayed and cleared once the origin arrives");
            assertTrue(hasMovement(returnRef, MovementType.RETURN_DEBIT), "the replayed return posts its RETURN_DEBIT");
            int logBefore = BootLogCapture.messages().size();
            persistCorruptPending(failReturnRef, failOriginRef);
            Response failOrigin = postClosed(closedBody(failOriginRef, CARD_PLAIN, CARD_PLAIN, "2026-06-11",
                    socleOffers(), "6.00", null));
            assertEquals(202, failOrigin.statusCode(), "an unreplayable held return never breaks the origin ingestion (202)");
            List<String> after = BootLogCapture.messages().subList(logBefore, BootLogCapture.messages().size());
            assertTrue(after.stream().anyMatch(m -> m.equals("Failed to replay held return " + failReturnRef)),
                    "the failed replay is logged with the contractual line, no exception escaping");
            assertFalse(pendingExists(failReturnRef), "even a failed replay clears its hold");
        } finally {
            deleteTicket(originRef);
            deleteTicket(failOriginRef);
            deleteMovementsByRef(returnRef);
            deletePending(returnRef);
            deletePending(failReturnRef);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // E10 — RETURN_DEBIT bounded by the line earn (§29.4)
    // --------------------------------------------------

    /**
     * E10 — bounded return debit: a partial return (1 of 3) debits pro-rata of the trace
     * line earn; further returns accumulate but never beyond the line's own earn, and a
     * negative balance is accepted — card …057 is the standing witness (I7, §29.4).
     */
    @Test
    void e10_returnDebitBoundedByLineEarn() {
        String originRef = "0101-2026-E10-ORIG";
        String firstReturn = "0101-2026-E10-RET1";
        String secondReturn = "0101-2026-E10-RET2";
        try {
            Response origin = postClosed(closedBody(originRef, CARD_PLAIN, CARD_PLAIN, "2026-07-10",
                    List.of(standardOffer("L1", EAN_COFFEE, "3.0", "6.00")), "6.00", null));
            assertEquals(202, origin.statusCode(), "the three-unit socle origin is accepted (202)");
            assertEquals(0, findMovement(originRef, MovementType.EARN, RULE_SOCLE).amount.compareTo(new BigDecimal("0.30")),
                    "the origin earns 5 % of 6.00 € = 0.30 € on the single three-unit line");
            Response first = postReturn(returnBody(firstReturn, originRef, "2026-07-11",
                    "{\"lineId\":\"L1\",\"quantity\":1.0}", null));
            assertEquals(202, first.statusCode(), "the first partial return is accepted (202)");
            assertEquals(0, movementAmount(firstReturn, MovementType.RETURN_DEBIT, RULE_SOCLE).compareTo(new BigDecimal("-0.10")),
                    "returning 1 of 3 debits a pro-rata third of the 0.30 € earn = 0.10 €");
            Response second = postReturn(returnBody(secondReturn, originRef, "2026-07-12",
                    "{\"lineId\":\"L1\",\"quantity\":5.0}", null));
            assertEquals(202, second.statusCode(), "an over-quantity return is accepted (202)");
            assertEquals(0, movementAmount(secondReturn, MovementType.RETURN_DEBIT, RULE_SOCLE).compareTo(new BigDecimal("-0.20")),
                    "the cumulated debit is bounded by the residual line earn 0.20 €, never beyond the line's 0.30 €");
            assertTrue(accountBalance(CARD_NEGATIVE).compareTo(BigDecimal.ZERO) < 0,
                    "card …057 stands witness that a negative balance is tolerated (I7)");
        } finally {
            deleteMovementsByRef(firstReturn);
            deleteMovementsByRef(secondReturn);
            deleteTicket(originRef);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // E11 — refundToCard out of every cap (§28.6, §29.5)
    // --------------------------------------------------

    /**
     * E11 — refund to card: a return carrying a {@code refundToCard} posts a {@code
     * REFUND_CREDIT} movement with a null {@code ruleCode}, out of every cap (it never
     * enters a monthly EARN cumulative) and perishable like earn (§28.6, §29.5).
     */
    @Test
    void e11_refundToCardIsAnUncappedCredit() {
        String originRef = "0101-2026-E11-ORIG";
        String returnRef = "0101-2026-E11-RET";
        BigDecimal before = accountBalance(CARD_PLAIN);
        try {
            Response origin = postClosed(closedBody(originRef, CARD_PLAIN, CARD_PLAIN, "2026-06-12",
                    socleOffers(), "6.00", null));
            assertEquals(202, origin.statusCode(), "the origin closure is accepted (202)");
            Response refund = postReturn(returnBody(returnRef, originRef, "2026-06-13", null,
                    "{\"card\":\"" + CARD_PLAIN + "\",\"amount\":3.00}"));
            assertEquals(202, refund.statusCode(), "the refund return is accepted (202)");
            FidelityMovement credit = findMovement(returnRef, MovementType.REFUND_CREDIT, null);
            assertNotNull(credit, "the refund posts a REFUND_CREDIT movement");
            assertEquals(0, credit.amount.compareTo(new BigDecimal("3.00")), "the refund credits the 3.00 € carried");
            assertNull(credit.ruleCode, "a REFUND_CREDIT carries no rule code (§29.4)");
            assertEquals(0, monthlyEarnTotal(CARD_PLAIN, "2026-06-13").compareTo(new BigDecimal("0.30")),
                    "the refund stays out of every cap: the EARN cumulative is the origin's 0.30 €, the 3.00 € refund excluded");
        } finally {
            deleteMovementsByRef(returnRef);
            deleteTicket(originRef);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // E12 — warnings in the trace, closed format (§25.4, Q-B)
    // --------------------------------------------------

    /**
     * E12 — traced warnings: the persisted format is {@code CODE} or {@code CODE:<ean>} —
     * an unknown-EAN line traces {@code UNKNOWN_EAN:3400000099999} while a card mismatch on
     * the same ticket traces the bare {@code CARD_MISMATCH}, both read off {@code
     * earn_trace_warnings} (§25.4, Q-B).
     */
    @Test
    void e12_warningsPersistedInClosedFormat() {
        String ticketRef = "0101-2026-E12-CLOSED";
        try {
            Response r = postClosed(closedBody(ticketRef, CARD_STUDENT, CARD_PLAIN, "2026-12-10",
                    List.of(standardOffer("L1", EAN_UNKNOWN, "1.0", "5.00")), "5.00", null));
            assertEquals(202, r.statusCode(), "an unknown EAN never fails the ingestion (202)");
            List<String> warnings = traceWarnings(ticketRef);
            assertTrue(warnings.contains("UNKNOWN_EAN:" + EAN_UNKNOWN),
                    "an unknown EAN traces the CODE:<ean> format but was " + warnings);
            assertTrue(warnings.contains("CARD_MISMATCH"), "a card mismatch traces the bare CODE format but was " + warnings);
        } finally {
            deleteTicket(ticketRef);
            recomputeBalance(CARD_PLAIN);
        }
    }

    // --------------------------------------------------
    // Helpers — HTTP
    // --------------------------------------------------

    /**
     * Posts a {@code ticket-closed} event as the {@code pos} operator, without following
     * redirects.
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
     * Posts a {@code ticket-return} event as the {@code pos} operator, without following
     * redirects.
     *
     * @param body The JSON request body.
     * @return The HTTP response.
     */
    private static Response postReturn(String body) {
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .contentType("application/json").body(body).post("/api/events/ticket-return");
    }

    // --------------------------------------------------
    // Helpers — JSON building
    // --------------------------------------------------

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
     * Builds a {@code ticket-closed} body carrying no card at all (no event card, no
     * request customerCode) — a cardless ticket (§29.1).
     *
     * @param ticketRef  The idempotency ticket reference.
     * @param fiscalDate The ISO fiscal date.
     * @param offers     The offer JSON fragments.
     * @param totalTtc   The reconciled total price TTC.
     * @return The request JSON.
     */
    private static String closedBodyNoCard(String ticketRef, String fiscalDate, List<String> offers, String totalTtc) {
        String vr = "\"valuationRequest\":{\"storeCode\":\"0101\",\"createdAt\":\"" + fiscalDate + "T10:00:00\"}";
        return "{\"ticketRef\":\"" + ticketRef + "\",\"fiscalDate\":\"" + fiscalDate + "\","
                + vr + ",\"valuationResponse\":" + valuationResponse(offers, totalTtc) + "}";
    }

    /**
     * Builds a {@code ticket-return} body with an optional returned line and an optional
     * refund-to-card block.
     *
     * @param returnRef  The return ticket reference.
     * @param originRef  The origin ticket reference.
     * @param fiscalDate The ISO fiscal date.
     * @param lineJson   The returned-line JSON fragment, or null for none.
     * @param refundJson The refund-to-card JSON fragment, or null for none.
     * @return The request JSON.
     */
    private static String returnBody(String returnRef, String originRef, String fiscalDate,
                                     String lineJson, String refundJson) {
        String lines = lineJson != null ? "[" + lineJson + "]" : "[]";
        String refund = refundJson != null ? ",\"refundToCard\":" + refundJson : "";
        return "{\"returnTicketRef\":\"" + returnRef + "\",\"originTicketRef\":\"" + originRef + "\","
                + "\"fiscalDate\":\"" + fiscalDate + "\",\"lines\":" + lines + refund + "}";
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
     * Builds a single-item Standard offer (a non-consuming, catalogue-tariff offer whose
     * line may earn); the offer amount equals its item amount, so the §22.1 offer invariant
     * holds.
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
     * The reference socle cart: milk + water + yoghurt = three socle units for a 6.00 €
     * net assiette (lines L1, L2, L3).
     *
     * @return The three Standard offer fragments.
     */
    private static List<String> socleOffers() {
        return List.of(standardOffer("L1", EAN_MILK, "1.0", "3.00"),
                standardOffer("L2", EAN_WATER, "1.0", "1.20"), standardOffer("L3", EAN_YAOURT, "1.0", "1.80"));
    }

    // --------------------------------------------------
    // Helpers — database (all in fresh transactions, no absolute ids)
    // --------------------------------------------------

    /**
     * Reads an account's denormalized balance in a fresh transaction.
     *
     * @param cardNumber The card number.
     * @return The balance, or null when the card is unknown.
     */
    private static BigDecimal accountBalance(String cardNumber) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(cardNumber);
            return account == null ? null : account.balance;
        });
    }

    /**
     * Finds a movement by its natural key in a fresh transaction; its scalar fields stay
     * readable on the detached instance.
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
     * Reads the signed amount of a movement identified by its natural key, in a fresh
     * transaction.
     *
     * @param ticketRef The ticket reference.
     * @param type      The movement type.
     * @param ruleCode  The rule code, or null.
     * @return The amount, or null when none matches.
     */
    private static BigDecimal movementAmount(String ticketRef, MovementType type, String ruleCode) {
        FidelityMovement movement = findMovement(ticketRef, type, ruleCode);
        return movement == null ? null : movement.amount;
    }

    /**
     * Reads the card number the movement of a natural key credits, dereferencing the lazy
     * account inside the transaction.
     *
     * @param ticketRef The ticket reference.
     * @param type      The movement type.
     * @param ruleCode  The rule code, or null.
     * @return The credited card number, or null when none matches.
     */
    private static String movementCard(String ticketRef, MovementType type, String ruleCode) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityMovement movement = FidelityMovement.findByNaturalKey(ticketRef, type, ruleCode);
            return movement == null ? null : movement.account.cardNumber;
        });
    }

    /**
     * Indicates whether any movement of a type exists for a ticket, in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @param type      The movement type.
     * @return true when at least one such movement exists.
     */
    private static boolean hasMovement(String ticketRef, MovementType type) {
        return QuarkusTransaction.requiringNew().call(() ->
                FidelityMovement.count("ticketRef = ?1 and type = ?2", ticketRef, type) > 0);
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
     * Sums the EARN euro credited across every rule this month for a card, in a fresh
     * transaction — the aggregate a REFUND_CREDIT must stay out of (§29.5).
     *
     * @param cardNumber The account card.
     * @param isoDate    Any ISO date in the target month.
     * @return The monthly EARN cumulative, scale 2 — a REFUND_CREDIT never contributes.
     */
    private static BigDecimal monthlyEarnTotal(String cardNumber, String isoDate) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(cardNumber);
            java.time.LocalDate date = java.time.LocalDate.parse(isoDate);
            return FidelityMovement.monthlyEarnTotal(account, date.withDayOfMonth(1),
                    date.withDayOfMonth(date.lengthOfMonth()));
        });
    }

    /**
     * Counts the distinct visit days a card has on a single ISO date, in a fresh
     * transaction (§29.1).
     *
     * @param cardNumber The card number.
     * @param isoDate    The ISO fiscal date to probe.
     * @return The distinct-visit-day count for that date (0 or 1).
     */
    private static long countVisits(String cardNumber, String isoDate) {
        return QuarkusTransaction.requiringNew().call(() -> {
            java.time.LocalDate date = java.time.LocalDate.parse(isoDate);
            return EarnTrace.countVisits(cardNumber, date, date);
        });
    }

    /**
     * Runs a read function over the trace of a ticket, in a fresh transaction, tolerating
     * a missing trace.
     *
     * @param ticketRef The ticket reference.
     * @param reader    The function reading the trace.
     * @param <T>       The read value type.
     * @return The read value, or null when the trace is absent.
     */
    private static <T> T onTrace(String ticketRef, Function<EarnTrace, T> reader) {
        return QuarkusTransaction.requiringNew().call(() -> {
            EarnTrace trace = EarnTrace.findByTicketRef(ticketRef);
            return trace == null ? null : reader.apply(trace);
        });
    }

    /**
     * Reads the status of a trace, in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @return The status, or null when the trace is absent.
     */
    private static String traceStatus(String ticketRef) {
        return onTrace(ticketRef, trace -> trace.status);
    }

    /**
     * Reads a copy of the warnings of a trace, in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @return The warnings copy, or an empty list when the trace is absent.
     */
    private static List<String> traceWarnings(String ticketRef) {
        List<String> warnings = onTrace(ticketRef, trace -> new ArrayList<>(trace.warnings));
        return warnings == null ? List.of() : warnings;
    }

    /**
     * Reads the recomputed earn total of a trace, in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @return The recalculated earn, or null when the trace is absent.
     */
    private static BigDecimal traceRecalc(String ticketRef) {
        return onTrace(ticketRef, trace -> trace.recalculatedEarn);
    }

    /**
     * Reads the displayed earn total of a trace, in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @return The displayed earn, or null when the trace is absent.
     */
    private static BigDecimal traceDisplayed(String ticketRef) {
        return onTrace(ticketRef, trace -> trace.displayedEarn);
    }

    /**
     * Reads the card number of a trace, in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @return The card number, or null when the trace is absent or cardless.
     */
    private static String traceCardNumber(String ticketRef) {
        return onTrace(ticketRef, trace -> trace.cardNumber);
    }

    /**
     * Reads one of the verbatim payloads of a trace, in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @param request   true for the request payload, false for the response payload.
     * @return The payload string, or null.
     */
    private static String tracePayload(String ticketRef, boolean request) {
        String payload = onTrace(ticketRef, trace -> request ? trace.requestPayload : trace.responsePayload);
        return payload == null ? "" : payload;
    }

    /**
     * Indicates whether a trace exists for a ticket, in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @return true when the trace exists.
     */
    private static boolean traceExists(String ticketRef) {
        return Boolean.TRUE.equals(onTrace(ticketRef, trace -> Boolean.TRUE));
    }

    /**
     * Indicates whether a held return exists for a return reference, in a fresh
     * transaction.
     *
     * @param returnTicketRef The return ticket reference.
     * @return true when the hold exists.
     */
    private static boolean pendingExists(String returnTicketRef) {
        return QuarkusTransaction.requiringNew().call(() ->
                PendingReturn.findByReturnTicketRef(returnTicketRef) != null);
    }

    /**
     * Counts the held returns of a return reference, in a fresh transaction — the hold is
     * unique by that key (§29.3).
     *
     * @param returnTicketRef The return ticket reference.
     * @return The hold count.
     */
    private static long countPending(String returnTicketRef) {
        return QuarkusTransaction.requiringNew().call(() ->
                PendingReturn.count("returnTicketRef", returnTicketRef));
    }

    /**
     * Persists a held return whose payload is deliberately corrupt, so its replay fails at
     * deserialization (§31.2), in a fresh transaction.
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
     * Recomputes an account's denormalized balance from its remaining movements, in a
     * fresh transaction — restoring the seeded balance after a test's rows are removed.
     *
     * @param cardNumber The card number.
     */
    private static void recomputeBalance(String cardNumber) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(cardNumber);
            if (account != null) {
                account.balance = FidelityMovement.computeBalance(account);
            }
        });
    }
}
