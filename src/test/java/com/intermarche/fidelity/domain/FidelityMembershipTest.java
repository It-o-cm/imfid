package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link FidelityMembership}: the membership of an account in a community
 * over a validity window (§14). The class holds no clock read of its own — {@code validFrom} and
 * {@code validTo} are stored fields and {@link FidelityMembership#isActiveOn} takes the fiscal date
 * as a fixed argument — so no {@code DateTimeProvider} is injected; the window boundaries are tested
 * by the two instants straddling each border, never by the day the campaign runs (§24.6, §30.3).
 * Both arms of every guard and each leg of the two compound guards of {@code isActiveOn} are driven
 * (§29.6), the Panache active-record finders are mocked through {@link PanacheEntityBase} in a
 * try-with-resources per the imfid unit bench, and the checksum is asserted as the pure function of
 * the business fields it is, including both arms of its two null-guarding ternaries.
 */
class FidelityMembershipTest {

    /**
     * A window opening on the 1st and closing on the 30th, used across the {@code isActiveOn} cases.
     */
    private static final LocalDate FROM = LocalDate.of(2026, 6, 1);

    /**
     * The inclusive end of the reference window.
     */
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);

    // --------------------------------------------------
    // isActiveOn() — guard 1: date == null || validFrom == null || date.isBefore(validFrom)
    // --------------------------------------------------

    /**
     * A null fiscal date is never active: the first leg {@code date == null} of the opening guard is
     * true and short-circuits to false.
     */
    @Test
    @DisplayName("isActiveOn(): null date is inactive")
    void isActiveOnNullDate() {
        assertFalse(membership(FROM, TO).isActiveOn(null));
    }

    /**
     * A membership with no start bound is never active: the first leg is false, the second leg
     * {@code validFrom == null} is true.
     */
    @Test
    @DisplayName("isActiveOn(): null validFrom is inactive")
    void isActiveOnNullValidFrom() {
        assertFalse(membership(null, TO).isActiveOn(LocalDate.of(2026, 6, 15)));
    }

    /**
     * The day before the window opens is inactive: the first two legs are false, the third leg
     * {@code date.isBefore(validFrom)} is true.
     */
    @Test
    @DisplayName("isActiveOn(): the day before validFrom is inactive")
    void isActiveOnDayBeforeStart() {
        assertFalse(membership(FROM, TO).isActiveOn(FROM.minusDays(1)));
    }

    /**
     * The start day itself is active (inclusive lower bound): all three legs of the opening guard are
     * false — this is the after-boundary instant straddling {@code validFrom}.
     */
    @Test
    @DisplayName("isActiveOn(): validFrom is active (inclusive)")
    void isActiveOnStartInclusive() {
        assertTrue(membership(FROM, TO).isActiveOn(FROM));
    }

    // --------------------------------------------------
    // isActiveOn() — guard 2: validTo == null || !date.isAfter(validTo)
    // --------------------------------------------------

    /**
     * An open-ended membership is active on any date at or after the start: the second guard's first
     * leg {@code validTo == null} is true.
     */
    @Test
    @DisplayName("isActiveOn(): null validTo stays active")
    void isActiveOnOpenWindow() {
        assertTrue(membership(FROM, null).isActiveOn(LocalDate.of(2030, 1, 1)));
    }

    /**
     * The end day itself is active (inclusive upper bound): the second guard's first leg is false and
     * the second leg {@code !date.isAfter(validTo)} is true — the before-boundary instant straddling
     * {@code validTo}.
     */
    @Test
    @DisplayName("isActiveOn(): validTo is active (inclusive)")
    void isActiveOnEndInclusive() {
        assertTrue(membership(FROM, TO).isActiveOn(TO));
    }

    /**
     * The day after the window closes is inactive: the second guard's first leg is false and the
     * second leg is false — the after-boundary instant straddling {@code validTo}.
     */
    @Test
    @DisplayName("isActiveOn(): the day after validTo is inactive")
    void isActiveOnDayAfterEnd() {
        assertFalse(membership(FROM, TO).isActiveOn(TO.plusDays(1)));
    }

    /**
     * A date strictly inside the closed window is active: the opening guard is fully false and the
     * closing guard's second leg holds.
     */
    @Test
    @DisplayName("isActiveOn(): a date inside the window is active")
    void isActiveOnInsideWindow() {
        assertTrue(membership(FROM, TO).isActiveOn(LocalDate.of(2026, 6, 15)));
    }

    // --------------------------------------------------
    // listForAccount()
    // --------------------------------------------------

    /**
     * The account finder delegates to the {@code account} query and returns its list (§14).
     */
    @Test
    @DisplayName("listForAccount(): returns the account's memberships")
    void listForAccountReturnsList() {
        FidelityAccount account = new FidelityAccount();
        List<FidelityMembership> memberships = List.of(new FidelityMembership());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("account", account)).thenReturn(memberships);
            assertEquals(memberships, FidelityMembership.listForAccount(account));
        }
    }

    // --------------------------------------------------
    // listActiveForAccount()
    // --------------------------------------------------

    /**
     * The active-membership finder delegates to the windowed query with the account and fiscal date
     * and returns its list; the date is a fixed argument, never a clock read (§31.1).
     */
    @Test
    @DisplayName("listActiveForAccount(): returns the memberships active on the date")
    void listActiveForAccountReturnsList() {
        FidelityAccount account = new FidelityAccount();
        LocalDate date = LocalDate.of(2026, 6, 15);
        List<FidelityMembership> memberships = List.of(new FidelityMembership());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(
                    "account = ?1 and validFrom <= ?2 and (validTo is null or validTo >= ?2)",
                    account, date)).thenReturn(memberships);
            assertEquals(memberships, FidelityMembership.listActiveForAccount(account, date));
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The checksum is a pure function of the business fields: two memberships with identical account
     * card, community code and window share it.
     */
    @Test
    @DisplayName("getChecksum(): identical business fields share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * A different validTo changes the checksum, so any window divergence is detected.
     */
    @Test
    @DisplayName("getChecksum(): a different validTo alters the checksum")
    void checksumChangesWithValidTo() {
        FidelityMembership other = sample();
        other.validTo = LocalDate.of(2027, 1, 31);
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A null account and a null community are tolerated: both ternaries take their null arm and the
     * checksum is that of the two bare dates, distinct from the fully-populated sample.
     */
    @Test
    @DisplayName("getChecksum(): null account and community take the null arm")
    void checksumNullAccountAndCommunity() {
        FidelityMembership membership = new FidelityMembership();
        membership.account = null;
        membership.community = null;
        membership.validFrom = FROM;
        membership.validTo = TO;
        assertEquals(java.util.Objects.hash(null, null, FROM, TO), membership.getChecksum());
        assertNotEquals(sample().getChecksum(), membership.getChecksum());
    }

    /**
     * A present account and community feed their business codes into the checksum: both ternaries
     * take their non-null arm, and swapping the community code alone changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): present account and community feed their codes")
    void checksumNonNullAccountAndCommunity() {
        FidelityMembership other = sample();
        other.community = community("OTHER");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a membership carrying only the window fields, used to drive {@code isActiveOn}.
     *
     * @param validFrom The inclusive start bound, or null.
     * @param validTo   The inclusive end bound, or null for an open window.
     * @return A minimally populated membership.
     */
    private FidelityMembership membership(LocalDate validFrom, LocalDate validTo) {
        FidelityMembership membership = new FidelityMembership();
        membership.validFrom = validFrom;
        membership.validTo = validTo;
        return membership;
    }

    /**
     * Builds an account carrying only the card number used by the checksum.
     *
     * @param cardNumber The card number.
     * @return A minimally populated account.
     */
    private FidelityAccount account(String cardNumber) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = cardNumber;
        return account;
    }

    /**
     * Builds a community carrying only the code used by the checksum.
     *
     * @param code The community business code.
     * @return A minimally populated community.
     */
    private FidelityCommunity community(String code) {
        FidelityCommunity community = new FidelityCommunity();
        community.code = code;
        return community;
    }

    /**
     * Builds a fully-populated membership with fixed business fields for checksum assertions.
     *
     * @return A sample membership with deterministic attributes.
     */
    private FidelityMembership sample() {
        FidelityMembership membership = new FidelityMembership();
        membership.account = account("3245070000001");
        membership.community = community("COMMUNITY_A");
        membership.validFrom = FROM;
        membership.validTo = TO;
        return membership;
    }
}
