package com.intermarche.fidelity.ingestion;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The fiscal event ingestion API exposed to the POS outbox (§27.4): {@code ticket-closed}
 * (credit) and {@code ticket-return}. Both are idempotent upserts by natural key (I8);
 * both answer 202 — a fiscal event is never rejected for ordering or data reasons (a
 * return before its origin is held and replayed, §29.3). The {@code pos} role guard
 * (§24.1) is attached in the security build step.
 */
@Path("/api/events")
@RunOnVirtualThread
public class EventsResource {

    /**
     * The ingestion service, the authority of the credit (§26.1).
     */
    @Inject
    IngestionService service;

    /**
     * Ingests a {@code ticket-closed} credit event (§27.4).
     *
     * @param event The credit event.
     * @return 202 accepted.
     */
    @POST
    @Path("/ticket-closed")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response ticketClosed(EventDtos.TicketClosed event) {
        if (event == null || event.ticketRef == null || event.ticketRef.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Missing ticketRef\"}").build();
        }
        service.ingestClosed(event);
        return Response.status(Response.Status.ACCEPTED).build();
    }

    /**
     * Ingests a {@code ticket-return} event (§27.4).
     *
     * @param event The return event.
     * @return 202 accepted.
     */
    @POST
    @Path("/ticket-return")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response ticketReturn(EventDtos.TicketReturn event) {
        if (event == null || event.returnTicketRef == null || event.returnTicketRef.isBlank()
                || event.originTicketRef == null || event.originTicketRef.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Missing returnTicketRef or originTicketRef\"}").build();
        }
        service.ingestReturn(event);
        return Response.status(Response.Status.ACCEPTED).build();
    }
}
