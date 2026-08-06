package com.intermarche.fidelity.burn;

import com.intermarche.fidelity.account.LedgerService;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.ReservationState;
import com.intermarche.fidelity.domain.util.ProgramClock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The burn reservation protocol — reservation at lease then confirmation (I11, §16,
 * §27.3). The register payment reserves; imfid checks the available balance
 * (balance − active reservations) and the once-per-day rule, then lays a renewable
 * lease with at most one active reservation per card; the fiscal closure confirms it
 * into a BURN movement; a cancelled payment releases it; an abandoned ticket lets the
 * lease expire and the balance frees itself — never a direct debit (I11).
 * <p>
 * Every write takes the per-card lock (§30.1). A PENDING_ACTIVATION account accrues but
 * does not burn (§25.3): a reservation is refused with {@code ACCOUNT_STATUS}. Only a
 * confirmed reservation (a BURN) consumes the once-per-day rule (§25.5).
 */
@ApplicationScoped
public class ReservationService {

    /**
     * The default lease TTL in seconds when the setting is absent (15 minutes, I11).
     */
    private static final long DEFAULT_LEASE_TTL_SECONDS = 900L;

    /**
     * The ledger service holding the lock, the available balance and the BURN post.
     */
    @Inject
    LedgerService ledger;

    /**
     * The program clock resolving the lease expiry and the fiscal day (§25.1, §30.3).
     */
    @Inject
    ProgramClock clock;

    /**
     * Reserves (or renews) a burn lease on a card (§27.3).
     *
     * @param card      The card number.
     * @param amount    The amount to reserve, euro at scale 2.
     * @param ticketRef The ticket the reservation is tied to.
     * @return The reservation outcome carrying the status and the reservation.
     */
    @Transactional
    public ReserveOutcome reserve(String card, BigDecimal amount, String ticketRef) {
        FidelityAccount account = ledger.lock(card);
        if (account == null) {
            return ReserveOutcome.notFound();
        }
        if (!account.canBurn()) {
            return ReserveOutcome.rejected("ACCOUNT_STATUS");
        }
        LocalDateTime now = clock.now();
        BigDecimal want = amount != null ? amount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        if (want.signum() <= 0) {
            return ReserveOutcome.rejected("INSUFFICIENT_BALANCE");
        }

        FidelityReservation active = FidelityReservation.findActiveForAccount(account);
        if (active != null) {
            if (active.isExpiredAt(now)) {
                // The lease has elapsed: it frees itself and does not block a new one (§16).
                active.state = ReservationState.EXPIRED;
                active.persist();
                active = null;
            } else if (ticketRef != null && ticketRef.equals(active.ticketRef)) {
                // Same ticket: renew the lease (§27.3). Available = balance (this is the only lease).
                if (want.compareTo(account.balance) > 0) {
                    return ReserveOutcome.rejected("INSUFFICIENT_BALANCE");
                }
                active.amount = want;
                active.expiresAt = now.plusSeconds(leaseTtlSeconds());
                active.persist();
                return ReserveOutcome.renewed(active);
            } else {
                // A live lease on another ticket blocks a new reservation (§27.3).
                return ReserveOutcome.conflict();
            }
        }

        // New reservation: once-per-day rule then available balance (§27.3).
        LocalDate today = clock.today();
        if (FidelityMovement.hasBurnOn(account, today)) {
            return ReserveOutcome.rejected("DAILY_RULE");
        }
        BigDecimal available = ledger.availableBalance(account);
        if (want.compareTo(available) > 0) {
            return ReserveOutcome.rejected("INSUFFICIENT_BALANCE");
        }
        FidelityReservation reservation = new FidelityReservation();
        reservation.account = account;
        reservation.amount = want;
        reservation.state = ReservationState.ACTIVE;
        reservation.ticketRef = ticketRef;
        reservation.expiresAt = now.plusSeconds(leaseTtlSeconds());
        reservation.persist();
        return ReserveOutcome.created(reservation);
    }

    /**
     * Confirms a reservation into a BURN at the fiscal moment, idempotently (§27.3).
     *
     * @param id         The reservation id.
     * @param fiscalDate The fiscal date; the current program day when null.
     * @return The confirmation outcome.
     */
    @Transactional
    public ConfirmOutcome confirm(Long id, LocalDate fiscalDate) {
        FidelityReservation reservation = id == null ? null : FidelityReservation.findById(id);
        if (reservation == null) {
            return ConfirmOutcome.NOT_FOUND;
        }
        FidelityAccount account = ledger.lock(reservation.account.cardNumber);
        reservation = FidelityReservation.findById(id);
        if (reservation.state == ReservationState.CONFIRMED) {
            return ConfirmOutcome.OK;
        }
        if (reservation.state != ReservationState.ACTIVE || reservation.isExpiredAt(clock.now())) {
            // Expired or already terminal: the synchronous path answers 410 (§27.3); the
            // ingestion path confirms anyway (§29.2).
            return ConfirmOutcome.GONE;
        }
        LocalDate date = fiscalDate != null ? fiscalDate : clock.today();
        ledger.post(account, MovementType.BURN, reservation.amount.negate(), date,
                null, reservation.ticketRef, List.of(), null);
        reservation.state = ReservationState.CONFIRMED;
        reservation.persist();
        return ConfirmOutcome.OK;
    }

    /**
     * Releases an active reservation, idempotently (§27.3) — a cancelled register
     * payment. A non-active reservation is left untouched; the call is always a 204.
     *
     * @param id The reservation id.
     */
    @Transactional
    public void release(Long id) {
        FidelityReservation reservation = id == null ? null : FidelityReservation.findById(id);
        if (reservation == null) {
            return;
        }
        ledger.lock(reservation.account.cardNumber);
        reservation = FidelityReservation.findById(id);
        if (reservation.state == ReservationState.ACTIVE) {
            reservation.state = ReservationState.RELEASED;
            reservation.persist();
        }
    }

    /**
     * Reads the lease TTL in seconds from the settings, falling back to 15 minutes (I11).
     *
     * @return The lease TTL in seconds.
     */
    private long leaseTtlSeconds() {
        int ttl = FidelityProgramSetting.getInt(
                FidelityProgramSetting.KEY_RESERVATION_LEASE_TTL_SECONDS, (int) DEFAULT_LEASE_TTL_SECONDS);
        return ttl > 0 ? ttl : DEFAULT_LEASE_TTL_SECONDS;
    }

    /**
     * The outcome of a reservation attempt (§27.3).
     */
    public static final class ReserveOutcome {

        /**
         * The outcome status.
         */
        public enum Status {
            /** A new reservation was created (201). */
            CREATED,
            /** The existing lease was renewed (200). */
            RENEWED,
            /** The card is unknown (404). */
            NOT_FOUND,
            /** A live lease on another ticket blocks this one (409). */
            CONFLICT,
            /** The reservation was rejected with a typed reason (422). */
            REJECTED
        }

        /**
         * The outcome status.
         */
        public final Status status;

        /**
         * The reservation, present on CREATED / RENEWED.
         */
        public final FidelityReservation reservation;

        /**
         * The rejection reason, present on REJECTED.
         */
        public final String reason;

        /**
         * Builds an outcome.
         *
         * @param status      The status.
         * @param reservation The reservation, or null.
         * @param reason      The rejection reason, or null.
         */
        private ReserveOutcome(Status status, FidelityReservation reservation, String reason) {
            this.status = status;
            this.reservation = reservation;
            this.reason = reason;
        }

        /**
         * Builds a CREATED outcome.
         *
         * @param reservation The created reservation.
         * @return The outcome.
         */
        static ReserveOutcome created(FidelityReservation reservation) {
            return new ReserveOutcome(Status.CREATED, reservation, null);
        }

        /**
         * Builds a RENEWED outcome.
         *
         * @param reservation The renewed reservation.
         * @return The outcome.
         */
        static ReserveOutcome renewed(FidelityReservation reservation) {
            return new ReserveOutcome(Status.RENEWED, reservation, null);
        }

        /**
         * Builds a NOT_FOUND outcome.
         *
         * @return The outcome.
         */
        static ReserveOutcome notFound() {
            return new ReserveOutcome(Status.NOT_FOUND, null, null);
        }

        /**
         * Builds a CONFLICT outcome.
         *
         * @return The outcome.
         */
        static ReserveOutcome conflict() {
            return new ReserveOutcome(Status.CONFLICT, null, null);
        }

        /**
         * Builds a REJECTED outcome with a typed reason.
         *
         * @param reason The rejection reason.
         * @return The outcome.
         */
        static ReserveOutcome rejected(String reason) {
            return new ReserveOutcome(Status.REJECTED, null, reason);
        }
    }

    /**
     * The outcome of a confirmation (§27.3).
     */
    public enum ConfirmOutcome {
        /** Confirmed (or idempotent no-op): 200. */
        OK,
        /** The reservation is unknown: 404. */
        NOT_FOUND,
        /** The lease has expired (synchronous path): 410. */
        GONE
    }
}
