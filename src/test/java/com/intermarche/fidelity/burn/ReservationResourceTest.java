package com.intermarche.fidelity.burn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import com.intermarche.fidelity.domain.FidelityReservation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link ReservationResource}: the §27.3 burn reservation
 * surface exposed to the POS. Every branch of the three endpoints is exercised — the
 * missing-payload guard (400) of {@code reserve} and its full {@code switch} over the
 * {@link ReservationService.ReserveOutcome.Status} nomenclature (201 created, 200
 * renewed, 404 unknown card, 409 live lease, 422 typed reason); the {@code fiscalDate}
 * ternary of {@code confirm} (both arms, null and non-null request) crossed with its
 * {@code switch} over {@link ReservationService.ConfirmOutcome} (200 ok, 404 unknown,
 * 410 gone); and the always-204 idempotent {@code release}.
 * <p>
 * Fully isolated: the sole {@link ReservationService} collaborator is mocked and wired on
 * the package-private injection field, so no clock, no Panache enhancement and no boot are
 * involved. Instants are fixed constants forwarded verbatim by the endpoints, which hold
 * no window or {@code earnYear} boundary of their own (§24.6).
 */
class ReservationResourceTest {

    /**
     * The fixed lease expiry echoed back in the success body; a constant, never a clock read.
     */
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 8, 8, 12, 15);

    /**
     * The fixed fiscal date carried by a confirmation body when present.
     */
    private static final LocalDate FISCAL_DATE = LocalDate.of(2026, 8, 8);

    /**
     * The system under test, freshly built per test with a mocked service.
     */
    private ReservationResource resource;

    /**
     * The mocked reservation service running the protocol under the per-card lock (§30.1).
     */
    private ReservationService service;

    /**
     * Builds a fresh system under test and wires the mocked service before each test.
     */
    @BeforeEach
    void setUp() {
        resource = new ReservationResource();
        service = Mockito.mock(ReservationService.class);
        resource.service = service;
    }

    /**
     * Builds a reservation request carrying the given card, amount and ticket.
     *
     * @param card      The card number.
     * @param amount    The amount to reserve.
     * @param ticketRef The ticket reference.
     * @return A ready reservation request.
     */
    private ReservationDtos.ReservationRequest request(String card, BigDecimal amount, String ticketRef) {
        ReservationDtos.ReservationRequest request = new ReservationDtos.ReservationRequest();
        request.card = card;
        request.amount = amount;
        request.ticketRef = ticketRef;
        return request;
    }

    /**
     * Builds a reservation entity carrying the given id and the fixed expiry.
     *
     * @param id The reservation id echoed in the response.
     * @return A reservation with {@link #EXPIRES_AT} as its expiry.
     */
    private FidelityReservation reservation(long id) {
        FidelityReservation reservation = new FidelityReservation();
        reservation.id = id;
        reservation.expiresAt = EXPIRES_AT;
        return reservation;
    }

    /**
     * A null payload short-circuits {@code reserve} to 400 without touching the service.
     */
    @Test
    @DisplayName("reserve: null request yields 400 and never calls the service")
    void reserveNullRequestReturnsBadRequest() {
        Response response = resource.reserve(null);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertNull(response.getEntity());
        Mockito.verifyNoInteractions(service);
    }

    /**
     * A CREATED outcome maps to 201 with the reservation id and expiry, and the request
     * fields are forwarded verbatim to the service.
     */
    @Test
    @DisplayName("reserve: CREATED yields 201 with body and forwards the request fields")
    void reserveCreatedReturnsCreated() {
        ReservationDtos.ReservationRequest request = request("CARD-1", new BigDecimal("5.00"), "T-1");
        Mockito.when(service.reserve("CARD-1", new BigDecimal("5.00"), "T-1"))
                .thenReturn(ReservationService.ReserveOutcome.created(reservation(42L)));
        Response response = resource.reserve(request);
        assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        ReservationDtos.ReservationResponse body = (ReservationDtos.ReservationResponse) response.getEntity();
        assertEquals(Long.valueOf(42L), body.reservationId);
        assertEquals(EXPIRES_AT, body.expiresAt);
    }

    /**
     * A RENEWED outcome maps to 200 with the reservation id and expiry in the body.
     */
    @Test
    @DisplayName("reserve: RENEWED yields 200 with body")
    void reserveRenewedReturnsOk() {
        ReservationDtos.ReservationRequest request = request("CARD-2", new BigDecimal("3.00"), "T-2");
        Mockito.when(service.reserve(any(), any(), any()))
                .thenReturn(ReservationService.ReserveOutcome.renewed(reservation(7L)));
        Response response = resource.reserve(request);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        ReservationDtos.ReservationResponse body = (ReservationDtos.ReservationResponse) response.getEntity();
        assertEquals(Long.valueOf(7L), body.reservationId);
        assertEquals(EXPIRES_AT, body.expiresAt);
    }

    /**
     * A NOT_FOUND outcome maps to 404 with no body.
     */
    @Test
    @DisplayName("reserve: NOT_FOUND yields 404")
    void reserveNotFoundReturnsNotFound() {
        Mockito.when(service.reserve(any(), any(), any()))
                .thenReturn(ReservationService.ReserveOutcome.notFound());
        Response response = resource.reserve(request("CARD-X", BigDecimal.ONE, "T"));
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
        assertNull(response.getEntity());
    }

    /**
     * A CONFLICT outcome maps to 409 with no body.
     */
    @Test
    @DisplayName("reserve: CONFLICT yields 409")
    void reserveConflictReturnsConflict() {
        Mockito.when(service.reserve(any(), any(), any()))
                .thenReturn(ReservationService.ReserveOutcome.conflict());
        Response response = resource.reserve(request("CARD-3", BigDecimal.TEN, "T-3"));
        assertEquals(Response.Status.CONFLICT.getStatusCode(), response.getStatus());
        assertNull(response.getEntity());
    }

    /**
     * A REJECTED outcome maps to 422 carrying the typed reason (§25.2, §27.3).
     */
    @Test
    @DisplayName("reserve: REJECTED yields 422 with the typed reason")
    void reserveRejectedReturnsUnprocessable() {
        Mockito.when(service.reserve(any(), any(), any()))
                .thenReturn(ReservationService.ReserveOutcome.rejected("INSUFFICIENT_BALANCE"));
        Response response = resource.reserve(request("CARD-4", BigDecimal.ONE, "T-4"));
        assertEquals(422, response.getStatus());
        ReservationDtos.RejectedReason body = (ReservationDtos.RejectedReason) response.getEntity();
        assertEquals("INSUFFICIENT_BALANCE", body.reason);
    }

    /**
     * A non-null confirmation body forwards its fiscal date (ternary true arm); an OK
     * outcome maps to 200.
     */
    @Test
    @DisplayName("confirm: non-null body forwards fiscalDate and OK yields 200")
    void confirmWithBodyForwardsFiscalDateAndReturnsOk() {
        ReservationDtos.ConfirmRequest request = new ReservationDtos.ConfirmRequest();
        request.fiscalDate = FISCAL_DATE;
        Mockito.when(service.confirm(eq(9L), eq(FISCAL_DATE))).thenReturn(ReservationService.ConfirmOutcome.OK);
        Response response = resource.confirm(9L, request);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        Mockito.verify(service).confirm(eq(9L), captor.capture());
        assertEquals(FISCAL_DATE, captor.getValue());
    }

    /**
     * A null confirmation body forwards a null fiscal date (ternary false arm); an OK
     * outcome still maps to 200.
     */
    @Test
    @DisplayName("confirm: null body forwards null fiscalDate and OK yields 200")
    void confirmNullBodyForwardsNullFiscalDateAndReturnsOk() {
        Mockito.when(service.confirm(eq(11L), isNull())).thenReturn(ReservationService.ConfirmOutcome.OK);
        Response response = resource.confirm(11L, null);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        Mockito.verify(service).confirm(eq(11L), isNull());
    }

    /**
     * A NOT_FOUND confirmation outcome maps to 404.
     */
    @Test
    @DisplayName("confirm: NOT_FOUND yields 404")
    void confirmNotFoundReturnsNotFound() {
        Mockito.when(service.confirm(any(), any())).thenReturn(ReservationService.ConfirmOutcome.NOT_FOUND);
        ReservationDtos.ConfirmRequest request = new ReservationDtos.ConfirmRequest();
        request.fiscalDate = FISCAL_DATE;
        Response response = resource.confirm(1L, request);
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    /**
     * A GONE confirmation outcome maps to 410 (expired lease, synchronous path).
     */
    @Test
    @DisplayName("confirm: GONE yields 410")
    void confirmGoneReturnsGone() {
        Mockito.when(service.confirm(any(), any())).thenReturn(ReservationService.ConfirmOutcome.GONE);
        ReservationDtos.ConfirmRequest request = new ReservationDtos.ConfirmRequest();
        request.fiscalDate = FISCAL_DATE;
        Response response = resource.confirm(2L, request);
        assertEquals(Response.Status.GONE.getStatusCode(), response.getStatus());
    }

    /**
     * A release is always a 204 and delegates the id to the service (idempotent, §27.3).
     */
    @Test
    @DisplayName("release: always yields 204 and delegates to the service")
    void releaseReturnsNoContent() {
        Response response = resource.release(5L);
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());
        assertNull(response.getEntity());
        Mockito.verify(service).release(5L);
    }
}
