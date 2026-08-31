package com.intermarche.fidelity.imports;

import com.intermarche.fidelity.domain.EarnTrace;
import io.quarkus.hibernate.orm.panache.Panache;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bulk CSV import of visits as dated {@link EarnTrace} headers (§18, §29.1).
 * <p>
 * A visit is a pass at the register, not an earn: every card-carrying
 * {@code ticket-closed} records a distinct civil day at the program zone, whether
 * it earned or not (§29.1). Visit counts derive from the {@code earn_traces}
 * headers (§14), so this domain seeds the "4th visit" state — an {@code ADJUSTMENT}
 * credits euros but never creates a visit (§18, §29.1). It writes header-only
 * traces with the {@code NO_MOVEMENT} status (no earn entry), touching no account
 * balance. This is how the 5 % &rarr; 10 % demo is staged from three dated headers
 * (§24.4). Each row is keyed by its ticket reference (idempotent, §29.4).
 * <p>
 * Consumed columns (resolved by header name; unknown columns of the shared
 * feed are ignored): TICKET_REF (key), CARD_NUMBER, STORE_CODE, FISCAL_DATE —
 * {@code FISCAL_DATE} an ISO local date. A missing card number or fiscal date fails the row and triggers the
 * staged fallback. The {@code fid-admin} role guard (§24.1) is attached in the
 * security build step.
 */
@Path("/fidelity/visits/import")
@ApplicationScoped
@RunOnVirtualThread
public class FidelityVisitCsvResource extends ImporterCsvResource {

    /** Header name of the natural key: the ticket reference. */
    static final String COL_TICKET_REF = "TICKET_REF";
    /** Header name of the visiting card number. */
    static final String COL_CARD_NUMBER = "CARD_NUMBER";
    /** Header name of the store code. */
    static final String COL_STORE_CODE = "STORE_CODE";
    /** Header name of the visit fiscal date. */
    static final String COL_FISCAL_DATE = "FISCAL_DATE";

    /** The columns this importer cannot work without. */
    private static final List<String> REQUIRED_COLUMNS = List.of(
            COL_CARD_NUMBER, COL_STORE_CODE, COL_FISCAL_DATE);

    /**
     * Imports visits as dated {@link EarnTrace} headers from a CSV stream (§18, §29.1).
     *
     * @param inputStream The visit CSV stream.
     * @return The JSON import report (created/updated counts and errors).
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    public Response importVisits(InputStream inputStream) {
        return this.importCsvStream(inputStream, COL_TICKET_REF, REQUIRED_COLUMNS);
    }

    /**
     * Bulk-fetches the trace headers already present for the chunk, keyed by ticket
     * reference, so an already-recorded visit is skipped (idempotency, §29.4).
     *
     * @param parsedLines The rows of the current chunk.
     * @param targetRefs  The distinct ticket references present in the chunk.
     * @param counters    The global {@code [created, updated]} counters.
     * @param errors      The accumulating list of definitive row errors.
     * @return A map of ticket reference to the existing {@link EarnTrace}, never null.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetRefs, int[] counters, List<String> errors) {
        Map<String, Object> contextMap = new HashMap<>();
        if (!parsedLines.isEmpty() && !targetRefs.isEmpty()) {
            for (EarnTrace trace : EarnTrace.<EarnTrace>list("ticketRef in ?1", targetRefs)) {
                contextMap.put(trace.ticketRef, trace);
            }
        }
        return contextMap;
    }

    /**
     * Writes a header-only visit trace for a new ticket reference; an already-present
     * reference is left untouched.
     *
     * @param data      The row to process.
     * @param entityMap A map of ticket reference to the existing {@link EarnTrace}.
     * @param counters  The local {@code [created, updated]} counters to increment.
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        if (entityMap.get(data.code) != null) {
            return; // Visit already recorded for this ticket (§29.4).
        }
        String cardNumber = safeGetNonBlank(data, COL_CARD_NUMBER);
        if (cardNumber == null) {
            throw new IllegalArgumentException("cardNumber is mandatory for a visit (§29.1).");
        }
        LocalDate fiscalDate = safeParseLocalDate(data, COL_FISCAL_DATE);
        if (fiscalDate == null) {
            throw new IllegalArgumentException("fiscalDate is mandatory.");
        }
        EarnTrace trace = new EarnTrace();
        trace.ticketRef = data.code;
        trace.cardNumber = cardNumber;
        trace.storeCode = safeGetNonBlank(data, COL_STORE_CODE);
        trace.fiscalDate = fiscalDate;
        trace.status = EarnTrace.STATUS_NO_MOVEMENT;
        Panache.getEntityManager().persist(trace);
        counters[0]++;
    }

    /**
     * Looks up a visit trace by ticket reference for the 1-by-1 fallback.
     *
     * @param data The row to look up.
     * @return The existing {@link EarnTrace}, or null when not yet recorded.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return EarnTrace.findByTicketRef(data.code);
    }
}
