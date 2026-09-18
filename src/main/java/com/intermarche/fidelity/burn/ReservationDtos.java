package com.intermarche.fidelity.burn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The DTOs of the burn reservation API (§27.3): the reservation request, its response,
 * the confirmation body and the typed 422 reason.
 * <p>
 * Plain carriers; fields are public for Jackson.
 */
public final class ReservationDtos {

    /**
     * Non-instantiable holder of the reservation DTOs.
     */
    private ReservationDtos() {
    }

    /**
     * The body of {@code POST /api/burn/reservations} (§27.3).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReservationRequest {

        /**
         * The card to reserve balance on.
         */
        public String card;

        /**
         * The amount to reserve, euro at scale 2.
         */
        public BigDecimal amount;

        /**
         * The ticket the reservation is tied to; a re-POST with the same ticketRef
         * renews the lease (§27.3).
         */
        public String ticketRef;
    }

    /**
     * The successful reservation response (§27.3).
     */
    public static class ReservationResponse {

        /**
         * The reservation id.
         */
        public Long reservationId;

        /**
         * The lease expiry instant at the program zone.
         */
        public LocalDateTime expiresAt;

        /**
         * Builds a reservation response.
         *
         * @param reservationId The reservation id.
         * @param expiresAt     The lease expiry.
         */
        public ReservationResponse(Long reservationId, LocalDateTime expiresAt) {
            this.reservationId = reservationId;
            this.expiresAt = expiresAt;
        }
    }

    /**
     * The body of {@code POST /api/burn/reservations/{id}/confirm} (§27.3).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConfirmRequest {

        /**
         * The fiscal date of the confirmation (ticket date), at the program zone.
         */
        public LocalDate fiscalDate;
    }

    /**
     * The body of a 200 confirmation (RFP BO-03-03-34): the ledger balances around
     * the debit, so the POS prints the new balance from the ledger keeper instead
     * of recomputing it.
     */
    public static class ConfirmResponse {

        /**
         * The balance before the debit, euro at scale 2; null on an idempotent
         * replay.
         */
        public java.math.BigDecimal balanceBefore;

        /**
         * The burned amount, euro at scale 2.
         */
        public java.math.BigDecimal burnedAmount;

        /**
         * The balance after the debit, read from the refreshed ledger (§14).
         */
        public java.math.BigDecimal balanceAfter;

        /**
         * The program instant the figures were read at (§30.3).
         */
        public java.time.LocalDateTime asOf;

        /**
         * Builds a confirmation body.
         *
         * @param balanceBefore The balance before the debit, or null.
         * @param burnedAmount  The burned amount.
         * @param balanceAfter  The balance after the debit.
         * @param asOf          The read instant.
         */
        public ConfirmResponse(java.math.BigDecimal balanceBefore, java.math.BigDecimal burnedAmount,
                               java.math.BigDecimal balanceAfter, java.time.LocalDateTime asOf) {
            this.balanceBefore = balanceBefore;
            this.burnedAmount = burnedAmount;
            this.balanceAfter = balanceAfter;
            this.asOf = asOf;
        }

        /**
         * Default constructor for Jackson.
         */
        public ConfirmResponse() {
        }
    }

    /**
     * The typed 422 body of a rejected reservation (§25.2, §27.3): the closed
     * {@code reason} nomenclature.
     */
    public static class RejectedReason {

        /**
         * The rejection reason: {@code INSUFFICIENT_BALANCE} | {@code DAILY_RULE} |
         * {@code ACCOUNT_STATUS} (§27.3).
         */
        public String reason;

        /**
         * Builds a rejected reason.
         *
         * @param reason The reason code.
         */
        public RejectedReason(String reason) {
            this.reason = reason;
        }
    }
}
