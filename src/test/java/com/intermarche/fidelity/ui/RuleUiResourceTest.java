package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.admin.AdminService;
import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.util.ProgramClock;
import com.intermarche.fidelity.rule.EarnRuleRegistry;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Plain unit coverage for {@link RuleUiResource}: the schema-driven rule administration screen
 * (§23.3) — the filtered list with its state badges, the creation form (blank or prefilled from a
 * duplicated source), the read-only consultation sheet, the in-place edition form restricted to a
 * rule not yet in force (§18), and the create/update/end-date/duplicate parameter mutations,
 * together with the private {@code buildSchemasJson}/{@code buildCommunitiesJson} builders and the
 * {@code parseDateTime}/{@code parseDecimal} parsers (§31.2).
 * <p>
 * Every collaborator is mocked: the {@link AdminService} and the {@link EarnRuleRegistry} with
 * Mockito, the {@link ProgramClock} as a fixed clock so no test ever reads the real wall time
 * (§24.6), and the Panache static finders — {@link FidelityRule#findByCode(String)},
 * {@link FidelityCommunity#listAllByCode()} and the inherited {@code count}/{@code find} of
 * {@link PanacheEntityBase} — with {@code mockStatic} in try-with-resources.
 * <p>
 * The {@code static native} Qute templates of the nested {@code Templates} class have no
 * instrumentable body, so under a plain unit run the {@code list}, {@code form}, {@code detail} and
 * {@code edit} render methods evaluate every guard and ternary argument fully (that computation
 * carries the branches) and then reach the native boundary, which raises an
 * {@link UnsatisfiedLinkError}; each render test drives one arm and asserts that boundary. The
 * redirect arms of {@code detail}/{@code edit} and every POST mutation return a real
 * {@link Response} asserted directly. Each guard is covered on both arms and, for compound guards,
 * on each leg (§29, §29.6): the sort whitelist and {@code desc} ternaries, the three
 * {@code code}/{@code type} filter legs of {@code list}, the {@code from} and {@code src} guards of
 * {@code form}, the {@code rule == null} and {@code !now.isBefore(validFrom)} guards of
 * {@code edit} straddled by the two instants around {@code validFrom}, the {@code validTo == null ||
 * isBlank} legs of {@code endDate}, the {@code !first} legs (and the empty loop) of both JSON
 * builders, and the null / blank / valid / malformed legs of {@code parseDateTime} (both the
 * length-10 date and the full date-time arms) and {@code parseDecimal}.
 */
class RuleUiResourceTest {

    /**
     * A fixed program instant driving the rule state and the in-force test — the campaign never
     * reads the real clock.
     */
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 9, 12, 0);

    /**
     * The system under test, freshly built per test with its mocked collaborators.
     */
    private RuleUiResource resource;

    /**
     * The mocked mutation service.
     */
    private AdminService admin;

    /**
     * The mocked schema registry.
     */
    private EarnRuleRegistry registry;

    /**
     * The mocked program clock, fixed to {@link #FIXED_NOW}.
     */
    private ProgramClock clock;

    /**
     * Wires a fresh resource with its mocked collaborators and a fixed clock.
     */
    @BeforeEach
    void setUp() {
        resource = new RuleUiResource();
        admin = mock(AdminService.class);
        registry = mock(EarnRuleRegistry.class);
        clock = mock(ProgramClock.class);
        resource.admin = admin;
        resource.registry = registry;
        resource.clock = clock;
        when(clock.now()).thenReturn(FIXED_NOW);
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

    /**
     * Stubs the registry with two schemas (covering the {@code !first} legs of the schema builder)
     * and one registered type.
     */
    private void stubRegistryWithTwoSchemas() {
        Map<String, String> schemas = new LinkedHashMap<>();
        schemas.put("COMMUNITY_EARN", "{\"type\":\"object\"}");
        schemas.put("MONTHLY_DATE_EARN", "{\"type\":\"object\"}");
        when(registry.allSchemas()).thenReturn(schemas);
        when(registry.registeredTypes()).thenReturn(Set.of("COMMUNITY_EARN", "MONTHLY_DATE_EARN"));
    }

    /**
     * Stubs the registry with an empty schema map (covering the empty-loop path of the schema
     * builder) and an empty type set.
     */
    private void stubRegistryEmpty() {
        when(registry.allSchemas()).thenReturn(new LinkedHashMap<>());
        when(registry.registeredTypes()).thenReturn(Set.of());
    }

    /**
     * Builds a community with the given code, for the community JSON builder.
     *
     * @param code The community code.
     * @return The community.
     */
    private FidelityCommunity community(String code) {
        FidelityCommunity community = new FidelityCommunity();
        community.code = code;
        return community;
    }

    /**
     * Builds a fully populated rule with the given code and window start.
     *
     * @param code      The rule code.
     * @param validFrom The window start.
     * @return The rule.
     */
    private FidelityRule rule(String code, LocalDateTime validFrom) {
        FidelityRule rule = new FidelityRule();
        rule.code = code;
        rule.type = "COMMUNITY_EARN";
        rule.label = "Label " + code;
        rule.validFrom = validFrom;
        rule.validTo = null;
        rule.priority = 5;
        rule.exclusive = false;
        rule.monthlyCapPerCard = new BigDecimal("15.00");
        rule.active = true;
        rule.specification = "{\"rate\":5}";
        return rule;
    }

    /**
     * Builds a mocked Panache query returning the given list from its paged terminal.
     *
     * @param rules The rules the query yields.
     * @return The mocked query.
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<FidelityRule> mockedQuery(List<FidelityRule> rules) {
        PanacheQuery<FidelityRule> query = mock(PanacheQuery.class);
        when(query.page(anyInt(), anyInt())).thenReturn(query);
        when(query.list()).thenReturn(rules);
        return query;
    }

    // --------------------------------------------------
    // list
    // --------------------------------------------------

    /**
     * {@code list}: a whitelisted sort key keeps the requested column, {@code dir="DESC"} takes the
     * descending ternary arm, a non-blank code and a non-blank type drive both legs of both filter
     * guards true (WHERE predicate and {@code filters.put}), and a one-row page executes the loop
     * body ({@code RuleRow.of}); the render reaches the native boundary.
     */
    @Test
    @DisplayName("list: valid sort, desc, both filters, one row")
    void listValidSortDescFiltersOneRow() {
        PanacheQuery<FidelityRule> query = mockedQuery(List.of(rule("R1", FIXED_NOW.minusDays(1))));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Map.class))).thenReturn(30L);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Map.class))).thenReturn(query);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list("AB", "COMMUNITY_EARN", "priority", "DESC", 1, "hi", true, context(true)));
        }
    }

    /**
     * {@code list}: an unknown sort key falls back to {@code code}, {@code dir="asc"} takes the
     * ascending ternary arm, a null code and a null type take the first (null) leg of both filter
     * guards and leave both {@code filters.put} unset, and an empty page skips the loop body.
     */
    @Test
    @DisplayName("list: unknown sort, asc, null filters, empty page")
    void listUnknownSortAscNullFiltersEmpty() {
        PanacheQuery<FidelityRule> query = mockedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Map.class))).thenReturn(0L);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Map.class))).thenReturn(query);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list(null, null, "bogus", "asc", 1, null, false, null));
        }
    }

    /**
     * {@code list}: a blank code takes the {@code !code.isBlank()} second leg false (no WHERE
     * predicate) while still setting the code {@code filters.put} (the string is non-null), and a
     * blank type takes the {@code !type.isBlank()} second leg false while still setting the type
     * {@code filters.put}.
     */
    @Test
    @DisplayName("list: blank code and blank type take the isBlank legs")
    void listBlankCodeAndType() {
        PanacheQuery<FidelityRule> query = mockedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Map.class))).thenReturn(0L);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Map.class))).thenReturn(query);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list("   ", "   ", "code", "asc", 1, null, false, context(false)));
        }
    }

    // --------------------------------------------------
    // form (creation)
    // --------------------------------------------------

    /**
     * {@code form}: a null {@code from} takes the first (null) leg of the duplicate guard, so no
     * source is looked up; empty schema and community collections drive the empty-loop path of both
     * JSON builders and the render reaches the native boundary.
     */
    @Test
    @DisplayName("form: null from, empty schemas and communities")
    void formNullFrom() {
        stubRegistryEmpty();
        try (MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class)) {
            communities.when(FidelityCommunity::listAllByCode).thenReturn(List.of());
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.form(null, null, false, context(true)));
        }
    }

    /**
     * {@code form}: a blank {@code from} takes the {@code !from.isBlank()} second leg false, so no
     * source is looked up either.
     */
    @Test
    @DisplayName("form: blank from takes the isBlank leg")
    void formBlankFrom() {
        stubRegistryEmpty();
        try (MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class)) {
            communities.when(FidelityCommunity::listAllByCode).thenReturn(List.of());
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.form("   ", null, false, context(true)));
        }
    }

    /**
     * {@code form}: a non-blank {@code from} whose source is unknown takes the {@code src == null}
     * arm, leaving the blank creation view unprefilled.
     */
    @Test
    @DisplayName("form: non-blank from, unknown source takes the null arm")
    void formFromUnknownSource() {
        stubRegistryEmpty();
        try (MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class);
                MockedStatic<FidelityRule> rules = mockStatic(FidelityRule.class)) {
            communities.when(FidelityCommunity::listAllByCode).thenReturn(List.of());
            rules.when(() -> FidelityRule.findByCode("SRC")).thenReturn(null);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.form(" SRC ", null, false, context(true)));
        }
    }

    /**
     * {@code form}: a non-blank {@code from} whose source exists takes the {@code src != null} arm
     * and prefills the code/type/specification; two schemas and two communities drive the
     * {@code !first} legs (both first and subsequent iterations) of both JSON builders.
     */
    @Test
    @DisplayName("form: non-blank from, known source prefills and covers both JSON builders")
    void formFromKnownSource() {
        stubRegistryWithTwoSchemas();
        try (MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class);
                MockedStatic<FidelityRule> rules = mockStatic(FidelityRule.class)) {
            communities.when(FidelityCommunity::listAllByCode)
                    .thenReturn(List.of(community("C1"), community("C2")));
            rules.when(() -> FidelityRule.findByCode("SRC")).thenReturn(rule("SRC", FIXED_NOW.minusDays(1)));
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.form("SRC", "hi", true, context(true)));
        }
    }

    // --------------------------------------------------
    // detail
    // --------------------------------------------------

    /**
     * {@code detail}: the {@code rule == null} arm redirects to the list with a failure notice.
     */
    @Test
    @DisplayName("detail: unknown rule redirects to the list")
    void detailUnknown() {
        try (MockedStatic<FidelityRule> rules = mockStatic(FidelityRule.class)) {
            rules.when(() -> FidelityRule.findByCode("nope")).thenReturn(null);
            Response response = resource.detail("nope", null, false, context(true));
            assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
            assertEquals("/ui/rules", response.getLocation().getPath());
            assertTrue(decodedQuery(response).contains("notice=Unknown rule 'nope'"));
            assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
        }
    }

    /**
     * {@code detail}: the {@code rule != null} arm assembles the read-only consultation view and the
     * render reaches the native boundary.
     */
    @Test
    @DisplayName("detail: known rule renders the consultation sheet")
    void detailKnown() {
        stubRegistryWithTwoSchemas();
        try (MockedStatic<FidelityRule> rules = mockStatic(FidelityRule.class);
                MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class)) {
            rules.when(() -> FidelityRule.findByCode("R1")).thenReturn(rule("R1", FIXED_NOW.minusDays(1)));
            communities.when(FidelityCommunity::listAllByCode)
                    .thenReturn(List.of(community("C1"), community("C2")));
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.detail("R1", "hi", true, context(true)));
        }
    }

    // --------------------------------------------------
    // edit
    // --------------------------------------------------

    /**
     * {@code edit}: the {@code rule == null} arm redirects to the list with a failure notice.
     */
    @Test
    @DisplayName("edit: unknown rule redirects to the list")
    void editUnknown() {
        try (MockedStatic<FidelityRule> rules = mockStatic(FidelityRule.class)) {
            rules.when(() -> FidelityRule.findByCode("nope")).thenReturn(null);
            Response response = resource.edit("nope", null, false, context(true));
            assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
            assertEquals("/ui/rules", response.getLocation().getPath());
            assertTrue(decodedQuery(response).contains("notice=Unknown rule 'nope'"));
            assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
        }
    }

    /**
     * {@code edit}: a rule whose {@code validFrom} equals the fixed instant is already in force —
     * {@code now.isBefore(validFrom)} is false so {@code !now.isBefore(...)} is true — and the
     * edition is refused with a redirect to the sheet. This is the on-the-border instant of the
     * window start.
     */
    @Test
    @DisplayName("edit: rule in force at the window start redirects to the sheet")
    void editInForceAtBorder() {
        try (MockedStatic<FidelityRule> rules = mockStatic(FidelityRule.class)) {
            rules.when(() -> FidelityRule.findByCode("R1")).thenReturn(rule("R1", FIXED_NOW));
            Response response = resource.edit("R1", null, false, context(true));
            assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
            assertEquals("/ui/rules/R1", response.getLocation().getPath());
            assertTrue(decodedQuery(response).contains(
                    "notice=Only a rule not yet in force can be edited; duplicate then close instead (§18)"));
            assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
        }
    }

    /**
     * {@code edit}: a rule whose {@code validFrom} is one minute after the fixed instant is not yet
     * in force — {@code now.isBefore(validFrom)} is true so {@code !now.isBefore(...)} is false —
     * and the edition view is assembled, the render reaching the native boundary. This is the
     * instant just before the window start.
     */
    @Test
    @DisplayName("edit: upcoming rule just before the window start renders the edition form")
    void editUpcomingBeforeBorder() {
        stubRegistryWithTwoSchemas();
        try (MockedStatic<FidelityRule> rules = mockStatic(FidelityRule.class);
                MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class)) {
            rules.when(() -> FidelityRule.findByCode("R1")).thenReturn(rule("R1", FIXED_NOW.plusMinutes(1)));
            communities.when(FidelityCommunity::listAllByCode)
                    .thenReturn(List.of(community("C1"), community("C2")));
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.edit("R1", "hi", true, context(true)));
        }
    }

    // --------------------------------------------------
    // create
    // --------------------------------------------------

    /**
     * {@code create}: a successful creation redirects to the list; a length-10 {@code validFrom}
     * takes the date-only arm of {@code parseDateTime}, a full {@code validTo} takes the date-time
     * arm, and a valid cap drives the {@code new BigDecimal} success of {@code parseDecimal}.
     */
    @Test
    @DisplayName("create: success parses date-only and date-time bounds and a valid cap")
    void createSuccess() {
        Response response = resource.create("R1", "COMMUNITY_EARN", "Label", "2026-08-10",
                "2026-08-11T12:30", 5, true, "5.00", true, "{}", null, null);
        verify(admin).createRule(eq("R1"), eq("COMMUNITY_EARN"), eq("Label"),
                eq(LocalDateTime.of(2026, 8, 10, 0, 0)), eq(LocalDateTime.of(2026, 8, 11, 12, 30)),
                eq(5), eq(true), eq(new BigDecimal("5.00")), eq(true), eq("{}"), isNull(), isNull());
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/rules", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Rule+R1+created"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code create}: an {@link AdminException} redirects to the creation form; a null
     * {@code validFrom} takes the null leg of {@code parseDateTime}, a blank {@code validTo} takes
     * its {@code isBlank} leg, and a malformed cap takes the {@code NumberFormatException} leg of
     * {@code parseDecimal}, all yielding null values.
     */
    @Test
    @DisplayName("create: refusal parses null/blank bounds and a malformed cap")
    void createFailure() {
        when(admin.createRule(eq("R1"), eq("COMMUNITY_EARN"), eq("Label"), isNull(), isNull(),
                eq(0), eq(false), isNull(), eq(false), eq("{}"), isNull(), isNull())).thenThrow(new AdminException("duplicate"));
        Response response = resource.create("R1", "COMMUNITY_EARN", "Label", null, "   ",
                0, false, "xx", false, "{}", null, null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/rules/new", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=duplicate"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // update
    // --------------------------------------------------

    /**
     * {@code update}: a successful update redirects to the rule sheet; a malformed {@code validFrom}
     * takes the {@code catch} arm of {@code parseDateTime} (yielding null), and a null cap takes the
     * null leg of {@code parseDecimal}.
     */
    @Test
    @DisplayName("update: success parses a malformed date and a null cap")
    void updateSuccess() {
        Response response = resource.update("R1", "COMMUNITY_EARN", "Label", "not-a-date",
                null, 3, false, null, true, "{}", null, null);
        verify(admin).updateRule(eq("R1"), eq("COMMUNITY_EARN"), eq("Label"), isNull(), isNull(),
                eq(3), eq(false), isNull(), eq(true), eq("{}"), isNull(), isNull());
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/rules/R1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Rule+R1+updated"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code update}: an {@link AdminException} redirects to the edition form; a blank cap takes the
     * {@code isBlank} leg of {@code parseDecimal} yielding a null cap.
     */
    @Test
    @DisplayName("update: refusal parses a blank cap and redirects to the form")
    void updateFailure() {
        when(admin.updateRule(eq("R1"), eq("COMMUNITY_EARN"), eq("Label"),
                eq(LocalDateTime.of(2026, 8, 10, 0, 0)), isNull(), eq(0), eq(false), isNull(),
                eq(false), eq("{}"), isNull(), isNull())).thenThrow(new AdminException("in force"));
        Response response = resource.update("R1", "COMMUNITY_EARN", "Label", "2026-08-10",
                null, 0, false, "   ", false, "{}", null, null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/rules/R1/edit", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=in+force"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // endDate
    // --------------------------------------------------

    /**
     * {@code endDate}: a null {@code validTo} takes the first (null) leg of the message ternary,
     * clearing the end of application, and redirects to the sheet with a success notice.
     */
    @Test
    @DisplayName("endDate: null validTo clears the end of application")
    void endDateClearedNull() {
        Response response = resource.endDate("R1", null);
        verify(admin).updateEndDate(eq("R1"), isNull());
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/rules/R1", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains("notice=Rule R1: end of application cleared (open-ended)"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code endDate}: a blank {@code validTo} takes the {@code isBlank} second leg of the message
     * ternary, also clearing the end of application.
     */
    @Test
    @DisplayName("endDate: blank validTo takes the isBlank leg")
    void endDateClearedBlank() {
        Response response = resource.endDate("R1", "   ");
        verify(admin).updateEndDate(eq("R1"), isNull());
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/rules/R1", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains("notice=Rule R1: end of application cleared (open-ended)"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code endDate}: a non-blank {@code validTo} takes the false arm of both ternary legs, is
     * parsed to a date-time and sets the end of application with a success notice.
     */
    @Test
    @DisplayName("endDate: non-blank validTo sets the end of application")
    void endDateSet() {
        Response response = resource.endDate("R1", "2026-09-01T00:00");
        verify(admin).updateEndDate(eq("R1"), eq(LocalDateTime.of(2026, 9, 1, 0, 0)));
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/rules/R1", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains(
                "notice=Rule R1: end of application set to 2026-09-01T00:00"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code endDate}: an {@link AdminException} redirects to the sheet with the refusal message.
     */
    @Test
    @DisplayName("endDate: refusal redirects with the refusal message")
    void endDateFailure() {
        when(admin.updateEndDate(eq("R1"), any())).thenThrow(new AdminException("window ended"));
        Response response = resource.endDate("R1", "2026-09-01T00:00");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/rules/R1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=window+ended"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // duplicate
    // --------------------------------------------------

    /**
     * {@code duplicate}: a successful duplication redirects to the list with a success notice.
     */
    @Test
    @DisplayName("duplicate: success redirects to the list")
    void duplicateSuccess() {
        Response response = resource.duplicate("R1", "R2");
        verify(admin).duplicateRule("R1", "R2");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/rules", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Rule+R1+duplicated+to+R2"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code duplicate}: an {@link AdminException} redirects to the list with the refusal message.
     */
    @Test
    @DisplayName("duplicate: refusal redirects with the refusal message")
    void duplicateFailure() {
        when(admin.duplicateRule("R1", "R2")).thenThrow(new AdminException("code taken"));
        Response response = resource.duplicate("R1", "R2");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/rules", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=code+taken"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }
}
