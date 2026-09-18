package com.intermarche.fidelity.earn;

import com.intermarche.fidelity.account.LedgerService;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.util.ProgramClock;
import com.intermarche.fidelity.domain.AppUser;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;

/**
 * The {@code POST /api/earn} projection endpoint (§27.1) — the only earn surface
 * exposed to the POS. It reads the {@code /valuation} couple, joins the card context
 * and returns the earn projection: total, per-rule entries, cap traces, burnable base
 * and warnings.
 * <p>
 * It is strictly a read: nothing is credited, no visit, no trace, no counter (§30.2) —
 * the credit happens at ingestion (§16). It returns an empty earn (200) for an absent
 * or unknown card (§20, §27.1), 400 for a payload that cannot be read, and 422 when a
 * §22.1 reconciliation invariant is violated (§25.2); the detail is logged, never
 * persisted at the projection (§30.2). The {@code pos} role guard (§24.1) is attached
 * in the security build step.
 */
@Path("/api/earn")
@RunOnVirtualThread
@RolesAllowed(AppUser.ROLE_POS)
public class EarnResource {

    private static final Logger LOGGER = Logger.getLogger(EarnResource.class);

    /**
     * The reader turning the {@code /valuation} couple into valued lines (§22).
     */
    @Inject
    ValuationReader reader;

    /**
     * The orchestration engine producing the earn projection (§15).
     */
    @Inject
    EarnEngine engine;

    /**
     * The program clock, for the fallback evaluation instant (§25.1).
     */
    @Inject
    ProgramClock clock;

    /**
     * The ledger service, keeper of the balances the response echoes
     * (RFP BO-03-03-31/-34).
     */
    @Inject
    LedgerService ledger;

    /**
     * Projects the earn of a valued basket for the presented card (§27.1).
     *
     * @param request The {@code /valuation} couple; must carry a valuation response.
     * @return 200 with the earn projection (empty for an absent/unknown card), 400 on
     *         an unreadable payload, or 422 on a reconciliation violation (§25.2).
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response earn(EarnRequest request) {
        if (request == null || request.valuationResponse == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Missing valuation response in /earn request")).build();
        }
        ValuationReading reading;
        try {
            reading = reader.read(request.valuationResponse);
        } catch (ValuationReconciliationException e) {
            LOGGER.warnf("Rejected /earn: reconciliation failed: %s", e.getMessage());
            return Response.status(422).entity(error(e.getMessage())).build();
        }

        LocalDateTime evalDateTime = evaluationInstant(request.valuationRequest);
        String mode = request.projectionMode == null || request.projectionMode.isBlank()
                ? EarnResponse.MODE_CARD : request.projectionMode.trim();
        if (EarnResponse.MODE_ANONYMOUS.equals(mode)) {
            // No account is resolved or created (§20); card-independent rules only,
            // nothing credited, no trace, no balances (RFP BO-03-03-28).
            return Response.ok(engine.evaluateAnonymous(reading, evalDateTime).toResponse()).build();
        }
        if (!EarnResponse.MODE_CARD.equals(mode)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(error("Unknown projectionMode '" + mode + "' (CARD | ANONYMOUS)")).build();
        }
        FidelityAccount account = resolveAccount(request.valuationRequest);

        EarnResult result = engine.evaluate(reading, account, evalDateTime, true);
        if (account != null) {
            // The printed balance must come from the ledger keeper, never be recomputed
            // by the POS (RFP BO-03-03-31/-34); the projection credits nothing (§30.2).
            java.math.BigDecimal available = ledger.availableBalance(account);
            result.balances = new EarnResponse.Balances(available,
                    available.add(result.total).setScale(2, java.math.RoundingMode.HALF_UP), clock.now());
        }
        return Response.ok(result.toResponse()).build();
    }

    /**
     * Resolves the loyalty account of the presented card, never auto-creating one
     * (§20). A blank or unknown card yields null — an empty earn (§27.1).
     *
     * @param valuationRequest The valuation request carrying the card; may be null.
     * @return The account, or null when absent or unknown.
     */
    private FidelityAccount resolveAccount(ValuationRequest valuationRequest) {
        if (valuationRequest == null || valuationRequest.customerCode == null
                || valuationRequest.customerCode.isBlank()) {
            return null;
        }
        return FidelityAccount.findByCardNumber(valuationRequest.customerCode.trim());
    }

    /**
     * Resolves the fiscal evaluation instant: the basket date (§31.1), falling back to
     * the current program time when absent.
     *
     * @param valuationRequest The valuation request carrying the date; may be null.
     * @return The evaluation instant, never null.
     */
    private LocalDateTime evaluationInstant(ValuationRequest valuationRequest) {
        if (valuationRequest != null && valuationRequest.createdAt != null) {
            return valuationRequest.createdAt;
        }
        return clock.now();
    }

    /**
     * Builds a minimal JSON error body for the 400/422 responses.
     *
     * @param message The error message.
     * @return A one-field JSON object.
     */
    private static String error(String message) {
        String safe = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"error\":\"" + safe + "\"}";
    }
}
