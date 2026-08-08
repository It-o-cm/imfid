package com.intermarche.fidelity.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link AdminException}: the business-rule refusal raised when an
 * administration mutation is rejected (§18, §28) and surfaced identically by GraphQL and
 * the admin UI (§21.3). The class is a single message-forwarding constructor with no
 * branches; this suite asserts the message round-trips (including the null and blank arms
 * of the {@code String} argument), that no cause is attached, and that the type is an
 * unchecked {@link RuntimeException} throwable and catchable as such. Pure logic, no
 * Panache and no clock: nothing here reads a {@code DateTimeProvider}.
 */
class AdminExceptionTest {

    /**
     * Confirms the operator-facing refusal message is forwarded verbatim to
     * {@link Throwable}.
     */
    @Test
    @DisplayName("constructor forwards the message verbatim")
    void constructorForwardsMessage() {
        AdminException ex = new AdminException("overlapping rule window");
        assertEquals("overlapping rule window", ex.getMessage());
    }

    /**
     * Covers the null arm of the {@code String} argument: a null message stays null.
     */
    @Test
    @DisplayName("constructor accepts a null message")
    void constructorAcceptsNullMessage() {
        AdminException ex = new AdminException(null);
        assertNull(ex.getMessage());
    }

    /**
     * Covers the non-null-but-blank arm of the {@code String} argument: an empty message
     * stays empty and is not coerced to null.
     */
    @Test
    @DisplayName("constructor preserves an empty message")
    void constructorPreservesEmptyMessage() {
        AdminException ex = new AdminException("");
        assertEquals("", ex.getMessage());
    }

    /**
     * Asserts no cause is attached by the message-only constructor.
     */
    @Test
    @DisplayName("constructor leaves the cause null")
    void constructorLeavesCauseNull() {
        AdminException ex = new AdminException("transfer blocked by active lease");
        assertNull(ex.getCause());
    }

    /**
     * Asserts the type is an unchecked exception, throwable and catchable as a
     * {@link RuntimeException}, with its message intact at the catch site (§21.3).
     */
    @Test
    @DisplayName("is an unchecked RuntimeException carrying its message")
    void isUncheckedRuntimeException() {
        AdminException thrown = assertThrows(
                AdminException.class,
                () -> {
                    throw new AdminException("enrollment cap reached");
                });
        assertTrue(thrown instanceof RuntimeException);
        assertEquals("enrollment cap reached", thrown.getMessage());
    }

    /**
     * Asserts the same instance is caught when narrowed through its {@link RuntimeException}
     * supertype, guaranteeing no wrapping occurs on the throw path.
     */
    @Test
    @DisplayName("propagates the same instance through the RuntimeException supertype")
    void propagatesSameInstance() {
        AdminException original = new AdminException("missing adjustment reason");
        RuntimeException caught = assertThrows(RuntimeException.class, () -> {
            throw original;
        });
        assertSame(original, caught);
    }
}
