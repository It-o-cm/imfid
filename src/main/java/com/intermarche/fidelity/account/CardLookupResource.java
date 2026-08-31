package com.intermarche.fidelity.account;

import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.CardHolder;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * The POS card lookup API — the fallback identity search (§33.3): finds cards by
 * the holder's phone, e-mail or name when no CRM access is configured. Exact
 * match only, bounded result list — never a browse. The {@code pos} role guard
 * (§24.1) is attached in the security build step.
 */
@Path("/api/cards/lookup")
@RunOnVirtualThread
@RolesAllowed(AppUser.ROLE_POS)
public class CardLookupResource {

    /**
     * The holder directory service (§33.3).
     */
    @Inject
    HolderService holders;

    /**
     * Looks up cards by holder identity (§33.3). Criteria priority: phone, then
     * e-mail, then name — the name matches by prefix (case- and accent-
     * insensitive), optionally narrowed by a first-name prefix.
     *
     * @param phone     The phone, in any keyed-in format; optional.
     * @param email     The e-mail; optional.
     * @param name      The last-name prefix; optional.
     * @param firstName An optional first-name prefix narrowing the name match.
     * @return 200 with the match list (possibly empty); 422 {reason} when no
     *         criterion is given or identity is CRM-managed.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response lookup(@QueryParam("phone") String phone, @QueryParam("email") String email,
                           @QueryParam("name") String name, @QueryParam("firstName") String firstName) {
        try {
            List<CardHolder> matches = holders.lookup(phone, email, name, firstName);
            List<Match> body = matches.stream().map(Match::of).toList();
            return Response.ok(body).build();
        } catch (AdminException e) {
            return Response.status(422).entity(new Rejection(e.getMessage())).build();
        }
    }

    /**
     * One lookup match: the card and the identity that matched, so the operator
     * can confirm with the customer before using the card (§33.3).
     */
    public static final class Match {

        /**
         * The card number.
         */
        public String card;

        /**
         * The account status, so the POS can warn on a non-active card.
         */
        public String status;

        /**
         * The holder's last name.
         */
        public String lastName;

        /**
         * The holder's first name, or null.
         */
        public String firstName;

        /**
         * The holder's phone as keyed in, or null.
         */
        public String phone;

        /**
         * The holder's e-mail, or null.
         */
        public String email;

        /**
         * Maps a holder row to a lookup match.
         *
         * @param holder The matched holder.
         * @return The match DTO.
         */
        static Match of(CardHolder holder) {
            Match match = new Match();
            match.card = holder.account.cardNumber;
            match.status = holder.account.status.name();
            match.lastName = holder.lastName;
            match.firstName = holder.firstName;
            match.phone = holder.phone;
            match.email = holder.email;
            return match;
        }
    }

    /**
     * The 422 rejection body: the refusal reason (§33.3).
     */
    public static final class Rejection {

        /**
         * The refusal reason.
         */
        public String reason;

        /**
         * Builds the rejection body.
         *
         * @param reason The refusal reason.
         */
        Rejection(String reason) {
            this.reason = reason;
        }
    }
}
