package com.intermarche.fidelity.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.fidelity.account.LedgerService;
import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.EarnTraceLine;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.PendingReturn;
import com.intermarche.fidelity.domain.ReservationState;
import com.intermarche.fidelity.earn.EarnEngine;
import com.intermarche.fidelity.earn.EarnResponse;
import com.intermarche.fidelity.earn.EarnResult;
import com.intermarche.fidelity.earn.ValuationReader;
import com.intermarche.fidelity.earn.ValuationReading;
import com.intermarche.fidelity.earn.ValuationReconciliationException;
import com.intermarche.fidelity.earn.WarningCode;
import com.intermarche.fidelity.rule.ValuedLine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fiscal event ingestion (§26.1, §27.4) — the authority of the credit: the recalc
 * at ingestion prevails (§26.1). Every write takes the per-card lock (§30.1), every
 * movement is idempotent by its natural key (I8), so an outbox replay in any order and
 * any number of times never double-counts.
 * <p>
 * It implements the fifth-pass rules: the visit is recorded whether the ticket earned
 * or not (§29.1); an expired lease is confirmed anyway with a warning (§29.2); a return
 * replayed before its origin is held and replayed automatically (§29.3); cumulated
 * returns are bounded by the earn of the line (§29.4); a refund-to-card is a
 * REFUND_CREDIT out of every cap (§28.6, §29.5); an event on a resiliated account
 * traces without moving the balance (§34.2); and the transfer chain routes returns to
 * the live account (§32.2).
 */
@ApplicationScoped
public class IngestionService {

    private static final Logger LOGGER = Logger.getLogger(IngestionService.class);

    /**
     * The reader turning the couple into valued lines (§22).
     */
    @Inject
    ValuationReader reader;

    /**
     * The earn engine recomputing the earn at ingestion (§26.1).
     */
    @Inject
    EarnEngine engine;

    /**
     * The ledger writer holding the lock and posting the idempotent movements (§30.1).
     */
    @Inject
    LedgerService ledger;

    /**
     * The mapper serializing the stored payloads and the held return replay.
     */
    @Inject
    ObjectMapper objectMapper;

    /**
     * Ingests a {@code ticket-closed} credit event (§27.4). Always a 202 — a fiscal
     * event is never rejected for data reasons (§25.2 applies to the projection).
     *
     * @param event The credit event.
     */
    @Transactional
    public void ingestClosed(EventDtos.TicketClosed event) {
        String card = resolveCardNumber(event);
        LocalDate fiscalDate = event.fiscalDate != null ? event.fiscalDate : today();
        String storeCode = event.valuationRequest != null ? event.valuationRequest.storeCode : null;

        List<String> warnings = new ArrayList<>();
        if (event.card != null && card != null && !event.card.equals(card)) {
            warnings.add(WarningCode.CARD_MISMATCH.name());
        }

        EarnTrace trace = upsertTrace(event, card, storeCode, fiscalDate);

        // The card is authoritative from the request; route through the transfer chain (§32.2).
        FidelityAccount account = card == null ? null : FidelityAccount.resolveActive(card);
        if (account == null) {
            finalizeTrace(trace, warnings, EarnTrace.STATUS_NO_MOVEMENT, displayedTotal(event), null);
            return;
        }
        ledger.lock(account.cardNumber);
        if (account.status == com.intermarche.fidelity.domain.AccountStatus.RESILIATED) {
            // A resiliated account's balance never moves again (§34.2).
            warnings.add(WarningCode.RESILIATED_ACCOUNT.name());
            finalizeTrace(trace, warnings, EarnTrace.STATUS_NO_MOVEMENT, displayedTotal(event), null);
            return;
        }

        ValuationReading reading;
        try {
            reading = reader.read(event.valuationResponse);
        } catch (ValuationReconciliationException e) {
            LOGGER.warnf("ticket-closed %s: reconciliation failed at ingestion: %s", event.ticketRef, e.getMessage());
            finalizeTrace(trace, warnings, EarnTrace.STATUS_FAILED, displayedTotal(event), null);
            return;
        }

        // The header is already written, so the current visit is counted (§29.1).
        EarnResult result = engine.evaluate(reading, account, fiscalDate.atStartOfDay(), false);
        collectWarnings(result, warnings);

        Map<String, ValuedLine> lineById = new LinkedHashMap<>();
        for (ValuedLine line : result.lines) {
            lineById.put(line.lineId, line);
        }

        BigDecimal recalcTotal = BigDecimal.ZERO;
        for (EarnResponse.Entry entry : result.entries) {
            ledger.post(account, MovementType.EARN, entry.amount, fiscalDate, entry.ruleCode,
                    event.ticketRef, entry.lineIds, null);
            recalcTotal = recalcTotal.add(entry.amount);
            addTraceLines(trace, entry, lineById);
        }
        recalcTotal = recalcTotal.setScale(2, RoundingMode.HALF_UP);

        BigDecimal displayed = displayedTotal(event);
        if (displayed != null && displayed.compareTo(recalcTotal) != 0) {
            warnings.add(WarningCode.EARN_MISMATCH.name());
        }

        // Confirm the burn reservation carried by the closure, expired or not (§29.2).
        if (event.reservationId != null) {
            confirmReservationAtIngestion(event.reservationId, fiscalDate, warnings);
        }

        finalizeTrace(trace, warnings, EarnTrace.STATUS_SUCCESS, displayed, recalcTotal);

        // Replay any returns held awaiting this origin (§29.3).
        replayPendingReturns(event.ticketRef);
    }

    /**
     * Ingests a {@code ticket-return} event (§27.4): holds it if the origin is not yet
     * ingested (§29.3), else applies the return debit and the optional refund.
     *
     * @param event The return event.
     */
    @Transactional
    public void ingestReturn(EventDtos.TicketReturn event) {
        EarnTrace origin = event.originTicketRef == null ? null : EarnTrace.findByTicketRef(event.originTicketRef);
        if (origin == null) {
            holdReturn(event);
            return;
        }
        processReturn(event, origin);
    }

    /**
     * Resolves the authoritative card number of a closure: the request customerCode,
     * falling back to the event card (§27.4).
     *
     * @param event The closure event.
     * @return The card number, or null.
     */
    private String resolveCardNumber(EventDtos.TicketClosed event) {
        if (event.valuationRequest != null && event.valuationRequest.customerCode != null
                && !event.valuationRequest.customerCode.isBlank()) {
            return event.valuationRequest.customerCode.trim();
        }
        return event.card != null && !event.card.isBlank() ? event.card.trim() : null;
    }

    /**
     * Upserts the trace header of a ticket, keyed by ticket reference, storing the
     * payloads and resetting the per-line detail on a replay (§26.1, §29.1).
     *
     * @param event      The closure event.
     * @param card       The resolved card number.
     * @param storeCode  The store code.
     * @param fiscalDate The fiscal date.
     * @return The persisted trace header.
     */
    private EarnTrace upsertTrace(EventDtos.TicketClosed event, String card, String storeCode, LocalDate fiscalDate) {
        EarnTrace trace = EarnTrace.findByTicketRef(event.ticketRef);
        if (trace == null) {
            trace = new EarnTrace();
            trace.ticketRef = event.ticketRef;
        } else {
            trace.lines.clear();
            trace.warnings.clear();
        }
        trace.cardNumber = card;
        trace.storeCode = storeCode;
        trace.fiscalDate = fiscalDate;
        trace.status = EarnTrace.STATUS_SUCCESS;
        trace.requestPayload = toJson(event.valuationRequest);
        trace.responsePayload = toJson(event.valuationResponse);
        trace.persist();
        return trace;
    }

    /**
     * Adds the per-line trace of a rule entry, splitting the earn across the lines
     * pro-rata by their eligible net, with a last-line remainder (§24.2, §29.4).
     *
     * @param trace    The trace header.
     * @param entry    The rule earn entry.
     * @param lineById The valued lines indexed by id.
     */
    private void addTraceLines(EarnTrace trace, EarnResponse.Entry entry, Map<String, ValuedLine> lineById) {
        BigDecimal totalBase = entry.baseAmount;
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < entry.lineIds.size(); i++) {
            String lineId = entry.lineIds.get(i);
            ValuedLine line = lineById.get(lineId);
            BigDecimal base = line != null ? line.earnBaseAmount() : BigDecimal.ZERO;
            BigDecimal earn;
            if (i == entry.lineIds.size() - 1) {
                earn = entry.amount.subtract(allocated);
            } else if (totalBase.signum() > 0) {
                earn = entry.amount.multiply(base).divide(totalBase, 2, RoundingMode.HALF_UP);
                allocated = allocated.add(earn);
            } else {
                earn = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
            EarnTraceLine traceLine = new EarnTraceLine();
            traceLine.lineId = lineId;
            traceLine.ean = line != null ? line.ean : null;
            traceLine.ruleCode = entry.ruleCode;
            traceLine.baseAmount = base;
            traceLine.earnAmount = earn.setScale(2, RoundingMode.HALF_UP);
            traceLine.quantity = line != null ? line.quantity : BigDecimal.ONE;
            trace.addLine(traceLine);
        }
    }

    /**
     * Confirms the reservation carried by a closure into a BURN, expired or not, warning
     * on an expired lease (§29.2). Idempotent: a confirmed or released reservation is
     * left untouched.
     *
     * @param reservationId The reservation id.
     * @param fiscalDate    The fiscal date of the burn.
     * @param warnings      The warnings collector.
     */
    private void confirmReservationAtIngestion(Long reservationId, LocalDate fiscalDate, List<String> warnings) {
        FidelityReservation reservation = FidelityReservation.findById(reservationId);
        if (reservation == null || reservation.state == ReservationState.CONFIRMED
                || reservation.state == ReservationState.RELEASED) {
            return;
        }
        FidelityAccount account = ledger.lock(reservation.account.cardNumber);
        if (account == null) {
            return;
        }
        if (reservation.state == ReservationState.EXPIRED || reservation.isExpiredAt(nowAtProgram())) {
            warnings.add(WarningCode.EXPIRED_LEASE_CONFIRMED.name());
        }
        ledger.post(account, MovementType.BURN, reservation.amount.negate(), fiscalDate,
                null, reservation.ticketRef, List.of(), null);
        reservation.state = ReservationState.CONFIRMED;
        reservation.persist();
    }

    /**
     * Holds a return awaiting its origin, idempotently by return ticket (§29.3).
     *
     * @param event The return event.
     */
    private void holdReturn(EventDtos.TicketReturn event) {
        if (PendingReturn.findByReturnTicketRef(event.returnTicketRef) != null) {
            return;
        }
        PendingReturn pending = new PendingReturn();
        pending.returnTicketRef = event.returnTicketRef;
        pending.originTicketRef = event.originTicketRef;
        pending.payload = toJson(event);
        pending.persist();
    }

    /**
     * Replays the returns held awaiting a just-ingested origin ticket, then removes them
     * (§29.3).
     *
     * @param originTicketRef The origin ticket reference.
     */
    private void replayPendingReturns(String originTicketRef) {
        List<PendingReturn> held = PendingReturn.listAwaitingOrigin(originTicketRef);
        if (held.isEmpty()) {
            return;
        }
        EarnTrace origin = EarnTrace.findByTicketRef(originTicketRef);
        for (PendingReturn pending : held) {
            try {
                EventDtos.TicketReturn event = objectMapper.readValue(pending.payload, EventDtos.TicketReturn.class);
                processReturn(event, origin);
            } catch (Exception e) {
                LOGGER.errorf(e, "Failed to replay held return %s", pending.returnTicketRef);
            }
            pending.delete();
        }
    }

    /**
     * Applies a return: the per-rule return debit bounded by the line earn (§29.4) and
     * the optional refund-to-card (§28.6), routing through the transfer chain (§32.2)
     * and never moving a resiliated balance (§34.2).
     *
     * @param event  The return event.
     * @param origin The origin ticket trace.
     */
    private void processReturn(EventDtos.TicketReturn event, EarnTrace origin) {
        FidelityAccount account = origin.cardNumber == null ? null : FidelityAccount.resolveActive(origin.cardNumber);
        if (account != null && account.status != com.intermarche.fidelity.domain.AccountStatus.RESILIATED) {
            ledger.lock(account.cardNumber);
            if (!FidelityMovement.hasMovementForTicket(event.returnTicketRef, MovementType.RETURN_DEBIT)) {
                applyReturnDebit(event, origin, account);
            }
        }
        applyRefundToCard(event);
    }

    /**
     * Computes and posts the per-rule return debit, bounded by the line earn (§29.4).
     *
     * @param event   The return event.
     * @param origin  The origin ticket trace.
     * @param account The live account to debit.
     */
    private void applyReturnDebit(EventDtos.TicketReturn event, EarnTrace origin, FidelityAccount account) {
        Map<String, BigDecimal> debitByRule = new LinkedHashMap<>();
        Map<String, List<String>> linesByRule = new LinkedHashMap<>();
        for (EventDtos.ReturnLine returnLine : event.lines) {
            if (returnLine == null || returnLine.lineId == null) {
                continue;
            }
            BigDecimal qtyRequested = returnLine.quantity != null
                    ? BigDecimal.valueOf(returnLine.quantity) : BigDecimal.ONE;
            for (EarnTraceLine traceLine : origin.lines) {
                if (!returnLine.lineId.equals(traceLine.lineId)) {
                    continue;
                }
                BigDecimal debit = lineDebit(traceLine, qtyRequested);
                if (debit.signum() <= 0) {
                    continue;
                }
                traceLine.persist();
                debitByRule.merge(traceLine.ruleCode, debit, BigDecimal::add);
                linesByRule.computeIfAbsent(traceLine.ruleCode, k -> new ArrayList<>()).add(traceLine.lineId);
            }
        }
        for (Map.Entry<String, BigDecimal> entry : debitByRule.entrySet()) {
            // Negative balance is tolerated: the debit passes anyway (I7).
            ledger.post(account, MovementType.RETURN_DEBIT, entry.getValue().negate(), event.fiscalDate,
                    entry.getKey(), event.returnTicketRef, linesByRule.get(entry.getKey()), null);
        }
    }

    /**
     * Computes the return debit of one origin trace line for a requested quantity,
     * bounded by the returnable quantity and the residual earn of the line (§29.4), and
     * advances the line's returned counters.
     *
     * @param traceLine    The origin trace line.
     * @param qtyRequested The requested return quantity.
     * @return The debit for this line, euro at scale 2 (zero when nothing is returnable).
     */
    private BigDecimal lineDebit(EarnTraceLine traceLine, BigDecimal qtyRequested) {
        BigDecimal originalQty = traceLine.quantity != null && traceLine.quantity.signum() > 0
                ? traceLine.quantity : BigDecimal.ONE;
        BigDecimal returnableQty = originalQty.subtract(traceLine.returnedQuantity);
        if (returnableQty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal thisQty = qtyRequested.min(returnableQty);
        if (thisQty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal fraction = thisQty.divide(originalQty, 6, RoundingMode.HALF_UP);
        BigDecimal debit = traceLine.earnAmount.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
        BigDecimal residual = traceLine.earnAmount.subtract(traceLine.returnedAmount);
        if (debit.compareTo(residual) > 0) {
            debit = residual;
        }
        if (debit.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        traceLine.returnedAmount = traceLine.returnedAmount.add(debit).setScale(2, RoundingMode.HALF_UP);
        traceLine.returnedQuantity = traceLine.returnedQuantity.add(thisQty);
        return debit;
    }

    /**
     * Posts the optional refund-to-card of a return as a REFUND_CREDIT — out of every
     * cap (§28.6, §29.5), idempotent by return ticket, never on a resiliated account
     * (§34.2), routed through the transfer chain (§32.2).
     *
     * @param event The return event.
     */
    private void applyRefundToCard(EventDtos.TicketReturn event) {
        EventDtos.RefundToCard refund = event.refundToCard;
        if (refund == null || refund.card == null || refund.amount == null || refund.amount.signum() == 0) {
            return;
        }
        if (FidelityMovement.hasMovementForTicket(event.returnTicketRef, MovementType.REFUND_CREDIT)) {
            return;
        }
        FidelityAccount account = FidelityAccount.resolveActive(refund.card);
        if (account == null || account.status == com.intermarche.fidelity.domain.AccountStatus.RESILIATED) {
            return;
        }
        ledger.lock(account.cardNumber);
        ledger.post(account, MovementType.REFUND_CREDIT, refund.amount.abs(), event.fiscalDate,
                null, event.returnTicketRef, List.of(), null);
    }

    /**
     * Collects the reading warnings of a result into the trace warnings.
     *
     * @param result   The earn result.
     * @param warnings The warnings collector.
     */
    private void collectWarnings(EarnResult result, List<String> warnings) {
        for (EarnResponse.Warning warning : result.warnings) {
            warnings.add(warning.ean != null ? warning.code + ":" + warning.ean : warning.code);
        }
    }

    /**
     * Sums the displayed earn carried by a closure, or null when none is carried (§26.1).
     *
     * @param event The closure event.
     * @return The displayed total, or null.
     */
    private BigDecimal displayedTotal(EventDtos.TicketClosed event) {
        if (event.displayedEarn == null || event.displayedEarn.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (EventDtos.DisplayedEarn displayed : event.displayedEarn) {
            if (displayed != null && displayed.amount != null) {
                total = total.add(displayed.amount);
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Finalizes a trace header with its outcome, warnings and earn totals (§26.1).
     *
     * @param trace         The trace header.
     * @param warnings      The collected warnings.
     * @param status        The ingestion status.
     * @param displayedEarn The displayed earn total, or null.
     * @param recalcEarn    The recomputed earn total, or null.
     */
    private void finalizeTrace(EarnTrace trace, List<String> warnings, String status,
                               BigDecimal displayedEarn, BigDecimal recalcEarn) {
        trace.status = status;
        trace.displayedEarn = displayedEarn;
        trace.recalculatedEarn = recalcEarn;
        trace.warnings.clear();
        trace.warnings.addAll(warnings);
        trace.persist();
    }

    /**
     * Serializes a payload to JSON, returning null on failure rather than throwing
     * (§31.2) — a trace payload is a debugging aid, not a correctness dependency.
     *
     * @param value The value to serialize.
     * @return The JSON string, or null.
     */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            LOGGER.warnf("Failed to serialize ingestion payload: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the current fiscal day at the program zone (§30.3).
     *
     * @return Today's fiscal date.
     */
    private LocalDate today() {
        return nowAtProgram().toLocalDate();
    }

    /**
     * Returns the current program instant (§24.6).
     *
     * @return The current program date-time.
     */
    private java.time.LocalDateTime nowAtProgram() {
        return com.intermarche.fidelity.domain.util.DateTimeProvider.now();
    }
}
