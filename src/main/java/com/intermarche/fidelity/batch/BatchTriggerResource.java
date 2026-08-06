package com.intermarche.fidelity.batch;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The manual trigger surface of the account lifecycle batches (§16 — manual triggering)
 * — the same batch engine the cron and the GraphQL {@code triggerBatch} drive, one
 * engine, several channels (§32.3). Simulate ({@code dryRun=true}) then execute
 * ({@code dryRun=false}), returning the accounts touched and the amounts (§23.4).
 * <p>
 * The {@code fid-admin} role guard (§24.1) is attached in the security build step.
 */
@Path("/api/batches")
@RunOnVirtualThread
public class BatchTriggerResource {

    /**
     * The batch service running the executions and simulations.
     */
    @Inject
    BatchService service;

    /**
     * Triggers a batch, simulating by default (§23.4).
     *
     * @param type   The batch type ({@code EXPIRY} | {@code PURGE} | {@code ACTIVATION_VOID}).
     * @param dryRun Whether to simulate (default true) or execute.
     * @return 200 with the batch result, or 400 on an unknown batch type.
     */
    @POST
    @Path("/{type}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response trigger(@PathParam("type") String type, @QueryParam("dryRun") @DefaultValue("true") boolean dryRun) {
        BatchType batchType;
        try {
            batchType = BatchType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Unknown batch type '" + type + "'\"}").build();
        }
        return Response.ok(service.run(batchType, dryRun)).build();
    }
}
