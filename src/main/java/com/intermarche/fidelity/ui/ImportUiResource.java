package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.imports.*;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * The Imports screen (§23.5) — the CSV bulk-import drop and its execution report, at the
 * imvaluation import pattern (§18): one form, a domain select and a file, dispatched to
 * the matching importer. The report is summarized into the POST → 303 → notice (§21.3),
 * so a reload never re-imports.
 */
@Path("/ui/imports")
@RunOnVirtualThread
@RolesAllowed(AppUser.ROLE_FID_ADMIN)
public class ImportUiResource {

    /**
     * The rule importer.
     */
    @Inject
    FidelityRuleCsvResource rules;
    /**
     * The community importer.
     */
    @Inject
    FidelityCommunityCsvResource communities;
    /**
     * The account importer.
     */
    @Inject
    FidelityAccountCsvResource accounts;
    /**
     * The membership importer.
     */
    @Inject
    FidelityMembershipCsvResource memberships;
    /**
     * The adjustment importer.
     */
    @Inject
    FidelityAdjustmentCsvResource adjustments;
    /**
     * The activation importer.
     */
    @Inject
    FidelityActivationCsvResource activations;
    /**
     * The visit importer.
     */
    @Inject
    FidelityVisitCsvResource visits;
    /**
     * The product importer.
     */
    @Inject
    ProductCsvResource products;
    /**
     * The product family importer.
     */
    @Inject
    ProductFamilyCsvResource productFamilies;

    /**
     * The type-safe templates of this resource.
     */
    @CheckedTemplate
    static class Templates {

        /**
         * The imports template.
         *
         * @param notice   A one-shot notice.
         * @param noticeOk Whether the notice reports a success.
         * @param canWrite Whether the user may write.
         * @return The rendered imports screen.
         */
        static native TemplateInstance imports(String notice, boolean noticeOk, boolean canWrite);
    }

    /**
     * Renders the imports screen (§23.5).
     *
     * @param notice   A one-shot notice.
     * @param noticeOk Whether the notice reports a success.
     * @param sc       The security context.
     * @return The rendered screen.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance imports(@QueryParam("notice") String notice,
                                    @QueryParam("noticeOk") boolean noticeOk, @Context SecurityContext sc) {
        return Templates.imports(notice == null ? "" : notice, noticeOk, UiSupport.canWrite(sc));
    }

    /**
     * Runs an import, dispatching the uploaded file to the domain's importer (§18, §23.5).
     *
     * @param input The multipart form (domain + file).
     * @return A redirect with the report summary as a notice.
     */
    @POST
    @Path("/run")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response run(MultipartFormDataInput input) {
        try {
            Map<String, List<InputPart>> parts = input.getFormDataMap();
            String domain = parts.get("domain").get(0).getBodyAsString().trim();
            InputStream file = parts.get("file").get(0).getBody(InputStream.class, null);
            Response report = dispatch(domain, file);
            String body = report.getEntity() != null ? report.getEntity().toString() : "";
            return UiSupport.redirect("/ui/imports", domain + " — " + summarize(body), report.getStatus() < 400);
        } catch (Exception e) {
            return UiSupport.redirect("/ui/imports", "Import failed: " + e.getMessage(), false);
        }
    }

    /**
     * Dispatches a file stream to the importer of a domain.
     *
     * @param domain The domain name.
     * @param file   The CSV stream.
     * @return The importer's JSON report response.
     */
    private Response dispatch(String domain, InputStream file) {
        return switch (domain) {
            case "PRODUCTS" -> products.importProducts(file);
            case "PRODUCT_FAMILIES" -> productFamilies.importProductFamilies(file);
            case "FIDELITY_RULES" -> rules.importRules(file);
            case "FIDELITY_COMMUNITIES" -> communities.importCommunities(file);
            case "FIDELITY_ACCOUNTS" -> accounts.importAccounts(file);
            case "MEMBERSHIPS" -> memberships.importMemberships(file);
            case "FIDELITY_ADJUSTMENTS" -> adjustments.importAdjustments(file);
            case "FIDELITY_ACTIVATIONS" -> activations.importActivations(file);
            case "FIDELITY_VISITS" -> visits.importVisits(file);
            default -> Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Unknown domain '" + domain + "'\"}").build();
        };
    }

    /**
     * Summarizes a JSON import report into a short human sentence.
     *
     * @param body The JSON report body.
     * @return A short summary.
     */
    private String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "no report";
        }
        String created = extract(body, "createdCount");
        String updated = extract(body, "updatedCount");
        boolean hasErrors = body.contains("\"errors\"");
        String summary = created + " créé(s), " + updated + " mis à jour";
        return hasErrors ? summary + " (avec erreurs — voir logs)" : summary;
    }

    /**
     * Extracts a numeric field value from a small JSON body, or "?" when absent.
     *
     * @param body  The JSON body.
     * @param field The numeric field name.
     * @return The value as a string, or "?".
     */
    private String extract(String body, String field) {
        int idx = body.indexOf("\"" + field + "\"");
        if (idx < 0) {
            return "?";
        }
        int colon = body.indexOf(':', idx);
        int end = colon + 1;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == ' ' || body.charAt(end) == '-')) {
            end++;
        }
        return body.substring(colon + 1, end).trim();
    }
}
