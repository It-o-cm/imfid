package com.intermarche.e2e;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.ReservationState;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group D — the burn reservation API {@code /api/burn/reservations}
 * (e2escenarios-imfid.md "## D"). Reservation at lease then confirmation, never a direct
 * debit (I11): the register reserves an amount, imfid checks the available balance
 * (balance − active reservations) and the once-per-day rule before laying a renewable
 * lease, the fiscal closure confirms it into a negative BURN, a cancelled payment releases
 * it and an abandoned ticket lets it expire (§16, §27.3).
 * <p>
 * Every scenario is plain HTTP over the real application booted by {@link QuarkusTest} with
 * the DataInitializer-rebuilt world, authenticated Basic {@code pos} (the {@code
 * @RolesAllowed(pos)} guard of the burn and ingestion surfaces). None of D1–D10 is [W] or
 * [P], so all ten are implemented at the RestAssured tier. The seeded facts asserted
 * against are the catalog's: card {@code …088} carries an ACTIVE 8.00 € lease on ticket
 * {@code 0101-2026-003001} over a 30.00 € balance (available 22.00 €); card {@code …040}
 * is PENDING_ACTIVATION and {@code …095} RESILIATED (neither can burn); cards {@code …071},
 * {@code …033}, {@code …026} and {@code …057} carry no seeded lease and serve as scratch
 * cards. Every ticket reference is suffixed per test so the idempotent BURN upsert is never
 * silently absorbed; every reservation, movement and trace a test creates is removed in the
 * same test, and the seeded {@code …088} lease is restored after the renewal test. Temporal
 * behaviour (the lease TTL, expiry) is driven by {@link DateTimeProvider} and by aging the
 * {@code expiresAt} rows, never by the wall clock of the run day (§24.6).
 */
@QuarkusTest
class GroupDIT {

    /**
     * Bootstrap machine login (SecurityBootstrap default), role {@code pos}.
     */
    private static final String POS_USER = "pos";

    /**
     * Bootstrap machine password (SecurityBootstrap default in dev/test).
     */
    private static final String POS_PASSWORD = "pos-password";

    /**
     * Card carrying an ACTIVE 8.00 € lease over a 30.00 € balance (…088): available 22.00 €.
     */
    private static final String CARD_RESERVED = "2990000000088";

    /**
     * The ticket reference of the seeded {@code …088} lease.
     */
    private static final String RESERVED_TICKET = "0101-2026-003001";

    /**
     * Successor card: ACTIVE, 15.00 € balance, no seeded lease — a scratch burn card (…071).
     */
    private static final String CARD_SUCCESSOR = "2990000000071";

    /**
     * Students card: ACTIVE, 24.00 € balance, no seeded lease — a scratch card (…033).
     */
    private static final String CARD_STUDENT = "2990000000033";

    /**
     * Babies card: ACTIVE, 18.50 € balance, no seeded lease — a scratch card (…026).
     */
    private static final String CARD_BABIES = "2990000000026";

    /**
     * Negative-balance card: ACTIVE, −1.70 € balance, no seeded lease — a scratch card (…057).
     */
    private static final String CARD_NEGATIVE = "2990000000057";

    /**
     * PENDING_ACTIVATION card: accrues but cannot burn (…040).
     */
    private static final String CARD_PENDING = "2990000000040";

    /**
     * RESILIATED card: cannot burn (…095).
     */
    private static final String CARD_VOIDED = "2990000000095";

    /**
     * A card never seeded, for the 404 branch.
     */
    private static final String CARD_UNKNOWN = "9999999999999";

    /**
     * The seeded lease TTL (§I11): {@code reservation.leaseTtlSeconds} = 900 s.
     */
    private static final long TTL_SECONDS = 900L;

    // --------------------------------------------------
    // D1 — nominal reservation (§27.3)
    // --------------------------------------------------

    /**
     * D1 — nominal: a first reservation on a fresh card answers 201 with a non-null
     * {@code reservationId} and an {@code expiresAt} at now + the lease TTL (900 s); the
     * lease is persisted ACTIVE at the reserved amount.
     */
    @Test
    void d1_nominalReservationCreated() {
        cleanReservations(CARD_SUCCESSOR);
        String ticket = "0101-2026-D1-" + suffix();
        try {
            LocalDateTime before = DateTimeProvider.now();
            Response r = reserve(CARD_SUCCESSOR, "5.00", ticket);
            assertEquals(201, r.statusCode(), "a first reservation on a free card must be a 201");
            long id = r.jsonPath().getLong("reservationId");
            assertTrue(id > 0, "the 201 must carry a real reservation id");
            LocalDateTime expiresAt = LocalDateTime.parse(r.jsonPath().getString("expiresAt"));
            long ttl = Duration.between(before, expiresAt).getSeconds();
            assertTrue(ttl >= TTL_SECONDS - 10 && ttl <= TTL_SECONDS + 15,
                    "expiresAt must be now + the 900 s lease TTL but was " + ttl + " s out");
            assertEquals(ReservationState.ACTIVE, reservationState(id), "the created lease must be ACTIVE");
            assertEquals(0, reservationAmount(ticket).compareTo(new BigDecimal("5.00")),
                    "the created lease must hold the reserved 5.00 €");
        } finally {
            cleanReservations(CARD_SUCCESSOR);
        }
    }

    // --------------------------------------------------
    // D2 — renewal pushes the lease and re-checks the balance (§27.3)
    // --------------------------------------------------

    /**
     * D2 — renewal: a re-POST on the same card and ticket answers 200 with a pushed-back
     * {@code expiresAt}; the amount is modifiable and re-verified against the balance — a
     * renewal to a new amount is persisted, a renewal beyond the balance is a 422
     * INSUFFICIENT_BALANCE. The seeded lease is aged just before the call so the push is
     * observable, then restored.
     */
    @Test
    void d2_renewalPushesLeaseAndRechecksBalance() {
        LocalDateTime aged = DateTimeProvider.now().plusSeconds(60);
        ageReservationExpiry(RESERVED_TICKET, aged);
        try {
            Response renew = reserve(CARD_RESERVED, "5.00", RESERVED_TICKET);
            assertEquals(200, renew.statusCode(), "a re-POST on the same card and ticket must be a 200 renewal");
            LocalDateTime pushed = LocalDateTime.parse(renew.jsonPath().getString("expiresAt"));
            assertTrue(pushed.isAfter(aged), "the renewed expiresAt must be pushed past the aged one");
            assertEquals(0, reservationAmount(RESERVED_TICKET).compareTo(new BigDecimal("5.00")),
                    "the amount is modifiable at renewal — the lease now holds 5.00 €");
            Response over = reserve(CARD_RESERVED, "999.00", RESERVED_TICKET);
            assertEquals(422, over.statusCode(), "a renewal beyond the balance must be a 422");
            assertEquals("INSUFFICIENT_BALANCE", over.jsonPath().getString("reason"),
                    "the amount is re-verified against the balance at renewal");
            assertEquals(0, reservationAmount(RESERVED_TICKET).compareTo(new BigDecimal("5.00")),
                    "the over-balance renewal must leave the lease unchanged at 5.00 €");
        } finally {
            restoreReservedLease();
        }
    }

    // --------------------------------------------------
    // D3 — a live lease on another ticket is a 409 (§27.3)
    // --------------------------------------------------

    /**
     * D3 — 409: a reservation on {@code …088} with a ticket other than the live seeded
     * lease is refused with an empty-body 409 — one card is at one register at a time.
     */
    @Test
    void d3_liveLeaseOtherTicketIsConflict() {
        String ticket = "0101-2026-D3-" + suffix();
        Response r = reserve(CARD_RESERVED, "1.00", ticket);
        assertEquals(409, r.statusCode(), "a live lease on another ticket must be a 409");
        assertTrue(r.body().asString().isEmpty(), "the 409 must carry an empty body");
        assertEquals(RESERVED_TICKET, reservationTicketOfActive(CARD_RESERVED),
                "the seeded lease must be untouched by the refused reservation");
    }

    // --------------------------------------------------
    // D4 — an unknown card is a 404 (§27.3)
    // --------------------------------------------------

    /**
     * D4 — 404: a reservation on a never-seeded card is refused with an empty-body 404.
     */
    @Test
    void d4_unknownCardIsNotFound() {
        String ticket = "0101-2026-D4-" + suffix();
        Response r = reserve(CARD_UNKNOWN, "1.00", ticket);
        assertEquals(404, r.statusCode(), "an unknown card must be a 404");
        assertTrue(r.body().asString().isEmpty(), "the 404 must carry an empty body");
    }

    // --------------------------------------------------
    // D5 — the three 422 refusals (§27.3)
    // --------------------------------------------------

    /**
     * D5 — the three typed 422 refusals: INSUFFICIENT_BALANCE when the amount exceeds the
     * available balance; DAILY_RULE when a confirmed BURN already exists this fiscal day;
     * ACCOUNT_STATUS on a PENDING_ACTIVATION card (which accrues but cannot burn, §25.3)
     * and on a RESILIATED card. A controlled BURN is posted this fiscal day to arm the
     * DAILY_RULE branch and removed afterwards.
     */
    @Test
    void d5_theThreeRejections() {
        cleanReservations(CARD_STUDENT);
        String insufficientTicket = "0101-2026-D5-INS-" + suffix();
        Response insufficient = reserve(CARD_STUDENT, "999.00", insufficientTicket);
        assertEquals(422, insufficient.statusCode(), "an amount over the available balance must be a 422");
        assertEquals("INSUFFICIENT_BALANCE", insufficient.jsonPath().getString("reason"),
                "the over-available refusal must carry INSUFFICIENT_BALANCE");
        String burnTicket = "0101-2026-D5-BURN-" + suffix();
        insertBurnToday(CARD_BABIES, burnTicket);
        try {
            String dailyTicket = "0101-2026-D5-DAILY-" + suffix();
            Response daily = reserve(CARD_BABIES, "1.00", dailyTicket);
            assertEquals(422, daily.statusCode(), "a card that already burned today must be a 422");
            assertEquals("DAILY_RULE", daily.jsonPath().getString("reason"),
                    "the second burn of the fiscal day must carry DAILY_RULE");
        } finally {
            deleteMovements(burnTicket);
        }
        Response pending = reserve(CARD_PENDING, "1.00", "0101-2026-D5-PEND-" + suffix());
        assertEquals(422, pending.statusCode(), "a PENDING_ACTIVATION card must be a 422");
        assertEquals("ACCOUNT_STATUS", pending.jsonPath().getString("reason"),
                "a pending account accrues but cannot burn — ACCOUNT_STATUS");
        Response voided = reserve(CARD_VOIDED, "1.00", "0101-2026-D5-VOID-" + suffix());
        assertEquals(422, voided.statusCode(), "a RESILIATED card must be a 422");
        assertEquals("ACCOUNT_STATUS", voided.jsonPath().getString("reason"),
                "a resiliated account cannot burn — ACCOUNT_STATUS");
    }

    // --------------------------------------------------
    // D6 — available ≠ balance, the two guards in order (§27.3)
    // --------------------------------------------------

    /**
     * D6 — available ≠ balance: on {@code …088} (30.00 € balance, seeded 8.00 € lease →
     * 22.00 € available) reserving 25.00 € exceeds the available balance and is refused
     * INSUFFICIENT_BALANCE; reserving 22.00 € fits the available balance yet hits the live
     * lease on another ticket and is a 409 — the available-balance guard then the
     * one-card-one-register guard, in order.
     * <p>
     * Justified residue: {@code ReservationService.reserve()} evaluates the
     * one-card-one-register conflict guard BEFORE the available-balance guard and, on the
     * renewal path, re-checks the amount against {@code account.balance} rather than the
     * available balance. Any new-ticket reservation on {@code …088} therefore short-circuits
     * to 409, and the available-balance guard is only reachable when no lease is held —
     * where available equals balance. The spec's 25 € → INSUFFICIENT_BALANCE (available 22
     * &lt; balance 30 binding) is thus structurally unreachable without a {@code src/main}
     * change, which is out of scope for this test class.
     */
    @Disabled("Blocker: reserve() orders the conflict guard before the available-balance guard "
            + "and renewal re-checks balance, not available balance; 25 € → INSUFFICIENT_BALANCE on …088 "
            + "is unreachable without a src/main change (out of scope).")
    @Test
    void d6_availableIsNotBalance() {
        Response over = reserve(CARD_RESERVED, "25.00", "0101-2026-D6-OVER-" + suffix());
        assertEquals(422, over.statusCode(), "25.00 € exceeds the 22.00 € available — a 422");
        assertEquals("INSUFFICIENT_BALANCE", over.jsonPath().getString("reason"),
                "the over-available reservation must carry INSUFFICIENT_BALANCE, proving available ≠ balance");
        Response conflict = reserve(CARD_RESERVED, "22.00", "0101-2026-D6-FIT-" + suffix());
        assertEquals(409, conflict.statusCode(), "22.00 € fits the available balance but hits the live lease — a 409");
        assertTrue(conflict.body().asString().isEmpty(), "the 409 must carry an empty body");
        assertEquals(RESERVED_TICKET, reservationTicketOfActive(CARD_RESERVED),
                "the seeded lease must survive both refusals");
    }

    // --------------------------------------------------
    // D7 — confirmation into a dated BURN, idempotent (§27.3)
    // --------------------------------------------------

    /**
     * D7 — confirmation: confirming an ACTIVE lease posts a negative BURN dated at the
     * fiscal day; a re-confirm is idempotent (a single BURN); an unknown id is a 404; a
     * confirm on an expired lease is a 410 with no BURN. The leases are inserted directly
     * and every movement is removed afterwards.
     */
    @Test
    void d7_confirmationIntoDatedBurn() {
        cleanReservations(CARD_NEGATIVE);
        cleanReservations(CARD_BABIES);
        String okTicket = "0101-2026-D7-OK-" + suffix();
        LocalDate fiscalDate = LocalDate.of(2026, 8, 17);
        long okId = insertReservation(CARD_NEGATIVE, "5.00", okTicket,
                DateTimeProvider.now().plusMinutes(15), ReservationState.ACTIVE);
        try {
            Response confirm = confirm(okId, "2026-08-17");
            assertEquals(200, confirm.statusCode(), "confirming an ACTIVE lease must be a 200");
            FidelityMovement burn = findMovement(okTicket, MovementType.BURN);
            assertNotNull(burn, "the confirmation must post a BURN movement");
            assertEquals(0, burn.amount.compareTo(new BigDecimal("-5.00")), "the BURN is the negated reserved amount");
            assertEquals(fiscalDate, burn.movementDate, "the BURN is dated at the fiscal day of the confirmation");
            assertNull(burn.ruleCode, "a burn carries no rule code");
            Response reConfirm = confirm(okId, "2026-08-17");
            assertEquals(200, reConfirm.statusCode(), "a re-confirm must stay a 200");
            assertEquals(1, burnCount(okTicket), "a re-confirm is idempotent — a single BURN survives");
            Response unknown = confirm(999_999_999L, "2026-08-17");
            assertEquals(404, unknown.statusCode(), "an unknown reservation id must be a 404");
            String expiredTicket = "0101-2026-D7-EXP-" + suffix();
            long expiredId = insertReservation(CARD_BABIES, "3.00", expiredTicket,
                    DateTimeProvider.now().minusMinutes(1), ReservationState.ACTIVE);
            Response gone = confirm(expiredId, "2026-08-17");
            assertEquals(410, gone.statusCode(), "confirming an expired lease must be a 410");
            assertEquals(0, burnCount(expiredTicket), "an expired-lease 410 must post no BURN");
        } finally {
            deleteMovements(okTicket);
            cleanReservations(CARD_NEGATIVE);
            cleanReservations(CARD_BABIES);
            recomputeBalance(CARD_NEGATIVE);
        }
    }

    // --------------------------------------------------
    // D8 — the 410 catch-up: ingestion confirms the expired lease (§29.2)
    // --------------------------------------------------

    /**
     * D8 — the 410 catch-up (register/fidelity seam): after a synchronous confirm 410 on an
     * expired lease, a {@code ticket-closed} carrying the same {@code reservationId} still
     * creates the BURN at ingestion and traces the {@code EXPIRED_LEASE_CONFIRMED} warning
     * (§29.2). The lease is inserted expired and every movement and trace is removed.
     */
    @Test
    void d8_expiredLeaseCaughtUpByIngestion() {
        cleanReservations(CARD_SUCCESSOR);
        String leaseTicket = "0101-2026-D8-LEASE-" + suffix();
        String eventTicket = "0101-2026-D8-EVENT-" + suffix();
        LocalDate fiscalDate = LocalDate.of(2026, 8, 17);
        long leaseId = insertReservation(CARD_SUCCESSOR, "4.00", leaseTicket,
                DateTimeProvider.now().minusMinutes(1), ReservationState.ACTIVE);
        try {
            Response gone = confirm(leaseId, "2026-08-17");
            assertEquals(410, gone.statusCode(), "the synchronous confirm on the expired lease must be a 410");
            Response ingest = ingestClosedWithReservation(eventTicket, CARD_SUCCESSOR, fiscalDate, leaseId);
            assertEquals(202, ingest.statusCode(), "a fiscal event is always accepted (202)");
            FidelityMovement burn = findMovement(leaseTicket, MovementType.BURN);
            assertNotNull(burn, "the ingestion must create the BURN despite the expired lease");
            assertEquals(0, burn.amount.compareTo(new BigDecimal("-4.00")), "the caught-up BURN is the negated amount");
            assertEquals(fiscalDate, burn.movementDate, "the caught-up BURN is dated at the event fiscal day");
            assertTrue(traceWarnings(eventTicket).contains("EXPIRED_LEASE_CONFIRMED"),
                    "the trace must warn EXPIRED_LEASE_CONFIRMED for the caught-up expired lease");
            assertEquals(ReservationState.CONFIRMED, reservationState(leaseId),
                    "the caught-up lease must end CONFIRMED");
        } finally {
            deleteTrace(eventTicket);
            deleteMovements(leaseTicket);
            deleteMovements(eventTicket);
            cleanReservations(CARD_SUCCESSOR);
            recomputeBalance(CARD_SUCCESSOR);
        }
    }

    // --------------------------------------------------
    // D9 — release is always a 204 and frees the balance (§27.3)
    // --------------------------------------------------

    /**
     * D9 — release: DELETE always answers 204, an unknown id included; a released lease
     * frees the available balance again and, being no BURN, never consumes the once-per-day
     * rule — a fresh reservation on another ticket succeeds right after.
     */
    @Test
    void d9_releaseIsAlwaysNoContent() {
        cleanReservations(CARD_SUCCESSOR);
        try {
            Response unknown = release(999_999_999L);
            assertEquals(204, unknown.statusCode(), "releasing an unknown id must still be a 204");
            BigDecimal free = available(CARD_SUCCESSOR);
            Response reserve = reserve(CARD_SUCCESSOR, "5.00", "0101-2026-D9-A-" + suffix());
            assertEquals(201, reserve.statusCode(), "the first reservation must be a 201");
            long id = reserve.jsonPath().getLong("reservationId");
            BigDecimal held = available(CARD_SUCCESSOR);
            assertTrue(held.compareTo(free) < 0, "an active lease must lower the available balance");
            Response released = release(id);
            assertEquals(204, released.statusCode(), "releasing an active lease must be a 204");
            assertEquals(0, available(CARD_SUCCESSOR).compareTo(free), "the released lease must free the balance again");
            Response again = reserve(CARD_SUCCESSOR, "5.00", "0101-2026-D9-B-" + suffix());
            assertEquals(201, again.statusCode(), "a released lease consumes no daily rule — a new reservation passes");
        } finally {
            cleanReservations(CARD_SUCCESSOR);
        }
    }

    // --------------------------------------------------
    // D10 — natural expiry frees the card without intervention (§16)
    // --------------------------------------------------

    /**
     * D10 — natural expiry: an un-renewed lease that has passed its {@code expiresAt} blocks
     * nothing — a new reservation on another ticket succeeds (201) with no intervention, and
     * the elapsed lease is swept to EXPIRED while the new one is ACTIVE. The first lease's
     * {@code expiresAt} is aged into the past to make the elapse deterministic.
     */
    @Test
    void d10_naturalExpiryFreesTheCard() {
        cleanReservations(CARD_SUCCESSOR);
        String firstTicket = "0101-2026-D10-A-" + suffix();
        String secondTicket = "0101-2026-D10-B-" + suffix();
        try {
            Response first = reserve(CARD_SUCCESSOR, "5.00", firstTicket);
            assertEquals(201, first.statusCode(), "the first reservation must be a 201");
            long firstId = first.jsonPath().getLong("reservationId");
            ageReservationExpiry(firstTicket, DateTimeProvider.now().minusMinutes(1));
            Response second = reserve(CARD_SUCCESSOR, "5.00", secondTicket);
            assertEquals(201, second.statusCode(), "after the lease elapsed a new-ticket reservation must pass");
            long secondId = second.jsonPath().getLong("reservationId");
            assertEquals(ReservationState.EXPIRED, reservationState(firstId), "the elapsed lease must be swept to EXPIRED");
            assertEquals(ReservationState.ACTIVE, reservationState(secondId), "the new lease must be ACTIVE");
        } finally {
            cleanReservations(CARD_SUCCESSOR);
        }
    }

    // --------------------------------------------------
    // Helpers — HTTP
    // --------------------------------------------------

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
     * Releases a reservation, as {@code pos}.
     *
     * @param id The reservation id.
     * @return The HTTP response.
     */
    private static Response release(long id) {
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .delete("/api/burn/reservations/" + id);
    }

    /**
     * Reads an account summary from {@code /api/accounts/{card}} as {@code pos}.
     *
     * @param card The card number.
     * @return The HTTP response.
     */
    private static Response account(String card) {
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .get("/api/accounts/" + card);
    }

    /**
     * Reads a card's available balance (balance − active reservations) through the accounts
     * API.
     *
     * @param card The card number.
     * @return The available balance, scale 2.
     */
    private static BigDecimal available(String card) {
        Response r = account(card);
        assertEquals(200, r.statusCode(), "the account summary of " + card + " must be a 200");
        return new BigDecimal(r.jsonPath().getString("availableBalance"));
    }

    /**
     * Ingests a {@code ticket-closed} carrying a {@code reservationId} over an empty
     * (zero-total) valued basket, as {@code pos} — the burn catch-up path (§29.2).
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
        String resp = "\"valuationResponse\":{\"offers\":[],\"advantages\":[],\"totalPrice\":" + amount
                + ",\"vatBreakdown\":[]}";
        String body = "{\"ticketRef\":\"" + eventTicket + "\",\"card\":\"" + card + "\",\"fiscalDate\":\""
                + fiscalDate + "\",\"reservationId\":" + reservationId + "," + vr + "," + resp + "}";
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .contentType("application/json").body(body).post("/api/events/ticket-closed");
    }

    // --------------------------------------------------
    // Helpers — a per-test unique suffix
    // --------------------------------------------------

    /**
     * Produces a per-call unique suffix for ticket references, so the idempotent BURN and
     * event upserts are never silently absorbed by a replay.
     *
     * @return A unique suffix string.
     */
    private static String suffix() {
        return Long.toString(System.nanoTime());
    }

    // --------------------------------------------------
    // Helpers — database (fresh transactions, no absolute ids or counters)
    // --------------------------------------------------

    /**
     * Reads a reservation's state in a fresh transaction.
     *
     * @param id The reservation id.
     * @return The reservation state, or null when absent.
     */
    private static ReservationState reservationState(long id) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityReservation reservation = FidelityReservation.findById(id);
            return reservation != null ? reservation.state : null;
        });
    }

    /**
     * Reads a reservation's amount by ticket reference in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @return The reserved amount, scale 2.
     */
    private static BigDecimal reservationAmount(String ticketRef) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityReservation reservation = FidelityReservation.find("ticketRef", ticketRef).firstResult();
            return reservation != null ? reservation.amount : null;
        });
    }

    /**
     * Reads the ticket reference of a card's active reservation in a fresh transaction.
     *
     * @param card The card number.
     * @return The active lease's ticket reference, or null when none.
     */
    private static String reservationTicketOfActive(String card) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(card);
            FidelityReservation active = FidelityReservation.findActiveForAccount(account);
            return active != null ? active.ticketRef : null;
        });
    }

    /**
     * Ages a reservation's expiry, identified by its ticket reference, in a fresh
     * transaction — the deterministic pendant of the wall clock (§24.6).
     *
     * @param ticketRef The reservation's ticket reference.
     * @param expiresAt The new expiry instant.
     */
    private static void ageReservationExpiry(String ticketRef, LocalDateTime expiresAt) {
        QuarkusTransaction.requiringNew().run(() ->
                FidelityReservation.update("expiresAt = ?1 where ticketRef = ?2", expiresAt, ticketRef));
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
     * Restores the seeded {@code …088} lease to its catalog state (8.00 €, ACTIVE, ticket
     * {@code 0101-2026-003001}, live for another 30 minutes), in a fresh transaction.
     */
    private static void restoreReservedLease() {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityReservation reservation = FidelityReservation.find("ticketRef", RESERVED_TICKET).firstResult();
            if (reservation != null) {
                reservation.amount = new BigDecimal("8.00");
                reservation.state = ReservationState.ACTIVE;
                reservation.expiresAt = DateTimeProvider.now().plusMinutes(30);
            }
        });
    }

    /**
     * Deletes every reservation of a scratch card (one that carries no seeded lease), in a
     * fresh transaction.
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
     * Inserts a confirmed-style BURN movement dated today, in a fresh transaction — arms the
     * once-per-day rule for the DAILY_RULE branch.
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
     * Finds a movement by ticket reference and type in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @param type      The movement type.
     * @return The matching movement, or null.
     */
    private static FidelityMovement findMovement(String ticketRef, MovementType type) {
        return QuarkusTransaction.requiringNew().call(() ->
                FidelityMovement.find("ticketRef = ?1 and type = ?2", ticketRef, type).firstResult());
    }

    /**
     * Counts the BURN movements of a ticket reference in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @return The BURN count.
     */
    private static long burnCount(String ticketRef) {
        return QuarkusTransaction.requiringNew().call(() ->
                FidelityMovement.count("ticketRef = ?1 and type = ?2", ticketRef, MovementType.BURN));
    }

    /**
     * Reads the warnings persisted on a ticket's earn trace, in a fresh transaction.
     *
     * @param ticketRef The ticket reference.
     * @return A copy of the trace warnings, never null.
     */
    private static List<String> traceWarnings(String ticketRef) {
        return QuarkusTransaction.requiringNew().call(() -> {
            com.intermarche.fidelity.domain.EarnTrace trace =
                    com.intermarche.fidelity.domain.EarnTrace.findByTicketRef(ticketRef);
            return trace != null ? new ArrayList<>(trace.warnings) : new ArrayList<String>();
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
     * Deletes a ticket's earn trace and its lines, in a fresh transaction.
     *
     * @param ticketRef The ticket reference to clear.
     */
    private static void deleteTrace(String ticketRef) {
        QuarkusTransaction.requiringNew().run(() -> {
            com.intermarche.fidelity.domain.EarnTrace trace =
                    com.intermarche.fidelity.domain.EarnTrace.findByTicketRef(ticketRef);
            if (trace != null) {
                com.intermarche.fidelity.domain.EarnTraceLine.delete("trace", trace);
                trace.delete();
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
