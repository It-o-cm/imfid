package com.intermarche.fidelity.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.ReservationState;
import com.intermarche.fidelity.domain.util.ProgramClock;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link LedgerService}, the single writer of the ledger (§14,
 * §16, §30.1). Every guard is exercised on both arms and each leg of every compound guard:
 * the {@code cardNumber == null} short-circuit of {@code lock}; the natural-key
 * idempotency hit/miss of {@code post} (I8, §29.4); the {@code amount != null} ternary; the
 * {@code lineRefs != null} guard (§31.2); every leg of {@code FISCAL_USE.contains(type) &&
 * fiscalDate != null}; every leg of the {@code lastUsedAt == null || isBefore(moment)}
 * no-rewind guard of {@code advanceLastUsed} (§14, §16); and every leg of {@code active !=
 * null && active.isHolding(now)} of {@code activeReservationTotal} (I11, §27.2).
 * <p>
 * Fully isolated. The injected {@link ProgramClock} is mocked and its instant is fixed at
 * the program zone (§24.6): the reservation-lease decision reads that frozen {@code now}
 * and the expiry border is probed by the two instants straddling {@code expiresAt}, never
 * the day the campaign runs. The account is a Mockito mock so its {@code persist()} is a
 * no-op under a plain {@code mvn test}; the movements the service constructs are
 * intercepted with {@code mockConstruction} (their {@code lineRefs} pre-seeded, since the
 * field initializer does not run on a mock) and every static finder is stubbed with
 * {@code mockStatic} in try-with-resources. Every {@link BigDecimal} is asserted by
 * {@code compareTo} at scale 2 (§30.5).
 */
class LedgerServiceTest {

    /**
     * The fixed fiscal day at the program zone driving {@code earnYear} (§30.3).
     */
    private static final LocalDate FISCAL_DATE = LocalDate.of(2026, 3, 15);

    /**
     * The civil year {@code earnYearOf} resolves for {@link #FISCAL_DATE} (§30.3).
     */
    private static final int FISCAL_YEAR = 2026;

    /**
     * The frozen program instant the mocked clock returns for the lease decision (I11).
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 15, 10, 0, 0);

    /**
     * The system under test, freshly built with a mocked clock per test.
     */
    private LedgerService service;

    /**
     * The mocked program clock fixing the reservation-lease instant (§24.6).
     */
    private ProgramClock clock;

    /**
     * Builds a fresh system under test and wires the mocked clock before each test.
     */
    @BeforeEach
    void setUp() {
        service = new LedgerService();
        clock = Mockito.mock(ProgramClock.class);
        service.clock = clock;
    }

    /**
     * Builds a mocked account so its {@code persist()} is a no-op and its public fields
     * (balance, lastUsedAt) can be read back after a write (§30.1).
     *
     * @param cardNumber The card number carried on the account.
     * @return The mocked account, never null.
     */
    private FidelityAccount account(String cardNumber) {
        FidelityAccount account = Mockito.mock(FidelityAccount.class);
        account.cardNumber = cardNumber;
        return account;
    }

    /**
     * Builds a real reservation carrying the fields {@code isHolding} reads (I11).
     *
     * @param amount    The reserved amount.
     * @param state     The reservation state.
     * @param expiresAt The lease expiry instant.
     * @return The reservation, never null.
     */
    private FidelityReservation reservation(BigDecimal amount, ReservationState state, LocalDateTime expiresAt) {
        FidelityReservation reservation = new FidelityReservation();
        reservation.amount = amount;
        reservation.state = state;
        reservation.expiresAt = expiresAt;
        return reservation;
    }

    /**
     * {@code lock(null)} short-circuits to null before any finder — the null arm of the
     * guard (§30.1).
     */
    @Test
    @DisplayName("lock: a null card number returns null without a finder call")
    void lockNullCardNumberReturnsNull() {
        assertNull(service.lock(null));
    }

    /**
     * {@code lock} trims the card number and delegates to the locking finder — the
     * non-null arm of the guard (§30.1).
     */
    @Test
    @DisplayName("lock: a padded card number is trimmed and locked")
    void lockTrimsAndDelegates() {
        FidelityAccount locked = new FidelityAccount();
        locked.cardNumber = "CARD-1";
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.lockByCardNumber("CARD-1")).thenReturn(locked);
            assertSame(locked, service.lock("  CARD-1  "));
        }
    }

    /**
     * A movement whose natural key already exists is returned unchanged and neither the
     * balance nor {@code lastUsedAt} is touched — the {@code ticketRef != null} arm and the
     * {@code existing != null} early-return arm (I8, §16, §27.4).
     */
    @Test
    @DisplayName("post: a natural-key hit returns the existing movement, no recompute")
    void postReturnsExistingOnNaturalKeyHit() {
        FidelityAccount account = account("CARD-1");
        FidelityMovement existing = new FidelityMovement();
        existing.ticketRef = "T-1";
        try (MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class)) {
            movements.when(() -> FidelityMovement.findByNaturalKey("T-1", MovementType.EARN, "R-BIO"))
                    .thenReturn(existing);
            FidelityMovement result = service.post(account, MovementType.EARN, new BigDecimal("8.00"),
                    FISCAL_DATE, "R-BIO", "T-1", List.of("L1"), null);
            assertSame(existing, result);
            movements.verify(() -> FidelityMovement.computeBalance(Mockito.any()), Mockito.never());
            Mockito.verify(account, Mockito.never()).persist();
        }
    }

    /**
     * A fiscal-use movement with a fresh natural key is posted at scale 2, its earn year
     * taken from {@code earnYearOf}, its lines copied, the balance recomputed and
     * {@code lastUsedAt} advanced from null — the {@code existing == null} create arm, the
     * {@code amount != null} arm, the {@code lineRefs != null} arm, the both-true leg of
     * {@code contains && fiscalDate != null} and the null leg of the no-rewind guard.
     */
    @Test
    @DisplayName("post: a fresh fiscal movement is scaled, credited and advances lastUsedAt")
    void postCreatesFiscalMovementAdvancingLastUsed() {
        FidelityAccount account = account("CARD-1");
        try (MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedConstruction<FidelityMovement> construction = Mockito.mockConstruction(
                     FidelityMovement.class, (mock, context) -> mock.lineRefs = new ArrayList<>())) {
            movements.when(() -> FidelityMovement.findByNaturalKey("T-1", MovementType.EARN, "R-BIO"))
                    .thenReturn(null);
            movements.when(() -> FidelityMovement.earnYearOf(FISCAL_DATE)).thenReturn(FISCAL_YEAR);
            movements.when(() -> FidelityMovement.computeBalance(account)).thenReturn(new BigDecimal("50.00"));
            FidelityMovement result = service.post(account, MovementType.EARN, new BigDecimal("8.005"),
                    FISCAL_DATE, "R-BIO", "T-1", List.of("L1", "L2"), "credit");
            assertSame(account, result.account);
            assertEquals(MovementType.EARN, result.type);
            assertEquals(0, result.amount.compareTo(new BigDecimal("8.01")));
            assertEquals(FISCAL_DATE, result.movementDate);
            assertEquals(FISCAL_YEAR, result.earnYear);
            assertEquals("R-BIO", result.ruleCode);
            assertEquals("T-1", result.ticketRef);
            assertEquals(List.of("L1", "L2"), result.lineRefs);
            assertEquals("credit", result.reason);
            assertEquals(0, account.balance.compareTo(new BigDecimal("50.00")));
            assertEquals(FISCAL_DATE.atStartOfDay(), account.lastUsedAt);
        }
    }

    /**
     * A batch ADJUSTMENT with no ticket, a null amount and null lines posts a zero at
     * scale 2, keeps its lines empty and does not advance {@code lastUsedAt} — the
     * {@code ticketRef == null} arm skipping the finder, the {@code amount == null}
     * ternary arm, the {@code lineRefs == null} arm and the contains-false leg (§29.5,
     * §32.1).
     */
    @Test
    @DisplayName("post: a null-amount, no-ticket adjustment posts zero and never advances lastUsedAt")
    void postCreatesBatchAdjustmentWithNullAmountAndLines() {
        FidelityAccount account = account("CARD-1");
        try (MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedConstruction<FidelityMovement> construction = Mockito.mockConstruction(
                     FidelityMovement.class, (mock, context) -> mock.lineRefs = new ArrayList<>())) {
            movements.when(() -> FidelityMovement.earnYearOf(FISCAL_DATE)).thenReturn(FISCAL_YEAR);
            movements.when(() -> FidelityMovement.computeBalance(account)).thenReturn(new BigDecimal("12.00"));
            FidelityMovement result = service.post(account, MovementType.ADJUSTMENT, null,
                    FISCAL_DATE, null, null, null, "goodwill");
            assertEquals(MovementType.ADJUSTMENT, result.type);
            assertEquals(0, result.amount.compareTo(new BigDecimal("0.00")));
            assertEquals(2, result.amount.scale());
            assertEquals(FISCAL_YEAR, result.earnYear);
            assertNull(result.ruleCode);
            assertNull(result.ticketRef);
            assertTrue(result.lineRefs.isEmpty());
            assertEquals("goodwill", result.reason);
            assertEquals(0, account.balance.compareTo(new BigDecimal("12.00")));
            assertNull(account.lastUsedAt);
            movements.verify(() -> FidelityMovement.findByNaturalKey(
                    Mockito.any(), Mockito.any(), Mockito.any()), Mockito.never());
        }
    }

    /**
     * A fiscal-use type with a null fiscal date posts an earn year of zero and does not
     * advance {@code lastUsedAt} — the {@code fiscalDate != null} false leg of the compound
     * guard, with {@code earnYearOf(null)} yielding zero (§30.3).
     */
    @Test
    @DisplayName("post: a fiscal type with a null fiscal date yields earnYear 0 and no advance")
    void postFiscalTypeWithNullFiscalDateSkipsAdvance() {
        FidelityAccount account = account("CARD-1");
        try (MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedConstruction<FidelityMovement> construction = Mockito.mockConstruction(
                     FidelityMovement.class, (mock, context) -> mock.lineRefs = new ArrayList<>())) {
            movements.when(() -> FidelityMovement.computeBalance(account)).thenReturn(new BigDecimal("0.00"));
            FidelityMovement result = service.post(account, MovementType.BURN, new BigDecimal("-4.00"),
                    null, null, null, List.of(), null);
            assertEquals(MovementType.BURN, result.type);
            assertEquals(0, result.amount.compareTo(new BigDecimal("-4.00")));
            assertNull(result.movementDate);
            assertEquals(0, result.earnYear);
            assertNull(account.lastUsedAt);
        }
    }

    /**
     * A fiscal use later than the stored {@code lastUsedAt} advances the purge clock — the
     * {@code isBefore(moment)} true leg of the no-rewind guard, probed at the instant just
     * before the fiscal day border (§14, §16).
     */
    @Test
    @DisplayName("post: a later fiscal use advances lastUsedAt")
    void postAdvancesLastUsedWhenEarlier() {
        FidelityAccount account = account("CARD-1");
        account.lastUsedAt = FISCAL_DATE.atStartOfDay().minusSeconds(1);
        try (MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedConstruction<FidelityMovement> construction = Mockito.mockConstruction(
                     FidelityMovement.class, (mock, context) -> mock.lineRefs = new ArrayList<>())) {
            movements.when(() -> FidelityMovement.earnYearOf(FISCAL_DATE)).thenReturn(FISCAL_YEAR);
            movements.when(() -> FidelityMovement.computeBalance(account)).thenReturn(new BigDecimal("30.00"));
            service.post(account, MovementType.EARN, new BigDecimal("5.00"),
                    FISCAL_DATE, "R-BIO", null, null, null);
            assertEquals(FISCAL_DATE.atStartOfDay(), account.lastUsedAt);
        }
    }

    /**
     * A replayed older ticket must not pull the purge clock back: a {@code lastUsedAt}
     * already past the fiscal day is left untouched — both legs of the no-rewind guard
     * false, probed at the instant just after the fiscal day border (§14, §16).
     */
    @Test
    @DisplayName("post: a stored lastUsedAt past the fiscal day is not rewound")
    void postDoesNotRewindLastUsedWhenLater() {
        FidelityAccount account = account("CARD-1");
        LocalDateTime later = FISCAL_DATE.atStartOfDay().plusSeconds(1);
        account.lastUsedAt = later;
        try (MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedConstruction<FidelityMovement> construction = Mockito.mockConstruction(
                     FidelityMovement.class, (mock, context) -> mock.lineRefs = new ArrayList<>())) {
            movements.when(() -> FidelityMovement.earnYearOf(FISCAL_DATE)).thenReturn(FISCAL_YEAR);
            movements.when(() -> FidelityMovement.computeBalance(account)).thenReturn(new BigDecimal("30.00"));
            service.post(account, MovementType.EARN, new BigDecimal("5.00"),
                    FISCAL_DATE, "R-BIO", null, null, null);
            assertEquals(later, account.lastUsedAt);
        }
    }

    /**
     * {@code refreshBalance} stores the computed signed sum and persists the account
     * (§14).
     */
    @Test
    @DisplayName("refreshBalance: stores the computed signed sum and persists")
    void refreshBalanceStoresComputedSum() {
        FidelityAccount account = account("CARD-1");
        try (MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class)) {
            movements.when(() -> FidelityMovement.computeBalance(account)).thenReturn(new BigDecimal("-5.00"));
            service.refreshBalance(account);
            assertEquals(0, account.balance.compareTo(new BigDecimal("-5.00")));
            Mockito.verify(account).persist();
        }
    }

    /**
     * With no active reservation the held total is zero at scale 2 — the {@code active ==
     * null} false leg of the guard (I11, §27.2).
     */
    @Test
    @DisplayName("activeReservationTotal: no active reservation holds nothing")
    void activeReservationTotalNoActiveReservation() {
        FidelityAccount account = new FidelityAccount();
        Mockito.when(clock.now()).thenReturn(NOW);
        try (MockedStatic<FidelityReservation> reservations = Mockito.mockStatic(FidelityReservation.class)) {
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(null);
            BigDecimal held = service.activeReservationTotal(account);
            assertEquals(0, held.compareTo(new BigDecimal("0.00")));
            assertEquals(2, held.scale());
        }
    }

    /**
     * An active lease not yet expired at {@code now} holds its amount — the both-true leg,
     * probed at the instant just before the expiry border (I11).
     */
    @Test
    @DisplayName("activeReservationTotal: a holding lease holds its amount")
    void activeReservationTotalHoldingAddsAmount() {
        FidelityAccount account = new FidelityAccount();
        Mockito.when(clock.now()).thenReturn(NOW);
        FidelityReservation active = reservation(
                new BigDecimal("30.00"), ReservationState.ACTIVE, NOW.plusSeconds(1));
        try (MockedStatic<FidelityReservation> reservations = Mockito.mockStatic(FidelityReservation.class)) {
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(active);
            BigDecimal held = service.activeReservationTotal(account);
            assertEquals(0, held.compareTo(new BigDecimal("30.00")));
        }
    }

    /**
     * An active reservation whose lease has expired at {@code now} holds nothing — the
     * {@code isHolding(now)} false leg, probed at the expiry border where {@code now}
     * equals {@code expiresAt} (I11).
     */
    @Test
    @DisplayName("activeReservationTotal: an expired lease holds nothing")
    void activeReservationTotalExpiredHoldsNothing() {
        FidelityAccount account = new FidelityAccount();
        Mockito.when(clock.now()).thenReturn(NOW);
        FidelityReservation expired = reservation(
                new BigDecimal("30.00"), ReservationState.ACTIVE, NOW);
        try (MockedStatic<FidelityReservation> reservations = Mockito.mockStatic(FidelityReservation.class)) {
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(expired);
            BigDecimal held = service.activeReservationTotal(account);
            assertEquals(0, held.compareTo(new BigDecimal("0.00")));
        }
    }

    /**
     * The available balance subtracts an active lease from the denormalized balance (I11,
     * §27.2, §28.3).
     */
    @Test
    @DisplayName("availableBalance: subtracts the active reservation from the balance")
    void availableBalanceSubtractsActiveReservation() {
        FidelityAccount account = new FidelityAccount();
        account.balance = new BigDecimal("100.00");
        Mockito.when(clock.now()).thenReturn(NOW);
        FidelityReservation active = reservation(
                new BigDecimal("30.00"), ReservationState.ACTIVE, NOW.plusSeconds(1));
        try (MockedStatic<FidelityReservation> reservations = Mockito.mockStatic(FidelityReservation.class)) {
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(active);
            BigDecimal available = service.availableBalance(account);
            assertEquals(0, available.compareTo(new BigDecimal("70.00")));
            assertEquals(2, available.scale());
        }
    }

    /**
     * With no active reservation the available balance equals the balance at scale 2
     * (I11, §27.2).
     */
    @Test
    @DisplayName("availableBalance: equals the balance when nothing is held")
    void availableBalanceWithoutReservationEqualsBalance() {
        FidelityAccount account = new FidelityAccount();
        account.balance = new BigDecimal("100.00");
        Mockito.when(clock.now()).thenReturn(NOW);
        try (MockedStatic<FidelityReservation> reservations = Mockito.mockStatic(FidelityReservation.class)) {
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(null);
            BigDecimal available = service.availableBalance(account);
            assertEquals(0, available.compareTo(new BigDecimal("100.00")));
        }
    }
}
