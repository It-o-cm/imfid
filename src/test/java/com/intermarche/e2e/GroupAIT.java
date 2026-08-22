package com.intermarche.e2e;

import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.domain.ReservationState;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E scenarios of group A — startup &amp; bootstrap (e2escenarios-imfid.md "## A"). The
 * real application boots once via {@link QuarkusTest} on the test port with the
 * DataInitializer-rebuilt world; A1 asserts the three contractual boot log lines captured
 * by {@link BootLogCapture}, A5/A6 assert the seeded facts through Panache under
 * {@link QuarkusTransaction}. A2 proves the reload/bootstrap invariants in-process; the
 * literal JVM restart it describes is proven for free by each fresh campaign boot (N6),
 * not re-fired in-process because re-dispatching {@code StartupEvent} would trip the earn
 * rule registry's duplicate-type guard. A3 and A4 are [P] (prod-like) and stay
 * {@link Disabled} as a justified residue. DB assertions never rely on absolute ids or
 * counters; BigDecimal amounts are compared by {@code compareTo} (§30.5).
 */
@QuarkusTest
class GroupAIT {

    /**
     * Rich-history demo card: adjustment, expiry, weekend, refund, socle, burn and return.
     */
    private static final String CARD_RICH = "2990000000019";

    /**
     * Babies-community member card.
     */
    private static final String CARD_BABIES = "2990000000026";

    /**
     * Students-community member card, with an expired Small-Budgets membership.
     */
    private static final String CARD_STUDENT = "2990000000033";

    /**
     * PENDING_ACTIVATION card: accrues but cannot burn (§25.3).
     */
    private static final String CARD_PENDING = "2990000000040";

    /**
     * Card left with a negative balance by a return debit (I7).
     */
    private static final String CARD_NEGATIVE = "2990000000057";

    /**
     * Lost card, RESILIATED, balance transferred to the successor (§32.2).
     */
    private static final String CARD_LOST = "2990000000064";

    /**
     * Successor card holding the transferred balance.
     */
    private static final String CARD_SUCCESSOR = "2990000000071";

    /**
     * Card carrying an ACTIVE reservation lease of 8.00 € (I11).
     */
    private static final String CARD_RESERVED = "2990000000088";

    /**
     * PENDING_ACTIVATION card voided by the two-month CGU batch (ACTIVATION_VOID).
     */
    private static final String CARD_VOIDED = "2990000000095";

    // --------------------------------------------------
    // A1 — nominal dev boot: the three contractual log lines
    // --------------------------------------------------

    /**
     * A1 — nominal dev boot: the seed logs the exact dataset line
     * {@code Dev/test dataset loaded: 46 products, 11 rules, 4 communities, 9 accounts,
     * 22 movements}; the earn rule registry logs {@code Earn rule registry started: 7
     * schemas registered …} strictly before the seed (registry @Priority default 2500 &lt;
     * seed 2700); the security bootstrap logs
     * {@code Bootstrap users created: 'pos' (pos), 'admin' (fid-admin)}.
     */
    @Test
    void a1_bootLogsAnnounceSeedRegistryAndBootstrap() {
        int registry = firstIndexMatching(m -> m.startsWith("Earn rule registry started: 7 schemas registered"));
        assertTrue(registry >= 0, "the registry must log 7 schemas registered at boot");
        int dataset = firstIndexMatching(m -> m.equals(
                "Dev/test dataset loaded: 46 products, 11 rules, 4 communities, 9 accounts, 22 movements"));
        assertTrue(dataset >= 0, "the seed must log the exact dataset line");
        int bootstrap = firstIndexMatching(m -> m.equals("Bootstrap users created: 'pos' (pos), 'admin' (fid-admin)"));
        assertTrue(bootstrap >= 0, "the security bootstrap must log the created operator accounts");
        assertTrue(registry < dataset, "the registry must start before the seed (@Priority 2500 < 2700)");
    }

    // --------------------------------------------------
    // A2 — reversed amnesia: reloaded world + bootstrap guard
    // --------------------------------------------------

    /**
     * A2 — reversed amnesia: after boot the full seeded world is present (wipe + reload,
     * never an empty-base detection) — the socle rule and the exact 46/11/4/9/22 counts —
     * while {@code app_users}, owned by the bootstrap and untouched by the seed's wipe,
     * carries exactly the two operator accounts with no duplicate, proving the
     * {@code AppUser.count() > 0} guard held across this JVM life. The literal
     * create-then-restart erasure is proven for free by each fresh campaign boot (N6).
     */
    @Test
    void a2_reloadedWorldAndBootstrapGuardHold() {
        QuarkusTransaction.requiringNew().run(() -> {
            assertNotNull(FidelityRule.findByCode("SOCLE_5_MARQUES"), "the reload must bring the socle rule back");
            assertEquals(46L, Product.count(), "the reload must bring the 46 products back");
            assertEquals(11L, FidelityRule.count(), "the reload must bring the 11 rules back");
            assertEquals(4L, FidelityCommunity.count(), "the reload must bring the 4 communities back");
            assertEquals(9L, FidelityAccount.count(), "the reload must bring the 9 accounts back");
            assertEquals(22L, FidelityMovement.count(), "the reload must bring the 22 movements back");
            assertEquals(2L, AppUser.count(), "the bootstrap guard must leave exactly the two operator accounts");
            assertEquals(1L, AppUser.count("username", "admin"), "there must be a single admin, never re-created");
            assertEquals(1L, AppUser.count("username", "pos"), "there must be a single pos, never re-created");
            AppUser admin = AppUser.findByUsername("admin");
            assertNotNull(admin, "the admin account must exist");
            assertTrue(admin.roles.contains(AppUser.ROLE_FID_ADMIN), "the admin account must carry fid-admin");
            AppUser pos = AppUser.findByUsername("pos");
            assertNotNull(pos, "the pos account must exist");
            assertTrue(pos.roles.contains(AppUser.ROLE_POS), "the pos account must carry pos");
        });
    }

    // --------------------------------------------------
    // A3 — SeedLoader is prod-only [P]
    // --------------------------------------------------

    /**
     * A3 — SeedLoader prod-only [P]: proving the CSV {@code SeedLoader} loads the nine CSVs
     * in order 01→09 on an empty base, and returns silently on a non-empty one, requires a
     * prod-profile boot on an empty PostgreSQL; the dev/test profile excludes the bean
     * entirely, so the empty-database seed path cannot be exercised here.
     */
    @Test
    @Disabled("[P] prod-like env required: SeedLoader is @IfBuildProfile(prod) and only loads the 9 CSVs on an empty "
            + "PostgreSQL base; the dev/test profile excludes the bean, so its seed path cannot be exercised "
            + "in-process — justified residue until a prod-like harness exists.")
    void a3_seedLoaderIsProdOnly() {
        // Intentionally empty: enabled only under a prod-like harness.
    }

    // --------------------------------------------------
    // A4 — incomplete prod startup fails loudly [P]
    // --------------------------------------------------

    /**
     * A4 — incomplete prod startup [P]: proving the application fails to start without
     * {@code IMFID_DB_URL}/{@code IMFID_SESSION_KEY}/the bootstrap passwords, with no silent
     * fallback, needs a prod-profile boot with those environment variables missing —
     * impossible under the in-process dev/test {@link QuarkusTest} which supplies its own
     * H2 and default credentials.
     */
    @Test
    @Disabled("[P] prod-like env required: a failing startup without IMFID_DB_URL/IMFID_SESSION_KEY/bootstrap "
            + "passwords needs a prod-profile boot with missing env, impossible under the in-process dev/test "
            + "@QuarkusTest — justified residue until a prod-like harness exists.")
    void a4_prodStartupFailsWithoutRequiredEnv() {
        // Intentionally empty: enabled only under a prod-like harness.
    }

    // --------------------------------------------------
    // A5 — the nine cards of the world
    // --------------------------------------------------

    /**
     * A5 — the nine cards: exact seeded states in the database — {@code …019} ACTIVE 56.70;
     * {@code …026} ACTIVE 18.50 (open BABIES membership); {@code …033} ACTIVE 24.00
     * (STUDENTS active to 2026-10-31 + SMALL_BUDGETS expired 2026-02-28); {@code …040}
     * PENDING_ACTIVATION 5.00; {@code …057} ACTIVE −1.70 (I7); {@code …064} RESILIATED 0.00
     * {@code transferredToCard=…071}; {@code …071} ACTIVE 15.00; {@code …088} ACTIVE 30.00
     * + ACTIVE lease 8.00 (bail in the future); {@code …095} RESILIATED 0.00 with an
     * ACTIVATION_VOID movement. Every number is a valid EAN-13 (check digit) prefixed 299.
     */
    @Test
    void a5_theNineCardsOfTheWorld() {
        QuarkusTransaction.requiringNew().run(() -> {
            assertAccount(CARD_RICH, AccountStatus.ACTIVE, "56.70");
            assertAccount(CARD_BABIES, AccountStatus.ACTIVE, "18.50");
            assertMembershipOpen(FidelityAccount.findByCardNumber(CARD_BABIES), "BABIES");
            assertAccount(CARD_STUDENT, AccountStatus.ACTIVE, "24.00");
            FidelityAccount student = FidelityAccount.findByCardNumber(CARD_STUDENT);
            assertMembershipValidTo(student, "STUDENTS", LocalDate.of(2026, 10, 31));
            assertMembershipValidTo(student, "SMALL_BUDGETS", LocalDate.of(2026, 2, 28));
            assertAccount(CARD_PENDING, AccountStatus.PENDING_ACTIVATION, "5.00");
            assertAccount(CARD_NEGATIVE, AccountStatus.ACTIVE, "-1.70");
            assertAccount(CARD_LOST, AccountStatus.RESILIATED, "0.00");
            assertEquals(CARD_SUCCESSOR, FidelityAccount.findByCardNumber(CARD_LOST).transferredToCard,
                    "the lost card must point at its successor");
            assertAccount(CARD_SUCCESSOR, AccountStatus.ACTIVE, "15.00");
            assertAccount(CARD_RESERVED, AccountStatus.ACTIVE, "30.00");
            FidelityReservation lease = FidelityReservation.findActiveForAccount(FidelityAccount.findByCardNumber(CARD_RESERVED));
            assertNotNull(lease, "the reserved card must hold an active lease");
            assertEquals(ReservationState.ACTIVE, lease.state, "the lease must be ACTIVE");
            assertEquals(0, lease.amount.compareTo(new BigDecimal("8.00")), "the lease must hold 8.00 €");
            assertTrue(lease.expiresAt.isAfter(DateTimeProvider.now()), "the lease must expire in the future");
            assertAccount(CARD_VOIDED, AccountStatus.RESILIATED, "0.00");
            assertEquals(1L, FidelityMovement.count("account = ?1 and type = ?2",
                    FidelityAccount.findByCardNumber(CARD_VOIDED), MovementType.ACTIVATION_VOID),
                    "the voided card must carry exactly one ACTIVATION_VOID movement");
        });
    }

    // --------------------------------------------------
    // A6 — calendar sensitivity of the seed
    // --------------------------------------------------

    /**
     * A6 — calendar sensitivity: the demo windows {@code ECOUPON_DEMO}/{@code CHALLENGE_DEMO}
     * are recalibrated to {@code [1st of month, 1st of next month)} at every boot; and the
     * 4th-visit boost of {@code …019} arms only with three visits in the current month —
     * invalid on the 1st–6th. Rather than depend on the run date, the boost sensitivity is
     * made deterministic by ageing {@code …019}'s visit rows: out of the month (fewer than
     * three visits, boost silent) then three distinct days inside it (boost armed).
     */
    @Test
    void a6_calendarSensitivityOfTheSeed() {
        LocalDate today = DateTimeProvider.now().toLocalDate();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        LocalDateTime monthStartAt = monthStart.atStartOfDay();
        LocalDateTime nextMonthStartAt = monthStart.plusMonths(1).atStartOfDay();
        QuarkusTransaction.requiringNew().run(() -> {
            FidelityRule ecoupon = FidelityRule.findByCode("ECOUPON_DEMO");
            assertNotNull(ecoupon, "the e-coupon demo rule must be seeded");
            assertEquals(monthStartAt, ecoupon.validFrom, "the e-coupon window must open on the 1st of the month");
            assertEquals(nextMonthStartAt, ecoupon.validTo, "the e-coupon window must close on the 1st of next month");
            FidelityRule challenge = FidelityRule.findByCode("CHALLENGE_DEMO");
            assertNotNull(challenge, "the challenge demo rule must be seeded");
            assertEquals(monthStartAt, challenge.validFrom, "the challenge window must open on the 1st of the month");
            assertEquals(nextMonthStartAt, challenge.validTo, "the challenge window must close on the 1st of next month");
        });
        ageAllTraces(CARD_RICH, monthStart.minusMonths(1));
        assertEquals(0L, visitsThisMonth(CARD_RICH, monthStart, monthEnd),
                "with every visit aged out of the month the counter falls below three: the boost stays silent (1st–6th regime)");
        ageTrace(CARD_RICH, "0101-2026-002001", monthStart);
        ageTrace(CARD_RICH, "0101-2026-002088", monthStart.plusDays(1));
        ageTrace(CARD_RICH, "0101-2026-002150", monthStart.plusDays(2));
        assertEquals(3L, visitsThisMonth(CARD_RICH, monthStart, monthEnd),
                "with three distinct visits inside the month the counter reaches three: the 4th-visit boost arms");
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Returns the index of the first captured boot message matching the predicate.
     *
     * @param predicate The message test.
     * @return The zero-based index of the first match, or -1 when none matches.
     */
    private static int firstIndexMatching(Predicate<String> predicate) {
        List<String> messages = BootLogCapture.messages();
        for (int i = 0; i < messages.size(); i++) {
            if (predicate.test(messages.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Asserts a seeded account exists with the expected status and balance, and that its
     * card number is a valid EAN-13 prefixed 299. Runs within the caller's transaction.
     *
     * @param cardNumber     The 13-digit card number.
     * @param expectedStatus The expected account status.
     * @param expectedBalance The expected balance in euro (scale 2), compared by compareTo.
     */
    private static void assertAccount(String cardNumber, AccountStatus expectedStatus, String expectedBalance) {
        FidelityAccount account = FidelityAccount.findByCardNumber(cardNumber);
        assertNotNull(account, "card " + cardNumber + " must be seeded");
        assertEquals(expectedStatus, account.status, "card " + cardNumber + " must be " + expectedStatus);
        assertEquals(0, account.balance.compareTo(new BigDecimal(expectedBalance)),
                "card " + cardNumber + " must hold " + expectedBalance + " € but was " + account.balance);
        assertTrue(cardNumber.startsWith("299"), "card " + cardNumber + " must carry the reserved 299 prefix");
        assertTrue(isValidEan13(cardNumber), "card " + cardNumber + " must be a valid EAN-13");
    }

    /**
     * Asserts the account holds an open (no end date) membership in the given community.
     * Runs within the caller's transaction.
     *
     * @param account       The account.
     * @param communityCode The community business code.
     */
    private static void assertMembershipOpen(FidelityAccount account, String communityCode) {
        FidelityMembership membership = membershipOf(account, communityCode);
        assertNotNull(membership, "the account must be a member of " + communityCode);
        assertNull(membership.validTo, "the " + communityCode + " membership must be open-ended");
    }

    /**
     * Asserts the account holds a membership in the community ending exactly on the given
     * date (encoding active vs expired as of the 2026 program). Runs within the caller's
     * transaction.
     *
     * @param account       The account.
     * @param communityCode The community business code.
     * @param expectedTo    The expected membership end date.
     */
    private static void assertMembershipValidTo(FidelityAccount account, String communityCode, LocalDate expectedTo) {
        FidelityMembership membership = membershipOf(account, communityCode);
        assertNotNull(membership, "the account must be a member of " + communityCode);
        assertEquals(expectedTo, membership.validTo, "the " + communityCode + " membership must end on " + expectedTo);
    }

    /**
     * Finds an account's membership in a community by business code. Runs within the
     * caller's transaction.
     *
     * @param account       The account.
     * @param communityCode The community business code.
     * @return The membership, or null when the account is not a member.
     */
    private static FidelityMembership membershipOf(FidelityAccount account, String communityCode) {
        for (FidelityMembership membership : FidelityMembership.listForAccount(account)) {
            if (membership.community != null && communityCode.equals(membership.community.code)) {
                return membership;
            }
        }
        return null;
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
     * Counts a card's distinct visit days in a fiscal range, in a fresh transaction.
     *
     * @param cardNumber The card.
     * @param from       The inclusive lower bound.
     * @param to         The inclusive upper bound.
     * @return The number of distinct visit days.
     */
    private static long visitsThisMonth(String cardNumber, LocalDate from, LocalDate to) {
        return QuarkusTransaction.requiringNew().call(() -> EarnTrace.countVisits(cardNumber, from, to));
    }

    /**
     * Validates a 13-digit EAN-13 by its modulo-10 check digit (odd positions weight 1,
     * even positions weight 3, total a multiple of 10).
     *
     * @param ean The candidate 13-character numeric string.
     * @return true when the string is a valid EAN-13.
     */
    private static boolean isValidEan13(String ean) {
        if (ean == null || ean.length() != 13) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            char c = ean.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            int digit = c - '0';
            sum += i % 2 == 0 ? digit : digit * 3;
        }
        return sum % 10 == 0;
    }
}
