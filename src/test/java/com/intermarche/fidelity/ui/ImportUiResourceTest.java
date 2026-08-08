package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.imports.FidelityAccountCsvResource;
import com.intermarche.fidelity.imports.FidelityActivationCsvResource;
import com.intermarche.fidelity.imports.FidelityAdjustmentCsvResource;
import com.intermarche.fidelity.imports.FidelityCommunityCsvResource;
import com.intermarche.fidelity.imports.FidelityMembershipCsvResource;
import com.intermarche.fidelity.imports.FidelityRuleCsvResource;
import com.intermarche.fidelity.imports.FidelityVisitCsvResource;
import com.intermarche.fidelity.imports.ProductCsvResource;
import com.intermarche.fidelity.imports.ProductFamilyCsvResource;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link ImportUiResource}: the imports screen render, the multipart
 * {@code run} dispatch and its report summary (§18, §23.5, §21.3), together with the private
 * {@code dispatch}/{@code summarize}/{@code extract} helpers driven through {@code run}. Every
 * importer collaborator is mocked with Mockito so no CSV is ever parsed, and the multipart
 * input is a Mockito {@link MultipartFormDataInput} feeding a fixed domain and file stream.
 * <p>
 * The {@code static native} Qute {@code imports} template of the nested {@code Templates} class
 * has no instrumentable body, so under a plain unit run the {@code imports} render method
 * evaluates the {@code notice == null ? "" : notice} ternary fully (that computation carries the
 * branch) and then reaches the native boundary, which raises an {@link UnsatisfiedLinkError};
 * each render test drives one arm and asserts that boundary. Every other path returns a real
 * {@link Response} asserted directly. Each guard is covered on both arms and, for compound
 * guards, on each leg (§29, §29.6): the {@code notice} ternary, the {@code getEntity() != null}
 * ternary and the {@code status < 400} guard of {@code run}, its try / {@code Exception} arms,
 * the nine domain cases plus the {@code default} of {@code dispatch}, the {@code body == null ||
 * body.isBlank()} legs and the {@code hasErrors} ternary of {@code summarize}, and the
 * {@code idx < 0} arms and every leg of the {@code end < length && (digit || space || minus)}
 * scan of {@code extract}.
 */
class ImportUiResourceTest {

    /**
     * The system under test, freshly built per test with its mocked importer collaborators.
     */
    private ImportUiResource resource;

    /**
     * The mocked rule importer.
     */
    private FidelityRuleCsvResource rules;

    /**
     * The mocked community importer.
     */
    private FidelityCommunityCsvResource communities;

    /**
     * The mocked account importer.
     */
    private FidelityAccountCsvResource accounts;

    /**
     * The mocked membership importer.
     */
    private FidelityMembershipCsvResource memberships;

    /**
     * The mocked adjustment importer.
     */
    private FidelityAdjustmentCsvResource adjustments;

    /**
     * The mocked activation importer.
     */
    private FidelityActivationCsvResource activations;

    /**
     * The mocked visit importer.
     */
    private FidelityVisitCsvResource visits;

    /**
     * The mocked product importer.
     */
    private ProductCsvResource products;

    /**
     * The mocked product family importer.
     */
    private ProductFamilyCsvResource productFamilies;

    /**
     * The file stream fed to every dispatch, shared so importer verification can match it.
     */
    private InputStream file;

    /**
     * Wires a fresh resource with all importer collaborators mocked and a shared file stream.
     */
    @BeforeEach
    void setUp() {
        resource = new ImportUiResource();
        rules = mock(FidelityRuleCsvResource.class);
        communities = mock(FidelityCommunityCsvResource.class);
        accounts = mock(FidelityAccountCsvResource.class);
        memberships = mock(FidelityMembershipCsvResource.class);
        adjustments = mock(FidelityAdjustmentCsvResource.class);
        activations = mock(FidelityActivationCsvResource.class);
        visits = mock(FidelityVisitCsvResource.class);
        products = mock(ProductCsvResource.class);
        productFamilies = mock(ProductFamilyCsvResource.class);
        resource.rules = rules;
        resource.communities = communities;
        resource.accounts = accounts;
        resource.memberships = memberships;
        resource.adjustments = adjustments;
        resource.activations = activations;
        resource.visits = visits;
        resource.products = products;
        resource.productFamilies = productFamilies;
        file = new ByteArrayInputStream(new byte[]{1, 2, 3});
    }

    /**
     * Builds a security context resolving the write role to the given decision.
     *
     * @param canWrite Whether the user holds the {@code fid-admin} role.
     * @return The mocked security context.
     */
    private SecurityContext context(boolean canWrite) {
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.isUserInRole(AppUser.ROLE_FID_ADMIN)).thenReturn(canWrite);
        return securityContext;
    }

    /**
     * Builds a multipart input yielding the given domain string and the shared file stream.
     *
     * @param domain The raw domain value returned by the domain part.
     * @return The mocked multipart form input.
     * @throws Exception Never — declared for the stubbed importer body accessors.
     */
    private MultipartFormDataInput input(String domain) throws Exception {
        MultipartFormDataInput multipart = mock(MultipartFormDataInput.class);
        InputPart domainPart = mock(InputPart.class);
        when(domainPart.getBodyAsString()).thenReturn(domain);
        InputPart filePart = mock(InputPart.class);
        when(filePart.getBody(eq(InputStream.class), org.mockito.ArgumentMatchers.<Type>isNull()))
                .thenReturn(file);
        Map<String, List<InputPart>> parts = Map.of("domain", List.of(domainPart),
                "file", List.of(filePart));
        when(multipart.getFormDataMap()).thenReturn(parts);
        return multipart;
    }

    /**
     * Returns the URL-decoded query of a redirect response, so assertions read the plain notice
     * regardless of the percent/plus encoding chosen by the URI builder.
     *
     * @param response The redirect response.
     * @return The decoded query string.
     */
    private String decodedQuery(Response response) {
        return java.net.URLDecoder.decode(response.getLocation().getQuery(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // --------------------------------------------------
    // imports (render)
    // --------------------------------------------------

    /**
     * {@code imports}: a null notice takes the {@code ""} arm of the ternary and, with a null
     * security context, the render reaches the native template boundary.
     */
    @Test
    @DisplayName("imports: null notice takes the empty arm and reaches the native boundary")
    void importsNullNotice() {
        assertThrows(UnsatisfiedLinkError.class, () -> resource.imports(null, false, null));
    }

    /**
     * {@code imports}: a non-null notice takes the {@code notice} arm of the ternary and, with a
     * writing security context, the render reaches the native template boundary.
     */
    @Test
    @DisplayName("imports: set notice takes the notice arm and reaches the native boundary")
    void importsSetNotice() {
        assertThrows(UnsatisfiedLinkError.class, () -> resource.imports("hi", true, context(true)));
    }

    // --------------------------------------------------
    // run — dispatch domains (switch cases)
    // --------------------------------------------------

    /**
     * {@code run}: the {@code PRODUCTS} domain dispatches to the product importer.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: PRODUCTS dispatches to the product importer")
    void runProducts() throws Exception {
        when(products.importProducts(file)).thenReturn(report(1, 2, false));
        Response response = resource.run(input("PRODUCTS"));
        verify(products).importProducts(file);
        assertSuccess(response, "PRODUCTS — 1 créé(s), 2 mis à jour");
    }

    /**
     * {@code run}: the {@code PRODUCT_FAMILIES} domain dispatches to the product family importer.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: PRODUCT_FAMILIES dispatches to the product family importer")
    void runProductFamilies() throws Exception {
        when(productFamilies.importProductFamilies(file)).thenReturn(report(3, 4, false));
        Response response = resource.run(input("PRODUCT_FAMILIES"));
        verify(productFamilies).importProductFamilies(file);
        assertSuccess(response, "PRODUCT_FAMILIES — 3 créé(s), 4 mis à jour");
    }

    /**
     * {@code run}: the {@code FIDELITY_RULES} domain dispatches to the rule importer.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: FIDELITY_RULES dispatches to the rule importer")
    void runRules() throws Exception {
        when(rules.importRules(file)).thenReturn(report(5, 6, false));
        Response response = resource.run(input("FIDELITY_RULES"));
        verify(rules).importRules(file);
        assertSuccess(response, "FIDELITY_RULES — 5 créé(s), 6 mis à jour");
    }

    /**
     * {@code run}: the {@code FIDELITY_COMMUNITIES} domain dispatches to the community importer.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: FIDELITY_COMMUNITIES dispatches to the community importer")
    void runCommunities() throws Exception {
        when(communities.importCommunities(file)).thenReturn(report(7, 8, false));
        Response response = resource.run(input("FIDELITY_COMMUNITIES"));
        verify(communities).importCommunities(file);
        assertSuccess(response, "FIDELITY_COMMUNITIES — 7 créé(s), 8 mis à jour");
    }

    /**
     * {@code run}: the {@code FIDELITY_ACCOUNTS} domain dispatches to the account importer.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: FIDELITY_ACCOUNTS dispatches to the account importer")
    void runAccounts() throws Exception {
        when(accounts.importAccounts(file)).thenReturn(report(9, 10, false));
        Response response = resource.run(input("FIDELITY_ACCOUNTS"));
        verify(accounts).importAccounts(file);
        assertSuccess(response, "FIDELITY_ACCOUNTS — 9 créé(s), 10 mis à jour");
    }

    /**
     * {@code run}: the {@code MEMBERSHIPS} domain dispatches to the membership importer.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: MEMBERSHIPS dispatches to the membership importer")
    void runMemberships() throws Exception {
        when(memberships.importMemberships(file)).thenReturn(report(11, 12, false));
        Response response = resource.run(input("MEMBERSHIPS"));
        verify(memberships).importMemberships(file);
        assertSuccess(response, "MEMBERSHIPS — 11 créé(s), 12 mis à jour");
    }

    /**
     * {@code run}: the {@code FIDELITY_ADJUSTMENTS} domain dispatches to the adjustment importer.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: FIDELITY_ADJUSTMENTS dispatches to the adjustment importer")
    void runAdjustments() throws Exception {
        when(adjustments.importAdjustments(file)).thenReturn(report(13, 14, false));
        Response response = resource.run(input("FIDELITY_ADJUSTMENTS"));
        verify(adjustments).importAdjustments(file);
        assertSuccess(response, "FIDELITY_ADJUSTMENTS — 13 créé(s), 14 mis à jour");
    }

    /**
     * {@code run}: the {@code FIDELITY_ACTIVATIONS} domain dispatches to the activation importer.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: FIDELITY_ACTIVATIONS dispatches to the activation importer")
    void runActivations() throws Exception {
        when(activations.importActivations(file)).thenReturn(report(15, 16, false));
        Response response = resource.run(input("FIDELITY_ACTIVATIONS"));
        verify(activations).importActivations(file);
        assertSuccess(response, "FIDELITY_ACTIVATIONS — 15 créé(s), 16 mis à jour");
    }

    /**
     * {@code run}: the {@code FIDELITY_VISITS} domain dispatches to the visit importer.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: FIDELITY_VISITS dispatches to the visit importer")
    void runVisits() throws Exception {
        when(visits.importVisits(file)).thenReturn(report(17, 18, false));
        Response response = resource.run(input("FIDELITY_VISITS"));
        verify(visits).importVisits(file);
        assertSuccess(response, "FIDELITY_VISITS — 17 créé(s), 18 mis à jour");
    }

    // --------------------------------------------------
    // run — default case and status guard
    // --------------------------------------------------

    /**
     * {@code run}: an unknown domain takes the {@code default} case of {@code dispatch}, whose
     * {@code 400} status takes the {@code status < 400} false arm (failure) and whose error body,
     * lacking both count fields and the {@code "errors"} marker, drives the {@code idx < 0} arm of
     * {@code extract} for both fields and the {@code hasErrors} false arm of {@code summarize}.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: unknown domain takes the default case and the failure status arm")
    void runUnknownDomain() throws Exception {
        Response response = resource.run(input("WHATEVER"));
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/imports", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains("notice=WHATEVER — ? créé(s), ? mis à jour"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // run — summarize / extract legs
    // --------------------------------------------------

    /**
     * {@code run}: a report carrying a leading-space created value, a negative updated value and an
     * {@code "errors"} marker drives the space and minus legs of the {@code extract} scan, the
     * digit leg and the comma/brace terminators, and the {@code hasErrors} true arm of
     * {@code summarize}. The domain trims its surrounding whitespace before dispatch.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: space/minus values with errors marker summarize with the errors suffix")
    void runSummaryWithErrors() throws Exception {
        Response report = Response.ok("{\"createdCount\": 5,\"updatedCount\":-3,\"errors\":[\"x\"]}").build();
        when(products.importProducts(file)).thenReturn(report);
        Response response = resource.run(input("  PRODUCTS  "));
        verify(products).importProducts(file);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(decodedQuery(response).contains(
                "notice=PRODUCTS — 5 créé(s), -3 mis à jour (avec erreurs — voir logs)"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code run}: a report whose updated value ends the string drives the {@code end < length}
     * false leg of the {@code extract} scan (loop stops on length), while an absent created field
     * drives the {@code idx < 0} arm; no {@code "errors"} marker keeps the {@code hasErrors} false
     * arm.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: value at end of body stops the scan on length")
    void runSummaryValueAtEnd() throws Exception {
        Response report = Response.ok("{\"updatedCount\":7").build();
        when(products.importProducts(file)).thenReturn(report);
        Response response = resource.run(input("PRODUCTS"));
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(decodedQuery(response).contains("notice=PRODUCTS — ? créé(s), 7 mis à jour"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code run}: an importer response with no entity takes the {@code ""} arm of the
     * {@code getEntity() != null} ternary, so {@code summarize} sees a blank body and takes the
     * {@code isBlank} leg of its guard, yielding the "no report" notice on a success status.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: null entity takes the empty-body arm and the isBlank leg")
    void runNullEntity() throws Exception {
        when(products.importProducts(file)).thenReturn(Response.ok().build());
        Response response = resource.run(input("PRODUCTS"));
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(decodedQuery(response).contains("notice=PRODUCTS — no report"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code run}: an entity whose {@code toString()} returns null takes the {@code getEntity() !=
     * null} true arm yet feeds a null body to {@code summarize}, driving the {@code body == null}
     * first leg of its guard true and yielding the "no report" notice.
     *
     * @throws Exception Never — declared for the multipart stubbing.
     */
    @Test
    @DisplayName("run: null-toString entity drives the body == null leg")
    void runNullBody() throws Exception {
        Object nullString = new Object() {
            /**
             * Renders as null to drive the {@code body == null} leg of {@code summarize}.
             *
             * @return Always null.
             */
            @Override
            public String toString() {
                return null;
            }
        };
        when(products.importProducts(file)).thenReturn(Response.ok(nullString).build());
        Response response = resource.run(input("PRODUCTS"));
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertTrue(decodedQuery(response).contains("notice=PRODUCTS — no report"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    // --------------------------------------------------
    // run — catch arm
    // --------------------------------------------------

    /**
     * {@code run}: a multipart input whose form-data map access throws takes the {@code catch} arm
     * and redirects with the "Import failed" failure notice.
     */
    @Test
    @DisplayName("run: an exception takes the catch arm and reports the failure")
    void runFailure() {
        MultipartFormDataInput multipart = mock(MultipartFormDataInput.class);
        when(multipart.getFormDataMap()).thenThrow(new RuntimeException("boom"));
        Response response = resource.run(multipart);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/imports", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains("notice=Import failed: boom"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // helpers
    // --------------------------------------------------

    /**
     * Builds a JSON import report response with the given counts and optional errors marker.
     *
     * @param created   The created count.
     * @param updated   The updated count.
     * @param hasErrors Whether to embed the {@code "errors"} marker.
     * @return The importer report response.
     */
    private Response report(int created, int updated, boolean hasErrors) {
        String errors = hasErrors ? ",\"errors\":[\"x\"]" : "";
        String body = "{\"createdCount\":" + created + ",\"updatedCount\":" + updated + errors + "}";
        return Response.ok(body).build();
    }

    /**
     * Asserts a successful redirect back to the imports screen carrying the expected notice.
     *
     * @param response The redirect response.
     * @param notice   The expected decoded notice text.
     */
    private void assertSuccess(Response response, String notice) {
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/imports", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains("notice=" + notice));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
        assertFalse(decodedQuery(response).contains("erreurs"));
    }
}
