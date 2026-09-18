package com.intermarche.fidelity.burn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import com.intermarche.fidelity.account.LedgerService;
import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.ReservationState;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import com.intermarche.fidelity.domain.util.ProgramClock;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link ReservationService}: the burn reservation protocol —
 * reservation at lease then confirmation (I11, §16, §27.3). It exercises every leg of
 * every guard of {@code reserve} (unknown card, non-burnable account, non-positive
 * amount, the elapsed / same-ticket-renew / other-ticket-conflict shapes of the live
 * lease, the once-per-day rule and the available-balance ceiling), of {@code confirm}
 * (missing, idempotent, terminal-or-expired, fiscal-date defaulting) and of
 * {@code release} (missing, active, non-active), plus both arms of the lease-TTL
 * fallback (§25.1, I11).
 * <p>
 * Fully isolated: {@link LedgerService} and {@link ProgramClock} are Mockito mocks
 * injected into the package-private fields. The Panache static finders
 * ({@link FidelityReservation#findActiveForAccount}, the inherited
 * {@link PanacheEntityBase#findById}, {@link FidelityMovement#hasBurnOn}) and the
 * setting reader ({@link FidelityProgramSetting#getInt}) are intercepted with
 * {@code mockStatic} in try-with-resources, and the {@code new FidelityReservation()}
 * the create path performs is neutralized with {@code mockConstruction} so no
 * {@code persist()} ever hits the (absent) session. The clock is fixed through the
 * mocked {@link ProgramClock} — every time-driven decision reads {@link #FIXED},
 * never the campaign day (§24.6, §30.3, §25.1). Every {@code BigDecimal} is asserted
 * by {@code compareTo}, to the cent (§30.5).
 */
class ReservationServiceTest {

    /**
     * The fixed program wall time every clock read resolves to (§24.6).
     */
    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 3, 15, 10, 0);

    /**
     * The fixed program fiscal day matching {@link #FIXED} (§30.3).
     */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    /**
     * The default lease TTL in seconds when the setting is absent (15 minutes, I11).
     */
    private static final long DEFAULT_TTL = 900L;

    /**
     * The system under test, freshly built per test with mocked collaborators.
     */
    private ReservationService service;

    /**
     * The mocked ledger holding the lock, the available balance and the BURN post.
     */
    private LedgerService ledger;

    /**
     * The mocked program clock resolving the lease expiry and the fiscal day.
     */
    private ProgramClock clock;

    /**
     * Wires a fresh service with its mocked collaborators and fixes the clock before each test.
     */
    @BeforeEach
    void setUp() {
        service = new ReservationService();
        ledger = Mockito.mock(LedgerService.class);
        clock = Mockito.mock(ProgramClock.class);
        service.ledger = ledger;
        service.clock = clock;
        Mockito.when(clock.now()).thenReturn(FIXED);
        Mockito.when(clock.today()).thenReturn(TODAY);
        DateTimeProvider.setFixedDateTime(FIXED);
    }

    /**
     * Clears the fixed clock after each test so no fixation leaks across the campaign (§24.6).
     */
    @AfterEach
    void tearDown() {
        DateTimeProvider.clear();
    }

    // --------------------------------------------------
    // Fixtures
    // --------------------------------------------------

    /**
     * Builds a fidelity account carrying a card number, a status and a balance.
     *
     * @param card    The card number.
     * @param status  The account status.
     * @param balance The materialized balance, euro at scale 2.
     * @return The account.
     */
    private FidelityAccount account(String card, AccountStatus status, String balance) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = card;
        account.status = status;
        account.balance = new BigDecimal(balance);
        return account;
    }

    /**
     * Builds an active reservation as a Mockito mock (so its {@code persist()} is a no-op)
     * carrying the ticket and expiry the lease reads (§27.3).
     *
     * @param ticketRef The ticket the lease is tied to.
     * @param expired   Whether the lease has elapsed at the fixed instant.
     * @return The reservation mock.
     */
    private FidelityReservation activeReservation(String ticketRef, boolean expired) {
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.ACTIVE;
        reservation.ticketRef = ticketRef;
        reservation.amount = new BigDecimal("3.00");
        reservation.expiresAt = FIXED.plusSeconds(60);
        Mockito.when(reservation.isExpiredAt(FIXED)).thenReturn(expired);
        return reservation;
    }

    // --------------------------------------------------
    // reserve(): the pre-flight guards
    // --------------------------------------------------

    /**
     * An unknown card yields NOT_FOUND — the {@code account == null} arm (§20, §30.1).
     */
    @Test
    @DisplayName("reserve(): an unknown card is NOT_FOUND")
    void reserveUnknownCard() {
        Mockito.when(ledger.lock("CARD1")).thenReturn(null);
        ReservationService.ReserveOutcome outcome = service.reserve("CARD1", new BigDecimal("5.00"), "T1");
        assertEquals(ReservationService.ReserveOutcome.Status.NOT_FOUND, outcome.status);
        assertNull(outcome.reservation);
    }

    /**
     * A non-ACTIVE account is refused with ACCOUNT_STATUS — the {@code !canBurn()} arm: a
     * PENDING_ACTIVATION account accrues but does not burn (§25.3).
     */
    @Test
    @DisplayName("reserve(): a PENDING_ACTIVATION account is rejected ACCOUNT_STATUS")
    void reserveCannotBurn() {
        FidelityAccount account = account("CARD1", AccountStatus.PENDING_ACTIVATION, "50.00");
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        ReservationService.ReserveOutcome outcome = service.reserve("CARD1", new BigDecimal("5.00"), "T1");
        assertEquals(ReservationService.ReserveOutcome.Status.REJECTED, outcome.status);
        assertEquals("ACCOUNT_STATUS", outcome.reason);
    }

    /**
     * A null amount collapses to zero and is rejected INSUFFICIENT_BALANCE — the null arm of the
     * {@code amount != null} ternary and the {@code signum() <= 0} true arm (§30.5).
     */
    @Test
    @DisplayName("reserve(): a null amount is rejected INSUFFICIENT_BALANCE")
    void reserveNullAmount() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        ReservationService.ReserveOutcome outcome = service.reserve("CARD1", null, "T1");
        assertEquals(ReservationService.ReserveOutcome.Status.REJECTED, outcome.status);
        assertEquals("INSUFFICIENT_BALANCE", outcome.reason);
    }

    /**
     * A non-null zero amount is rejected INSUFFICIENT_BALANCE — the non-null arm of the ternary
     * with the {@code signum() <= 0} true arm (§30.5).
     */
    @Test
    @DisplayName("reserve(): a zero amount is rejected INSUFFICIENT_BALANCE")
    void reserveZeroAmount() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        ReservationService.ReserveOutcome outcome = service.reserve("CARD1", new BigDecimal("0.00"), "T1");
        assertEquals(ReservationService.ReserveOutcome.Status.REJECTED, outcome.status);
        assertEquals("INSUFFICIENT_BALANCE", outcome.reason);
    }

    // --------------------------------------------------
    // reserve(): a live lease already on the card
    // --------------------------------------------------

    /**
     * An elapsed lease frees itself and does not block a new reservation — the
     * {@code active.isExpiredAt(now)} true arm, then the create path (§16, §27.3).
     */
    @Test
    @DisplayName("reserve(): an elapsed lease frees itself and a new reservation is created")
    void reserveElapsedLeaseThenCreates() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        Mockito.when(ledger.availableBalance(account)).thenReturn(new BigDecimal("50.00"));
        FidelityReservation active = activeReservation("OLD", true);
        try (MockedStatic<FidelityReservation> reservations = Mockito.mockStatic(FidelityReservation.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedConstruction<FidelityReservation> cons = Mockito.mockConstruction(FidelityReservation.class)) {
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(active);
            movements.when(() -> FidelityMovement.hasBurnOn(account, TODAY)).thenReturn(false);
            settings.when(() -> FidelityProgramSetting.getInt(
                    FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, (int) DEFAULT_TTL)).thenReturn(1200);
            ReservationService.ReserveOutcome outcome = service.reserve("CARD1", new BigDecimal("5.00"), "NEW");
            assertEquals(ReservationService.ReserveOutcome.Status.CREATED, outcome.status);
            assertEquals(ReservationState.EXPIRED, active.state);
            FidelityReservation created = cons.constructed().get(0);
            assertSame(created, outcome.reservation);
            assertSame(account, created.account);
            assertEquals(ReservationState.ACTIVE, created.state);
            assertEquals("NEW", created.ticketRef);
            assertEquals(0, created.amount.compareTo(new BigDecimal("5.00")));
            assertEquals(FIXED.plusSeconds(1200), created.expiresAt);
        }
    }

    /**
     * A same-ticket re-POST within balance renews the lease — the {@code ticketRef != null &&
     * ticketRef.equals(active.ticketRef)} both-legs-true branch, the {@code want <= balance}
     * false arm, and the {@code ttl <= 0} fallback to 900 s (§27.3, I11).
     */
    @Test
    @DisplayName("reserve(): a same-ticket re-POST renews the lease with the default TTL")
    void reserveSameTicketRenew() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        FidelityReservation active = activeReservation("T1", false);
        try (MockedStatic<FidelityReservation> reservations = Mockito.mockStatic(FidelityReservation.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class)) {
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(active);
            settings.when(() -> FidelityProgramSetting.getInt(
                    FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, (int) DEFAULT_TTL)).thenReturn(0);
            ReservationService.ReserveOutcome outcome = service.reserve("CARD1", new BigDecimal("7.00"), "T1");
            assertEquals(ReservationService.ReserveOutcome.Status.RENEWED, outcome.status);
            assertSame(active, outcome.reservation);
            assertEquals(0, active.amount.compareTo(new BigDecimal("7.00")));
            assertEquals(FIXED.plusSeconds(DEFAULT_TTL), active.expiresAt);
        }
    }

    /**
     * A same-ticket renew beyond the balance is rejected INSUFFICIENT_BALANCE — the
     * {@code want.compareTo(account.balance) > 0} true arm (§27.3).
     */
    @Test
    @DisplayName("reserve(): a same-ticket renew beyond the balance is rejected")
    void reserveSameTicketRenewInsufficient() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "6.00");
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        FidelityReservation active = activeReservation("T1", false);
        try (MockedStatic<FidelityReservation> reservations = Mockito.mockStatic(FidelityReservation.class)) {
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(active);
            ReservationService.ReserveOutcome outcome = service.reserve("CARD1", new BigDecimal("7.00"), "T1");
            assertEquals(ReservationService.ReserveOutcome.Status.REJECTED, outcome.status);
            assertEquals("INSUFFICIENT_BALANCE", outcome.reason);
        }
    }

    /**
     * A live lease on another ticket blocks a new reservation — the else branch reached with the
     * second leg false ({@code ticketRef != null} but {@code !ticketRef.equals(active.ticketRef)},
     * §27.3).
     */
    @Test
    @DisplayName("reserve(): a live lease on another ticket is a CONFLICT")
    void reserveOtherTicketConflict() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        FidelityReservation active = activeReservation("OTHER", false);
        try (MockedStatic<FidelityReservation> reservations = Mockito.mockStatic(FidelityReservation.class)) {
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(active);
            ReservationService.ReserveOutcome outcome = service.reserve("CARD1", new BigDecimal("5.00"), "T1");
            assertEquals(ReservationService.ReserveOutcome.Status.CONFLICT, outcome.status);
            assertNull(outcome.reservation);
        }
    }

    /**
     * A null-ticket re-POST against a live lease conflicts — the first leg false
     * ({@code ticketRef != null} is false), which cannot renew and falls to the conflict (§27.3).
     */
    @Test
    @DisplayName("reserve(): a null-ticket request against a live lease is a CONFLICT")
    void reserveNullTicketConflict() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        FidelityReservation active = activeReservation("T1", false);
        try (MockedStatic<FidelityReservation> reservations = Mockito.mockStatic(FidelityReservation.class)) {
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(active);
            ReservationService.ReserveOutcome outcome = service.reserve("CARD1", new BigDecimal("5.00"), null);
            assertEquals(ReservationService.ReserveOutcome.Status.CONFLICT, outcome.status);
        }
    }

    // --------------------------------------------------
    // reserve(): the new-reservation path
    // --------------------------------------------------

    /**
     * A card that already burnt today is refused DAILY_RULE — the {@code hasBurnOn} true arm on the
     * no-active-lease path (§25.5, §16).
     */
    @Test
    @DisplayName("reserve(): a card that already burnt today is rejected DAILY_RULE")
    void reserveDailyRule() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        try (MockedStatic<FidelityReservation> reservations = Mockito.mockStatic(FidelityReservation.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class)) {
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(null);
            movements.when(() -> FidelityMovement.hasBurnOn(account, TODAY)).thenReturn(true);
            ReservationService.ReserveOutcome outcome = service.reserve("CARD1", new BigDecimal("5.00"), "T1");
            assertEquals(ReservationService.ReserveOutcome.Status.REJECTED, outcome.status);
            assertEquals("DAILY_RULE", outcome.reason);
        }
    }

    /**
     * A want beyond the available balance is refused INSUFFICIENT_BALANCE — the
     * {@code want.compareTo(available) > 0} true arm on the create path (§27.2, §27.3).
     */
    @Test
    @DisplayName("reserve(): a want beyond the available balance is rejected")
    void reserveInsufficientAvailable() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        Mockito.when(ledger.availableBalance(account)).thenReturn(new BigDecimal("4.00"));
        try (MockedStatic<FidelityReservation> reservations = Mockito.mockStatic(FidelityReservation.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class)) {
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(null);
            movements.when(() -> FidelityMovement.hasBurnOn(account, TODAY)).thenReturn(false);
            ReservationService.ReserveOutcome outcome = service.reserve("CARD1", new BigDecimal("5.00"), "T1");
            assertEquals(ReservationService.ReserveOutcome.Status.REJECTED, outcome.status);
            assertEquals("INSUFFICIENT_BALANCE", outcome.reason);
        }
    }

    /**
     * A first reservation within the available balance is created — the create path with the
     * {@code want <= available} false arm and the {@code ttl > 0} fallback arm (§27.3, I11).
     */
    @Test
    @DisplayName("reserve(): a first reservation within the available balance is CREATED")
    void reserveCreatesNew() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        Mockito.when(ledger.availableBalance(account)).thenReturn(new BigDecimal("50.00"));
        try (MockedStatic<FidelityReservation> reservations = Mockito.mockStatic(FidelityReservation.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedConstruction<FidelityReservation> cons = Mockito.mockConstruction(FidelityReservation.class)) {
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(null);
            movements.when(() -> FidelityMovement.hasBurnOn(account, TODAY)).thenReturn(false);
            settings.when(() -> FidelityProgramSetting.getInt(
                    FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, (int) DEFAULT_TTL)).thenReturn(1200);
            ReservationService.ReserveOutcome outcome = service.reserve("CARD1", new BigDecimal("5.005"), "T1");
            assertEquals(ReservationService.ReserveOutcome.Status.CREATED, outcome.status);
            FidelityReservation created = cons.constructed().get(0);
            assertSame(created, outcome.reservation);
            assertSame(account, created.account);
            assertEquals(ReservationState.ACTIVE, created.state);
            assertEquals("T1", created.ticketRef);
            assertEquals(0, created.amount.compareTo(new BigDecimal("5.01")));
            assertEquals(FIXED.plusSeconds(1200), created.expiresAt);
        }
    }

    // --------------------------------------------------
    // confirm(): the fiscal confirmation
    // --------------------------------------------------

    /**
     * A null id confirms nothing — the null arm of the {@code id == null} ternary, no lookup and no
     * lock (§27.3).
     */
    @Test
    @DisplayName("confirm(): a null id is NOT_FOUND")
    void confirmNullId() {
        ReservationService.ConfirmResult outcome = service.confirm(null, TODAY);
        assertEquals(ReservationService.ConfirmOutcome.NOT_FOUND, outcome.outcome);
        Mockito.verifyNoInteractions(ledger);
    }

    /**
     * A missing reservation is NOT_FOUND — the non-null arm of the ternary with the
     * {@code reservation == null} true arm (§27.3).
     */
    @Test
    @DisplayName("confirm(): a missing reservation is NOT_FOUND")
    void confirmMissing() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(9L)).thenReturn(null);
            ReservationService.ConfirmResult outcome = service.confirm(9L, TODAY);
            assertEquals(ReservationService.ConfirmOutcome.NOT_FOUND, outcome.outcome);
            Mockito.verifyNoInteractions(ledger);
        }
    }

    /**
     * An already-confirmed reservation is an idempotent OK — the {@code state == CONFIRMED} true arm
     * (§27.3).
     */
    @Test
    @DisplayName("confirm(): an already-confirmed reservation is idempotently OK")
    void confirmAlreadyConfirmed() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.CONFIRMED;
        reservation.account = account;
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(reservation);
            ReservationService.ConfirmResult outcome = service.confirm(1L, TODAY);
            assertEquals(ReservationService.ConfirmOutcome.OK, outcome.outcome);
            Mockito.verify(ledger, Mockito.never()).post(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    /**
     * A terminal (non-ACTIVE) reservation is GONE — the first leg of the
     * {@code state != ACTIVE || isExpiredAt} guard, short-circuiting before the lease test (§27.3).
     */
    @Test
    @DisplayName("confirm(): a released reservation is GONE")
    void confirmReleasedIsGone() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.RELEASED;
        reservation.account = account;
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(reservation);
            ReservationService.ConfirmResult outcome = service.confirm(1L, TODAY);
            assertEquals(ReservationService.ConfirmOutcome.GONE, outcome.outcome);
            Mockito.verify(ledger, Mockito.never()).post(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    /**
     * An ACTIVE but elapsed lease is GONE — the first leg false ({@code state == ACTIVE}) and the
     * second leg true ({@code isExpiredAt(now)}) of the guard (§27.3).
     */
    @Test
    @DisplayName("confirm(): an ACTIVE but expired lease is GONE")
    void confirmActiveExpiredIsGone() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.ACTIVE;
        reservation.account = account;
        Mockito.when(reservation.isExpiredAt(FIXED)).thenReturn(true);
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(reservation);
            ReservationService.ConfirmResult outcome = service.confirm(1L, TODAY);
            assertEquals(ReservationService.ConfirmOutcome.GONE, outcome.outcome);
            Mockito.verify(ledger, Mockito.never()).post(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    /**
     * A live lease with an explicit fiscal date posts the BURN on that date — both legs of the
     * guard false and the non-null arm of the {@code fiscalDate != null} ternary (§27.3).
     */
    @Test
    @DisplayName("confirm(): a live lease posts the BURN on the given fiscal date")
    void confirmActiveWithFiscalDate() {
        LocalDate fiscal = LocalDate.of(2026, 3, 14);
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.ACTIVE;
        reservation.account = account;
        reservation.amount = new BigDecimal("5.00");
        reservation.ticketRef = "T1";
        Mockito.when(reservation.isExpiredAt(FIXED)).thenReturn(false);
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(reservation);
            ReservationService.ConfirmResult outcome = service.confirm(1L, fiscal);
            assertEquals(ReservationService.ConfirmOutcome.OK, outcome.outcome);
            assertEquals(ReservationState.CONFIRMED, reservation.state);
            Mockito.verify(ledger).post(eq(account), eq(MovementType.BURN),
                    argThat(a -> a.compareTo(new BigDecimal("-5.00")) == 0), eq(fiscal), isNull(),
                    eq("T1"), eq(List.of()), isNull());
        }
    }

    /**
     * A live lease with a null fiscal date posts on the current program day — the null arm of the
     * {@code fiscalDate != null} ternary, defaulting to {@code clock.today()} (§30.3).
     */
    @Test
    @DisplayName("confirm(): a null fiscal date posts the BURN on the program day")
    void confirmActiveNullFiscalDate() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.ACTIVE;
        reservation.account = account;
        reservation.amount = new BigDecimal("8.00");
        reservation.ticketRef = "T2";
        Mockito.when(reservation.isExpiredAt(FIXED)).thenReturn(false);
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(reservation);
            ReservationService.ConfirmResult outcome = service.confirm(1L, null);
            assertEquals(ReservationService.ConfirmOutcome.OK, outcome.outcome);
            assertEquals(ReservationState.CONFIRMED, reservation.state);
            Mockito.verify(ledger).post(eq(account), eq(MovementType.BURN),
                    argThat(a -> a.compareTo(new BigDecimal("-8.00")) == 0), eq(TODAY), isNull(),
                    eq("T2"), eq(List.of()), isNull());
        }
    }

    // --------------------------------------------------
    // release(): the cancelled payment
    // --------------------------------------------------

    /**
     * A null id releases nothing — the null arm of the {@code id == null} ternary, no lookup and no
     * lock (§27.3).
     */
    @Test
    @DisplayName("release(): a null id is a no-op")
    void releaseNullId() {
        service.release(null);
        Mockito.verifyNoInteractions(ledger);
    }

    /**
     * A missing reservation releases nothing — the {@code reservation == null} true arm (§27.3).
     */
    @Test
    @DisplayName("release(): a missing reservation is a no-op")
    void releaseMissing() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(9L)).thenReturn(null);
            service.release(9L);
            Mockito.verifyNoInteractions(ledger);
        }
    }

    /**
     * An active reservation is released — the {@code state == ACTIVE} true arm, under the per-card
     * lock (§27.3, §30.1).
     */
    @Test
    @DisplayName("release(): an active reservation becomes RELEASED")
    void releaseActive() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.ACTIVE;
        reservation.account = account;
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(reservation);
            service.release(1L);
            assertEquals(ReservationState.RELEASED, reservation.state);
            Mockito.verify(ledger).lock("CARD1");
        }
    }

    /**
     * A non-active reservation is left untouched — the {@code state == ACTIVE} false arm; the call
     * still takes the lock and is always a no-op write (§27.3).
     */
    @Test
    @DisplayName("release(): a confirmed reservation is left untouched")
    void releaseNonActive() {
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE, "50.00");
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.CONFIRMED;
        reservation.account = account;
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(reservation);
            service.release(1L);
            assertEquals(ReservationState.CONFIRMED, reservation.state);
            Mockito.verify(ledger).lock("CARD1");
        }
    }
}
