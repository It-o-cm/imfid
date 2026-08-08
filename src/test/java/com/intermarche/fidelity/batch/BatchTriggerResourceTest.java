package com.intermarche.fidelity.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link BatchTriggerResource}, the manual trigger surface of the
 * account-lifecycle batches (§16, §23.4, §32.3). Every branch of {@code trigger} is
 * exercised on both arms: the valid-type success path (dry-run and execute), the
 * {@link IllegalArgumentException} arm (unknown enum value) and the
 * {@link NullPointerException} arm (null type), plus the trimming and upper-casing of the
 * raw path parameter. The injected {@link BatchService} is mocked — this resource carries
 * no clock and no persistence, so no {@code DateTimeProvider} nor Panache mock is needed.
 */
class BatchTriggerResourceTest {

    /**
     * The resource under test, with a mocked batch service injected.
     */
    private BatchTriggerResource resource;

    /**
     * The mocked batch service collaborating with the resource.
     */
    private BatchService service;

    /**
     * Wires a fresh resource with a mocked batch service before each test.
     */
    @BeforeEach
    void setUp() {
        service = Mockito.mock(BatchService.class);
        resource = new BatchTriggerResource();
        resource.service = service;
    }

    /**
     * A valid upper-case type with {@code dryRun=true} returns 200 carrying the service
     * result, and forwards the parsed enum and the dry-run flag verbatim to the service.
     */
    @Test
    @DisplayName("valid type, dry-run true -> 200 and forwards EXPIRY/true to the service")
    void validTypeDryRunTrue() {
        BatchResult expected = new BatchResult("EXPIRY", true);
        Mockito.when(service.run(BatchType.EXPIRY, true)).thenReturn(expected);
        Response response = resource.trigger("EXPIRY", true);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertSame(expected, response.getEntity());
        Mockito.verify(service).run(BatchType.EXPIRY, true);
    }

    /**
     * A valid type with {@code dryRun=false} returns 200 and forwards the execute flag
     * (the non-dry-run arm of the boolean parameter) verbatim to the service.
     */
    @Test
    @DisplayName("valid type, dry-run false -> 200 and forwards PURGE/false to the service")
    void validTypeDryRunFalse() {
        BatchResult expected = new BatchResult("PURGE", false);
        Mockito.when(service.run(BatchType.PURGE, false)).thenReturn(expected);
        Response response = resource.trigger("PURGE", false);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertSame(expected, response.getEntity());
        ArgumentCaptor<Boolean> dryRun = ArgumentCaptor.forClass(Boolean.class);
        Mockito.verify(service).run(Mockito.eq(BatchType.PURGE), dryRun.capture());
        assertFalse(dryRun.getValue());
    }

    /**
     * A lower-case, whitespace-padded type is trimmed then upper-cased before parsing, so
     * it resolves to the matching enum and reaches the service on the success path.
     */
    @Test
    @DisplayName("padded lower-case type is trimmed and upper-cased -> 200 with ACTIVATION_VOID")
    void typeIsTrimmedAndUpperCased() {
        BatchResult expected = new BatchResult("ACTIVATION_VOID", true);
        Mockito.when(service.run(BatchType.ACTIVATION_VOID, true)).thenReturn(expected);
        Response response = resource.trigger("  activation_void  ", true);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertSame(expected, response.getEntity());
        Mockito.verify(service).run(BatchType.ACTIVATION_VOID, true);
    }

    /**
     * An unknown type value triggers {@link IllegalArgumentException} from
     * {@code valueOf}: the catch arm returns 400 with the error body and the service is
     * never called.
     */
    @Test
    @DisplayName("unknown type -> 400 error body, service untouched (IllegalArgumentException arm)")
    void unknownTypeIsBadRequest() {
        Response response = resource.trigger("NOPE", true);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals("{\"error\":\"Unknown batch type 'NOPE'\"}", response.getEntity());
        Mockito.verifyNoInteractions(service);
    }

    /**
     * A null type dereferences to {@link NullPointerException} on {@code type.trim()}: the
     * same catch arm returns 400, echoing {@code null} in the body, and the service is
     * never called.
     */
    @Test
    @DisplayName("null type -> 400 error body echoing null, service untouched (NullPointerException arm)")
    void nullTypeIsBadRequest() {
        Response response = resource.trigger(null, true);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals("{\"error\":\"Unknown batch type 'null'\"}", response.getEntity());
        Mockito.verifyNoInteractions(service);
    }

    /**
     * A blank (whitespace-only) type trims to the empty string, which is not a valid enum
     * value: it takes the {@link IllegalArgumentException} arm and returns 400 echoing the
     * original raw value.
     */
    @Test
    @DisplayName("blank type -> 400 error body echoing the raw value (empty valueOf)")
    void blankTypeIsBadRequest() {
        Response response = resource.trigger("   ", true);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals("{\"error\":\"Unknown batch type '   '\"}", response.getEntity());
        assertTrue(response.getEntity().toString().contains("   "));
        Mockito.verifyNoInteractions(service);
    }
}
