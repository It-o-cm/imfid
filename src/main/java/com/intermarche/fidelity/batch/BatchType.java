package com.intermarche.fidelity.batch;

/**
 * The account lifecycle batches (§16): the 1st-March expiry, the 24-month purge and the
 * two-month activation void. Each runs on a cron and is triggerable in dry-run then
 * execute from the admin UI and GraphQL (§23.4, §32.3).
 */
public enum BatchType {

    /**
     * 1st-March expiry of the residual balance acquired during civil year N-1, consumed
     * FIFO by earnYear, bounded by the available balance (§16, §28.3).
     */
    EXPIRY,

    /**
     * 24-month purge of the balance of accounts unused since {@code lastUsedAt}, which
     * then move to RESILIATED (§16).
     */
    PURGE,

    /**
     * Two-month cancellation of the advantages of a PENDING_ACTIVATION account not
     * activated in time, which then moves to RESILIATED (§16).
     */
    ACTIVATION_VOID
}
