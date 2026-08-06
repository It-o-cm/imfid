package com.intermarche.fidelity.domain;

/**
 * States of a burn reservation lease (§16, I11).
 * <p>
 * The register payment reserves ({@link #ACTIVE}); the fiscal closure confirms
 * it into a {@link MovementType#BURN} movement ({@link #CONFIRMED}); a cancelled
 * payment releases it explicitly ({@link #RELEASED}); an abandoned ticket lets
 * the lease {@link #EXPIRED} and the balance frees itself. Only a CONFIRMED
 * reservation consumes the once-per-day rule (§25.5).
 */
public enum ReservationState {

    /**
     * Live lease holding part of the available balance; renewable while the ticket
     * stays open. At most one ACTIVE reservation per card (a card is physically at
     * one register, §16).
     */
    ACTIVE,

    /**
     * Confirmed at the fiscal moment (idempotent, key ticketRef): became the BURN
     * movement, dated on the ticket (§16).
     */
    CONFIRMED,

    /**
     * Explicitly released when the register payment is cancelled; no movement (§16).
     */
    RELEASED,

    /**
     * Lease expired on a dead ticket with neither closure nor cancellation; the
     * balance frees itself, no movement (§16).
     */
    EXPIRED
}
