package com.intermarche.fidelity.domain;

import com.intermarche.fidelity.domain.util.DateTimeProvider;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * A burn reservation lease and its lifecycle (§16, I11).
 * <p>
 * At the FIDELITY payment the POS reserves an amount; imfid checks the available
 * balance (balance minus active reservations) and the once-per-day rule, then
 * lays a lease with a renewable TTL — never an eternal reservation, and at most
 * one {@link ReservationState#ACTIVE} lease per card. The fiscal closure confirms
 * it into a {@link MovementType#BURN}. Only a confirmed reservation consumes the
 * once-per-day rule (§25.5).
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "fidelity_reservations",
        indexes = {
                @Index(name = "idx_reservation_account", columnList = "account_id"),
                @Index(name = "idx_reservation_state", columnList = "state"),
                @Index(name = "idx_reservation_ticket", columnList = "ticket_ref")
        }
)
public class FidelityReservation extends BaseEntity {

    /**
     * The account the reservation holds balance on.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    public FidelityAccount account;

    /**
     * The reserved amount in euro at scale 2 (§30.5).
     */
    @Column(nullable = false, precision = 19, scale = 2)
    @NotNull(message = "Reservation amount is mandatory")
    public BigDecimal amount;

    /**
     * The reservation state (§16, I11).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @NotNull(message = "Reservation state is mandatory")
    public ReservationState state = ReservationState.ACTIVE;

    /**
     * The ticket reference this reservation is tied to; the idempotency key of the
     * confirmation (§16).
     */
    @Column(name = "ticket_ref", length = 80)
    public String ticketRef;

    /**
     * The lease expiry instant, at the program zone; renewable by re-POST while the
     * ticket stays open (§16).
     */
    @Column(name = "expires_at", nullable = false)
    @NotNull(message = "Reservation expiry is mandatory")
    public LocalDateTime expiresAt;

    // --------------------------------------------------
    // Domain helpers
    // --------------------------------------------------

    /**
     * Indicates whether the lease has expired at the given instant.
     *
     * @param at The instant to test; when null the current program time is used.
     * @return true when the lease expiry is at or before the instant.
     */
    public boolean isExpiredAt(LocalDateTime at) {
        LocalDateTime moment = at != null ? at : DateTimeProvider.now();
        return expiresAt != null && !moment.isBefore(expiresAt);
    }

    /**
     * Indicates whether the reservation still actively holds balance: state ACTIVE
     * and not yet expired at the given instant.
     *
     * @param at The instant to test; when null the current program time is used.
     * @return true when the reservation is holding balance.
     */
    public boolean isHolding(LocalDateTime at) {
        return state == ReservationState.ACTIVE && !isExpiredAt(at);
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds the active reservation of an account, if any — at most one exists (§16).
     *
     * @param account The account.
     * @return The active reservation, or null.
     */
    public static FidelityReservation findActiveForAccount(FidelityAccount account) {
        return find("account = ?1 and state = ?2", account, ReservationState.ACTIVE).firstResult();
    }

    /**
     * Lists the active reservations that have expired at the given instant,
     * candidates for the lease-expiry sweep (§16).
     *
     * @param at The instant to compare expiry against.
     * @return The expired-but-still-active reservations, never null.
     */
    public static List<FidelityReservation> listExpired(LocalDateTime at) {
        return list("state = ?1 and expiresAt <= ?2", ReservationState.ACTIVE, at);
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum from the reservation's business fields, using the
     * account business code.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        String cardNumber = account != null ? account.cardNumber : null;
        return Objects.hash(cardNumber, amount, state, ticketRef, expiresAt);
    }
}
