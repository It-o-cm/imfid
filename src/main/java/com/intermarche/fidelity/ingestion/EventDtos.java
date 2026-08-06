package com.intermarche.fidelity.ingestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.intermarche.fidelity.earn.ValuationRequest;
import com.intermarche.fidelity.earn.ValuationResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The DTOs of the fiscal event ingestion (§27.4): the {@code ticket-closed} credit
 * event and the {@code ticket-return} event, both replayable outbox upserts (I8).
 * <p>
 * Plain carriers ignoring unknown properties; fields are public for Jackson.
 */
public final class EventDtos {

    /**
     * Non-instantiable holder of the event DTOs.
     */
    private EventDtos() {
    }

    /**
     * The {@code POST /api/events/ticket-closed} payload (§27.4).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TicketClosed {

        /**
         * The ticket reference (store + number), the idempotency key with the type and
         * rule code (I8).
         */
        public String ticketRef;

        /**
         * The event card; a coherence control only — the card is authoritative from
         * {@code valuationRequest.customerCode} (§27.4). May be null.
         */
        public String card;

        /**
         * The fiscal date (withdrawal date for Drive), at the program zone; drives the
         * earnYear and the visit day (§30.3, §29.1).
         */
        public LocalDate fiscalDate;

        /**
         * The {@code /valuation} request; the authoritative card and the basket (§26.1).
         */
        public ValuationRequest valuationRequest;

        /**
         * The {@code /valuation} response; recomputed at ingestion (§26.1).
         */
        public ValuationResponse valuationResponse;

        /**
         * The earn displayed at the register, for trace only; never authoritative (§26.1).
         * Never null (§31.2).
         */
        public List<DisplayedEarn> displayedEarn = new ArrayList<>();

        /**
         * The reservation confirmed by this closure, if any (§27.4); null otherwise.
         */
        public Long reservationId;
    }

    /**
     * One displayed earn entry carried for trace (§27.4).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DisplayedEarn {

        /**
         * The rule code.
         */
        public String ruleCode;

        /**
         * The displayed amount, euro at scale 2.
         */
        public BigDecimal amount;
    }

    /**
     * The {@code POST /api/events/ticket-return} payload (§27.4).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TicketReturn {

        /**
         * The return ticket reference; the idempotency key of the return (§29.4).
         */
        public String returnTicketRef;

        /**
         * The origin ticket reference the return applies to (§27.4).
         */
        public String originTicketRef;

        /**
         * The fiscal date of the return, at the program zone.
         */
        public LocalDate fiscalDate;

        /**
         * The returned lines with their quantities (§27.4). Never null (§31.2).
         */
        public List<ReturnLine> lines = new ArrayList<>();

        /**
         * The optional voluntary refund to a card (§28.6); null otherwise.
         */
        public RefundToCard refundToCard;
    }

    /**
     * One returned line (§27.4).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReturnLine {

        /**
         * The original line id.
         */
        public String lineId;

        /**
         * The returned quantity.
         */
        public Double quantity;
    }

    /**
     * The voluntary refund-to-card of a return (§28.6).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RefundToCard {

        /**
         * The card credited.
         */
        public String card;

        /**
         * The refunded amount, euro at scale 2; bounded by the POS to the return total
         * (§28.6).
         */
        public BigDecimal amount;
    }
}
