package com.intermarche.fidelity.burn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.burn.ReservationDtos.ConfirmRequest;
import com.intermarche.fidelity.burn.ReservationDtos.RejectedReason;
import com.intermarche.fidelity.burn.ReservationDtos.ReservationRequest;
import com.intermarche.fidelity.burn.ReservationDtos.ReservationResponse;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link ReservationDtos}: the burn reservation API carriers (§27.3).
 * The holder and its four nested classes are branch-free Jackson DTOs, so the whole surface
 * is the private holder constructor, the implicit and explicit nested constructors and their
 * public fields. Every case asserts fresh defaults and that each field carries the exact
 * reference or value assigned; {@code BigDecimal} amounts are compared by {@code compareTo}.
 */
class ReservationDtosTest {

    /**
     * The holder is non-instantiable: its sole constructor is private, so reflection is the
     * only reach, and it runs without side effect to cover the private line.
     *
     * @throws Exception When the reflective construction fails.
     */
    @Test
    @DisplayName("holder exposes a single private constructor that runs without effect")
    void holderConstructorIsPrivateAndRunnable() throws Exception {
        Constructor<ReservationDtos>[] constructors = castConstructors(ReservationDtos.class.getDeclaredConstructors());
        assertEquals(1, constructors.length);
        Constructor<ReservationDtos> constructor = constructors[0];
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
    private Constructor<ReservationDtos>[] castConstructors(Constructor<?>[] raw) {
        return (Constructor<ReservationDtos>[]) raw;
    }

    /**
     * A freshly built reservation request nulls every field, so the DTO ships no accidental
     * defaults before Jackson binds them.
     */
    @Test
    @DisplayName("reservation request defaults are null")
    void reservationRequestDefaults() {
        ReservationRequest request = new ReservationRequest();
        assertNull(request.card);
        assertNull(request.amount);
        assertNull(request.ticketRef);
    }

    /**
     * Every reservation request field is a plain carrier: each holds the exact reference or
     * value assigned and reads it back unchanged; the amount is compared by {@code compareTo}
     * so scale never masks equality (§30.5).
     */
    @Test
    @DisplayName("reservation request fields carry the exact values assigned")
    void reservationRequestCarriesAssignedValues() {
        ReservationRequest request = new ReservationRequest();
        request.card = "CARD-7";
        request.amount = new BigDecimal("12.00");
        request.ticketRef = "STORE42#1001";
        assertEquals("CARD-7", request.card);
        assertEquals(0, new BigDecimal("12").compareTo(request.amount));
        assertEquals("STORE42#1001", request.ticketRef);
    }

    /**
     * The reservation response constructor carries its id and expiry by exact reference,
     * confirming the explicit constructor wires both fields verbatim.
     */
    @Test
    @DisplayName("reservation response constructor carries the id and expiry")
    void reservationResponseCarriesConstructorArguments() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        ReservationResponse response = new ReservationResponse(55L, expiresAt);
        assertEquals(55L, response.reservationId);
        assertSame(expiresAt, response.expiresAt);
    }

    /**
     * The reservation response accepts a null id and a null expiry through its constructor,
     * carrying both nulls back unchanged.
     */
    @Test
    @DisplayName("reservation response constructor carries null id and expiry")
    void reservationResponseCarriesNullConstructorArguments() {
        ReservationResponse response = new ReservationResponse(null, null);
        assertNull(response.reservationId);
        assertNull(response.expiresAt);
    }

    /**
     * A freshly built confirm request nulls its fiscal date, so it carries no accidental
     * default before Jackson binds it.
     */
    @Test
    @DisplayName("confirm request default fiscal date is null")
    void confirmRequestDefaults() {
        ConfirmRequest request = new ConfirmRequest();
        assertNull(request.fiscalDate);
    }

    /**
     * The confirm request carries its fiscal date by exact reference.
     */
    @Test
    @DisplayName("confirm request carries the fiscal date assigned")
    void confirmRequestCarriesAssignedValue() {
        ConfirmRequest request = new ConfirmRequest();
        LocalDate fiscalDate = LocalDate.of(2025, 12, 31);
        request.fiscalDate = fiscalDate;
        assertSame(fiscalDate, request.fiscalDate);
    }

    /**
     * The rejected reason constructor carries its reason code verbatim, confirming the
     * explicit constructor wires the field.
     */
    @Test
    @DisplayName("rejected reason constructor carries the reason code")
    void rejectedReasonCarriesConstructorArgument() {
        RejectedReason rejected = new RejectedReason("INSUFFICIENT_BALANCE");
        assertEquals("INSUFFICIENT_BALANCE", rejected.reason);
    }

    /**
     * The rejected reason accepts a null reason through its constructor, carrying the null
     * back unchanged.
     */
    @Test
    @DisplayName("rejected reason constructor carries a null reason")
    void rejectedReasonCarriesNullConstructorArgument() {
        RejectedReason rejected = new RejectedReason(null);
        assertNull(rejected.reason);
    }

    /**
     * The nested DTOs default- and value-construct without throwing, confirming the implicit
     * and explicit constructors carry no hidden failure path.
     */
    @Test
    @DisplayName("all nested DTOs construct without throwing")
    void nestedDtosConstructWithoutThrowing() {
        assertNotNull(new ReservationRequest());
        assertNotNull(new ReservationResponse(1L, LocalDateTime.of(2026, 1, 1, 0, 0)));
        assertNotNull(new ConfirmRequest());
        assertNotNull(new RejectedReason("DAILY_RULE"));
    }
}
