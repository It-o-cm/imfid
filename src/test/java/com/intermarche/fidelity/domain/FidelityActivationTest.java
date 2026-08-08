package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link FidelityActivation}: the per-card activation flag read by the engine
 * (§14). The class holds no clock read of its own — {@code periodStart} and {@code periodEnd} are
 * stored {@link LocalDate} fields and the fiscal date under test is passed in as a fixed argument — so
 * no {@code DateTimeProvider} is injected; every temporal border is instead straddled by the two fixed
 * dates encircling it (§24.6). The {@link FidelityActivation#isActiveOn} guard is exercised leg by leg
 * with both arms of each ternary (§29.6), the Panache active-record finders are mocked through
 * {@link PanacheEntityBase} in a try-with-resources per the imfid unit bench, and the checksum is
 * asserted as the pure function of the business fields it is, both arms of its account ternary covered.
 */
class FidelityActivationTest {

    /**
     * A reference program-zone fiscal date used as the activation window's inclusive lower bound.
     */
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);

    /**
     * A reference program-zone fiscal date used as the activation window's inclusive upper bound.
     */
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 12, 31);

    // --------------------------------------------------
    // isActiveOn()
    // --------------------------------------------------

    /**
     * A null fiscal date is never active: the first leg {@code date == null} of the reject guard is
     * true, the others never evaluated.
     */
    @Test
    @DisplayName("isActiveOn(): null date is not active")
    void isActiveOnNullDate() {
        FidelityActivation activation = window(PERIOD_START, PERIOD_END);
        assertFalse(activation.isActiveOn(null));
    }

    /**
     * An activation with no start is never active: the first leg is false, the second
     * {@code periodStart == null} is true.
     */
    @Test
    @DisplayName("isActiveOn(): null periodStart is not active")
    void isActiveOnNullStart() {
        FidelityActivation activation = window(null, PERIOD_END);
        assertFalse(activation.isActiveOn(LocalDate.of(2026, 6, 15)));
    }

    /**
     * The day before the start is out of the window: the first two legs are false, the third
     * {@code date.isBefore(periodStart)} is true. Lower border, before side.
     */
    @Test
    @DisplayName("isActiveOn(): the day before periodStart is not active")
    void isActiveOnDayBeforeStart() {
        FidelityActivation activation = window(PERIOD_START, PERIOD_END);
        assertFalse(activation.isActiveOn(PERIOD_START.minusDays(1)));
    }

    /**
     * The start day itself is inside the window: every reject-guard leg is false and, with a closed
     * end, the return's second leg {@code !date.isAfter(periodEnd)} is true. Lower border, on side.
     */
    @Test
    @DisplayName("isActiveOn(): periodStart itself is active")
    void isActiveOnStartDay() {
        FidelityActivation activation = window(PERIOD_START, PERIOD_END);
        assertTrue(activation.isActiveOn(PERIOD_START));
    }

    /**
     * An open-ended activation stays active on and after its start: the reject guard passes and the
     * return's first leg {@code periodEnd == null} is true, short-circuiting the after-check.
     */
    @Test
    @DisplayName("isActiveOn(): open-ended period is active from the start on")
    void isActiveOnOpenEnded() {
        FidelityActivation activation = window(PERIOD_START, null);
        assertTrue(activation.isActiveOn(PERIOD_START));
    }

    /**
     * The end day itself is inside the closed window: the reject guard passes, the return's first leg
     * is false and the second {@code !date.isAfter(periodEnd)} is true. Upper border, on side.
     */
    @Test
    @DisplayName("isActiveOn(): periodEnd itself is active")
    void isActiveOnEndDay() {
        FidelityActivation activation = window(PERIOD_START, PERIOD_END);
        assertTrue(activation.isActiveOn(PERIOD_END));
    }

    /**
     * The day after the end falls out of the closed window: the reject guard passes, the return's
     * first leg is false and the second {@code !date.isAfter(periodEnd)} is false. Upper border, after
     * side.
     */
    @Test
    @DisplayName("isActiveOn(): the day after periodEnd is not active")
    void isActiveOnDayAfterEnd() {
        FidelityActivation activation = window(PERIOD_START, PERIOD_END);
        assertFalse(activation.isActiveOn(PERIOD_END.plusDays(1)));
    }

    // --------------------------------------------------
    // listForAccount()
    // --------------------------------------------------

    /**
     * The per-account lister delegates to the {@code account} query and returns its list unchanged
     * (never null, §conventions).
     */
    @Test
    @DisplayName("listForAccount(): returns the account's activations")
    void listForAccountReturnsList() {
        FidelityAccount account = new FidelityAccount();
        List<FidelityActivation> activations = List.of(new FidelityActivation(), new FidelityActivation());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("account", account)).thenReturn(activations);
            assertEquals(activations, FidelityActivation.listForAccount(account));
        }
    }

    // --------------------------------------------------
    // findActive()
    // --------------------------------------------------

    /**
     * The active finder delegates to the period-bounded query and returns its first result when an
     * activation matches the account, rule and fiscal date (§31.1).
     */
    @Test
    @DisplayName("findActive(): returns the matching active activation")
    void findActiveFound() {
        FidelityAccount account = new FidelityAccount();
        LocalDate date = LocalDate.of(2026, 6, 15);
        FidelityActivation found = new FidelityActivation();
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityActivation> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and ruleCode = ?2 and periodStart <= ?3 and (periodEnd is null or periodEnd >= ?3)",
                    account, "ECOUPON_EARN", date)).thenReturn(query);
            assertSame(found, FidelityActivation.findActive(account, "ECOUPON_EARN", date));
        }
    }

    /**
     * The active finder returns null when no activation is open for the date, so a rule requiring
     * activation earns nothing (§31.1).
     */
    @Test
    @DisplayName("findActive(): returns null when none is active")
    void findActiveAbsent() {
        FidelityAccount account = new FidelityAccount();
        LocalDate date = LocalDate.of(2026, 6, 15);
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityActivation> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and ruleCode = ?2 and periodStart <= ?3 and (periodEnd is null or periodEnd >= ?3)",
                    account, "CHALLENGE_EARN", date)).thenReturn(query);
            assertNull(FidelityActivation.findActive(account, "CHALLENGE_EARN", date));
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * With an account attached, the checksum's ternary takes its non-null arm and folds in the account
     * card number; two activations with identical business fields share the checksum.
     */
    @Test
    @DisplayName("getChecksum(): identical business fields with an account share a checksum")
    void checksumStableWithAccount() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * With no account, the checksum's ternary takes its null arm; two account-less activations with
     * identical business fields still share the checksum.
     */
    @Test
    @DisplayName("getChecksum(): null account takes the null card-number arm")
    void checksumStableWithoutAccount() {
        assertEquals(accountless().getChecksum(), accountless().getChecksum());
    }

    /**
     * The account card number participates in the checksum: an otherwise identical activation on a
     * different card diverges, exercising the non-null ternary arm against the null one.
     */
    @Test
    @DisplayName("getChecksum(): a different card number alters the checksum")
    void checksumChangesWithCard() {
        assertNotEquals(sample().getChecksum(), accountless().getChecksum());
    }

    /**
     * Changing any business field — here the mission flag — changes the checksum, so any divergence is
     * detected.
     */
    @Test
    @DisplayName("getChecksum(): a different mission flag alters the checksum")
    void checksumChangesWithMission() {
        FidelityActivation other = sample();
        other.missionDone = true;
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds an activation with the given inclusive window bounds, used to drive {@code isActiveOn}.
     *
     * @param start The inclusive period start, or null for an unstarted activation.
     * @param end   The inclusive period end, or null for an open-ended activation.
     * @return A minimally populated activation.
     */
    private FidelityActivation window(LocalDate start, LocalDate end) {
        FidelityActivation activation = new FidelityActivation();
        activation.periodStart = start;
        activation.periodEnd = end;
        return activation;
    }

    /**
     * Builds a fully-populated activation on a fixed card, for checksum assertions.
     *
     * @return A sample activation with a deterministic account and business fields.
     */
    private FidelityActivation sample() {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = "3245070000001";
        FidelityActivation activation = new FidelityActivation();
        activation.account = account;
        activation.ruleCode = "ECOUPON_EARN";
        activation.periodStart = PERIOD_START;
        activation.periodEnd = PERIOD_END;
        activation.missionDone = false;
        return activation;
    }

    /**
     * Builds the same activation as {@link #sample()} but with no account attached, to drive the null
     * arm of the checksum ternary.
     *
     * @return A sample activation without an account.
     */
    private FidelityActivation accountless() {
        FidelityActivation activation = sample();
        activation.account = null;
        return activation;
    }
}
