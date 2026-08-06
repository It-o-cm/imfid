package com.intermarche.fidelity.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.admin.AdminService;
import com.intermarche.fidelity.admin.MembershipInput;
import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.util.ProgramClock;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.ArrayList;
import java.util.List;

/**
 * The Communities administration screen (§23.2): the list (code, label, monthly cap,
 * active members, renewal window) and the community sheet whose memberships are edited in
 * a direct-editing workbench (§21.3) — the whole membership set is loaded, manipulated
 * client-side and submitted entire; a member absent from the submission is removed. The
 * community parameters themselves are administered by import (§18) and shown read-only.
 */
@Path("/ui/communities")
@RunOnVirtualThread
@RolesAllowed(AppUser.ROLE_FID_ADMIN)
public class CommunityUiResource {

    /**
     * The shared JSON mapper for the workbench payload.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    /**
     * The mutation service (§23.2).
     */
    @Inject
    AdminService admin;

    /**
     * The program clock resolving the active-member count (§25.1).
     */
    @Inject
    ProgramClock clock;

    /**
     * The type-safe templates of this resource.
     */
    @CheckedTemplate
    static class Templates {

        /**
         * The community list template.
         *
         * @param view The list view model.
         * @return The rendered list.
         */
        static native TemplateInstance list(ListView<CommunityRow> view);

        /**
         * The community workbench template.
         *
         * @param community   The community.
         * @param membersJson The current memberships as JSON.
         * @param canWrite    Whether the user may write.
         * @param notice      A one-shot notice.
         * @param noticeOk    Whether the notice reports a success.
         * @return The rendered workbench.
         */
        static native TemplateInstance workbench(FidelityCommunity community, String membersJson,
                                                 boolean canWrite, String notice, boolean noticeOk);
    }

    /**
     * Renders the community list (§23.2).
     *
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered list.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") boolean noticeOk, @Context SecurityContext sc) {
        List<FidelityCommunity> communities = FidelityCommunity.listAllByCode();
        List<CommunityRow> rows = new ArrayList<>();
        for (FidelityCommunity community : communities) {
            long members = FidelityMembership.count(
                    "community = ?1 and (validTo is null or validTo >= ?2)", community, clock.today());
            rows.add(CommunityRow.of(community, members));
        }
        ListView<CommunityRow> view = new ListView<>(rows, "/ui/communities", new java.util.LinkedHashMap<>(),
                "code", false, 1, 1, rows.size(), Math.max(1, rows.size()), "community", notice, noticeOk,
                UiSupport.canWrite(sc));
        return Templates.list(view);
    }

    /**
     * Renders the community workbench (§23.2).
     *
     * @param code     The community code.
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered workbench, or 404 when unknown.
     */
    @GET
    @Path("/{code}")
    @Produces(MediaType.TEXT_HTML)
    public Response workbench(@PathParam("code") String code, @QueryParam("notice") String notice,
                              @QueryParam("noticeOk") boolean noticeOk, @Context SecurityContext sc) {
        FidelityCommunity community = FidelityCommunity.findByCode(code);
        if (community == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Unknown community").build();
        }
        String membersJson = buildMembersJson(community);
        return Response.ok(Templates.workbench(community, membersJson, UiSupport.canWrite(sc),
                notice == null ? "" : notice, noticeOk)).build();
    }

    /**
     * Replaces the community's memberships from the workbench submission (§23.2).
     *
     * @param code    The community code.
     * @param members The full membership set as JSON.
     * @return A redirect with a notice.
     */
    @POST
    @Path("/{code}/memberships")
    @Transactional
    public Response memberships(@PathParam("code") String code, @FormParam("members") String members) {
        try {
            List<MembershipInput> entries = MAPPER.readValue(
                    members == null || members.isBlank() ? "[]" : members,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, MembershipInput.class));
            int count = admin.replaceMemberships(code, entries);
            return UiSupport.redirect("/ui/communities/" + code, count + " membership(s) saved", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/communities/" + code, e.getMessage(), false);
        } catch (Exception e) {
            return UiSupport.redirect("/ui/communities/" + code, "Invalid submission: " + e.getMessage(), false);
        }
    }

    /**
     * Builds the current memberships of a community as a JSON array for the workbench.
     *
     * @param community The community.
     * @return The memberships JSON array string.
     */
    private String buildMembersJson(FidelityCommunity community) {
        List<MembershipInput> members = new ArrayList<>();
        for (FidelityMembership membership : FidelityMembership.<FidelityMembership>list("community", community)) {
            MembershipInput input = new MembershipInput();
            input.card = membership.account != null ? membership.account.cardNumber : null;
            input.validFrom = membership.validFrom;
            input.validTo = membership.validTo;
            members.add(input);
        }
        try {
            return MAPPER.writeValueAsString(members);
        } catch (Exception e) {
            return "[]";
        }
    }
}
