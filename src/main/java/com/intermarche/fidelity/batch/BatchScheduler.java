package com.intermarche.fidelity.batch;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The cron driver of the account lifecycle batches (§16, §24.6): it fires the expiry on
 * 1 March, and the purge and activation-void daily. Every schedule reads its clock
 * through the {@link com.intermarche.fidelity.domain.util.ProgramClock}, never the server
 * clock (§24.6); the same batches are also triggerable in dry-run then execute from the
 * admin UI and GraphQL (§23.4, §32.3).
 */
@ApplicationScoped
public class BatchScheduler {

    private static final Logger LOGGER = Logger.getLogger(BatchScheduler.class);

    /**
     * The batch service running the executions.
     */
    @Inject
    BatchService batchService;

    /**
     * Fires the 1st-March expiry batch at 03:00 on 1 March (§16).
     */
    @Scheduled(cron = "0 0 3 1 3 ?")
    void scheduledExpiry() {
        BatchResult result = batchService.run(BatchType.EXPIRY, false);
        LOGGER.infof("Scheduled EXPIRY: %d accounts, %s € expired", result.accountsAffected, result.totalAmount);
    }

    /**
     * Fires the 24-month purge batch daily at 04:00 (§16).
     */
    @Scheduled(cron = "0 0 4 * * ?")
    void scheduledPurge() {
        BatchResult result = batchService.run(BatchType.PURGE, false);
        LOGGER.infof("Scheduled PURGE: %d accounts, %s € purged", result.accountsAffected, result.totalAmount);
    }

    /**
     * Fires the two-month activation-void batch daily at 05:00 (§16).
     */
    @Scheduled(cron = "0 0 5 * * ?")
    void scheduledActivationVoid() {
        BatchResult result = batchService.run(BatchType.ACTIVATION_VOID, false);
        LOGGER.infof("Scheduled ACTIVATION_VOID: %d accounts, %s € voided", result.accountsAffected, result.totalAmount);
    }
}
