package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link FidelityReservation}: a burn reservation lease and its lifecycle
 * (§16, I11). The temporal helpers are exercised on a frozen clock — the {@code at == null} arm
 * reads {@link DateTimeProvider}, so its instant is fixed and both instants straddling the lease
 * expiry border are tested, never the wall clock (§24.6). The Panache active-record finders are
 * driven through a {@link PanacheEntityBase} static mock in a try-with-resources per the imfid unit
 * bench; every guard and ternary arm is covered leg by leg (§29, §29.6), and the checksum is
 * asserted as the pure function of the business fields it is.
 */
class FidelityReservationTest {

    /**
     * Clears the fixed clock after each test so a frozen instant never leaks across cases.
     */
    @AfterEach
    void clearClock() {
        DateTimeProvider.clear();
    }

    // --------------------------------------------------
    // isExpiredAt()
    // --------------------------------------------------

    /**
     * The {@code expiresAt == null} arm short-circuits to false: a reservation with no expiry set
     * is never reported as expired, so the guard never dereferences a null instant (§31.2).
     */
    @Test
    @DisplayName("isExpiredAt(): a null expiry is never expired")
    void isExpiredAtNullExpiry() {
        FidelityReservation reservation = new FidelityReservation();
        reservation.expiresAt = null;
        assertFalse(reservation.isExpiredAt(LocalDateTime.of(2026, 3, 1, 12, 0)));
    }

    /**
     * The non-null {@code at} arm, moment strictly before expiry: {@code isBefore} is true so the
     * lease has not expired yet.
     */
    @Test
    @DisplayName("isExpiredAt(): an instant before expiry is not expired")
    void isExpiredAtBeforeBorder() {
        FidelityReservation reservation = new FidelityReservation();
        reservation.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        assertFalse(reservation.isExpiredAt(LocalDateTime.of(2026, 3, 1, 11, 59, 59)));
    }

    /**
     * The non-null {@code at} arm at the exact expiry instant: {@code isBefore} is false so the
     * lease is expired at the border, the closing side of the frontier (§16).
     */
    @Test
    @DisplayName("isExpiredAt(): the expiry instant itself is expired")
    void isExpiredAtOnBorder() {
        FidelityReservation reservation = new FidelityReservation();
        reservation.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        assertTrue(reservation.isExpiredAt(LocalDateTime.of(2026, 3, 1, 12, 0)));
    }

    /**
     * The non-null {@code at} arm strictly after expiry: an instant past the border is expired.
     */
    @Test
    @DisplayName("isExpiredAt(): an instant after expiry is expired")
    void isExpiredAtAfterBorder() {
        FidelityReservation reservation = new FidelityReservation();
        reservation.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        assertTrue(reservation.isExpiredAt(LocalDateTime.of(2026, 3, 1, 12, 0, 1)));
    }

    /**
     * The {@code at == null} arm before the border: the current program time from the frozen
     * {@link DateTimeProvider} is used, and an instant just before expiry is not expired (§24.6).
     */
    @Test
    @DisplayName("isExpiredAt(): a null instant reads the clock, before expiry not expired")
    void isExpiredAtNullInstantBeforeBorder() {
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 3, 1, 11, 59, 59));
        FidelityReservation reservation = new FidelityReservation();
        reservation.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        assertFalse(reservation.isExpiredAt(null));
    }

    /**
     * The {@code at == null} arm at the border: the frozen program time at the expiry instant is
     * expired, the two instants around the border resolving to distinct verdicts (§24.6).
     */
    @Test
    @DisplayName("isExpiredAt(): a null instant reads the clock, at expiry is expired")
    void isExpiredAtNullInstantOnBorder() {
        DateTimeProvider.setFixedDateTime(LocalDateTime.of(2026, 3, 1, 12, 0));
        FidelityReservation reservation = new FidelityReservation();
        reservation.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        assertTrue(reservation.isExpiredAt(null));
    }

    // --------------------------------------------------
    // isHolding()
    // --------------------------------------------------

    /**
     * The first leg false: a non-ACTIVE reservation (here CONFIRMED) holds no balance regardless
     * of expiry, the {@code &&} short-circuiting before the expiry check (§29.6).
     */
    @Test
    @DisplayName("isHolding(): a non-ACTIVE reservation holds nothing")
    void isHoldingNotActive() {
        FidelityReservation reservation = new FidelityReservation();
        reservation.state = ReservationState.CONFIRMED;
        reservation.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        assertFalse(reservation.isHolding(LocalDateTime.of(2026, 3, 1, 11, 0)));
    }

    /**
     * The first leg true, second leg true: an ACTIVE lease not yet expired holds balance (§16).
     */
    @Test
    @DisplayName("isHolding(): an ACTIVE, unexpired reservation holds balance")
    void isHoldingActiveUnexpired() {
        FidelityReservation reservation = new FidelityReservation();
        reservation.state = ReservationState.ACTIVE;
        reservation.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        assertTrue(reservation.isHolding(LocalDateTime.of(2026, 3, 1, 11, 59, 59)));
    }

    /**
     * The first leg true, second leg false: an ACTIVE but expired lease no longer holds balance,
     * the balance freeing itself at the border (§16).
     */
    @Test
    @DisplayName("isHolding(): an ACTIVE but expired reservation holds nothing")
    void isHoldingActiveExpired() {
        FidelityReservation reservation = new FidelityReservation();
        reservation.state = ReservationState.ACTIVE;
        reservation.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        assertFalse(reservation.isHolding(LocalDateTime.of(2026, 3, 1, 12, 0)));
    }

    // --------------------------------------------------
    // findActiveForAccount()
    // --------------------------------------------------

    /**
     * The finder queries the {@code state = ACTIVE} predicate for the account and returns its first
     * result — at most one ACTIVE lease exists per card (§16).
     */
    @Test
    @DisplayName("findActiveForAccount(): returns the single active lease")
    void findActiveForAccountFound() {
        FidelityAccount account = new FidelityAccount();
        FidelityReservation active = new FidelityReservation();
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityReservation> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(active);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and state = ?2", account, ReservationState.ACTIVE)).thenReturn(query);
            assertSame(active, FidelityReservation.findActiveForAccount(account));
        }
    }

    /**
     * A card with no live lease yields a null first result, so the caller distinguishes the absence
     * of an active reservation (§16).
     */
    @Test
    @DisplayName("findActiveForAccount(): returns null when no active lease exists")
    void findActiveForAccountAbsent() {
        FidelityAccount account = new FidelityAccount();
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityReservation> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and state = ?2", account, ReservationState.ACTIVE)).thenReturn(query);
            assertNull(FidelityReservation.findActiveForAccount(account));
        }
    }

    // --------------------------------------------------
    // listExpired()
    // --------------------------------------------------

    /**
     * The sweep finder lists the ACTIVE leases whose expiry is at or before the given instant, the
     * candidates for lease-expiry (§16), and returns the query's list verbatim.
     */
    @Test
    @DisplayName("listExpired(): lists the active leases expired at the instant")
    void listExpiredReturnsList() {
        LocalDateTime at = LocalDateTime.of(2026, 3, 1, 12, 0);
        List<FidelityReservation> expired = List.of(new FidelityReservation(), new FidelityReservation());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(
                    "state = ?1 and expiresAt <= ?2", ReservationState.ACTIVE, at)).thenReturn(expired);
            assertEquals(expired, FidelityReservation.listExpired(at));
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The non-null-account arm folds the account card number into the checksum: two reservations
     * with identical business fields share it.
     */
    @Test
    @DisplayName("getChecksum(): identical business fields share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * Changing any business field — here the amount — changes the checksum, so any lease divergence
     * is detected. Different {@code BigDecimal} scales are distinct business states (§30.5).
     */
    @Test
    @DisplayName("getChecksum(): a different amount alters the checksum")
    void checksumChangesWithAmount() {
        FidelityReservation other = sample();
        other.amount = new BigDecimal("99.99");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * The null-account arm folds a null card number: a reservation whose account is not yet attached
     * still computes a checksum without throwing, and it differs from the attached sample.
     */
    @Test
    @DisplayName("getChecksum(): a null account folds a null card number")
    void checksumNullAccount() {
        FidelityReservation detached = sample();
        detached.account = null;
        FidelityReservation detachedTwin = sample();
        detachedTwin.account = null;
        assertEquals(detached.getChecksum(), detachedTwin.getChecksum());
        assertNotEquals(sample().getChecksum(), detached.getChecksum());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a fully-populated reservation with fixed business fields for checksum assertions.
     *
     * @return A sample reservation with deterministic attributes and an attached account.
     */
    private FidelityReservation sample() {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = "3245070000001";
        FidelityReservation reservation = new FidelityReservation();
        reservation.account = account;
        reservation.amount = new BigDecimal("12.34");
        reservation.state = ReservationState.ACTIVE;
        reservation.ticketRef = "T-1";
        reservation.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        return reservation;
    }
}
