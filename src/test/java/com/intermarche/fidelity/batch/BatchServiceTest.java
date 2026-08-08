package com.intermarche.fidelity.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.account.LedgerService;
import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.BatchRunLog;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.util.ProgramClock;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link BatchService}, the account-lifecycle batches (§16, §25.1,
 * §28.3): the 1st-March expiry, the 24-month purge and the two-month activation void, each
 * in dry-run (simulate) or execution (§23.4, §32.3). Every guard is exercised on both arms:
 * the {@code dryRun ? candidate : lock} candidate/lock ternary of the three batches; the
 * {@code toExpire.signum() <= 0} skip and the {@code residual.min(available)} bound of the
 * expiry; the {@code balance.signum() > 0} post guard and the {@code balance.max(ZERO)}
 * positive/negative clamp of the purge and the activation void; the {@code entry.getKey() <=
 * expireYear} old-year selector and the {@code debits.min(credit)} FIFO consumption of
 * {@code residualUpToYear} (both directions); and the {@code !dryRun} record guard of
 * {@code run} on both arms (§23.4).
 * <p>
 * Fully isolated. The injected {@link ProgramClock} is mocked and its instant is fixed at
 * the program zone (§24.6): the 1st-March run date is frozen at 2026-03-01, so the expiry
 * year is 2025 and the {@code earnYear} border is probed by an included year 2025 and an
 * excluded year 2026 straddling it, never the day the campaign runs (§30.3, §25.1). The
 * accounts are Mockito mocks so their {@code persist()} is a no-op under a plain
 * {@code mvn test}; the {@link BatchRunLog} the service constructs on execution is
 * intercepted with {@code mockConstruction} to neutralise its {@code persist()}. The
 * inherited {@code list} finder is stubbed on {@link PanacheEntityBase} (its declaring
 * class), the entity-specific finders ({@code FidelityAccount.listUnusedSince},
 * {@code FidelityMovement.creditsByEarnYear}, {@code totalDebits}) on their own classes,
 * all with {@code mockStatic} in try-with-resources. Every {@link BigDecimal} is asserted
 * by {@code compareTo} at scale 2 (§30.5).
 */
class BatchServiceTest {

    /**
     * The frozen program instant the mocked clock returns for the run (§24.6).
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 1, 9, 0, 0);

    /**
     * The frozen fiscal run date at the program zone (§30.3).
     */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);

    /**
     * The 24-month purge cut-off derived from {@link #NOW} (§16).
     */
    private static final LocalDateTime PURGE_THRESHOLD = NOW.minusMonths(24);

    /**
     * The two-month activation-void cut-off derived from {@link #NOW} (§16).
     */
    private static final LocalDateTime ACTIVATION_THRESHOLD = NOW.minusMonths(2);

    /**
     * The system under test, freshly built with mocked collaborators per test.
     */
    private BatchService service;

    /**
     * The mocked ledger holding the lock, the available balance and the movement posts.
     */
    private LedgerService ledger;

    /**
     * The mocked program clock fixing the run instant at the program zone (§24.6).
     */
    private ProgramClock clock;

    /**
     * Builds a fresh system under test and wires the mocked collaborators before each test.
     */
    @BeforeEach
    void setUp() {
        service = new BatchService();
        ledger = Mockito.mock(LedgerService.class);
        clock = Mockito.mock(ProgramClock.class);
        service.ledger = ledger;
        service.clock = clock;
        Mockito.lenient().when(clock.now()).thenReturn(NOW);
        Mockito.lenient().when(clock.today()).thenReturn(TODAY);
    }

    /**
     * Builds a mocked account carrying its card number and denormalized balance so its
     * {@code persist()} is a no-op under a plain {@code mvn test} (§30.1).
     *
     * @param cardNumber The card number.
     * @param balance    The denormalized balance, euro at scale 2 (I7 tolerates negative).
     * @return The mocked account, never null.
     */
    private FidelityAccount account(String cardNumber, BigDecimal balance) {
        FidelityAccount account = Mockito.mock(FidelityAccount.class);
        account.cardNumber = cardNumber;
        account.balance = balance;
        return account;
    }

    /**
     * Builds an ascending {@code earnYear -> credit} map so the FIFO walk is deterministic.
     *
     * @param y1 The oldest earn year.
     * @param c1 The oldest year's credit.
     * @param y2 The middle earn year.
     * @param c2 The middle year's credit.
     * @param y3 The newest earn year.
     * @param c3 The newest year's credit.
     * @return The credits by earn year, oldest first, at scale 2.
     */
    private Map<Integer, BigDecimal> credits(int y1, String c1, int y2, String c2, int y3, String c3) {
        Map<Integer, BigDecimal> map = new LinkedHashMap<>();
        map.put(y1, new BigDecimal(c1));
        map.put(y2, new BigDecimal(c2));
        map.put(y3, new BigDecimal(c3));
        return map;
    }

    /**
     * A BigDecimal argument matcher comparing by {@code compareTo} at value equality,
     * null-safe, so a posted amount is verified irrespective of trailing-zero scale (§30.5).
     *
     * @param expected The expected value.
     * @return A matcher placeholder (null), never used directly.
     */
    private static BigDecimal amount(String expected) {
        return Mockito.argThat(a -> a != null && a.compareTo(new BigDecimal(expected)) == 0);
    }

    /**
     * The 1st-March expiry in dry-run computes the residual of old years bounded by the
     * available balance without any write — the {@code dryRun ? candidate} arm, the
     * {@code residual.min(available)} residual-taken direction, the {@code signum() <= 0}
     * false arm, the {@code !dryRun} false record arm, the {@code getKey() <= expireYear}
     * both arms (2024/2025 included, 2026 excluded) and the {@code residualOld < 0} false
     * arm (§16, §28.3).
     */
    @Test
    @DisplayName("expiry dry-run: sums the old-year residual, bounded by the available balance, no write")
    void expiryDryRunComputesResidualWithoutWriting() {
        FidelityAccount candidate = account("CARD-1", new BigDecimal("0.00"));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class)) {
            panache.when(() -> PanacheEntityBase.<FidelityAccount>list(
                    "status <> ?1 and balance > 0", AccountStatus.RESILIATED)).thenReturn(List.of(candidate));
            movements.when(() -> FidelityMovement.creditsByEarnYear(candidate))
                    .thenReturn(credits(2024, "10.00", 2025, "5.00", 2026, "7.00"));
            movements.when(() -> FidelityMovement.totalDebits(candidate)).thenReturn(new BigDecimal("0.00"));
            Mockito.when(ledger.availableBalance(candidate)).thenReturn(new BigDecimal("20.00"));
            BatchResult result = service.run(BatchType.EXPIRY, true);
            assertEquals("EXPIRY", result.batch);
            assertTrue(result.dryRun);
            assertEquals(1, result.accountsAffected);
            assertEquals(0, result.totalAmount.compareTo(new BigDecimal("15.00")));
            assertEquals("CARD-1", result.lines.get(0).cardNumber);
            assertEquals(0, result.lines.get(0).amount.compareTo(new BigDecimal("15.00")));
            Mockito.verify(ledger, Mockito.never()).lock(Mockito.any());
            Mockito.verify(ledger, Mockito.never()).post(Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        }
    }

    /**
     * The 1st-March expiry in execution locks the card, consumes debits FIFO from the
     * oldest year, bounds the residual by the available balance and posts a negative EXPIRY
     * movement, then records the run — the {@code dryRun ? ... : lock} arm, both directions
     * of {@code debits.min(credit)} (full then partial consumption), the
     * {@code residual.min(available)} available-taken direction, the {@code !dryRun} post
     * and record arms (§16, §28.3, §23.4).
     */
    @Test
    @DisplayName("expiry execution: locks, consumes debits FIFO, posts a bounded EXPIRY and records the run")
    void expiryExecutionPostsBoundedExpiry() {
        FidelityAccount candidate = account("CARD-1", new BigDecimal("0.00"));
        FidelityAccount locked = account("CARD-1", new BigDecimal("0.00"));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedConstruction<BatchRunLog> logs = Mockito.mockConstruction(BatchRunLog.class)) {
            panache.when(() -> PanacheEntityBase.<FidelityAccount>list(
                    "status <> ?1 and balance > 0", AccountStatus.RESILIATED)).thenReturn(List.of(candidate));
            Mockito.when(ledger.lock("CARD-1")).thenReturn(locked);
            movements.when(() -> FidelityMovement.creditsByEarnYear(locked))
                    .thenReturn(credits(2024, "10.00", 2025, "5.00", 2026, "7.00"));
            movements.when(() -> FidelityMovement.totalDebits(locked)).thenReturn(new BigDecimal("12.00"));
            Mockito.when(ledger.availableBalance(locked)).thenReturn(new BigDecimal("1.00"));
            BatchResult result = service.run(BatchType.EXPIRY, false);
            assertEquals(1, result.accountsAffected);
            assertEquals(0, result.totalAmount.compareTo(new BigDecimal("1.00")));
            Mockito.verify(ledger).post(Mockito.eq(locked), Mockito.eq(MovementType.EXPIRY),
                    amount("-1.00"), Mockito.eq(TODAY), Mockito.isNull(), Mockito.isNull(),
                    Mockito.eq(List.of()), Mockito.eq("1st-March expiry of earnYear <= 2025"));
            BatchRunLog log = logs.constructed().get(0);
            assertEquals("EXPIRY", log.batchType);
            assertEquals(NOW, log.runAt);
            assertEquals(1, log.accountsAffected);
            assertEquals(0, log.totalAmount.compareTo(new BigDecimal("1.00")));
            Mockito.verify(log).persist();
        }
    }

    /**
     * The 1st-March expiry skips an account whose available balance leaves nothing to
     * expire — the {@code toExpire.signum() <= 0} true continue arm, leaving the result
     * empty (§16, §28.3).
     */
    @Test
    @DisplayName("expiry: an exhausted available balance yields nothing to expire and is skipped")
    void expirySkipsWhenNothingToExpire() {
        FidelityAccount candidate = account("CARD-1", new BigDecimal("0.00"));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class)) {
            panache.when(() -> PanacheEntityBase.<FidelityAccount>list(
                    "status <> ?1 and balance > 0", AccountStatus.RESILIATED)).thenReturn(List.of(candidate));
            movements.when(() -> FidelityMovement.creditsByEarnYear(candidate))
                    .thenReturn(credits(2024, "10.00", 2025, "5.00", 2026, "7.00"));
            movements.when(() -> FidelityMovement.totalDebits(candidate)).thenReturn(new BigDecimal("0.00"));
            Mockito.when(ledger.availableBalance(candidate)).thenReturn(new BigDecimal("0.00"));
            BatchResult result = service.run(BatchType.EXPIRY, true);
            assertEquals(0, result.accountsAffected);
            assertTrue(result.lines.isEmpty());
            assertEquals(0, result.totalAmount.compareTo(new BigDecimal("0.00")));
        }
    }

    /**
     * The 24-month purge in dry-run reports the reclaimable balance without any write — the
     * {@code dryRun ? candidate} arm, the {@code !dryRun} false arm skipping the post and
     * resiliation, and the {@code balance.max(ZERO)} positive arm (§16).
     */
    @Test
    @DisplayName("purge dry-run: reports the reclaimable balance, no lock, no post, no resiliation")
    void purgeDryRunComputes() {
        FidelityAccount candidate = account("CARD-9", new BigDecimal("20.00"));
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.listUnusedSince(PURGE_THRESHOLD))
                    .thenReturn(List.of(candidate));
            BatchResult result = service.run(BatchType.PURGE, true);
            assertEquals("PURGE", result.batch);
            assertTrue(result.dryRun);
            assertEquals(1, result.accountsAffected);
            assertEquals(0, result.totalAmount.compareTo(new BigDecimal("20.00")));
            assertEquals("CARD-9", result.lines.get(0).cardNumber);
            Mockito.verify(ledger, Mockito.never()).lock(Mockito.any());
            Mockito.verify(ledger, Mockito.never()).post(Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(candidate, Mockito.never()).persist();
        }
    }

    /**
     * The 24-month purge in execution locks the card, posts a negative PURGE for the
     * positive balance, resiliates the account and records the run — the {@code ... : lock}
     * arm, the {@code !dryRun} arm, the {@code balance.signum() > 0} true post arm and the
     * {@code balance.max(ZERO)} positive arm (§16, §23.4).
     */
    @Test
    @DisplayName("purge execution: locks, posts the balance as PURGE, resiliates and records the run")
    void purgeExecutionPositiveBalancePostsAndResiliates() {
        FidelityAccount candidate = account("CARD-9", new BigDecimal("0.00"));
        FidelityAccount locked = account("CARD-9", new BigDecimal("20.00"));
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class);
             MockedConstruction<BatchRunLog> logs = Mockito.mockConstruction(BatchRunLog.class)) {
            accounts.when(() -> FidelityAccount.listUnusedSince(PURGE_THRESHOLD))
                    .thenReturn(List.of(candidate));
            Mockito.when(ledger.lock("CARD-9")).thenReturn(locked);
            BatchResult result = service.run(BatchType.PURGE, false);
            assertEquals(1, result.accountsAffected);
            assertEquals(0, result.totalAmount.compareTo(new BigDecimal("20.00")));
            Mockito.verify(ledger).post(Mockito.eq(locked), Mockito.eq(MovementType.PURGE),
                    amount("-20.00"), Mockito.eq(TODAY), Mockito.isNull(), Mockito.isNull(),
                    Mockito.eq(List.of()), Mockito.eq("24-month inactivity purge"));
            assertEquals(AccountStatus.RESILIATED, locked.status);
            Mockito.verify(locked).persist();
            BatchRunLog log = logs.constructed().get(0);
            assertEquals("PURGE", log.batchType);
            assertEquals(NOW, log.runAt);
            assertEquals(1, log.accountsAffected);
            assertEquals(0, log.totalAmount.compareTo(new BigDecimal("20.00")));
            Mockito.verify(log).persist();
        }
    }

    /**
     * The 24-month purge resiliates a negative-balance account without a post — the
     * {@code balance.signum() > 0} false arm skipping the movement and the
     * {@code balance.max(ZERO)} negative arm clamping the reported amount to zero (§16, I7).
     */
    @Test
    @DisplayName("purge execution: a negative balance is resiliated without a post, reported as zero")
    void purgeExecutionNonPositiveSkipsPost() {
        FidelityAccount candidate = account("CARD-9", new BigDecimal("0.00"));
        FidelityAccount locked = account("CARD-9", new BigDecimal("-5.00"));
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class);
             MockedConstruction<BatchRunLog> logs = Mockito.mockConstruction(BatchRunLog.class)) {
            accounts.when(() -> FidelityAccount.listUnusedSince(PURGE_THRESHOLD))
                    .thenReturn(List.of(candidate));
            Mockito.when(ledger.lock("CARD-9")).thenReturn(locked);
            BatchResult result = service.run(BatchType.PURGE, false);
            assertEquals(1, result.accountsAffected);
            assertEquals(0, result.totalAmount.compareTo(new BigDecimal("0.00")));
            assertEquals(0, result.lines.get(0).amount.compareTo(new BigDecimal("0.00")));
            Mockito.verify(ledger, Mockito.never()).post(Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
            assertEquals(AccountStatus.RESILIATED, locked.status);
            Mockito.verify(locked).persist();
            assertEquals(1, logs.constructed().size());
        }
    }

    /**
     * The two-month activation void in dry-run reports the cancellable advantage without any
     * write — the third {@code switch} arm, the {@code dryRun ? candidate} arm, the
     * {@code !dryRun} false arm and the {@code balance.max(ZERO)} positive arm (§16).
     */
    @Test
    @DisplayName("activation void dry-run: reports the cancellable advantage, no write")
    void activationVoidDryRunComputes() {
        FidelityAccount candidate = account("CARD-7", new BigDecimal("12.00"));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.<FidelityAccount>list("status = ?1 and createdAt < ?2",
                    AccountStatus.PENDING_ACTIVATION, ACTIVATION_THRESHOLD)).thenReturn(List.of(candidate));
            BatchResult result = service.run(BatchType.ACTIVATION_VOID, true);
            assertEquals("ACTIVATION_VOID", result.batch);
            assertTrue(result.dryRun);
            assertEquals(1, result.accountsAffected);
            assertEquals(0, result.totalAmount.compareTo(new BigDecimal("12.00")));
            assertEquals("CARD-7", result.lines.get(0).cardNumber);
            Mockito.verify(ledger, Mockito.never()).lock(Mockito.any());
            Mockito.verify(candidate, Mockito.never()).persist();
        }
    }

    /**
     * The two-month activation void in execution locks the card, posts a negative
     * ACTIVATION_VOID for the positive balance, resiliates the account and records the run —
     * the {@code ... : lock} arm, the {@code balance.signum() > 0} true post arm and the
     * {@code balance.max(ZERO)} positive arm (§16, §23.4).
     */
    @Test
    @DisplayName("activation void execution: locks, posts the advantage, resiliates and records the run")
    void activationVoidExecutionPositive() {
        FidelityAccount candidate = account("CARD-7", new BigDecimal("0.00"));
        FidelityAccount locked = account("CARD-7", new BigDecimal("12.00"));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<BatchRunLog> logs = Mockito.mockConstruction(BatchRunLog.class)) {
            panache.when(() -> PanacheEntityBase.<FidelityAccount>list("status = ?1 and createdAt < ?2",
                    AccountStatus.PENDING_ACTIVATION, ACTIVATION_THRESHOLD)).thenReturn(List.of(candidate));
            Mockito.when(ledger.lock("CARD-7")).thenReturn(locked);
            BatchResult result = service.run(BatchType.ACTIVATION_VOID, false);
            assertEquals(1, result.accountsAffected);
            assertEquals(0, result.totalAmount.compareTo(new BigDecimal("12.00")));
            Mockito.verify(ledger).post(Mockito.eq(locked), Mockito.eq(MovementType.ACTIVATION_VOID),
                    amount("-12.00"), Mockito.eq(TODAY), Mockito.isNull(), Mockito.isNull(),
                    Mockito.eq(List.of()), Mockito.eq("Two-month activation void"));
            assertEquals(AccountStatus.RESILIATED, locked.status);
            Mockito.verify(locked).persist();
            BatchRunLog log = logs.constructed().get(0);
            assertEquals("ACTIVATION_VOID", log.batchType);
            assertEquals(1, log.accountsAffected);
            assertEquals(0, log.totalAmount.compareTo(new BigDecimal("12.00")));
            Mockito.verify(log).persist();
        }
    }

    /**
     * The two-month activation void resiliates a zero-balance account without a post — the
     * {@code balance.signum() > 0} false arm skipping the movement and the
     * {@code balance.max(ZERO)} zero arm (§16).
     */
    @Test
    @DisplayName("activation void execution: a zero balance is resiliated without a post")
    void activationVoidExecutionNonPositive() {
        FidelityAccount candidate = account("CARD-7", new BigDecimal("0.00"));
        FidelityAccount locked = account("CARD-7", new BigDecimal("0.00"));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<BatchRunLog> logs = Mockito.mockConstruction(BatchRunLog.class)) {
            panache.when(() -> PanacheEntityBase.<FidelityAccount>list("status = ?1 and createdAt < ?2",
                    AccountStatus.PENDING_ACTIVATION, ACTIVATION_THRESHOLD)).thenReturn(List.of(candidate));
            Mockito.when(ledger.lock("CARD-7")).thenReturn(locked);
            BatchResult result = service.run(BatchType.ACTIVATION_VOID, false);
            assertEquals(1, result.accountsAffected);
            assertEquals(0, result.totalAmount.compareTo(new BigDecimal("0.00")));
            Mockito.verify(ledger, Mockito.never()).post(Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
            assertEquals(AccountStatus.RESILIATED, locked.status);
            Mockito.verify(locked).persist();
            assertEquals(1, logs.constructed().size());
        }
    }
}
