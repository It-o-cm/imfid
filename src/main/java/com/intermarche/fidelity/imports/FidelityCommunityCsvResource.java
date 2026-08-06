package com.intermarche.fidelity.imports;

import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.AppUser;
import io.quarkus.hibernate.orm.panache.Panache;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bulk CSV import of loyalty communities (§13, §18).
 * <p>
 * Communities — Babies, Large Families, Small Budgets, Students — are referenced
 * by their {@link FidelityCommunity#code} in {@code COMMUNITY_EARN} and
 * {@code MONTHLY_DATE_EARN} rules and carry the per-card monthly cap, the
 * enrollment cap and the annual renewal window (§13, §15).
 * <p>
 * File format (7 pipe-delimited columns):
 * {@code code|label|monthlyCap|enrollmentCap|renewalStartMonth|renewalEndMonth|eligibilityCriteria}.
 * The {@code fid-admin} role guard (§24.1) is attached in the security build step.
 */
@Path("/fidelity/communities/import")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed(AppUser.ROLE_FID_ADMIN)
public class FidelityCommunityCsvResource extends ImporterCsvResource {

    /**
     * Number of columns expected in the community CSV.
     */
    private static final int COLUMNS = 7;

    /**
     * Imports or updates loyalty communities from a CSV stream (§18).
     *
     * @param inputStream The community CSV stream.
     * @return The JSON import report (created/updated counts and errors).
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    public Response importCommunities(InputStream inputStream) {
        return this.importCsvStream(inputStream, COLUMNS);
    }

    /**
     * Bulk-fetches the communities already present for the chunk, keyed by code.
     *
     * @param parsedLines The rows of the current chunk.
     * @param targetCodes The distinct community codes present in the chunk.
     * @param counters    The global {@code [created, updated]} counters.
     * @param errors      The accumulating list of definitive row errors.
     * @return A map of code to existing {@link FidelityCommunity}, never null.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        Map<String, Object> contextMap = new HashMap<>();
        if (!parsedLines.isEmpty() && !targetCodes.isEmpty()) {
            for (FidelityCommunity community : FidelityCommunity.<FidelityCommunity>list("code in ?1", targetCodes)) {
                contextMap.put(community.code, community);
            }
        }
        return contextMap;
    }

    /**
     * Creates a new community or updates an existing one, skipping unchanged rows
     * through the checksum optimization.
     *
     * @param data      The row to process.
     * @param entityMap A map of code to existing {@link FidelityCommunity}.
     * @param counters  The local {@code [created, updated]} counters to increment.
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        FidelityCommunity community = (FidelityCommunity) entityMap.get(data.code);
        if (community == null) {
            community = new FidelityCommunity();
            community.code = data.code;
            feedCommunity(data, community);
            counters[0]++;
            Panache.getEntityManager().persist(community);
        } else if (community.checksum == null || community.checksum != computeIncomingChecksum(data)) {
            community = FidelityCommunity.findById(community.id);
            feedCommunity(data, community);
            counters[1]++;
        }
    }

    /**
     * Looks up a community by code for the 1-by-1 fallback.
     *
     * @param data The row to look up.
     * @return The {@link FidelityCommunity}, or null when absent.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return FidelityCommunity.findByCode(data.code);
    }

    /**
     * Populates a community's business fields from a parsed row.
     *
     * @param data      The row carrying the values.
     * @param community The community to populate.
     */
    private void feedCommunity(LineData data, FidelityCommunity community) {
        String[] parts = data.parts;
        community.label = safeGet(parts, 1);
        community.monthlyCap = safeParseBigDecimal(parts, 2);
        community.enrollmentCap = safeParseInt(parts, 3);
        community.renewalStartMonth = safeParseInt(parts, 4);
        community.renewalEndMonth = safeParseInt(parts, 5);
        community.eligibilityCriteria = safeGet(parts, 6);
    }

    /**
     * Computes the checksum of a row's incoming values, mirroring
     * {@link FidelityCommunity#getChecksum()}.
     *
     * @param data The row to hash.
     * @return The incoming checksum.
     */
    private int computeIncomingChecksum(LineData data) {
        String[] parts = data.parts;
        return Objects.hash(
                data.code,
                safeGet(parts, 1),
                safeParseBigDecimal(parts, 2),
                safeParseInt(parts, 3),
                safeParseInt(parts, 4),
                safeParseInt(parts, 5),
                safeGet(parts, 6)
        );
    }
}
