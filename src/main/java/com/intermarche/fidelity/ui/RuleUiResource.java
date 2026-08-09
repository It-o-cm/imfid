package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.admin.AdminService;
import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.util.ProgramClock;
import com.intermarche.fidelity.rule.EarnRuleRegistry;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Rules administration screen (§23.3): the list with its state badges, and the
 * schema-driven creation form — the type is chosen from the schema registry (§12), the
 * fields are generated from the factory's JSON Schema with its {@code x-widget}
 * annotations (§21.3), and a Form/JSON toggle keeps the raw data reachable. The central
 * gesture is duplicate → adjust → close the old one (§23.3); a rule is never deleted and
 * a closed rule is never modified (§13, §18). Every rule stays consultable on a read-only
 * sheet, and the one sound exception to immutability is the in-place edition of a rule
 * not yet entered into force — it has no history and no replay depends on it (§18).
 * POST → 303 → notice throughout (§21.3).
 */
@Path("/ui/rules")
@RunOnVirtualThread
@RolesAllowed(AppUser.ROLE_FID_ADMIN)
public class RuleUiResource {

    /**
     * The whitelist of sortable columns (guide §5.1).
     */
    private static final Set<String> SORTABLE = Set.of("code", "type", "validFrom", "priority");

    /**
     * The schema registry publishing the factory schemas (§12, §21.3).
     */
    @Inject
    EarnRuleRegistry registry;

    /**
     * The mutation service (§18).
     */
    @Inject
    AdminService admin;

    /**
     * The program clock resolving the rule state (§25.1).
     */
    @Inject
    ProgramClock clock;

    /**
     * The type-safe templates of this resource.
     */
    @CheckedTemplate
    static class Templates {

        /**
         * The rule list template.
         *
         * @param view The list view model.
         * @return The rendered list.
         */
        static native TemplateInstance list(ListView<RuleRow> view);

        /**
         * The schema-driven rule form template (creation and edition modes).
         *
         * @param view The form view model.
         * @return The rendered form.
         */
        static native TemplateInstance form(RuleFormView view);
    }

    /**
     * Renders the filtered rule list (§23.3).
     *
     * @param code     The code filter fragment.
     * @param type     The type filter.
     * @param sort     The sort key.
     * @param dir      The sort direction.
     * @param page     The one-based page number.
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered list.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list(@QueryParam("code") String code, @QueryParam("type") String type,
                                 @QueryParam("sort") @DefaultValue("code") String sort,
                                 @QueryParam("dir") @DefaultValue("asc") String dir,
                                 @QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("notice") String notice, @QueryParam("noticeOk") boolean noticeOk,
                                 @Context SecurityContext sc) {
        String sortKey = SORTABLE.contains(sort) ? sort : "code";
        boolean desc = "desc".equalsIgnoreCase(dir);
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder where = new StringBuilder("1=1");
        if (code != null && !code.isBlank()) {
            where.append(" and lower(code) like :code");
            params.put("code", "%" + code.trim().toLowerCase() + "%");
        }
        if (type != null && !type.isBlank()) {
            where.append(" and type = :type");
            params.put("type", type.trim());
        }
        long total = FidelityRule.count(where.toString(), params);
        int pageCount = (int) Math.max(1, Math.ceil((double) total / UiSupport.PAGE_SIZE));
        int current = UiSupport.clampPage(page, pageCount);
        List<FidelityRule> rules = FidelityRule.find(
                        where + " order by " + sortKey + (desc ? " desc" : " asc"), params)
                .page(current - 1, UiSupport.PAGE_SIZE).list();
        LocalDateTime now = clock.now();
        List<RuleRow> rows = new ArrayList<>();
        for (FidelityRule rule : rules) {
            rows.add(RuleRow.of(rule, now));
        }
        Map<String, String> filters = new LinkedHashMap<>();
        if (code != null) {
            filters.put("code", code);
        }
        if (type != null) {
            filters.put("type", type);
        }
        ListView<RuleRow> view = new ListView<>(rows, "/ui/rules", filters, sortKey, desc,
                current, pageCount, total, UiSupport.PAGE_SIZE, "rule", notice, noticeOk, UiSupport.canWrite(sc));
        return Templates.list(view);
    }

    /**
     * Renders the schema-driven creation form, optionally prefilled from a source rule to
     * duplicate (§23.3).
     *
     * @param from     The source code to duplicate the specification from, or blank.
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered form.
     */
    @GET
    @Path("/new")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance form(@QueryParam("from") String from,
                                 @QueryParam("notice") String notice, @QueryParam("noticeOk") boolean noticeOk,
                                 @Context SecurityContext sc) {
        RuleFormView view = RuleFormView.creation(buildSchemasJson(), buildCommunitiesJson(),
                new TreeSet<>(registry.registeredTypes()), UiSupport.canWrite(sc));
        if (from != null && !from.isBlank()) {
            FidelityRule src = FidelityRule.findByCode(from.trim());
            if (src != null) {
                view.code = src.code + "_V2";
                view.type = src.type;
                view.specification = src.specification;
            }
        }
        view.notice = notice;
        view.noticeOk = noticeOk;
        return Templates.form(view);
    }

    /**
     * Renders the read-only rule sheet (§23.3): the very same schema-driven form as
     * edition, prefilled and frozen — same labels, widgets and percent conversions —
     * with the Form/JSON toggle keeping the raw specification inspectable.
     *
     * @param code     The rule code.
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered sheet, or a redirect to the list when the code is unknown.
     */
    @GET
    @Path("/{code}")
    @Produces(MediaType.TEXT_HTML)
    public Response detail(@PathParam("code") String code,
                           @QueryParam("notice") String notice, @QueryParam("noticeOk") boolean noticeOk,
                           @Context SecurityContext sc) {
        FidelityRule rule = FidelityRule.findByCode(code);
        if (rule == null) {
            return UiSupport.redirect("/ui/rules", "Unknown rule '" + code + "'", false);
        }
        RuleFormView view = RuleFormView.consultation(rule, clock.now(), buildSchemasJson(),
                buildCommunitiesJson(), new TreeSet<>(registry.registeredTypes()), UiSupport.canWrite(sc));
        view.notice = notice;
        view.noticeOk = noticeOk;
        return Response.ok(Templates.form(view)).build();
    }

    /**
     * Renders the edition form of a rule not yet entered into force (§18); any other
     * rule is redirected to its sheet with an explanation — versioning applies instead.
     *
     * @param code     The rule code.
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered form, or a redirect when the rule is unknown or already in force.
     */
    @GET
    @Path("/{code}/edit")
    @Produces(MediaType.TEXT_HTML)
    public Response edit(@PathParam("code") String code,
                         @QueryParam("notice") String notice, @QueryParam("noticeOk") boolean noticeOk,
                         @Context SecurityContext sc) {
        FidelityRule rule = FidelityRule.findByCode(code);
        if (rule == null) {
            return UiSupport.redirect("/ui/rules", "Unknown rule '" + code + "'", false);
        }
        if (!clock.now().isBefore(rule.validFrom)) {
            return UiSupport.redirect("/ui/rules/" + code,
                    "Only a rule not yet in force can be edited; duplicate then close instead (§18)", false);
        }
        RuleFormView view = RuleFormView.edition(rule, buildSchemasJson(), buildCommunitiesJson(),
                new TreeSet<>(registry.registeredTypes()), UiSupport.canWrite(sc));
        view.notice = notice;
        view.noticeOk = noticeOk;
        return Response.ok(Templates.form(view)).build();
    }

    /**
     * Updates a rule not yet entered into force from the schema-driven form (§18, §23.3).
     *
     * @param code          The code of the rule to update (frozen identity).
     * @param type          The rule type.
     * @param label         The printed label.
     * @param validFrom     The window start (ISO date-time).
     * @param validTo       The window end, or blank.
     * @param priority      The priority.
     * @param exclusive     Whether exclusive.
     * @param cap           The per-card cap, or blank.
     * @param active        Whether active.
     * @param specification The JSON specification (built by the form JS).
     * @return A redirect with a notice.
     */
    @POST
    @Path("/{code}/update")
    @Transactional
    public Response update(@PathParam("code") String code, @FormParam("type") String type,
                           @FormParam("label") String label, @FormParam("validFrom") String validFrom,
                           @FormParam("validTo") String validTo, @FormParam("priority") @DefaultValue("0") int priority,
                           @FormParam("exclusive") boolean exclusive, @FormParam("monthlyCapPerCard") String cap,
                           @FormParam("active") boolean active, @FormParam("specification") String specification) {
        try {
            admin.updateRule(code, type, label, parseDateTime(validFrom), parseDateTime(validTo),
                    priority, exclusive, parseDecimal(cap), active, specification);
            return UiSupport.redirect("/ui/rules/" + code, "Rule " + code + " updated", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/rules/" + code + "/edit", e.getMessage(), false);
        }
    }

    /**
     * Creates a rule from the schema-driven form (§18, §23.3).
     *
     * @param code          The rule code.
     * @param type          The rule type.
     * @param label         The printed label.
     * @param validFrom     The window start (ISO date-time).
     * @param validTo       The window end, or blank.
     * @param priority      The priority.
     * @param exclusive     Whether exclusive.
     * @param cap           The per-card cap, or blank.
     * @param active        Whether active.
     * @param specification The JSON specification (built by the form JS).
     * @return A redirect with a notice.
     */
    @POST
    @Path("/create")
    @Transactional
    public Response create(@FormParam("code") String code, @FormParam("type") String type,
                           @FormParam("label") String label, @FormParam("validFrom") String validFrom,
                           @FormParam("validTo") String validTo, @FormParam("priority") @DefaultValue("0") int priority,
                           @FormParam("exclusive") boolean exclusive, @FormParam("monthlyCapPerCard") String cap,
                           @FormParam("active") boolean active, @FormParam("specification") String specification) {
        try {
            admin.createRule(code, type, label, parseDateTime(validFrom), parseDateTime(validTo),
                    priority, exclusive, parseDecimal(cap), active, specification);
            return UiSupport.redirect("/ui/rules", "Rule " + code + " created", true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/rules/new", e.getMessage(), false);
        }
    }

    /**
     * Updates the end of application of a rule whose window has not ended (§18, §23.3);
     * a blank date clears the end (open-ended again).
     *
     * @param code    The rule code.
     * @param validTo The new window end (ISO date-time), or blank for open-ended.
     * @return A redirect to the rule sheet with a notice.
     */
    @POST
    @Path("/{code}/end-date")
    @Transactional
    public Response endDate(@PathParam("code") String code, @FormParam("validTo") String validTo) {
        try {
            admin.updateEndDate(code, parseDateTime(validTo));
            String message = validTo == null || validTo.isBlank()
                    ? "Rule " + code + ": end of application cleared (open-ended)"
                    : "Rule " + code + ": end of application set to " + validTo.trim();
            return UiSupport.redirect("/ui/rules/" + code, message, true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/rules/" + code, e.getMessage(), false);
        }
    }

    /**
     * Duplicates a rule under a new code (§23.3).
     *
     * @param code    The source rule code.
     * @param newCode The new rule code.
     * @return A redirect with a notice.
     */
    @POST
    @Path("/{code}/duplicate")
    @Transactional
    public Response duplicate(@PathParam("code") String code, @FormParam("newCode") String newCode) {
        try {
            admin.duplicateRule(code, newCode);
            return UiSupport.redirect("/ui/rules", "Rule " + code + " duplicated to " + newCode, true);
        } catch (AdminException e) {
            return UiSupport.redirect("/ui/rules", e.getMessage(), false);
        }
    }

    /**
     * Builds the aggregated {@code type -> schema} JSON object for the form generator,
     * the very same schemas the engine validates against (§12, §21.3).
     *
     * @return The schemas JSON object string.
     */
    private String buildSchemasJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : registry.allSchemas().entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        return sb.append('}').toString();
    }

    /**
     * Builds the community codes JSON array for the community widget.
     *
     * @return The community codes JSON array string.
     */
    private String buildCommunitiesJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (FidelityCommunity community : FidelityCommunity.listAllByCode()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(community.code).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * Parses an ISO date-time, returning null when blank or malformed (§31.2).
     *
     * @param value The date-time string.
     * @return The date-time, or null.
     */
    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String v = value.trim();
            return v.length() == 10 ? LocalDateTime.parse(v + "T00:00:00") : LocalDateTime.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses a decimal, returning null when blank or malformed (§31.2).
     *
     * @param value The decimal string.
     * @return The decimal, or null.
     */
    private BigDecimal parseDecimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
