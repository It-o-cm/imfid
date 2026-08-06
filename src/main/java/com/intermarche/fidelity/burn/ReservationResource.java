package com.intermarche.fidelity.burn;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The burn reservation API exposed to the POS (§27.3): reserve, confirm and release a
 * decagnottage lease. Reservation at lease then confirmation — never a direct debit
 * (I11). The {@code pos} role guard (§24.1) is attached in the security build step.
 */
@Path("/api/burn/reservations")
@RunOnVirtualThread
public class ReservationResource {

    /**
     * The reservation service running the protocol under the per-card lock (§30.1).
     */
    @Inject
    ReservationService service;

    /**
     * Reserves (or renews) a burn lease (§27.3).
     *
     * @param request The reservation request (card, amount, ticketRef).
     * @return 201 created / 200 renewed with {reservationId, expiresAt}; 404 unknown
     *         card; 409 a live lease on another ticket; 422 {reason} otherwise.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response reserve(ReservationDtos.ReservationRequest request) {
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        ReservationService.ReserveOutcome outcome = service.reserve(request.card, request.amount, request.ticketRef);
        switch (outcome.status) {
            case CREATED:
                return Response.status(Response.Status.CREATED)
                        .entity(new ReservationDtos.ReservationResponse(
                                outcome.reservation.id, outcome.reservation.expiresAt)).build();
            case RENEWED:
                return Response.ok(new ReservationDtos.ReservationResponse(
                        outcome.reservation.id, outcome.reservation.expiresAt)).build();
            case NOT_FOUND:
                return Response.status(Response.Status.NOT_FOUND).build();
            case CONFLICT:
                return Response.status(Response.Status.CONFLICT).build();
            case REJECTED:
            default:
                return Response.status(422).entity(new ReservationDtos.RejectedReason(outcome.reason)).build();
        }
    }

    /**
     * Confirms a reservation into a BURN at the fiscal moment, idempotently (§27.3).
     *
     * @param id      The reservation id.
     * @param request The confirmation body (fiscalDate).
     * @return 200 confirmed/idempotent; 404 unknown; 410 expired lease.
     */
    @POST
    @Path("/{id}/confirm")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response confirm(@PathParam("id") Long id, ReservationDtos.ConfirmRequest request) {
        java.time.LocalDate fiscalDate = request != null ? request.fiscalDate : null;
        ReservationService.ConfirmOutcome outcome = service.confirm(id, fiscalDate);
        switch (outcome) {
            case OK:
                return Response.ok().build();
            case NOT_FOUND:
                return Response.status(Response.Status.NOT_FOUND).build();
            case GONE:
            default:
                return Response.status(Response.Status.GONE).build();
        }
    }

    /**
     * Releases a reservation, idempotently (§27.3).
     *
     * @param id The reservation id.
     * @return 204, always (idempotent).
     */
    @DELETE
    @Path("/{id}")
    public Response release(@PathParam("id") Long id) {
        service.release(id);
        return Response.noContent().build();
    }
}
