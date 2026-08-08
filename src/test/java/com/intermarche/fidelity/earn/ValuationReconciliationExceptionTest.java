package com.intermarche.fidelity.earn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link ValuationReconciliationException}: the unchecked
 * failure raised when a {@code /valuation} response violates a reconciliation
 * invariant (§22.1) and mapped to a 422 on {@code POST /earn} (§25.2). The class is a
 * single message-forwarding constructor with no branches; this suite asserts the
 * message round-trips (including the null and blank arms of the {@code String}
 * argument), that no cause is attached, and that the type is an unchecked
 * {@link RuntimeException} throwable as such. Pure logic, no Panache and no clock:
 * nothing here reads a {@code DateTimeProvider}.
 */
class ValuationReconciliationExceptionTest {

    /**
     * Confirms the descriptive message is forwarded verbatim to {@link Throwable}.
     */
    @Test
    @DisplayName("constructor forwards the message verbatim")
    void constructorForwardsMessage() {
        ValuationReconciliationException ex =
                new ValuationReconciliationException("sum of items != offer amount");
        assertEquals("sum of items != offer amount", ex.getMessage());
    }

    /**
     * Covers the null arm of the {@code String} argument: a null message stays null.
     */
    @Test
    @DisplayName("constructor accepts a null message")
    void constructorAcceptsNullMessage() {
        ValuationReconciliationException ex = new ValuationReconciliationException(null);
        assertNull(ex.getMessage());
    }

    /**
     * Covers the non-null-but-blank arm of the {@code String} argument: an empty
     * message stays empty and is not coerced to null.
     */
    @Test
    @DisplayName("constructor preserves an empty message")
    void constructorPreservesEmptyMessage() {
        ValuationReconciliationException ex = new ValuationReconciliationException("");
        assertEquals("", ex.getMessage());
    }

    /**
     * Asserts no cause is attached by the message-only constructor.
     */
    @Test
    @DisplayName("constructor leaves the cause null")
    void constructorLeavesCauseNull() {
        ValuationReconciliationException ex =
                new ValuationReconciliationException("incoherent totals");
        assertNull(ex.getCause());
    }

    /**
     * Asserts the type is an unchecked exception, throwable and catchable as a
     * {@link RuntimeException}, with its message intact at the catch site (§25.2).
     */
    @Test
    @DisplayName("is an unchecked RuntimeException carrying its message")
    void isUncheckedRuntimeException() {
        ValuationReconciliationException thrown = assertThrows(
                ValuationReconciliationException.class,
                () -> {
                    throw new ValuationReconciliationException("422 reconciliation");
                });
        assertTrue(thrown instanceof RuntimeException);
        assertEquals("422 reconciliation", thrown.getMessage());
    }

    /**
     * Asserts the same instance is caught when narrowed through its {@link RuntimeException}
     * supertype, guaranteeing no wrapping occurs on the throw path.
     */
    @Test
    @DisplayName("propagates the same instance through the RuntimeException supertype")
    void propagatesSameInstance() {
        ValuationReconciliationException original =
                new ValuationReconciliationException("Σ items != amount");
        RuntimeException caught = assertThrows(RuntimeException.class, () -> {
            throw original;
        });
        assertSame(original, caught);
    }
}
