package com.intermarche.fidelity.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.earn.ValuationRequest;
import com.intermarche.fidelity.earn.ValuationResponse;
import com.intermarche.fidelity.ingestion.EventDtos.DisplayedEarn;
import com.intermarche.fidelity.ingestion.EventDtos.RefundToCard;
import com.intermarche.fidelity.ingestion.EventDtos.ReturnLine;
import com.intermarche.fidelity.ingestion.EventDtos.TicketClosed;
import com.intermarche.fidelity.ingestion.EventDtos.TicketReturn;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link EventDtos}: the fiscal event ingestion carriers (§27.4).
 * The holder and its five nested classes are branch-free Jackson DTOs, so the whole
 * surface is the private holder constructor, the implicit nested constructors, their
 * public fields, and the two collections seeded non-null (§31.2). Every case asserts fresh
 * defaults and that each field carries the exact reference or value assigned; {@code
 * BigDecimal} amounts are compared by {@code compareTo}.
 */
class EventDtosTest {

    /**
     * The holder is non-instantiable: its sole constructor is private, so reflection is
     * the only reach, and it runs without side effect to cover the private line.
     *
     * @throws Exception When the reflective construction fails.
     */
    @Test
    @DisplayName("holder exposes a single private constructor that runs without effect")
    void holderConstructorIsPrivateAndRunnable() throws Exception {
        Constructor<EventDtos>[] constructors = castConstructors(EventDtos.class.getDeclaredConstructors());
        assertEquals(1, constructors.length);
        Constructor<EventDtos> constructor = constructors[0];
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }

    /**
     * Casts the raw reflected constructors to the holder type; isolated to confine the
     * unchecked cast to a single documented helper.
     *
     * @param raw The constructors returned by reflection.
     * @return The same array typed to the holder constructor.
     */
    @SuppressWarnings("unchecked")
    private Constructor<EventDtos>[] castConstructors(Constructor<?>[] raw) {
        return (Constructor<EventDtos>[]) raw;
    }

    /**
     * A freshly built ticket-closed event nulls every optional field but seeds the
     * displayed-earn trace as an empty non-null list (§31.2), so the DTO ships no
     * accidental defaults and no null collection.
     */
    @Test
    @DisplayName("ticket-closed defaults are null except the non-null empty displayedEarn")
    void ticketClosedDefaults() {
        TicketClosed event = new TicketClosed();
        assertNull(event.ticketRef);
        assertNull(event.card);
        assertNull(event.fiscalDate);
        assertNull(event.valuationRequest);
        assertNull(event.valuationResponse);
        assertNull(event.reservationId);
        assertNotNull(event.displayedEarn);
        assertTrue(event.displayedEarn.isEmpty());
    }

    /**
     * Every ticket-closed field is a plain carrier: each holds the exact reference or value
     * assigned and reads it back unchanged, including the authoritative valuation couple.
     */
    @Test
    @DisplayName("ticket-closed fields carry the exact references assigned")
    void ticketClosedCarriesAssignedReferences() {
        TicketClosed event = new TicketClosed();
        ValuationRequest request = new ValuationRequest();
        ValuationResponse response = new ValuationResponse();
        LocalDate fiscalDate = LocalDate.of(2026, 1, 1);
        List<DisplayedEarn> trace = new ArrayList<>();
        event.ticketRef = "STORE42#1001";
        event.card = "CARD-7";
        event.fiscalDate = fiscalDate;
        event.valuationRequest = request;
        event.valuationResponse = response;
        event.reservationId = 55L;
        event.displayedEarn = trace;
        assertEquals("STORE42#1001", event.ticketRef);
        assertEquals("CARD-7", event.card);
        assertSame(fiscalDate, event.fiscalDate);
        assertSame(request, event.valuationRequest);
        assertSame(response, event.valuationResponse);
        assertEquals(55L, event.reservationId);
        assertSame(trace, event.displayedEarn);
    }

    /**
     * A freshly built displayed-earn entry nulls its rule code and amount, so trace rows
     * carry no accidental defaults before Jackson binds them.
     */
    @Test
    @DisplayName("displayed-earn defaults are null")
    void displayedEarnDefaults() {
        DisplayedEarn entry = new DisplayedEarn();
        assertNull(entry.ruleCode);
        assertNull(entry.amount);
    }

    /**
     * The displayed-earn entry carries its rule code verbatim and its amount by value; the
     * amount is compared by {@code compareTo} so scale never masks equality (§30.5).
     */
    @Test
    @DisplayName("displayed-earn carries the rule code and the amount by value")
    void displayedEarnCarriesAssignedValues() {
        DisplayedEarn entry = new DisplayedEarn();
        entry.ruleCode = "R-EARN";
        entry.amount = new BigDecimal("1.50");
        assertEquals("R-EARN", entry.ruleCode);
        assertEquals(0, new BigDecimal("1.5").compareTo(entry.amount));
    }

    /**
     * A freshly built ticket-return event nulls every optional field but seeds the returned
     * lines as an empty non-null list (§31.2), so no null collection ever ships.
     */
    @Test
    @DisplayName("ticket-return defaults are null except the non-null empty lines")
    void ticketReturnDefaults() {
        TicketReturn event = new TicketReturn();
        assertNull(event.returnTicketRef);
        assertNull(event.originTicketRef);
        assertNull(event.fiscalDate);
        assertNull(event.refundToCard);
        assertNotNull(event.lines);
        assertTrue(event.lines.isEmpty());
    }

    /**
     * Every ticket-return field is a plain carrier: each holds the exact reference or value
     * assigned and reads it back unchanged, including the optional refund-to-card.
     */
    @Test
    @DisplayName("ticket-return fields carry the exact references assigned")
    void ticketReturnCarriesAssignedReferences() {
        TicketReturn event = new TicketReturn();
        LocalDate fiscalDate = LocalDate.of(2025, 12, 31);
        List<ReturnLine> lines = new ArrayList<>();
        RefundToCard refund = new RefundToCard();
        event.returnTicketRef = "RET#9";
        event.originTicketRef = "STORE42#1001";
        event.fiscalDate = fiscalDate;
        event.lines = lines;
        event.refundToCard = refund;
        assertEquals("RET#9", event.returnTicketRef);
        assertEquals("STORE42#1001", event.originTicketRef);
        assertSame(fiscalDate, event.fiscalDate);
        assertSame(lines, event.lines);
        assertSame(refund, event.refundToCard);
    }

    /**
     * A freshly built return line nulls its line id and quantity, so it carries no
     * accidental defaults before Jackson binds them.
     */
    @Test
    @DisplayName("return line defaults are null")
    void returnLineDefaults() {
        ReturnLine line = new ReturnLine();
        assertNull(line.lineId);
        assertNull(line.quantity);
    }

    /**
     * The return line carries its line id verbatim and its quantity by value.
     */
    @Test
    @DisplayName("return line carries the line id and the quantity by value")
    void returnLineCarriesAssignedValues() {
        ReturnLine line = new ReturnLine();
        line.lineId = "L-3";
        line.quantity = 2.0d;
        assertEquals("L-3", line.lineId);
        assertEquals(2.0d, line.quantity);
    }

    /**
     * A freshly built refund-to-card nulls its card and amount, so it carries no accidental
     * defaults before Jackson binds them.
     */
    @Test
    @DisplayName("refund-to-card defaults are null")
    void refundToCardDefaults() {
        RefundToCard refund = new RefundToCard();
        assertNull(refund.card);
        assertNull(refund.amount);
    }

    /**
     * The refund-to-card carries its card verbatim and its amount by value; the amount is
     * compared by {@code compareTo} so scale never masks equality (§30.5).
     */
    @Test
    @DisplayName("refund-to-card carries the card and the amount by value")
    void refundToCardCarriesAssignedValues() {
        RefundToCard refund = new RefundToCard();
        refund.card = "CARD-7";
        refund.amount = new BigDecimal("12.00");
        assertEquals("CARD-7", refund.card);
        assertEquals(0, new BigDecimal("12").compareTo(refund.amount));
    }

    /**
     * The nested DTOs default-construct without throwing, confirming the implicit
     * constructors carry no hidden failure path.
     */
    @Test
    @DisplayName("all nested DTOs default-construct without throwing")
    void nestedDtosConstructWithoutThrowing() {
        assertNotNull(new TicketClosed());
        assertNotNull(new DisplayedEarn());
        assertNotNull(new TicketReturn());
        assertNotNull(new ReturnLine());
        assertNotNull(new RefundToCard());
    }
}
