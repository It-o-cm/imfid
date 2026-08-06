package com.intermarche.fidelity.batch;

import com.intermarche.fidelity.account.LedgerService;
import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.BatchRunLog;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.util.ProgramClock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The account lifecycle batches (§16, §25.1, §28.3): the 1st-March expiry, the 24-month
 * purge and the two-month activation void. Each runs in dry-run (simulate) or execution
 * (§23.4, §32.3); every account write takes the per-card lock (§30.1) and consumes the
 * available balance — net of the active reservations, never below zero — so a batch
 * never touches euros a register is encashing (§28.3).
 * <p>
 * A dry-run computes the same figures without any write; an execution posts the movements
 * and records a {@link BatchRunLog} for the supervision screen (§23.4).
 */
@ApplicationScoped
public class BatchService {

    /**
     * The number of months of inactivity after which an account is purged (§16).
     */
    private static final int PURGE_INACTIVITY_MONTHS = 24;

    /**
     * The number of months after first use within which a card must be activated (§16).
     */
    private static final int ACTIVATION_DEADLINE_MONTHS = 2;

    /**
     * The ledger writer holding the lock, the available balance and the movement posts.
     */
    @Inject
    LedgerService ledger;

    /**
     * The program clock resolving the run date at the program zone (§25.1, §30.3).
     */
    @Inject
    ProgramClock clock;

    /**
     * Runs a batch, dry-run or execution, recording the run on execution (§23.4, §32.3).
     *
     * @param type   The batch type.
     * @param dryRun Whether to simulate without writing.
     * @return The batch result, never null.
     */
    @Transactional
    public BatchResult run(BatchType type, boolean dryRun) {
        BatchResult result = switch (type) {
            case EXPIRY -> expiry(dryRun);
            case PURGE -> purge(dryRun);
            case ACTIVATION_VOID -> activationVoid(dryRun);
        };
        if (!dryRun) {
            recordRun(type, result);
        }
        return result;
    }

    /**
     * Expires the residual balance acquired during civil year N-1, consumed FIFO by
     * earnYear, bounded by the available balance (§16, §28.3).
     *
     * @param dryRun Whether to simulate.
     * @return The expiry result.
     */
    private BatchResult expiry(boolean dryRun) {
        BatchResult result = new BatchResult(BatchType.EXPIRY.name(), dryRun);
        LocalDate today = clock.today();
        int expireYear = today.getYear() - 1;
        for (FidelityAccount candidate : FidelityAccount.<FidelityAccount>list(
                "status <> ?1 and balance > 0", AccountStatus.RESILIATED)) {
            FidelityAccount account = dryRun ? candidate : ledger.lock(candidate.cardNumber);
            BigDecimal residual = residualUpToYear(account, expireYear);
            BigDecimal available = ledger.availableBalance(account);
            BigDecimal toExpire = residual.min(available);
            if (toExpire.signum() <= 0) {
                continue;
            }
            if (!dryRun) {
                ledger.post(account, MovementType.EXPIRY, toExpire.negate(), today, null, null,
                        List.of(), "1st-March expiry of earnYear <= " + expireYear);
            }
            result.add(account.cardNumber, toExpire);
        }
        return result;
    }

    /**
     * Purges the balance of accounts unused for 24 months and resiliates them (§16).
     *
     * @param dryRun Whether to simulate.
     * @return The purge result.
     */
    private BatchResult purge(boolean dryRun) {
        BatchResult result = new BatchResult(BatchType.PURGE.name(), dryRun);
        LocalDateTime threshold = clock.now().minusMonths(PURGE_INACTIVITY_MONTHS);
        for (FidelityAccount candidate : FidelityAccount.listUnusedSince(threshold)) {
            FidelityAccount account = dryRun ? candidate : ledger.lock(candidate.cardNumber);
            BigDecimal balance = account.balance;
            if (!dryRun) {
                if (balance.signum() > 0) {
                    ledger.post(account, MovementType.PURGE, balance.negate(), clock.today(), null, null,
                            List.of(), "24-month inactivity purge");
                }
                account.status = AccountStatus.RESILIATED;
                account.persist();
            }
            result.add(account.cardNumber, balance.max(BigDecimal.ZERO));
        }
        return result;
    }

    /**
     * Cancels the advantages of PENDING_ACTIVATION accounts not activated two months
     * after first use, and resiliates them (§16).
     *
     * @param dryRun Whether to simulate.
     * @return The activation-void result.
     */
    private BatchResult activationVoid(boolean dryRun) {
        BatchResult result = new BatchResult(BatchType.ACTIVATION_VOID.name(), dryRun);
        LocalDateTime threshold = clock.now().minusMonths(ACTIVATION_DEADLINE_MONTHS);
        for (FidelityAccount candidate : FidelityAccount.<FidelityAccount>list(
                "status = ?1 and createdAt < ?2", AccountStatus.PENDING_ACTIVATION, threshold)) {
            FidelityAccount account = dryRun ? candidate : ledger.lock(candidate.cardNumber);
            BigDecimal balance = account.balance;
            if (!dryRun) {
                if (balance.signum() > 0) {
                    ledger.post(account, MovementType.ACTIVATION_VOID, balance.negate(), clock.today(),
                            null, null, List.of(), "Two-month activation void");
                }
                account.status = AccountStatus.RESILIATED;
                account.persist();
            }
            result.add(account.cardNumber, balance.max(BigDecimal.ZERO));
        }
        return result;
    }

    /**
     * Computes the residual balance of an account attributable to earnYears at or before
     * a year, after a FIFO consumption of the debits from the oldest year first (§16,
     * §28.3).
     *
     * @param account    The account.
     * @param expireYear The last earnYear to include in the residual.
     * @return The residual balance of the old years, euro at scale 2, never negative.
     */
    private BigDecimal residualUpToYear(FidelityAccount account, int expireYear) {
        Map<Integer, BigDecimal> credits = FidelityMovement.creditsByEarnYear(account);
        BigDecimal debits = FidelityMovement.totalDebits(account);
        BigDecimal residualOld = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> entry : credits.entrySet()) {
            BigDecimal credit = entry.getValue();
            BigDecimal consumed = debits.min(credit);
            BigDecimal residual = credit.subtract(consumed);
            debits = debits.subtract(consumed);
            if (entry.getKey() <= expireYear) {
                residualOld = residualOld.add(residual);
            }
        }
        return residualOld.signum() < 0 ? BigDecimal.ZERO : residualOld;
    }

    /**
     * Records an executed batch run for the supervision screen (§23.4).
     *
     * @param type   The batch type.
     * @param result The run result.
     */
    private void recordRun(BatchType type, BatchResult result) {
        BatchRunLog log = new BatchRunLog();
        log.batchType = type.name();
        log.runAt = clock.now();
        log.accountsAffected = result.accountsAffected;
        log.totalAmount = result.totalAmount;
        log.persist();
    }
}
