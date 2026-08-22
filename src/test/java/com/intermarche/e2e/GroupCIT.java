package com.intermarche.e2e;

import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.EarnTraceLine;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group C — the {@code POST /api/earn} projection
 * (e2escenarios-imfid.md "## C"). Every scenario is plain HTTP: RestAssured over the
 * real application booted by {@link QuarkusTest} with the DataInitializer-rebuilt 2026
 * world, authenticated Basic {@code pos} (the {@code @RolesAllowed(pos)} guard of the
 * earn/ingestion surfaces). No scenario in this group is [W] or [P], so all seventeen are
 * implemented at the RestAssured tier.
 * <p>
 * Every crafted {@code /valuation} couple satisfies the §22.1 reconciliation invariants
 * (offers-only, one Standard offer per line whose {@code amount} equals its single item,
 * total = Σ offers, no discounts) unless the scenario deliberately breaks them (C3). Earn
 * amounts are computed against the real engine semantics: the socle rule ({@code
 * SOCLE_5_MARQUES}) earns 5 % of the net socle assiette below the 4th-visit boost and
 * 10 % at or above it, on brands {Pâturages, Paquito, Fiorini, Labell, Mäy}, once three
 * eligible items are present (a UNIT quantity counts its units). Dated evaluations go
 * through the payload's {@code createdAt}, never the real clock; the 4th-visit and
 * challenge scenarios age the relevant rows via {@link QuarkusTransaction} to stay
 * deterministic on every calendar day (§24.4). BigDecimal amounts are compared by {@code
 * compareTo} (§30.5); DB assertions never rely on absolute ids or counters, and any row a
 * test creates is cleaned up in the same test.
 */
@QuarkusTest
class GroupCIT {

    /**
     * Bootstrap machine login (SecurityBootstrap default), role {@code pos}.
     */
    private static final String POS_USER = "pos";

    /**
     * Bootstrap machine password (SecurityBootstrap default in dev/test).
     */
    private static final String POS_PASSWORD = "pos-password";

    /**
     * Rich-history card: three armable visits, e-coupon and challenge activations (…019).
     */
    private static final String CARD_RICH = "2990000000019";

    /**
     * Students-community member card, monthly-date and community targets (…033).
     */
    private static final String CARD_STUDENT = "2990000000033";

    /**
     * Successor card: ACTIVE, no visits, no memberships, no activations — the neutral
     * earning card used where a bare 5 % socle rate is wanted (…071).
     */
    private static final String CARD_PLAIN = "2990000000071";

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
     * Chocolate biscuits, brand Fiorini (socle), UNIT — a socle assiette line and a
     * bundle line.
     */
    private static final String EAN_BISCUITS = "3300000000013";

    /**
     * Crisps 150g, brand "Brand N", family F_L, UNIT — a weekend F&amp;L line, not socle.
     */
    private static final String EAN_CHIPS = "3300000000014";

    /**
     * Shower gel, brand Labell (socle + Students scope), no family, UNIT — a Students
     * community line that stays below the socle item threshold alone.
     */
    private static final String EAN_GEL_LABELL = "3400000000002";

    /**
     * Feminine-hygiene pads, brand Labell, family HYGIENE_FEM, UNIT — the 40 %-on-the-28th
     * target.
     */
    private static final String EAN_HYGIENE = "3400000000030";

    /**
     * Cat food 1.5kg, family RAYON_ECOUPON, no brand, UNIT — the e-coupon target.
     */
    private static final String EAN_CROQUETTES = "3400000000040";

    /**
     * Gift card 50 €, family CARTES_CADEAUX, UNIT — a CGU program-exclusion line.
     */
    private static final String EAN_GIFTCARD = "3400000000060";

    /**
     * Lay's crisps, brand Lay's, no family, UNIT — the challenge target.
     */
    private static final String EAN_LAYS = "3400000000070";

    /**
     * Non-stick pan, brand Tefal, no family, UNIT — a scope carrier for the degraded-spec
     * custom rule (no seeded rule targets Tefal).
     */
    private static final String EAN_TEFAL = "3300000000031";

    /**
     * Stainless pot, brand Staub, no family, UNIT — a scope carrier for the priority
     * custom rules (no seeded rule targets Staub).
     */
    private static final String EAN_STAUB = "3300000000032";

    /**
     * An EAN absent from the imfid product reference, for the UNKNOWN_EAN warning.
     */
    private static final String EAN_UNKNOWN = "3300000000404";

    // --------------------------------------------------
    // C1 — the projection is a pure read (§30.2)
    // --------------------------------------------------

    /**
     * C1 — pure read: after a fully earning {@code /earn} (a socle basket on the rich
     * card) the account, movement, earn-trace and visit tables are byte-for-byte
     * unchanged — the contrast with the ingestion credit (F6) is the contract (§30.2).
     */
    @Test
    void c1_projectionWritesNothing() {
        long accountsBefore = count(() -> FidelityAccount.count());
        long movementsBefore = count(() -> FidelityMovement.count());
        long tracesBefore = count(() -> EarnTrace.count());
        Response earn = postEarn(earnBody(CARD_RICH, "2026-08-17T10:00:00", socleOffers(), "6.00"));
        assertEquals(200, earn.statusCode(), "a valid /earn must answer 200");
        assertEquals(accountsBefore, count(() -> FidelityAccount.count()), "no account may be written by a projection");
        assertEquals(movementsBefore, count(() -> FidelityMovement.count()), "no movement may be written by a projection");
        assertEquals(tracesBefore, count(() -> EarnTrace.count()), "no earn-trace or visit may be written by a projection");
    }

    // --------------------------------------------------
    // C2 — missing valuation response is a 400
    // --------------------------------------------------

    /**
     * C2 — 400: a body carrying no {@code valuationResponse} is rejected with the literal
     * {@code {"error":"Missing valuation response in /earn request"}} (§27.1).
     */
    @Test
    void c2_missingValuationResponseIs400() {
        Response r = postEarn("{\"valuationRequest\":{\"customerCode\":\"" + CARD_RICH + "\",\"storeCode\":\"0101\"}}");
        assertEquals(400, r.statusCode(), "a body without a valuation response must be a 400");
        assertEquals("Missing valuation response in /earn request", r.jsonPath().getString("error"),
                "the 400 must carry the literal missing-response message");
    }

    // --------------------------------------------------
    // C3 — reconciliation violations are 422 (§25.2)
    // --------------------------------------------------

    /**
     * C3 — 422 reconciliation: an incoherent global total (&gt; 1 centime) yields the
     * {@code Incoherent totals: totalPrice …} message; an offer whose Σ items differs from
     * its amount yields {@code Offer '<type>': Σ items …}; both are logged WARN as {@code
     * Rejected /earn: reconciliation failed: <msg>} and never persisted (§25.2, §30.2).
     * The calibrated message tails are frozen through their stable prefixes and the closed
     * middle tokens ("≠ Σoffers", "≠ amount").
     */
    @Test
    void c3_reconciliationViolationsAre422() {
        int logBefore = BootLogCapture.messages().size();
        Response totals = postEarn(earnBody(CARD_RICH, "2026-08-17T10:00:00", socleOffers(), "5.00"));
        assertEquals(422, totals.statusCode(), "an incoherent global total must be a 422");
        String totalsError = totals.jsonPath().getString("error");
        assertTrue(totalsError.startsWith("Incoherent totals: totalPrice "),
                "the totals 422 must carry the frozen Incoherent-totals prefix but was " + totalsError);
        assertTrue(totalsError.contains("Σoffers"), "the totals 422 must name Σoffers but was " + totalsError);
        String offerType = "MixedBundle: PROMO_COFFEE_PACK x1 for 4.50€";
        String badOffer = offerObject(offerType, "4.50",
                offerItem("L3", EAN_COFFEE, "1.0", "2.86") + "," + offerItem("L4", EAN_BISCUITS, "1.0", "0.50"));
        Response offer = postEarn(earnBody(CARD_RICH, "2026-08-17T10:00:00", List.of(badOffer), "4.50"));
        assertEquals(422, offer.statusCode(), "an offer whose items do not sum to its amount must be a 422");
        String offerError = offer.jsonPath().getString("error");
        assertTrue(offerError.startsWith("Offer '" + offerType + "': Σ items "),
                "the offer 422 must carry the frozen Offer prefix but was " + offerError);
        assertTrue(offerError.contains("amount"), "the offer 422 must name the offer amount but was " + offerError);
        List<String> messages = BootLogCapture.messages().subList(logBefore, BootLogCapture.messages().size());
        assertTrue(messages.stream().anyMatch(m -> m.startsWith("Rejected /earn: reconciliation failed: Incoherent totals")),
                "the totals rejection must be logged WARN with the reconciliation-failed line");
        assertTrue(messages.stream().anyMatch(m -> m.startsWith("Rejected /earn: reconciliation failed: Offer '")),
                "the offer rejection must be logged WARN with the reconciliation-failed line");
    }

    // --------------------------------------------------
    // C4 — absent or unknown card is an empty earn (§20)
    // --------------------------------------------------

    /**
     * C4 — empty earn: a card that is absent, blank or the never-seeded {@code
     * 9999999999999} answers 200 with {@code total 0} and no entries — never a 404, never
     * an auto-created account (§20). The burnable base is still computed.
     */
    @Test
    void c4_absentOrUnknownCardIsEmptyEarn() {
        long accountsBefore = count(() -> FidelityAccount.count());
        Response absent = postEarn(earnBodyNoCard("2026-08-17T10:00:00", socleOffers(), "6.00"));
        assertEmptyEarn(absent, "an absent card must be an empty earn");
        Response blank = postEarn(earnBody("", "2026-08-17T10:00:00", socleOffers(), "6.00"));
        assertEmptyEarn(blank, "a blank card must be an empty earn");
        Response unknown = postEarn(earnBody("9999999999999", "2026-08-17T10:00:00", socleOffers(), "6.00"));
        assertEmptyEarn(unknown, "an unknown card must be an empty earn");
        assertEquals(accountsBefore, count(() -> FidelityAccount.count()), "no account may be auto-created for a card");
        assertNull(findAccount("9999999999999"), "the unknown card must never have been created");
    }

    // --------------------------------------------------
    // C5 — dated evaluation drives windows and versions
    // --------------------------------------------------

    /**
     * C5 — dated evaluation: the same F&amp;L basket lights FL_WEEKEND on a Saturday and
     * not on a Monday; the same socle basket answers SOCLE_4_MARQUES before 2026-05-18 and
     * SOCLE_5_MARQUES on/after it (versioned windows); and a missing {@code createdAt}
     * falls back to the program clock (now, 2026 — the SOCLE_5 era).
     */
    @Test
    void c5_datedEvaluationDrivesWindowsAndVersions() {
        assertEquals(DayOfWeek.SATURDAY, LocalDate.of(2026, 8, 8).getDayOfWeek(), "2026-08-08 must be a Saturday");
        assertEquals(DayOfWeek.MONDAY, LocalDate.of(2026, 8, 10).getDayOfWeek(), "2026-08-10 must be a Monday");
        List<String> chips = List.of(standardOffer("L1", EAN_CHIPS, "1.0", "1.80"));
        Response saturday = postEarn(earnBody(CARD_PLAIN, "2026-08-08T10:00:00", chips, "1.80"));
        assertNotNull(entry(saturday, "FL_WEEKEND"), "FL_WEEKEND must light on a Saturday");
        Response monday = postEarn(earnBody(CARD_PLAIN, "2026-08-10T10:00:00", chips, "1.80"));
        assertNull(entry(monday, "FL_WEEKEND"), "FL_WEEKEND must be dark on a Monday");
        Response before = postEarn(earnBody(CARD_PLAIN, "2026-05-17T10:00:00", socleOffers(), "6.00"));
        assertNotNull(entry(before, "SOCLE_4_MARQUES"), "before 2026-05-18 the 4-brand socle version answers");
        assertNull(entry(before, "SOCLE_5_MARQUES"), "the 5-brand socle version is not yet in force before 2026-05-18");
        Response onward = postEarn(earnBody(CARD_PLAIN, "2026-05-18T10:00:00", socleOffers(), "6.00"));
        assertNotNull(entry(onward, "SOCLE_5_MARQUES"), "on 2026-05-18 the 5-brand socle version answers");
        assertNull(entry(onward, "SOCLE_4_MARQUES"), "the 4-brand socle version is frozen from 2026-05-18");
        Response noDate = postEarn(earnBodyNoDate(CARD_PLAIN, socleOffers(), "6.00"));
        assertEquals(200, noDate.statusCode(), "a missing createdAt must fall back to the program clock, not error");
        assertNotNull(entry(noDate, "SOCLE_5_MARQUES"), "the program-clock fallback resolves to the current SOCLE_5 era");
    }

    // --------------------------------------------------
    // C6 — UNKNOWN_EAN: out of every assiette, inside the burnable base (§25.4)
    // --------------------------------------------------

    /**
     * C6 — UNKNOWN_EAN: a line whose EAN is unknown to the imfid reference raises the
     * literal §25.4 warning and proves both halves — the line earns nothing (no entry) yet
     * stays inside the burnable base (§25.4).
     */
    @Test
    void c6_unknownEanWarnsButStaysBurnable() {
        Response r = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00",
                List.of(standardOffer("L1", EAN_UNKNOWN, "1.0", "5.00")), "5.00"));
        assertEquals(200, r.statusCode(), "an unknown EAN never fails the projection");
        Map<String, ?> warning = r.jsonPath().getMap("warnings.find { it.code == 'UNKNOWN_EAN' }");
        assertNotNull(warning, "an unknown EAN must raise an UNKNOWN_EAN warning");
        assertEquals(EAN_UNKNOWN, warning.get("ean"), "the warning must carry the offending EAN");
        assertEquals("EAN " + EAN_UNKNOWN + " unknown to the imfid product reference; line kept out of every "
                        + "earn assiette but inside the burnable base (§25.4)", warning.get("message"),
                "the warning must carry the frozen §25.4 literal");
        assertTrue(r.jsonPath().getList("entries").isEmpty(), "the unknown line must be out of every earn assiette");
        assertEquals(0, decimal(r, "burnableBase").compareTo(new BigDecimal("5.00")),
                "the unknown line must stay inside the burnable base");
    }

    // --------------------------------------------------
    // C7 — I2 non-cumul with commercial offers, the canonical trap
    // --------------------------------------------------

    /**
     * C7 — I2 non-cumul: coffee + biscuits absorbed by the {@code PROMO_COFFEE_PACK} bundle
     * are consumed lines — earn 0, no warning (a green); the reference socle cart of milk +
     * water + yoghurt (three socle units, no offer) earns about 5 % of its net (§22.1, I2).
     */
    @Test
    void c7_nonCumulWithCommercialOffers() {
        String bundle = offerObject("MixedBundle: PROMO_COFFEE_PACK x1 for 4.50€", "4.50",
                offerItem("L3", EAN_COFFEE, "1.0", "2.86") + "," + offerItem("L4", EAN_BISCUITS, "1.0", "1.64"));
        Response consumed = postEarn(earnBody(CARD_RICH, "2026-08-17T10:00:00", List.of(bundle), "4.50"));
        assertEquals(200, consumed.statusCode(), "the bundle basket must be a clean 200");
        assertEquals(0, decimal(consumed, "total").compareTo(BigDecimal.ZERO), "consumed lines must earn nothing");
        assertTrue(consumed.jsonPath().getList("entries").isEmpty(), "consumed lines must produce no entry");
        assertTrue(consumed.jsonPath().getList("warnings").isEmpty(), "a bundle non-cumul is silent — no warning");
        Response earning = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00", socleOffers(), "6.00"));
        Map<String, ?> socle = entry(earning, "SOCLE_5_MARQUES");
        assertNotNull(socle, "the reference socle cart must earn");
        assertEquals(0, new BigDecimal(String.valueOf(socle.get("amount"))).compareTo(new BigDecimal("0.30")),
                "the socle earn is 5 % of the 6.00 € net = 0.30 €");
    }

    // --------------------------------------------------
    // C8 — socle assiette counted in units (§22.2)
    // --------------------------------------------------

    /**
     * C8 — socle in units: one coffee + one biscuits = two eligible items, below the
     * threshold of three, earns nothing; two coffees + one biscuits = three items earns —
     * a UNIT quantity multiplies the item count, a weighing counts one (§22.2).
     */
    @Test
    void c8_socleAssietteCountedInUnits() {
        List<String> two = List.of(
                standardOffer("L1", EAN_COFFEE, "1.0", "4.00"),
                standardOffer("L2", EAN_BISCUITS, "1.0", "2.00"));
        Response below = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00", two, "6.00"));
        assertEquals(0, decimal(below, "total").compareTo(BigDecimal.ZERO), "two items are below the socle threshold");
        assertNull(entry(below, "SOCLE_5_MARQUES"), "below three eligible items the socle grants nothing");
        List<String> three = List.of(
                standardOffer("L1", EAN_COFFEE, "2.0", "4.00"),
                standardOffer("L2", EAN_BISCUITS, "1.0", "2.00"));
        Response ok = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00", three, "6.00"));
        Map<String, ?> socle = entry(ok, "SOCLE_5_MARQUES");
        assertNotNull(socle, "two coffee units plus one biscuits reach three eligible items");
        assertEquals(0, new BigDecimal(String.valueOf(socle.get("amount"))).compareTo(new BigDecimal("0.30")),
                "the socle earn is 5 % of the 6.00 € net = 0.30 €");
    }

    // --------------------------------------------------
    // C9 — the 4th-visit boost (§24.4, I3) [cross A6]
    // --------------------------------------------------

    /**
     * C9 — 4th-visit boost: the rich card, aged to exactly three distinct visit days this
     * month, earns the socle at 10 % on the call itself (the current visit is the fourth),
     * while a card with no visit this month earns the same basket at 5 %. The visit rows
     * are aged rather than read from the run date, so the test is never calendar-flaky.
     */
    @Test
    void c9_fourthVisitBoost() {
        LocalDate today = DateTimeProvider.now().toLocalDate();
        LocalDate monthStart = today.withDayOfMonth(1);
        ageAllTraces(CARD_RICH, monthStart.minusMonths(1));
        ageTrace(CARD_RICH, "0101-2026-002001", monthStart);
        ageTrace(CARD_RICH, "0101-2026-002088", monthStart.plusDays(1));
        ageTrace(CARD_RICH, "0101-2026-002150", monthStart.plusDays(2));
        String createdAt = monthStart.plusDays(14).atTime(10, 0).toString();
        Response boosted = postEarn(earnBody(CARD_RICH, createdAt, socleOffers(), "6.00"));
        Map<String, ?> boostedSocle = entry(boosted, "SOCLE_5_MARQUES");
        assertNotNull(boostedSocle, "the rich card must earn the socle");
        assertEquals(0, new BigDecimal(String.valueOf(boostedSocle.get("amount"))).compareTo(new BigDecimal("0.60")),
                "at the fourth visit the socle boosts to 10 % of 6.00 € = 0.60 €");
        Response base = postEarn(earnBody(CARD_PLAIN, createdAt, socleOffers(), "6.00"));
        Map<String, ?> baseSocle = entry(base, "SOCLE_5_MARQUES");
        assertNotNull(baseSocle, "a no-visit card must still earn the socle");
        assertEquals(0, new BigDecimal(String.valueOf(baseSocle.get("amount"))).compareTo(new BigDecimal("0.30")),
                "with no prior visit the socle stays at the 5 % base rate = 0.30 €");
    }

    // --------------------------------------------------
    // C10 — the three cap floors (§15, I5, §27.2)
    // --------------------------------------------------

    /**
     * C10 — the cap floors: a Students basket large enough to overflow the caps truncates
     * at the community floor, tracing {@code {"scope":"COMMUNITY:STUDENTS","capAmount":
     * 20.00,"truncatedBy":…}}; every cap trace scope stays inside the closed nomenclature
     * {@code RULE:<code>} | {@code COMMUNITY:<code>} | {@code GLOBAL}. A controlled
     * hygiene-28 earn is posted this month so the community cumulative exceeds the rule
     * cumulative and the community floor bites distinctly; it is removed afterwards.
     */
    @Test
    void c10_theThreeCapFloors() {
        LocalDate today = DateTimeProvider.now().toLocalDate();
        LocalDate evalDate = today.withDayOfMonth(15);
        String ticketRef = "0101-2026-C10-HYGIENE";
        postEarnMovement(CARD_STUDENT, "STUDENTS_HYGIENE_28", "5.00", evalDate, ticketRef);
        try {
            BigDecimal ruleUsed = ruleEarnThisMonth(CARD_STUDENT, "COMMUNITY_STUDENTS", evalDate);
            BigDecimal communityUsed = studentsCommunityEarnThisMonth(CARD_STUDENT, evalDate);
            BigDecimal expectedGranted = new BigDecimal("20.00").subtract(communityUsed).setScale(2, RoundingMode.HALF_UP);
            Response r = postEarn(earnBody(CARD_STUDENT, evalDate.atTime(10, 0).toString(),
                    List.of(standardOffer("L1", EAN_GEL_LABELL, "1.0", "300.00")), "300.00"));
            assertEquals(200, r.statusCode(), "the Students basket must answer 200");
            Map<String, ?> community = r.jsonPath().getMap("capsApplied.find { it.scope == 'COMMUNITY:STUDENTS' }");
            assertNotNull(community, "the community floor must trace a COMMUNITY:STUDENTS truncation");
            assertEquals(0, new BigDecimal(String.valueOf(community.get("capAmount"))).compareTo(new BigDecimal("20.00")),
                    "the community cap value is 20.00 €");
            assertEquals(0, new BigDecimal(String.valueOf(community.get("truncatedBy"))).compareTo(new BigDecimal("5.00")),
                    "the community floor removes exactly the hygiene-28 overflow (5.00 €) above the rule floor");
            Map<String, ?> entry = entry(r, "COMMUNITY_STUDENTS");
            assertNotNull(entry, "the Students community rule must still grant its capped earn");
            assertEquals(0, new BigDecimal(String.valueOf(entry.get("amount"))).compareTo(expectedGranted),
                    "the granted earn is the community headroom 20.00 − used = " + expectedGranted);
            assertEquals(0, decimal(r, "total").compareTo(expectedGranted), "the total equals the single capped entry");
            List<Map<String, ?>> caps = r.jsonPath().getList("capsApplied");
            for (Map<String, ?> cap : caps) {
                String scope = String.valueOf(cap.get("scope"));
                assertTrue(scope.matches("RULE:.+|COMMUNITY:.+|GLOBAL"),
                        "every cap scope must stay in the closed nomenclature but was " + scope);
            }
            assertTrue(ruleUsed.compareTo(communityUsed) < 0,
                    "the seeded hygiene-28 earn must push the community cumulative above the rule cumulative");
        } finally {
            deleteMovements(ticketRef);
        }
    }

    // --------------------------------------------------
    // C11 — once per month (§15, hasEarnedThisPeriod)
    // --------------------------------------------------

    /**
     * C11 — once per period: on the 28th the Students card earns the 40 % hygiene-Labell
     * rule; once a ticket is ingested crediting it this month, a second evaluation the same
     * 28th finds {@code STUDENTS_HYGIENE_28} silent (its per-period uniqueness is the
     * existence of an EARN of the code this month). The ingested ticket is removed
     * afterwards; its {@code ticketRef} is unique so the idempotent upsert is not absorbed.
     */
    @Test
    void c11_oncePerMonth() {
        LocalDate today = DateTimeProvider.now().toLocalDate();
        LocalDate day28 = today.withDayOfMonth(28);
        String createdAt = day28.atTime(10, 0).toString();
        String ticketRef = "0101-2026-C11-HYGIENE";
        List<String> hygiene = List.of(standardOffer("L1", EAN_HYGIENE, "1.0", "10.00"));
        try {
            Response first = postEarn(earnBody(CARD_STUDENT, createdAt, hygiene, "10.00"));
            Map<String, ?> firstEntry = entry(first, "STUDENTS_HYGIENE_28");
            assertNotNull(firstEntry, "on the 28th the first evaluation lights the 40 % hygiene rule");
            assertEquals(0, new BigDecimal(String.valueOf(firstEntry.get("amount"))).compareTo(new BigDecimal("4.00")),
                    "the hygiene rule earns 40 % of the 10.00 € net = 4.00 €");
            ingestClosed(ticketRef, CARD_STUDENT, day28, hygiene, "10.00");
            assertTrue(hasEarn(ticketRef, "STUDENTS_HYGIENE_28"), "the ingestion must credit the hygiene rule this month");
            Response second = postEarn(earnBody(CARD_STUDENT, createdAt, hygiene, "10.00"));
            assertNull(entry(second, "STUDENTS_HYGIENE_28"),
                    "a second evaluation the same period finds the once-per-month rule silent");
        } finally {
            deleteTrace(ticketRef);
            deleteMovements(ticketRef);
        }
    }

    // --------------------------------------------------
    // C12 — activations gate e-coupons and challenges (§14)
    // --------------------------------------------------

    /**
     * C12 — activations: an e-coupon basket earns nothing without a card activation and
     * earns with one (the rich card); the challenge, seeded with a 4.50 € running base and
     * a done mission, crosses the 5 € tier with a 0.60 € Lay's purchase and grants the
     * differential reward of 1.00 €. The challenge trace is aged into the current month so
     * the running base is deterministic.
     */
    @Test
    void c12_activationsGateEcouponAndChallenge() {
        LocalDate today = DateTimeProvider.now().toLocalDate();
        LocalDate monthStart = today.withDayOfMonth(1);
        String createdAt = monthStart.plusDays(14).atTime(10, 0).toString();
        List<String> croquettes = List.of(standardOffer("L1", EAN_CROQUETTES, "1.0", "5.00"));
        Response noActivation = postEarn(earnBody(CARD_PLAIN, createdAt, croquettes, "5.00"));
        assertTrue(noActivation.jsonPath().getList("entries").isEmpty(),
                "without an activation the e-coupon grants nothing");
        Response activated = postEarn(earnBody(CARD_RICH, createdAt, croquettes, "5.00"));
        Map<String, ?> ecoupon = entry(activated, "ECOUPON_DEMO");
        assertNotNull(ecoupon, "with an activation the e-coupon earns");
        assertEquals(0, new BigDecimal(String.valueOf(ecoupon.get("amount"))).compareTo(new BigDecimal("1.00")),
                "the e-coupon earns 20 % of the 5.00 € net = 1.00 €");
        ageTrace(CARD_RICH, "0101-2026-002001", monthStart.plusDays(2));
        Response challenge = postEarn(earnBody(CARD_RICH, createdAt,
                List.of(standardOffer("L1", EAN_LAYS, "1.0", "0.60")), "0.60"));
        Map<String, ?> challengeEntry = entry(challenge, "CHALLENGE_DEMO");
        assertNotNull(challengeEntry, "crossing the challenge tier must grant a reward");
        assertEquals(0, new BigDecimal(String.valueOf(challengeEntry.get("amount"))).compareTo(new BigDecimal("1.00")),
                "the 4.50 € base plus 0.60 € crosses the 5 € tier for a differential gain of 1.00 €");
    }

    // --------------------------------------------------
    // C13 — degraded specifications are tolerated (§31.2)
    // --------------------------------------------------

    /**
     * C13 — degraded specs: a rule persisted directly with {@code activeDays:["MONDAY",
     * null,""]} recognises MONDAY while ignoring the JSON null and the blank (no phantom
     * every-day from {@code ""} — the {@code isBlank} guard is semantic), and a challenge
     * with {@code tiers:[1,null,{…}]} keeps only the object tier. No 500: the degradation
     * is silent (§31.2). The custom rules target the Tefal brand no seeded rule touches and
     * are removed afterwards.
     */
    @Test
    void c13_degradedSpecificationsTolerated() {
        assertEquals(DayOfWeek.MONDAY, LocalDate.of(2026, 8, 10).getDayOfWeek(), "2026-08-10 must be a Monday");
        assertEquals(DayOfWeek.TUESDAY, LocalDate.of(2026, 8, 11).getDayOfWeek(), "2026-08-11 must be a Tuesday");
        String calCode = "C13_CAL_DEGRADED";
        String calSpec = "{\"scope\":{\"include\":{\"brands\":[\"Tefal\"]}},\"rate\":0.10,"
                + "\"activeDays\":[\"MONDAY\",null,\"\"]}";
        persistRule(calCode, FidelityRule.TYPE_CALENDAR_FAMILY_EARN, "C13 degraded calendar",
                LocalDateTime.of(2026, 1, 1, 0, 0), 95, true, null, calSpec);
        try {
            List<String> pan = List.of(standardOffer("L1", EAN_TEFAL, "1.0", "10.00"));
            Response monday = postEarn(earnBody(CARD_PLAIN, "2026-08-10T10:00:00", pan, "10.00"));
            assertEquals(200, monday.statusCode(), "a degraded spec must never 500");
            Map<String, ?> mondayEntry = entry(monday, calCode);
            assertNotNull(mondayEntry, "MONDAY must be recognised from the degraded activeDays");
            assertEquals(0, new BigDecimal(String.valueOf(mondayEntry.get("amount"))).compareTo(new BigDecimal("1.00")),
                    "the degraded calendar rule earns 10 % of 10.00 € = 1.00 € on its active day");
            Response tuesday = postEarn(earnBody(CARD_PLAIN, "2026-08-11T10:00:00", pan, "10.00"));
            assertEquals(200, tuesday.statusCode(), "a degraded spec must never 500 on an inactive day");
            assertNull(entry(tuesday, calCode),
                    "the blank token must not become a phantom every-day: Tuesday stays dark");
        } finally {
            deleteRule(calCode);
        }
    }

    // --------------------------------------------------
    // C14 — descending priority, exclusivity consumes (I2)
    // --------------------------------------------------

    /**
     * C14 — descending priority: two live rules on the same Staub brand, the higher one
     * exclusive, resolve so only the higher-priority rule earns and the lower receives
     * nothing (its lines consumed); swapping the priorities flips which rule earns — the
     * data-borne non-cumul arbitration between rules (I2). The custom rules are removed
     * afterwards.
     */
    @Test
    void c14_descendingPriorityExclusiveConsumes() {
        String high = "C14_HIGH";
        String low = "C14_LOW";
        persistRule(high, FidelityRule.TYPE_CALENDAR_FAMILY_EARN, "C14 high", LocalDateTime.of(2026, 1, 1, 0, 0),
                100, true, null, staubSpec("0.10"));
        persistRule(low, FidelityRule.TYPE_CALENDAR_FAMILY_EARN, "C14 low", LocalDateTime.of(2026, 1, 1, 0, 0),
                90, true, null, staubSpec("0.20"));
        try {
            List<String> pot = List.of(standardOffer("L1", EAN_STAUB, "1.0", "10.00"));
            Response r = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00", pot, "10.00"));
            assertNotNull(entry(r, high), "the higher-priority exclusive rule must earn first");
            assertNull(entry(r, low), "the lower-priority rule receives nothing — the line is consumed");
            assertEquals(0, new BigDecimal(String.valueOf(entry(r, high).get("amount"))).compareTo(new BigDecimal("1.00")),
                    "the high rule earns 10 % of 10.00 € = 1.00 €");
            swapPriorities(high, 90, low, 100);
            Response flipped = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00", pot, "10.00"));
            assertNotNull(entry(flipped, low), "after the swap the now-higher rule earns");
            assertNull(entry(flipped, high), "the now-lower rule receives nothing");
            assertEquals(0, new BigDecimal(String.valueOf(entry(flipped, low).get("amount"))).compareTo(new BigDecimal("2.00")),
                    "the earn flips to the other rule's 20 % of 10.00 € = 2.00 €");
        } finally {
            deleteRule(high);
            deleteRule(low);
        }
    }

    // --------------------------------------------------
    // C15 — a non-exclusive rule shares its assiette
    // --------------------------------------------------

    /**
     * C15 — exclusive consumes, non-exclusive shares: the same two-rule Staub mount with
     * the higher-priority rule made non-exclusive lets both rules credit the same assiette
     * — a deliberate cumul. The pair C14/C15 freezes the semantics of the exclusive flag.
     * The custom rules are removed afterwards.
     */
    @Test
    void c15_nonExclusiveRuleSharesAssiette() {
        String high = "C15_HIGH";
        String low = "C15_LOW";
        persistRule(high, FidelityRule.TYPE_CALENDAR_FAMILY_EARN, "C15 high", LocalDateTime.of(2026, 1, 1, 0, 0),
                100, false, null, staubSpec("0.10"));
        persistRule(low, FidelityRule.TYPE_CALENDAR_FAMILY_EARN, "C15 low", LocalDateTime.of(2026, 1, 1, 0, 0),
                90, true, null, staubSpec("0.20"));
        try {
            List<String> pot = List.of(standardOffer("L1", EAN_STAUB, "1.0", "10.00"));
            Response r = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00", pot, "10.00"));
            Map<String, ?> highEntry = entry(r, high);
            Map<String, ?> lowEntry = entry(r, low);
            assertNotNull(highEntry, "the non-exclusive higher rule earns");
            assertNotNull(lowEntry, "the lower rule earns the same shared assiette");
            assertEquals(0, new BigDecimal(String.valueOf(highEntry.get("amount"))).compareTo(new BigDecimal("1.00")),
                    "the non-exclusive rule earns 10 % of 10.00 € = 1.00 €");
            assertEquals(0, new BigDecimal(String.valueOf(lowEntry.get("amount"))).compareTo(new BigDecimal("2.00")),
                    "the shared rule earns 20 % of the same 10.00 € = 2.00 €");
            assertEquals(0, decimal(r, "total").compareTo(new BigDecimal("3.00")),
                    "both entries credit the same base — total 3.00 € (voluntary cumul)");
        } finally {
            deleteRule(high);
            deleteRule(low);
        }
    }

    // --------------------------------------------------
    // C16 — the entries contract (§18, §29.4)
    // --------------------------------------------------

    /**
     * C16 — entries contract: each entry carries a stable {@code ruleCode}, a printable
     * {@code label} (the only rule datum that travels, §18), a post-cap {@code amount}, a
     * {@code baseAmount} and {@code lineIds} equal to exactly the assiette lines — never
     * empty on a non-null entry (§29.4).
     */
    @Test
    void c16_entriesContract() {
        Response r = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00", socleOffers(), "6.00"));
        Map<String, ?> socle = entry(r, "SOCLE_5_MARQUES");
        assertNotNull(socle, "the socle entry must be present");
        assertEquals("SOCLE_5_MARQUES", socle.get("ruleCode"), "the entry must carry the stable rule code");
        assertEquals("Marques du quotidien (5 marques)", socle.get("label"), "the entry must carry the printable label");
        assertEquals(0, new BigDecimal(String.valueOf(socle.get("amount"))).compareTo(new BigDecimal("0.30")),
                "the amount is the post-cap earn 0.30 €");
        assertEquals(0, new BigDecimal(String.valueOf(socle.get("baseAmount"))).compareTo(new BigDecimal("6.00")),
                "the baseAmount is the net eligible assiette 6.00 €");
        List<?> lineIds = (List<?>) socle.get("lineIds");
        assertEquals(List.of("L1", "L2", "L3"), lineIds, "the lineIds must be exactly the three assiette lines");
        assertFalse(lineIds.isEmpty(), "a non-null entry never carries empty lineIds");
    }

    // --------------------------------------------------
    // C17 — the CGU exclusion seeds the consumed set (§22.3)
    // --------------------------------------------------

    /**
     * C17 — CGU exclusion: a gift-card line (family CARTES_CADEAUX) is absent from every
     * earn assiette and from the burnable base — the engine seeds its consumed set with the
     * exclusion lines before any producer runs — while {@code CGU_EXCLUSION} itself never
     * appears in {@code entries[]} (a filter, not a producer, §22.3).
     */
    @Test
    void c17_cguExclusionSeedsConsumedSet() {
        List<String> offers = new java.util.ArrayList<>(socleOffers());
        offers.add(standardOffer("L4", EAN_GIFTCARD, "1.0", "50.00"));
        Response r = postEarn(earnBody(CARD_PLAIN, "2026-08-17T10:00:00", offers, "56.00"));
        assertEquals(200, r.statusCode(), "the mixed basket must answer 200");
        assertNull(entry(r, "CGU_EXCLUSION"), "a program exclusion is a filter and never an entry");
        Map<String, ?> socle = entry(r, "SOCLE_5_MARQUES");
        assertNotNull(socle, "the socle lines still earn beside the excluded gift card");
        assertFalse(((List<?>) socle.get("lineIds")).contains("L4"), "the gift-card line must be out of every assiette");
        assertEquals(0, decimal(r, "burnableBase").compareTo(new BigDecimal("6.00")),
                "the burnable base is totalPrice 56.00 € minus the 50.00 € gift-card net = 6.00 €");
    }

    // --------------------------------------------------
    // Helpers — HTTP
    // --------------------------------------------------

    /**
     * Posts a {@code /valuation} couple to {@code /api/earn} as the {@code pos} operator,
     * without following redirects.
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
     * Ingests a {@code ticket-closed} credit event as the {@code pos} operator (§27.4).
     *
     * @param ticketRef The unique ticket reference (idempotency key).
     * @param card      The authoritative card number.
     * @param fiscalDate The fiscal date of the ticket.
     * @param offers    The offer JSON fragments of the valued couple.
     * @param totalTtc  The reconciled total price TTC.
     */
    private static void ingestClosed(String ticketRef, String card, LocalDate fiscalDate,
                                     List<String> offers, String totalTtc) {
        String vr = "\"valuationRequest\":{\"customerCode\":\"" + card + "\",\"storeCode\":\"0101\",\"createdAt\":\""
                + fiscalDate.atTime(10, 0) + "\"}";
        String resp = "\"valuationResponse\":" + valuationResponse(offers, totalTtc);
        String body = "{\"ticketRef\":\"" + ticketRef + "\",\"card\":\"" + card + "\",\"fiscalDate\":\""
                + fiscalDate + "\"," + vr + "," + resp + "}";
        Response r = RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .contentType("application/json").body(body).post("/api/events/ticket-closed");
        assertEquals(202, r.statusCode(), "a fiscal event is always accepted (202)");
    }

    // --------------------------------------------------
    // Helpers — JSON building
    // --------------------------------------------------

    /**
     * Builds a full {@code /earn} request body with a card and an evaluation date.
     *
     * @param card       The customer card, or null/blank as the scenario needs.
     * @param createdAt  The ISO evaluation instant.
     * @param offers     The offer JSON fragments.
     * @param totalTtc   The reconciled total price TTC.
     * @return The request JSON.
     */
    private static String earnBody(String card, String createdAt, List<String> offers, String totalTtc) {
        String vr = "\"valuationRequest\":{\"customerCode\":\"" + card + "\",\"storeCode\":\"0101\",\"createdAt\":\""
                + createdAt + "\"}";
        return "{" + vr + ",\"valuationResponse\":" + valuationResponse(offers, totalTtc) + "}";
    }

    /**
     * Builds an {@code /earn} request body carrying no {@code customerCode} (an absent card).
     *
     * @param createdAt The ISO evaluation instant.
     * @param offers    The offer JSON fragments.
     * @param totalTtc  The reconciled total price TTC.
     * @return The request JSON.
     */
    private static String earnBodyNoCard(String createdAt, List<String> offers, String totalTtc) {
        String vr = "\"valuationRequest\":{\"storeCode\":\"0101\",\"createdAt\":\"" + createdAt + "\"}";
        return "{" + vr + ",\"valuationResponse\":" + valuationResponse(offers, totalTtc) + "}";
    }

    /**
     * Builds an {@code /earn} request body carrying no {@code createdAt} (program-clock
     * fallback).
     *
     * @param card     The customer card.
     * @param offers   The offer JSON fragments.
     * @param totalTtc The reconciled total price TTC.
     * @return The request JSON.
     */
    private static String earnBodyNoDate(String card, List<String> offers, String totalTtc) {
        String vr = "\"valuationRequest\":{\"customerCode\":\"" + card + "\",\"storeCode\":\"0101\"}";
        return "{" + vr + ",\"valuationResponse\":" + valuationResponse(offers, totalTtc) + "}";
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
        return List.of(
                standardOffer("L1", EAN_MILK, "1.0", "3.00"),
                standardOffer("L2", EAN_WATER, "1.0", "1.20"),
                standardOffer("L3", EAN_YAOURT, "1.0", "1.80"));
    }

    /**
     * Builds a Staub-brand calendar specification active every day at the given rate — an
     * unconditional single-line producer for the priority scenarios.
     *
     * @param rate The rate as a JSON fraction.
     * @return The specification JSON.
     */
    private static String staubSpec(String rate) {
        return "{\"scope\":{\"include\":{\"brands\":[\"Staub\"]}},\"rate\":" + rate + ",\"activeDays\":"
                + "[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"]}";
    }

    // --------------------------------------------------
    // Helpers — response reading
    // --------------------------------------------------

    /**
     * Finds an earn entry by rule code in a projection response.
     *
     * @param response The {@code /earn} response.
     * @param ruleCode The rule code to find.
     * @return The entry map, or null when absent.
     */
    private static Map<String, ?> entry(Response response, String ruleCode) {
        return response.jsonPath().getMap("entries.find { it.ruleCode == '" + ruleCode + "' }");
    }

    /**
     * Reads a top-level decimal field of a projection response.
     *
     * @param response The {@code /earn} response.
     * @param field    The field name.
     * @return The value as a BigDecimal.
     */
    private static BigDecimal decimal(Response response, String field) {
        return new BigDecimal(response.jsonPath().getString(field));
    }

    /**
     * Asserts a response is a 200 empty earn: total zero and no entries.
     *
     * @param response The {@code /earn} response.
     * @param message  The assertion message.
     */
    private static void assertEmptyEarn(Response response, String message) {
        assertEquals(200, response.statusCode(), message + " — must be 200, never 404");
        assertEquals(0, decimal(response, "total").compareTo(BigDecimal.ZERO), message + " — total 0");
        assertTrue(response.jsonPath().getList("entries").isEmpty(), message + " — no entries");
    }

    // --------------------------------------------------
    // Helpers — database (all in fresh transactions, no absolute ids)
    // --------------------------------------------------

    /**
     * Runs a counting query in a fresh transaction.
     *
     * @param supplier The count supplier.
     * @return The counted value.
     */
    private static long count(java.util.function.Supplier<Long> supplier) {
        return QuarkusTransaction.requiringNew().call(supplier::get);
    }

    /**
     * Finds an account by card number in a fresh transaction.
     *
     * @param cardNumber The card number.
     * @return The account, or null.
     */
    private static FidelityAccount findAccount(String cardNumber) {
        return QuarkusTransaction.requiringNew().call(() -> FidelityAccount.findByCardNumber(cardNumber));
    }

    /**
     * Ages every visit/earn trace of a card to a single fiscal date, in a fresh transaction.
     *
     * @param cardNumber The card whose traces move.
     * @param date       The new fiscal date for every trace.
     */
    private static void ageAllTraces(String cardNumber, LocalDate date) {
        QuarkusTransaction.requiringNew().run(() ->
                EarnTrace.update("fiscalDate = ?1 where cardNumber = ?2", date, cardNumber));
    }

    /**
     * Ages one visit/earn trace, identified by its ticket reference, to a fiscal date, in a
     * fresh transaction.
     *
     * @param cardNumber The card the trace belongs to.
     * @param ticketRef  The trace's ticket reference.
     * @param date       The new fiscal date.
     */
    private static void ageTrace(String cardNumber, String ticketRef, LocalDate date) {
        QuarkusTransaction.requiringNew().run(() ->
                EarnTrace.update("fiscalDate = ?1 where cardNumber = ?2 and ticketRef = ?3", date, cardNumber, ticketRef));
    }

    /**
     * Posts a controlled EARN movement crediting a rule this month, in a fresh transaction
     * — used to set a cap cumulative deterministically.
     *
     * @param cardNumber  The account card.
     * @param ruleCode    The crediting rule code.
     * @param amountEuro  The signed amount in euro.
     * @param date        The fiscal date of the movement.
     * @param ticketRef   The unique ticket reference (for later removal).
     */
    private static void postEarnMovement(String cardNumber, String ruleCode, String amountEuro,
                                         LocalDate date, String ticketRef) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(cardNumber);
            FidelityMovement movement = new FidelityMovement();
            movement.account = account;
            movement.type = MovementType.EARN;
            movement.amount = new BigDecimal(amountEuro);
            movement.movementDate = date;
            movement.earnYear = date.getYear();
            movement.ruleCode = ruleCode;
            movement.ticketRef = ticketRef;
            movement.persist();
        });
    }

    /**
     * Sums the euro earned this month by one rule for a card, in a fresh transaction.
     *
     * @param cardNumber The account card.
     * @param ruleCode   The rule code.
     * @param evalDate   Any date in the target month.
     * @return The per-rule monthly cumulative, scale 2.
     */
    private static BigDecimal ruleEarnThisMonth(String cardNumber, String ruleCode, LocalDate evalDate) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(cardNumber);
            Map<String, BigDecimal> byRule = FidelityMovement.monthlyEarnByRule(account,
                    evalDate.withDayOfMonth(1), evalDate.withDayOfMonth(evalDate.lengthOfMonth()));
            return byRule.getOrDefault(ruleCode, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        });
    }

    /**
     * Sums the euro earned this month across every rule mapped to the STUDENTS community
     * for a card, in a fresh transaction — the community cap cumulative.
     *
     * @param cardNumber The account card.
     * @param evalDate   Any date in the target month.
     * @return The STUDENTS community monthly cumulative, scale 2.
     */
    private static BigDecimal studentsCommunityEarnThisMonth(String cardNumber, LocalDate evalDate) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(cardNumber);
            Map<String, BigDecimal> byRule = FidelityMovement.monthlyEarnByRule(account,
                    evalDate.withDayOfMonth(1), evalDate.withDayOfMonth(evalDate.lengthOfMonth()));
            BigDecimal sum = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> e : byRule.entrySet()) {
                FidelityRule rule = FidelityRule.findByCode(e.getKey());
                if (rule != null && "STUDENTS".equals(rule.communityCodeFromSpec())) {
                    sum = sum.add(e.getValue());
                }
            }
            return sum.setScale(2, RoundingMode.HALF_UP);
        });
    }

    /**
     * Indicates whether an EARN movement of a rule exists for a ticket, in a fresh
     * transaction.
     *
     * @param ticketRef The ticket reference.
     * @param ruleCode  The rule code.
     * @return true when such a movement exists.
     */
    private static boolean hasEarn(String ticketRef, String ruleCode) {
        return QuarkusTransaction.requiringNew().call(() ->
                FidelityMovement.count("ticketRef = ?1 and type = ?2 and ruleCode = ?3",
                        ticketRef, MovementType.EARN, ruleCode) > 0);
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
     * Deletes the earn trace of a ticket reference and its lines, in a fresh transaction.
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
     * Persists a custom earn rule directly (bypassing the import schema validation, as the
     * degraded-spec scenario requires), in a fresh transaction.
     *
     * @param code          The stable rule code.
     * @param type          The factory type code.
     * @param label         The printed label.
     * @param validFrom     The window start.
     * @param priority      The evaluation priority.
     * @param exclusive     Whether the rule consumes its lines.
     * @param cap           The per-card monthly cap, or null.
     * @param specification The mechanic-specific JSON specification.
     */
    private static void persistRule(String code, String type, String label, LocalDateTime validFrom,
                                    int priority, boolean exclusive, String cap, String specification) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityRule rule = new FidelityRule();
            rule.code = code;
            rule.type = type;
            rule.label = label;
            rule.validFrom = validFrom;
            rule.validTo = null;
            rule.priority = priority;
            rule.exclusive = exclusive;
            rule.monthlyCapPerCard = cap != null ? new BigDecimal(cap) : null;
            rule.specification = specification;
            rule.active = true;
            rule.persist();
        });
    }

    /**
     * Swaps the priorities of two rules, in a fresh transaction.
     *
     * @param codeA     The first rule code.
     * @param priorityA The first rule's new priority.
     * @param codeB     The second rule code.
     * @param priorityB The second rule's new priority.
     */
    private static void swapPriorities(String codeA, int priorityA, String codeB, int priorityB) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityRule a = FidelityRule.findByCode(codeA);
            FidelityRule b = FidelityRule.findByCode(codeB);
            a.priority = priorityA;
            b.priority = priorityB;
        });
    }

    /**
     * Deletes a custom rule by code, in a fresh transaction.
     *
     * @param code The rule code to remove.
     */
    private static void deleteRule(String code) {
        QuarkusTransaction.requiringNew().run(() -> FidelityRule.delete("code", code));
    }
}
