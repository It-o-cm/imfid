package com.intermarche.e2e;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.MovementType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group F — the account read API {@code GET /api/accounts/…}
 * (e2escenarios-imfid.md "## F"). Every scenario is plain HTTP: RestAssured over the real
 * application booted by {@link QuarkusTest} with the DataInitializer-rebuilt 2026 world,
 * authenticated Basic {@code pos} (the {@code @RolesAllowed(pos)} guard of {@code
 * AccountResource}). No scenario in this group is [W] or [P], so all three are implemented
 * at the RestAssured tier.
 * <p>
 * The read API takes no lock (§30.1): F1 is the unknown-card 404, F2 is the summary of the
 * reserved card {@code …088} (balance, available balance under an active lease, the closed
 * cap-scope nomenclature whose cumulatives count EARN only — never an ADJUSTMENT — and the
 * memberships with their windows), and F3 is the paginated history (most recent first, the
 * 200-row clamp, the negative-page clamp, the closed nine-name movement nomenclature and
 * the reason carried by ADJUSTMENT rows). Assertions read the JSON body and cross-check the
 * seeded facts the catalog states. F3 needs a card with more than 200 movements to observe
 * the {@code size=999 → 200} clamp, which the seeded world does not offer, so it inserts a
 * back-dated bulk under a marker ticket reference and removes it in a {@code finally}, then
 * recomputes the touched balance — the class stays order-independent. DB mutations go
 * through Panache under {@link QuarkusTransaction}, always by natural key and delta, never
 * absolute ids or counters. BigDecimal amounts are compared by {@code compareTo} (§30.5).
 */
@QuarkusTest
class GroupFIT {

    /**
     * Bootstrap machine login (SecurityBootstrap default), role {@code pos}.
     */
    private static final String POS_USER = "pos";

    /**
     * Bootstrap machine password (SecurityBootstrap default in dev/test).
     */
    private static final String POS_PASSWORD = "pos-password";

    /**
     * Reserved card: ACTIVE, balance 30.00 €, one ACTIVE lease of 8.00 € (I11) so its
     * available balance is 22.00 €; a single ADJUSTMENT (July 2026), no memberships (…088).
     */
    private static final String CARD_RESERVED = "2990000000088";

    /**
     * Rich-history card: ACTIVE, carrying six of the nine ledger movement types plus a
     * reason-bearing ADJUSTMENT — the history-shape witness (…019).
     */
    private static final String CARD_RICH = "2990000000019";

    /**
     * A card number absent from the seeded world — the unknown-card probe of F1.
     */
    private static final String CARD_UNKNOWN = "2990000000999";

    /**
     * The seeded GLOBAL monthly cap (FidelityProgramSetting KEY_GLOBAL_MONTHLY_CAP, §25.1).
     */
    private static final BigDecimal GLOBAL_CAP = new BigDecimal("400.00");

    /**
     * The closed nine-name movement nomenclature (§14): every restituted {@code type} badge
     * belongs to this set, EARN through TRANSFER.
     */
    private static final Set<String> NOMENCLATURE = Set.of("EARN", "REFUND_CREDIT", "ADJUSTMENT",
            "BURN", "RETURN_DEBIT", "EXPIRY", "PURGE", "ACTIVATION_VOID", "TRANSFER");

    // --------------------------------------------------
    // F1 — unknown card: a 404 with the closed literal (§27.2)
    // --------------------------------------------------

    /**
     * F1 — unknown card: {@code GET /api/accounts/{card}} on a card absent from the seeded
     * world answers 404 with the closed body {@code {"error":"Unknown card"}} (§27.2).
     */
    @Test
    void f1_unknownCardIs404() {
        Response r = getAccount(CARD_UNKNOWN);
        assertEquals(404, r.statusCode(), "an unknown card must be a 404");
        assertEquals("Unknown card", r.jsonPath().getString("error"),
                "the 404 carries the closed unknown-card literal");
    }

    // --------------------------------------------------
    // F2 — the account summary: available ≠ balance under a lease (§27.2, I11)
    // --------------------------------------------------

    /**
     * F2 — summary of {@code …088}: 200 with {@code balance} 30.00 €, {@code
     * availableBalance} 22.00 € (30.00 − the 8.00 € active lease, I11), status ACTIVE, the
     * month's visits, {@code monthlyCaps[]} whose scopes are the closed {@code GLOBAL} |
     * {@code RULE:<code>} | {@code COMMUNITY:<code>} nomenclature and whose cumulatives count
     * EARN only — the seeded ADJUSTMENT never figures, so every {@code used} is 0.00 € — and
     * {@code memberships[]} (empty for this card, but each membership carries its window).
     */
    @Test
    void f2_summaryExposesBalanceAvailableCapsAndMemberships() {
        Response r = getAccount(CARD_RESERVED);
        assertEquals(200, r.statusCode(), "a known card answers 200 with its summary");
        assertEquals(CARD_RESERVED, r.jsonPath().getString("cardNumber"), "the summary carries its card number");
        assertEquals("ACTIVE", r.jsonPath().getString("status"), "card …088 is ACTIVE");
        assertEquals(0, bd(r.jsonPath().get("balance")).compareTo(new BigDecimal("30.00")),
                "the balance is the seeded 30.00 €");
        assertEquals(0, bd(r.jsonPath().get("availableBalance")).compareTo(new BigDecimal("22.00")),
                "the available balance is 30.00 € minus the 8.00 € active lease = 22.00 € (I11)");
        assertEquals(0, r.jsonPath().getInt("monthVisits"), "card …088 has no seeded visit this month");
        List<Map<String, ?>> caps = r.jsonPath().getList("monthlyCaps");
        assertFalse(caps.isEmpty(), "the summary always carries at least the GLOBAL cap");
        for (Map<String, ?> cap : caps) {
            String scope = String.valueOf(cap.get("scope"));
            assertTrue(scope.equals("GLOBAL") || scope.startsWith("RULE:") || scope.startsWith("COMMUNITY:"),
                    "every cap scope is of the closed nomenclature but was " + scope);
            assertEquals(0, bd(cap.get("used")).compareTo(BigDecimal.ZERO),
                    "the cumulatives count EARN only — the seeded ADJUSTMENT never figures, so " + scope + " is 0.00 €");
        }
        Map<String, ?> global = r.jsonPath().getMap("monthlyCaps.find { it.scope == 'GLOBAL' }");
        assertNotNull(global, "the GLOBAL cap is always present");
        assertEquals(0, bd(global.get("cap")).compareTo(GLOBAL_CAP), "the GLOBAL cap is the seeded 400.00 €");
        assertTrue(r.jsonPath().getList("memberships").isEmpty(),
                "card …088 has no membership, so its memberships list is empty");
    }

    // --------------------------------------------------
    // F3 — the movement history: order, clamps and nomenclature (§27.2, §31.3)
    // --------------------------------------------------

    /**
     * F3 — history of {@code …019}: {@code GET /api/accounts/{card}/movements} returns the
     * movements most recent first ({@code movementDate desc}), restitutes the {@code type}
     * badge within the closed nine-name nomenclature and carries {@code reason} on ADJUSTMENT
     * rows (null on the others); {@code page=-5} is clamped to page 0 (§31.3) and {@code
     * size=999} is clamped to 200 rows (proven by a back-dated bulk removed afterwards).
     */
    @Test
    void f3_historyOrderClampsAndNomenclature() {
        String bulkRef = "F3-BULK";
        long baseCount = countMovements(CARD_RICH);
        try {
            Response page0 = getMovements(CARD_RICH, 0, 50);
            assertEquals(200, page0.statusCode(), "a known card answers 200 with its history page");
            List<Map<String, ?>> items = page0.jsonPath().getList("items");
            assertFalse(items.isEmpty(), "card …019 carries a seeded history");
            LocalDate previous = null;
            for (Map<String, ?> item : items) {
                String type = String.valueOf(item.get("type"));
                assertTrue(NOMENCLATURE.contains(type), "every type is of the closed nomenclature but was " + type);
                LocalDate date = LocalDate.parse(String.valueOf(item.get("date")));
                if (previous != null) {
                    assertTrue(date.compareTo(previous) <= 0, "the movements are most recent first (movementDate desc)");
                }
                previous = date;
            }
            Set<String> restituted = items.stream().map(i -> String.valueOf(i.get("type"))).collect(Collectors.toSet());
            for (String seeded : List.of("EARN", "EXPIRY", "ADJUSTMENT", "REFUND_CREDIT", "BURN", "RETURN_DEBIT")) {
                assertTrue(restituted.contains(seeded), "the seeded type " + seeded + " is restituted");
            }
            Map<String, ?> adjustment = page0.jsonPath().getMap("items.find { it.type == 'ADJUSTMENT' }");
            assertNotNull(adjustment, "card …019 carries its seeded ADJUSTMENT");
            assertEquals("Initial demo balance (seed)", adjustment.get("reason"),
                    "the ADJUSTMENT carries its reason (§32.1)");
            Map<String, ?> earn = page0.jsonPath().getMap("items.find { it.type == 'EARN' }");
            assertNotNull(earn, "card …019 carries an EARN row");
            assertNull(earn.get("reason"), "a non-ADJUSTMENT movement carries no reason");
            Response negativePage = getMovements(CARD_RICH, -5, 50);
            assertEquals(page0.jsonPath().getString("items[0].date"), negativePage.jsonPath().getString("items[0].date"),
                    "page=-5 is clamped to page 0 — the same top row");
            assertEquals(page0.jsonPath().getLong("totalCount"), negativePage.jsonPath().getLong("totalCount"),
                    "the negative-page clamp changes no total");
            insertBulk(CARD_RICH, 201, bulkRef);
            Response big = getMovements(CARD_RICH, 0, 999);
            assertEquals(200, big.jsonPath().getList("items").size(),
                    "size=999 is clamped to 200 rows (§31.3)");
            assertEquals(baseCount + 201, big.jsonPath().getLong("totalCount"),
                    "the total counts every movement, clamp or not");
        } finally {
            deleteByRef(bulkRef);
            recomputeBalance(CARD_RICH);
        }
    }

    // --------------------------------------------------
    // Helpers — HTTP
    // --------------------------------------------------

    /**
     * Reads an account summary as the {@code pos} operator, without following redirects.
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
     * Reads a page of an account's movements as the {@code pos} operator, without following
     * redirects.
     *
     * @param card The card number.
     * @param page The requested page index (may be negative).
     * @param size The requested page size.
     * @return The HTTP response.
     */
    private static Response getMovements(String card, int page, int size) {
        return RestAssured.given().redirects().follow(false)
                .auth().preemptive().basic(POS_USER, POS_PASSWORD)
                .queryParam("page", page).queryParam("size", size)
                .get("/api/accounts/" + card + "/movements");
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

    // --------------------------------------------------
    // Helpers — database (all in fresh transactions, no absolute ids)
    // --------------------------------------------------

    /**
     * Counts the movements of a card in a fresh transaction.
     *
     * @param cardNumber The card number.
     * @return The movement count.
     */
    private static long countMovements(String cardNumber) {
        return QuarkusTransaction.requiringNew().call(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(cardNumber);
            return account == null ? 0L : FidelityMovement.count("account", account);
        });
    }

    /**
     * Inserts a back-dated bulk of EARN movements on a card under a marker ticket reference,
     * so the history crosses the 200-row page clamp; the rows sit at the tail (year 2020) so
     * they never displace the seeded rows on the first page.
     *
     * @param cardNumber The card number.
     * @param count      The number of rows to insert.
     * @param marker     The marker ticket reference, removed afterwards.
     */
    private static void insertBulk(String cardNumber, int count, String marker) {
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityAccount account = FidelityAccount.findByCardNumber(cardNumber);
            for (int i = 0; i < count; i++) {
                FidelityMovement movement = new FidelityMovement();
                movement.account = account;
                movement.type = MovementType.EARN;
                movement.amount = new BigDecimal("0.01");
                movement.movementDate = LocalDate.of(2020, 1, 1);
                movement.earnYear = 2020;
                movement.ruleCode = null;
                movement.ticketRef = marker;
                movement.reason = null;
                movement.persist();
            }
        });
    }

    /**
     * Deletes every movement of a ticket reference in a fresh transaction.
     *
     * @param ticketRef The ticket reference to clear.
     */
    private static void deleteByRef(String ticketRef) {
        QuarkusTransaction.requiringNew().run(() -> FidelityMovement.delete("ticketRef", ticketRef));
    }

    /**
     * Recomputes an account's denormalized balance from its remaining movements in a fresh
     * transaction — restoring the seeded balance after a test's rows are removed.
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
