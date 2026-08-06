package com.intermarche.fidelity.domain;

/**
 * The closed nomenclature of loyalty ledger movements (§14, §32.1).
 * <p>
 * The account balance is the signed sum of its movements; expiry consumes them
 * FIFO by {@link FidelityMovement#earnYear}. This nomenclature is engraved:
 * plugging a new earn mechanic never adds a movement type (§19, I10).
 */
public enum MovementType {

    /**
     * Loyalty credited by a rule at the ticket fiscal moment (§16, I8).
     * Carries a {@code ruleCode} and a {@code ticketRef}; capped by rule,
     * community and global monthly caps.
     */
    EARN,

    /**
     * Cash-back style credit issued when a returned line is refunded to the card
     * (§28.6). Not an advantage: out of every cap (§29.5); its {@code ruleCode}
     * is always null (§29.4).
     */
    REFUND_CREDIT,

    /**
     * Administration gesture on the card sheet (§32.1): signed amount, mandatory
     * reason, out of every cap, expirable like earn (earnYear = civil year of the
     * gesture). Used for seeded initial balances, after-sales corrections and
     * acceptance fixtures.
     */
    ADJUSTMENT,

    /**
     * Loyalty spent: the debit created when a burn reservation is confirmed at the
     * ticket fiscal moment (§16, I11). Never a direct debit.
     */
    BURN,

    /**
     * Debit reversing the earn of a returned line, computed (even partially) from
     * the earn trace (§16, §29.4). Passes even with an insufficient balance:
     * negative balance is tolerated (I7). Decomposed by rule like EARN.
     */
    RETURN_DEBIT,

    /**
     * Expiry of the residual balance acquired during civil year N-1, posted by the
     * 1st-March batch, consuming FIFO by earnYear (§16).
     */
    EXPIRY,

    /**
     * Purge of the balance of an account unused for 24 months (lastUsedAt), which
     * then moves to RESILIATED (§16).
     */
    PURGE,

    /**
     * Cancellation of the advantages accrued by a PENDING_ACTIVATION account not
     * activated two months after its first use (CGU, §16); the account then moves
     * to RESILIATED.
     */
    ACTIVATION_VOID,

    /**
     * Loss/theft transfer: an outgoing movement on the deactivated account and an
     * incoming movement on the new one, preserving the earnYears so the balance
     * does not "rejuvenate" (§16, §34.3).
     */
    TRANSFER
}
