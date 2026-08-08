package com.intermarche.fidelity.domain.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link DateTimeProvider}: the single mockable clock source (§24.6)
 * that every clock-driven decision in imfid reads instead of the system clock — civil-day
 * visits (I3), rule validity windows, reservation leases (I11) and the fiscal-day resolution
 * at the program zone (§30.3, §25.1). Both arms of the {@code now()} ternary are exercised
 * (fixed instant vs. system fallback), both arms of the {@code setFixedDateTime} null guard,
 * the {@code clear()} reset and the utility-class constructor throw (§29). No Panache and no
 * real wall-clock decision: the two arms are pinned on injected fixed instants, and the sole
 * system-clock read is the fallback under test itself, asserted only as non-null. The static
 * fixed time is reset before and after each test so the cases stay isolated and order-free.
 */
class DateTimeProviderTest {

    /**
     * A fixed instant on the eve of the fiscal boundary (§30.3): the 31st of December just
     * before midnight, used to pin the non-null arm of {@code now()}.
     */
    private static final LocalDateTime FISCAL_EVE = LocalDateTime.of(2025, 12, 31, 23, 59, 59);

    /**
     * The instant straddling the same boundary on the far side: the 1st of January at the
     * first second, proving the provider echoes whichever instant is fixed, never the wall clock.
     */
    private static final LocalDateTime FISCAL_MORROW = LocalDateTime.of(2026, 1, 1, 0, 0, 1);

    /**
     * Clears any fixed instant before each test so no case inherits another's static state.
     */
    @BeforeEach
    void resetBefore() {
        DateTimeProvider.clear();
    }

    /**
     * Clears the fixed instant after each test, leaving the shared static provider on its
     * system-time default for any later suite.
     */
    @AfterEach
    void resetAfter() {
        DateTimeProvider.clear();
    }

    /**
     * With a fixed instant set, {@code now()} takes the {@code fixedTime != null} true arm and
     * returns that very instant — the same reference, unchanged.
     */
    @Test
    @DisplayName("now(): returns the fixed instant when one is set")
    void nowReturnsFixedInstantWhenSet() {
        DateTimeProvider.setFixedDateTime(FISCAL_EVE);
        assertSame(FISCAL_EVE, DateTimeProvider.now());
    }

    /**
     * Fixing the instant on the far side of the fiscal border proves the provider echoes the
     * injected value rather than the day the campaign runs: both straddling instants are
     * returned verbatim across two fixings.
     */
    @Test
    @DisplayName("now(): echoes whichever instant straddling the fiscal border is fixed")
    void nowEchoesBothStraddlingInstants() {
        DateTimeProvider.setFixedDateTime(FISCAL_EVE);
        assertEquals(FISCAL_EVE, DateTimeProvider.now());
        DateTimeProvider.setFixedDateTime(FISCAL_MORROW);
        assertEquals(FISCAL_MORROW, DateTimeProvider.now());
    }

    /**
     * With no fixed instant, {@code now()} takes the {@code fixedTime != null} false arm and
     * falls back to the system clock: the sole real-clock read in the suite, asserted only as
     * a present value since this fallback is the behaviour under test.
     */
    @Test
    @DisplayName("now(): falls back to the system clock when no instant is fixed")
    void nowFallsBackToSystemClockWhenUnset() {
        LocalDateTime before = LocalDateTime.now();
        LocalDateTime current = DateTimeProvider.now();
        LocalDateTime after = LocalDateTime.now();
        assertNotNull(current);
        assertFalse(current.isBefore(before));
        assertFalse(current.isAfter(after));
    }

    /**
     * {@code clear()} drops a previously fixed instant, so a subsequent {@code now()} reverts to
     * the system clock: the fixed value seen first is no longer echoed afterwards.
     */
    @Test
    @DisplayName("clear(): reverts now() from the fixed instant back to the system clock")
    void clearRevertsToSystemClock() {
        DateTimeProvider.setFixedDateTime(FISCAL_EVE);
        assertSame(FISCAL_EVE, DateTimeProvider.now());
        DateTimeProvider.clear();
        LocalDateTime current = DateTimeProvider.now();
        assertNotNull(current);
        assertFalse(FISCAL_EVE.equals(current));
    }

    /**
     * {@code setFixedDateTime} takes the non-null arm of its {@code requireNonNull} guard for a
     * valid instant, storing it so {@code now()} echoes it.
     */
    @Test
    @DisplayName("setFixedDateTime(): stores a non-null instant")
    void setFixedDateTimeStoresNonNullInstant() {
        DateTimeProvider.setFixedDateTime(FISCAL_MORROW);
        assertSame(FISCAL_MORROW, DateTimeProvider.now());
    }

    /**
     * {@code setFixedDateTime} takes the null arm of its {@code requireNonNull} guard and throws
     * with the documented message, leaving the provider unchanged.
     */
    @Test
    @DisplayName("setFixedDateTime(): rejects a null instant")
    void setFixedDateTimeRejectsNull() {
        NullPointerException error = assertThrows(NullPointerException.class,
                () -> DateTimeProvider.setFixedDateTime(null));
        assertEquals("Fixed time cannot be null", error.getMessage());
    }

    /**
     * The utility-class constructor is private and always throws: invoked reflectively it wraps
     * an {@link UnsupportedOperationException} carrying the documented message, pinning the
     * no-instantiation contract.
     */
    @Test
    @DisplayName("constructor: forbids instantiation of the utility class")
    void constructorForbidsInstantiation() throws NoSuchMethodException {
        Constructor<DateTimeProvider> constructor = DateTimeProvider.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException wrapper = assertThrows(InvocationTargetException.class,
                constructor::newInstance);
        Throwable cause = wrapper.getCause();
        assertInstanceOf(UnsupportedOperationException.class, cause);
        assertEquals("This is a utility class and cannot be instantiated", cause.getMessage());
    }
}
