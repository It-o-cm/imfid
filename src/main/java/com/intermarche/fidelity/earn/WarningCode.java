package com.intermarche.fidelity.earn;

/**
 * The closed nomenclature of {@code /earn} and ingestion warnings (§27.1).
 * <p>
 * Warnings are never blocking; extending the nomenclature is a document revision
 * (§27.1). The projection ({@code /earn}) raises {@link #UNKNOWN_EAN}; the ingestion
 * additionally raises the drift and lifecycle codes (§26.1, §29.2, §34.2, §27.4).
 */
public enum WarningCode {

    /**
     * An EAN present in the {@code /valuation} response is unknown to the imfid
     * reference: the line stays out of every assiette (but inside the burnable base,
     * §25.4).
     */
    UNKNOWN_EAN,

    /**
     * The recomputed earn differs from the one displayed at the register; the recalc
     * prevails (§26.1). Ingestion only.
     */
    EARN_MISMATCH,

    /**
     * A burn reservation was confirmed although its lease had expired — the ingestion
     * is authoritative and creates the BURN anyway (§29.2). Ingestion only.
     */
    EXPIRED_LEASE_CONFIRMED,

    /**
     * A fiscal event targeted a resiliated account: the header is traced, no movement
     * is created (§34.2). Ingestion only.
     */
    RESILIATED_ACCOUNT,

    /**
     * The event {@code card} field disagrees with {@code valuationRequest.customerCode};
     * the latter is authoritative, the mismatch is a warning (§27.4). Ingestion only.
     */
    CARD_MISMATCH
}
