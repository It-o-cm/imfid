package com.intermarche.fidelity.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.intermarche.fidelity.ingestion.EventDtos.TicketClosed;
import com.intermarche.fidelity.ingestion.EventDtos.TicketReturn;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link EventsResource}: the fiscal event ingestion API (§27.4).
 * The resource is a thin guard-and-delegate: each endpoint rejects a missing natural key
 * with 400 and otherwise delegates to {@link IngestionService} and answers 202. Every leg
 * of the two compound validity guards is exercised true-alone (§29.6), plus the all-false
 * happy path. The service is a Mockito mock injected into the package-private field, so no
 * Panache boot and no clock are involved.
 */
class EventsResourceTest {

    /**
     * The resource under test, with its collaborator injected per test.
     */
    private EventsResource resource;

    /**
     * The mocked ingestion service, the authority of the credit (§26.1).
     */
    private IngestionService service;

    /**
     * Wires a fresh resource and mock before each test for isolation.
     */
    @BeforeEach
    void setUp() {
        service = mock(IngestionService.class);
        resource = new EventsResource();
        resource.service = service;
    }

    /**
     * Builds a valid {@code ticket-closed} event carrying the given ticket reference.
     *
     * @param ticketRef The ticket reference to seed.
     * @return The seeded event.
     */
    private TicketClosed closedWith(String ticketRef) {
        TicketClosed event = new TicketClosed();
        event.ticketRef = ticketRef;
        return event;
    }

    /**
     * Builds a valid {@code ticket-return} event carrying the given references.
     *
     * @param returnRef The return ticket reference to seed.
     * @param originRef The origin ticket reference to seed.
     * @return The seeded event.
     */
    private TicketReturn returnWith(String returnRef, String originRef) {
        TicketReturn event = new TicketReturn();
        event.returnTicketRef = returnRef;
        event.originTicketRef = originRef;
        return event;
    }

    /**
     * First leg true: a null event is rejected without touching the service.
     */
    @Test
    @DisplayName("ticketClosed: null event → 400")
    void ticketClosedNullEvent() {
        Response response = resource.ticketClosed(null);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Missing ticketRef"));
        verifyNoInteractions(service);
    }

    /**
     * Second leg true: a non-null event with a null ticketRef is rejected.
     */
    @Test
    @DisplayName("ticketClosed: null ticketRef → 400")
    void ticketClosedNullRef() {
        Response response = resource.ticketClosed(closedWith(null));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Missing ticketRef"));
        verifyNoInteractions(service);
    }

    /**
     * Third leg true: a non-null but blank ticketRef is rejected.
     */
    @Test
    @DisplayName("ticketClosed: blank ticketRef → 400")
    void ticketClosedBlankRef() {
        Response response = resource.ticketClosed(closedWith("   "));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Missing ticketRef"));
        verifyNoInteractions(service);
    }

    /**
     * All legs false: a valid event is delegated and answered 202.
     */
    @Test
    @DisplayName("ticketClosed: valid event → 202 and delegate")
    void ticketClosedValid() {
        TicketClosed event = closedWith("STORE-42#0007");
        Response response = resource.ticketClosed(event);
        assertEquals(Response.Status.ACCEPTED.getStatusCode(), response.getStatus());
        verify(service).ingestClosed(event);
    }

    /**
     * First leg true: a null return event is rejected without touching the service.
     */
    @Test
    @DisplayName("ticketReturn: null event → 400")
    void ticketReturnNullEvent() {
        Response response = resource.ticketReturn(null);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Missing returnTicketRef or originTicketRef"));
        verifyNoInteractions(service);
    }

    /**
     * Second leg true: a null returnTicketRef is rejected (origin valid, so no short-circuit
     * masking).
     */
    @Test
    @DisplayName("ticketReturn: null returnTicketRef → 400")
    void ticketReturnNullReturnRef() {
        Response response = resource.ticketReturn(returnWith(null, "STORE-1#1"));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Missing returnTicketRef or originTicketRef"));
        verifyNoInteractions(service);
    }

    /**
     * Third leg true: a blank returnTicketRef is rejected (origin valid).
     */
    @Test
    @DisplayName("ticketReturn: blank returnTicketRef → 400")
    void ticketReturnBlankReturnRef() {
        Response response = resource.ticketReturn(returnWith("  ", "STORE-1#1"));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Missing returnTicketRef or originTicketRef"));
        verifyNoInteractions(service);
    }

    /**
     * Fourth leg true: a null originTicketRef is rejected (return ref valid, so the first
     * two legs are false).
     */
    @Test
    @DisplayName("ticketReturn: null originTicketRef → 400")
    void ticketReturnNullOriginRef() {
        Response response = resource.ticketReturn(returnWith("STORE-1#R1", null));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Missing returnTicketRef or originTicketRef"));
        verifyNoInteractions(service);
    }

    /**
     * Fifth leg true: a blank originTicketRef is rejected (return ref valid).
     */
    @Test
    @DisplayName("ticketReturn: blank originTicketRef → 400")
    void ticketReturnBlankOriginRef() {
        Response response = resource.ticketReturn(returnWith("STORE-1#R1", "   "));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertTrue(response.getEntity().toString().contains("Missing returnTicketRef or originTicketRef"));
        verifyNoInteractions(service);
    }

    /**
     * All legs false: a valid return event is delegated and answered 202.
     */
    @Test
    @DisplayName("ticketReturn: valid event → 202 and delegate")
    void ticketReturnValid() {
        TicketReturn event = returnWith("STORE-1#R1", "STORE-1#O1");
        Response response = resource.ticketReturn(event);
        assertEquals(Response.Status.ACCEPTED.getStatusCode(), response.getStatus());
        verify(service).ingestReturn(event);
        verify(service, never()).ingestClosed(null);
    }
}
