package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link FidelityAccount}: the card-keyed loyalty account (§14). The class
 * carries no clock read of its own — {@code activatedAt} and {@code lastUsedAt} are stored fields,
 * not {@code DateTimeProvider} calls — so no time is injected here; the temporal threshold of
 * {@link FidelityAccount#listUnusedSince} is passed in as a fixed argument. The Panache active-record
 * finders are mocked through {@link PanacheEntityBase} in a try-with-resources per the imfid unit
 * bench, the status guards are exercised leg by leg (§29.6), the transfer-chain walk is driven to its
 * null-successor, live-successor and cycle-guard exits, and the checksum is asserted as the pure
 * function of the business fields it is.
 */
class FidelityAccountTest {

    // --------------------------------------------------
    // canEarn()
    // --------------------------------------------------

    /**
     * An ACTIVE account earns: the first leg of the {@code ACTIVE || PENDING_ACTIVATION} guard is
     * true.
     */
    @Test
    @DisplayName("canEarn(): ACTIVE earns")
    void canEarnActive() {
        FidelityAccount account = new FidelityAccount();
        account.status = AccountStatus.ACTIVE;
        assertTrue(account.canEarn());
    }

    /**
     * A PENDING_ACTIVATION account earns: the first leg is false, the second true.
     */
    @Test
    @DisplayName("canEarn(): PENDING_ACTIVATION earns")
    void canEarnPending() {
        FidelityAccount account = new FidelityAccount();
        account.status = AccountStatus.PENDING_ACTIVATION;
        assertTrue(account.canEarn());
    }

    /**
     * A RESILIATED account never earns: both legs of the guard are false.
     */
    @Test
    @DisplayName("canEarn(): RESILIATED does not earn")
    void canEarnResiliated() {
        FidelityAccount account = new FidelityAccount();
        account.status = AccountStatus.RESILIATED;
        assertFalse(account.canEarn());
    }

    // --------------------------------------------------
    // canBurn()
    // --------------------------------------------------

    /**
     * Only a fully ACTIVE account may burn: the equality guard is true.
     */
    @Test
    @DisplayName("canBurn(): ACTIVE burns")
    void canBurnActive() {
        FidelityAccount account = new FidelityAccount();
        account.status = AccountStatus.ACTIVE;
        assertTrue(account.canBurn());
    }

    /**
     * A PENDING_ACTIVATION account cannot burn: the equality guard is false (§25.3).
     */
    @Test
    @DisplayName("canBurn(): PENDING_ACTIVATION does not burn")
    void canBurnPending() {
        FidelityAccount account = new FidelityAccount();
        account.status = AccountStatus.PENDING_ACTIVATION;
        assertFalse(account.canBurn());
    }

    /**
     * A RESILIATED account cannot burn: the equality guard is false.
     */
    @Test
    @DisplayName("canBurn(): RESILIATED does not burn")
    void canBurnResiliated() {
        FidelityAccount account = new FidelityAccount();
        account.status = AccountStatus.RESILIATED;
        assertFalse(account.canBurn());
    }

    // --------------------------------------------------
    // isTransferred()
    // --------------------------------------------------

    /**
     * A live account with no successor card is not transferred: the first leg
     * {@code transferredToCard != null} is false.
     */
    @Test
    @DisplayName("isTransferred(): null successor is not transferred")
    void isTransferredNull() {
        FidelityAccount account = new FidelityAccount();
        account.transferredToCard = null;
        assertFalse(account.isTransferred());
    }

    /**
     * A successor set to a blank string is not a transfer: the first leg is true, the second
     * {@code !isBlank()} is false.
     */
    @Test
    @DisplayName("isTransferred(): blank successor is not transferred")
    void isTransferredBlank() {
        FidelityAccount account = new FidelityAccount();
        account.transferredToCard = "   ";
        assertFalse(account.isTransferred());
    }

    /**
     * A non-blank successor card marks the account as transferred: both legs are true.
     */
    @Test
    @DisplayName("isTransferred(): non-blank successor is transferred")
    void isTransferredSet() {
        FidelityAccount account = new FidelityAccount();
        account.transferredToCard = "3245070000002";
        assertTrue(account.isTransferred());
    }

    // --------------------------------------------------
    // findByCardNumber()
    // --------------------------------------------------

    /**
     * The card finder delegates to the {@code cardNumber} query and returns its first result when a
     * card matches.
     */
    @Test
    @DisplayName("findByCardNumber(): returns the matching account")
    void findByCardNumberFound() {
        FidelityAccount found = new FidelityAccount();
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityAccount> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", "3245070000001")).thenReturn(query);
            assertSame(found, FidelityAccount.findByCardNumber("3245070000001"));
        }
    }

    /**
     * The card finder returns null when the card is unknown, feeding the empty-earn contract (§20).
     */
    @Test
    @DisplayName("findByCardNumber(): returns null when unknown")
    void findByCardNumberAbsent() {
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityAccount> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", "0000000000000")).thenReturn(query);
            assertNull(FidelityAccount.findByCardNumber("0000000000000"));
        }
    }

    // --------------------------------------------------
    // lockByCardNumber()
    // --------------------------------------------------

    /**
     * The locking finder takes the pessimistic write lock (§30.1) and returns the locked account
     * when the card exists.
     */
    @Test
    @DisplayName("lockByCardNumber(): returns the locked account")
    void lockByCardNumberFound() {
        FidelityAccount found = new FidelityAccount();
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityAccount> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.withLock(LockModeType.PESSIMISTIC_WRITE)).thenReturn(query);
        Mockito.when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", "3245070000001")).thenReturn(query);
            assertSame(found, FidelityAccount.lockByCardNumber("3245070000001"));
        }
    }

    /**
     * The locking finder returns null when the card is unknown, still having requested the lock.
     */
    @Test
    @DisplayName("lockByCardNumber(): returns null when unknown")
    void lockByCardNumberAbsent() {
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityAccount> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.withLock(LockModeType.PESSIMISTIC_WRITE)).thenReturn(query);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", "0000000000000")).thenReturn(query);
            assertNull(FidelityAccount.lockByCardNumber("0000000000000"));
        }
    }

    // --------------------------------------------------
    // resolveActive()
    // --------------------------------------------------

    /**
     * Resolving an unknown starting card returns null: the first leg {@code account != null} of the
     * loop guard is false and the walk never starts (§32.2).
     */
    @Test
    @DisplayName("resolveActive(): unknown start card resolves to null")
    void resolveActiveUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "UNKNOWN", null);
            assertNull(FidelityAccount.resolveActive("UNKNOWN"));
        }
    }

    /**
     * A live account with no successor resolves to itself: the second leg {@code isTransferred()} of
     * the loop guard is false on the first evaluation.
     */
    @Test
    @DisplayName("resolveActive(): live account resolves to itself")
    void resolveActiveLive() {
        FidelityAccount live = account("CARD_A", null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "CARD_A", live);
            assertSame(live, FidelityAccount.resolveActive("CARD_A"));
        }
    }

    /**
     * A transferred account whose successor is missing resolves to the resiliated tail: the loop
     * enters, then breaks on the {@code next == null} guard (§32.2).
     */
    @Test
    @DisplayName("resolveActive(): dangling successor resolves to the resiliated tail")
    void resolveActiveDanglingSuccessor() {
        FidelityAccount tail = account("CARD_A", "CARD_B");
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "CARD_A", tail);
            stubFind(panache, "CARD_B", null);
            assertSame(tail, FidelityAccount.resolveActive("CARD_A"));
        }
    }

    /**
     * A transfer chain resolves to its live successor: the loop advances past the transferred head
     * ({@code next != null}) and stops on the live successor.
     */
    @Test
    @DisplayName("resolveActive(): chain resolves to the live successor")
    void resolveActiveChain() {
        FidelityAccount head = account("CARD_A", "CARD_B");
        FidelityAccount successor = account("CARD_B", null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "CARD_A", head);
            stubFind(panache, "CARD_B", successor);
            assertSame(successor, FidelityAccount.resolveActive("CARD_A"));
        }
    }

    /**
     * A cyclic transfer chain terminates on the cycle guard: a self-referential card keeps both the
     * non-null and transferred legs true, so the walk exits only when the third leg
     * {@code guard++ < 100} turns false.
     */
    @Test
    @DisplayName("resolveActive(): cycle stops on the guard")
    void resolveActiveCycleGuard() {
        FidelityAccount loop = account("CARD_A", "CARD_A");
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubFind(panache, "CARD_A", loop);
            assertSame(loop, FidelityAccount.resolveActive("CARD_A"));
        }
    }

    // --------------------------------------------------
    // listUnusedSince()
    // --------------------------------------------------

    /**
     * The purge candidate finder delegates to the last-used query and returns its list (§16); the
     * threshold is a fixed argument, never a clock read.
     */
    @Test
    @DisplayName("listUnusedSince(): returns the stale accounts")
    void listUnusedSinceReturnsList() {
        LocalDateTime threshold = LocalDateTime.of(2024, 8, 8, 0, 0, 0);
        List<FidelityAccount> stale = List.of(new FidelityAccount(), new FidelityAccount());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(
                    "status <> ?1 and coalesce(lastUsedAt, createdAt) < ?2",
                    AccountStatus.RESILIATED, threshold)).thenReturn(stale);
            assertEquals(stale, FidelityAccount.listUnusedSince(threshold));
        }
    }

    // --------------------------------------------------
    // page()
    // --------------------------------------------------

    /**
     * The paging finder orders by card number, pages to the requested size and returns its list.
     */
    @Test
    @DisplayName("page(): pages the card-number-ordered query")
    void pageReturnsList() {
        List<FidelityAccount> accounts = List.of(new FidelityAccount());
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityAccount> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.page(ArgumentMatchers.any(Page.class))).thenReturn(query);
        Mockito.when(query.list()).thenReturn(accounts);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("order by cardNumber")).thenReturn(query);
            assertEquals(accounts, FidelityAccount.page(2, 20));
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The checksum is a pure function of the business fields: two accounts with identical attributes
     * share it.
     */
    @Test
    @DisplayName("getChecksum(): identical business fields share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * Changing any business field — here the balance — changes the checksum, so any account
     * divergence is detected.
     */
    @Test
    @DisplayName("getChecksum(): a different balance alters the checksum")
    void checksumChangesWithBalance() {
        FidelityAccount other = sample();
        other.balance = new BigDecimal("99.99");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    // --------------------------------------------------
    // Field initializers
    // --------------------------------------------------

    /**
     * A freshly constructed account defaults to ACTIVE (§30.4).
     */
    @Test
    @DisplayName("new: status defaults to ACTIVE")
    void statusDefaultsToActive() {
        assertEquals(AccountStatus.ACTIVE, new FidelityAccount().status);
    }

    /**
     * A freshly constructed account starts at a zero balance scaled to 2 (§30.5), compared by
     * {@code compareTo} so {@code 0.00} and {@code 0} are treated as equal.
     */
    @Test
    @DisplayName("new: balance defaults to zero at scale 2")
    void balanceDefaultsToZero() {
        BigDecimal balance = new FidelityAccount().balance;
        assertTrue(BigDecimal.ZERO.compareTo(balance) == 0);
        assertEquals(2, balance.scale());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds an account with the given card and successor, used to drive the transfer-chain walk.
     *
     * @param cardNumber        The account's own card number.
     * @param transferredToCard The successor card, or null for a live account.
     * @return A minimally populated account.
     */
    private FidelityAccount account(String cardNumber, String transferredToCard) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = cardNumber;
        account.transferredToCard = transferredToCard;
        return account;
    }

    /**
     * Stubs the {@code cardNumber} finder to return the given account for the given card number.
     *
     * @param panache    The active Panache static mock.
     * @param cardNumber The card number the finder is queried with.
     * @param result     The account to return, or null when the card is unknown.
     */
    private void stubFind(MockedStatic<PanacheEntityBase> panache, String cardNumber,
            FidelityAccount result) {
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityAccount> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(result);
        panache.when(() -> PanacheEntityBase.find("cardNumber", cardNumber)).thenReturn(query);
    }

    /**
     * Builds a fully-populated account with fixed business fields for checksum assertions.
     *
     * @return A sample account with deterministic attributes.
     */
    private FidelityAccount sample() {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = "3245070000001";
        account.status = AccountStatus.ACTIVE;
        account.activatedAt = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
        account.lastUsedAt = LocalDateTime.of(2026, 6, 30, 18, 30, 0);
        account.balance = new BigDecimal("12.34");
        account.transferredToCard = null;
        return account;
    }
}
