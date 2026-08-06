package com.intermarche.fidelity.earn;

/**
 * Raised when a {@code /valuation} response violates a reconciliation invariant of
 * §22.1 — Σ items ≠ amount of an offer, or incoherent totals (§25.2). Maps to a
 * 422 on {@code POST /earn} (§25.2); the detail is logged, never persisted at the
 * projection (§30.2).
 */
public class ValuationReconciliationException extends RuntimeException {

    /**
     * Builds the exception with a descriptive message.
     *
     * @param message The reconciliation failure detail.
     */
    public ValuationReconciliationException(String message) {
        super(message);
    }
}
