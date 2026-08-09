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
 * catalog itself is administered here too: unit creation and parameter edition through a
 * form (the CSV import remains the central bulk feed, both upserting by code), and an
 * open/closed-to-enrollments toggle — deactivation blocks new memberships only, existing
 * members keep earning while their memberships and the referencing rules run (§23.2).
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

        /**
         * The community catalog form template (creation and edition modes).
         *
         * @param view The form view model.
         * @return The rendered form.
         */
        static native TemplateInstance form(CommunityFormView view);
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
     * Renders the community creation form (§23.2).
     *
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered form.
     */
    @GET
    @Path("/new")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance form(@QueryParam("notice") String notice,
                                 @QueryParam("noticeOk") boolean noticeOk, @Context SecurityContext sc) {
        CommunityFormView view = CommunityFormView.creation(UiSupport.canWrite(sc));
        view.notice = notice;
        view.noticeOk = noticeOk;
        return Templates.form(view);
    }

    /**
     * Creates a community from the form (§23.2).
     *
     * @param code                The community code.
     * @param label               The human label.
     * @param monthlyCap          The monthly cap, or blank.
     * @param enrollmentCap       The enrollment cap, or blank.
     * @param renewalStartMonth   The renewal window start month, or blank.
     * @param renewalEndMonth     The renewal window end month, or blank.
     * @param eligibilityCriteria The eligibility criterion, or blank.
     * @return A redirect with a notice.
     */
    @POST
    @Path("/create")
    @Transactional
    public Response create(@FormParam("code") String code, @FormParam("label") String label,
                           @FormParam("monthlyCap") String monthlyCap,
                           @FormParam("enrollmentCap") String enrollmentCap,
                           @FormParam("renewalStartMonth") String renewalStartMonth,
                           @FormParam("renewalEndMonth") String renewalEndMonth,
                           @FormParam("eligibilityCriteria") String eligibilityCriteria) {
        try {
            admin.createCommunity(code, label, parseDecimal(monthlyCap), parseInteger(enrollmentCap),
                    parseInteger(renewalStartMonth), parseInteger(renewalEndMonth), eligibilityCriteria);
            return UiSupport.redirect("/ui/communities/" + code.trim(), "Community " + code.trim() + " created", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/communities/new", e.getMessage(), false);
        }
    }

    /**
     * Renders the parameter edition form of a community (§23.2).
     *
     * @param code     The community code.
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered form, or a redirect when the community is unknown.
     */
    @GET
    @Path("/{code}/edit")
    @Produces(MediaType.TEXT_HTML)
    public Response edit(@PathParam("code") String code, @QueryParam("notice") String notice,
                         @QueryParam("noticeOk") boolean noticeOk, @Context SecurityContext sc) {
        FidelityCommunity community = FidelityCommunity.findByCode(code);
        if (community == null) {
            return UiSupport.redirect("/ui/communities", "Unknown community '" + code + "'", false);
        }
        CommunityFormView view = CommunityFormView.edition(community, UiSupport.canWrite(sc));
        view.notice = notice;
        view.noticeOk = noticeOk;
        return Response.ok(Templates.form(view)).build();
    }

    /**
     * Updates the parameters of a community from the form (§23.2); the code is frozen.
     *
     * @param code                The community code.
     * @param label               The human label.
     * @param monthlyCap          The monthly cap, or blank.
     * @param enrollmentCap       The enrollment cap, or blank.
     * @param renewalStartMonth   The renewal window start month, or blank.
     * @param renewalEndMonth     The renewal window end month, or blank.
     * @param eligibilityCriteria The eligibility criterion, or blank.
     * @return A redirect with a notice.
     */
    @POST
    @Path("/{code}/update")
    @Transactional
    public Response update(@PathParam("code") String code, @FormParam("label") String label,
                           @FormParam("monthlyCap") String monthlyCap,
                           @FormParam("enrollmentCap") String enrollmentCap,
                           @FormParam("renewalStartMonth") String renewalStartMonth,
                           @FormParam("renewalEndMonth") String renewalEndMonth,
                           @FormParam("eligibilityCriteria") String eligibilityCriteria) {
        try {
            admin.updateCommunity(code, label, parseDecimal(monthlyCap), parseInteger(enrollmentCap),
                    parseInteger(renewalStartMonth), parseInteger(renewalEndMonth), eligibilityCriteria);
            return UiSupport.redirect("/ui/communities/" + code, "Community " + code + " updated", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/communities/" + code + "/edit", e.getMessage(), false);
        }
    }

    /**
     * Opens or closes a community to new enrollments (§23.2).
     *
     * @param code   The community code.
     * @param active Whether the community accepts new enrollments.
     * @return A redirect with a notice.
     */
    @POST
    @Path("/{code}/active")
    @Transactional
    public Response setActive(@PathParam("code") String code, @FormParam("active") boolean active) {
        try {
            admin.setCommunityActive(code, active);
            return UiSupport.redirect("/ui/communities/" + code,
                    active ? "Community " + code + " reopened to enrollments"
                            : "Community " + code + " closed to new enrollments", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/communities/" + code, e.getMessage(), false);
        }
    }

    /**
     * Parses a decimal form field, returning null when blank or malformed (§31.2).
     *
     * @param value The raw field value.
     * @return The decimal, or null.
     */
    private java.math.BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses an integer form field, returning null when blank or malformed (§31.2).
     *
     * @param value The raw field value.
     * @return The integer, or null.
     */
    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
