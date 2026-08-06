package com.intermarche.fidelity.domain;

/**
 * Lifecycle status of a {@link FidelityAccount} (§14, §25.3, §30.4).
 */
public enum AccountStatus {

    /**
     * Fully operative account: it both earns and burns.
     * Default status offered by the card creation screen (§30.4).
     */
    ACTIVE,

    /**
     * Accrues loyalty from first use but cannot burn until activated within two
     * months, per the CGU (§25.3): a reservation is refused with a reason. The
     * activation-void batch handles accounts still pending at two months (§16).
     */
    PENDING_ACTIVATION,

    /**
     * Terminated account: neither earns nor burns; its balance never moves again
     * (§34.2). The sheet stays consultable in administration for history (§25.3).
     */
    RESILIATED
}
