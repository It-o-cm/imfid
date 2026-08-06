package com.intermarche.fidelity.account;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.AppUser;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The account read API exposed to the POS (§27.2): {@code GET /api/accounts/{card}}
 * (status, balance, available balance, month's visits, cap cumulatives, memberships)
 * and {@code GET /api/accounts/{card}/movements} (paginated history for the in-store
 * consultation and the card sheet, §20, §23.1).
 * <p>
 * Reads, no lock (§30.1). Pagination defaults to 50, capped at 200 (§31.3). An unknown
 * card is a 404. The {@code pos} role guard (§24.1) is attached in the security step.
 */
@Path("/api/accounts")
@RunOnVirtualThread
@RolesAllowed(AppUser.ROLE_POS)
public class AccountResource {

    /**
     * Default page size of the movement history (§31.3).
     */
    private static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * Maximum page size; a larger request is capped, never rejected (§31.3).
     */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * The account read service (§27.2).
     */
    @Inject
    AccountService service;

    /**
     * Returns the summary of an account (§27.2).
     *
     * @param card The card number.
     * @return 200 with the summary, or 404 when the card is unknown.
     */
    @GET
    @Path("/{card}")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response account(@PathParam("card") String card) {
        FidelityAccount account = FidelityAccount.findByCardNumber(card);
        if (account == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Unknown card\"}").build();
        }
        return Response.ok(service.summary(account)).build();
    }

    /**
     * Returns a page of an account's movements, most recent first (§27.2).
     *
     * @param card The card number.
     * @param page The zero-based page index (negative read as 0).
     * @param size The page size (default 50, capped at 200, §31.3).
     * @return 200 with the movement page, or 404 when the card is unknown.
     */
    @GET
    @Path("/{card}/movements")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response movements(@PathParam("card") String card,
                              @QueryParam("page") @DefaultValue("0") int page,
                              @QueryParam("size") @DefaultValue("50") int size) {
        FidelityAccount account = FidelityAccount.findByCardNumber(card);
        if (account == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Unknown card\"}").build();
        }
        int pageIndex = Math.max(0, page);
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return Response.ok(service.movements(account, pageIndex, pageSize)).build();
    }
}
