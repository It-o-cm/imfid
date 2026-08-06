package com.intermarche.fidelity.admin;

/**
 * A business-rule refusal of an administration mutation (§18, §28) — an overlapping rule
 * window, an invalid specification, a transfer or resiliation blocked by an active lease
 * (§28.1), an enrollment cap reached (§28.4), a missing adjustment reason (§32.1).
 * <p>
 * Surfaced by GraphQL as a query error and by the admin UI as a failure notice (§21.3),
 * so the two administration channels reject identically.
 */
public class AdminException extends RuntimeException {

    /**
     * Builds the exception with an operator-facing message.
     *
     * @param message The refusal reason.
     */
    public AdminException(String message) {
        super(message);
    }
}
