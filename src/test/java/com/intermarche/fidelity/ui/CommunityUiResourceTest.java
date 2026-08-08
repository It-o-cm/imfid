package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.admin.AdminService;
import com.intermarche.fidelity.admin.MembershipInput;
import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.util.ProgramClock;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Plain unit coverage for {@link CommunityUiResource}: the community list, the direct-editing
 * membership workbench, the creation and edition forms, the create/update/setActive parameter
 * mutations and the workbench membership submission, together with the shared
 * {@code parseDecimal}/{@code parseInteger}/{@code buildMembersJson} helpers (§23.2, §31.2).
 * Every collaborator is mocked: the {@link AdminService} with Mockito, the {@link ProgramClock}
 * as a fixed clock so no test ever reads the real wall time (§24.6), and the Panache static
 * finders — {@link FidelityCommunity#listAllByCode()} / {@link FidelityCommunity#findByCode(String)}
 * and the inherited {@code count}/{@code list} of {@link PanacheEntityBase} — with
 * {@code mockStatic} in try-with-resources.
 * <p>
 * The {@code static native} Qute templates of the nested {@code Templates} class have no
 * instrumentable body, so under a plain unit run the {@code list}, {@code workbench},
 * {@code form} and {@code edit} render methods evaluate every guard and ternary argument fully
 * (that computation carries the branches) and then reach the native boundary, which raises an
 * {@link UnsatisfiedLinkError}; each render test drives one arm and asserts that boundary. The
 * 404 arm of {@code workbench}, the redirect arm of {@code edit} and every POST mutation return
 * a real {@link Response} that is asserted directly. Each guard is covered on both arms and, for
 * compound guards, on each leg (§29, §29.6): the empty and non-empty community loop, the
 * {@code account == null} both arms of {@code buildMembersJson}, the {@code notice == null}
 * ternary of {@code workbench}, the {@code value == null || value.isBlank()} legs plus the
 * {@code new BigDecimal}/{@code Integer.valueOf} success and failure of {@code parseDecimal} and
 * {@code parseInteger}, the {@code active} ternary of {@code setActive}, the
 * {@code members == null || members.isBlank()} legs of {@code memberships} and its try /
 * {@code AdminException} / general {@code Exception} arms.
 */
class CommunityUiResourceTest {

    /**
     * A fixed program date driving the active-member count — the campaign never reads the clock.
     */
    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 8, 15);

    /**
     * The system under test, freshly built per test with its mocked collaborators.
     */
    private CommunityUiResource resource;

    /**
     * The mocked mutation service.
     */
    private AdminService admin;

    /**
     * The mocked program clock, fixed to {@link #FIXED_TODAY}.
     */
    private ProgramClock clock;

    /**
     * Wires a fresh resource with its mocked collaborators and a fixed clock.
     */
    @BeforeEach
    void setUp() {
        resource = new CommunityUiResource();
        admin = mock(AdminService.class);
        clock = mock(ProgramClock.class);
        resource.admin = admin;
        resource.clock = clock;
        when(clock.today()).thenReturn(FIXED_TODAY);
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
     * Builds a community with the given code and populated parameter fields.
     *
     * @param code The community code.
     * @return The community.
     */
    private FidelityCommunity community(String code) {
        FidelityCommunity community = new FidelityCommunity();
        community.code = code;
        community.label = "Label " + code;
        community.monthlyCap = new BigDecimal("15.00");
        community.enrollmentCap = 200;
        community.renewalStartMonth = 1;
        community.renewalEndMonth = 12;
        community.eligibilityCriteria = "criteria";
        community.active = true;
        return community;
    }

    // --------------------------------------------------
    // list
    // --------------------------------------------------

    /**
     * {@code list}: an empty catalog skips the loop body and the render reaches the native
     * boundary through a zero-row {@link ListView}.
     */
    @Test
    @DisplayName("list: empty catalog reaches the native boundary")
    void listEmpty() {
        try (MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class)) {
            communities.when(FidelityCommunity::listAllByCode).thenReturn(List.of());
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list(null, false, null));
        }
    }

    /**
     * {@code list}: a one-community catalog executes the loop body (membership count and
     * {@code CommunityRow.of}) and reaches the native boundary.
     */
    @Test
    @DisplayName("list: one community counts members and renders")
    void listOneRow() {
        FidelityCommunity community = community("COM1");
        try (MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            communities.when(FidelityCommunity::listAllByCode).thenReturn(List.of(community));
            panache.when(() -> PanacheEntityBase.count(anyString(), any(), any())).thenReturn(7L);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list("hi", true, context(true)));
            panache.verify(() -> PanacheEntityBase.count(anyString(), eq(community), eq(FIXED_TODAY)));
        }
    }

    // --------------------------------------------------
    // workbench
    // --------------------------------------------------

    /**
     * {@code workbench}: the {@code community == null} arm returns a 404 without touching the
     * membership feed.
     */
    @Test
    @DisplayName("workbench: unknown community returns 404")
    void workbenchUnknown() {
        try (MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class)) {
            communities.when(() -> FidelityCommunity.findByCode("nope")).thenReturn(null);
            Response response = resource.workbench("nope", null, false, context(true));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
            assertEquals("Unknown community", response.getEntity());
        }
    }

    /**
     * {@code workbench}: a known community with a null {@code notice} takes the {@code ""} arm of
     * the notice ternary; a membership whose account is null takes the {@code account == null}
     * arm of {@code buildMembersJson}, and the render reaches the native boundary.
     */
    @Test
    @DisplayName("workbench: known community, null notice and null-account member")
    void workbenchKnownNullNotice() {
        FidelityCommunity community = community("COM1");
        FidelityMembership membership = new FidelityMembership();
        membership.account = null;
        membership.validFrom = LocalDate.of(2026, 1, 1);
        membership.validTo = null;
        try (MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            communities.when(() -> FidelityCommunity.findByCode("COM1")).thenReturn(community);
            panache.when(() -> PanacheEntityBase.list(anyString(), (Object) any())).thenReturn(List.of(membership));
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.workbench("COM1", null, false, null));
        }
    }

    /**
     * {@code workbench}: a known community with a non-null {@code notice} takes the {@code notice}
     * arm of the ternary; a membership with a non-null account takes the {@code account != null}
     * arm of {@code buildMembersJson}, and the render reaches the native boundary.
     */
    @Test
    @DisplayName("workbench: known community, set notice and carded member")
    void workbenchKnownSetNotice() {
        FidelityCommunity community = community("COM1");
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = "C1";
        FidelityMembership membership = new FidelityMembership();
        membership.account = account;
        membership.validFrom = LocalDate.of(2026, 2, 1);
        membership.validTo = LocalDate.of(2026, 12, 31);
        try (MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            communities.when(() -> FidelityCommunity.findByCode("COM1")).thenReturn(community);
            panache.when(() -> PanacheEntityBase.list(anyString(), (Object) any())).thenReturn(List.of(membership));
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.workbench("COM1", "saved", true, context(true)));
        }
    }

    // --------------------------------------------------
    // form (creation)
    // --------------------------------------------------

    /**
     * {@code form}: the creation view is assembled and the render reaches the native boundary.
     */
    @Test
    @DisplayName("form: creation view reaches the native boundary")
    void formCreation() {
        assertThrows(UnsatisfiedLinkError.class,
                () -> resource.form("hi", true, context(true)));
    }

    // --------------------------------------------------
    // create
    // --------------------------------------------------

    /**
     * {@code create}: a successful creation redirects to the new community sheet; valid decimal
     * and integer fields drive the {@code new BigDecimal}/{@code Integer.valueOf} success legs of
     * both parsers.
     */
    @Test
    @DisplayName("create: success parses valid fields and redirects to the sheet")
    void createSuccess() {
        Response response = resource.create(" COM1 ", "Label", "12.50", "100", "3", "9", "crit");
        verify(admin).createCommunity(eq(" COM1 "), eq("Label"), eq(new BigDecimal("12.50")),
                eq(100), eq(3), eq(9), eq("crit"));
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/communities/COM1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Community+COM1+created"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code create}: an {@link AdminException} redirects to the creation form; a blank decimal
     * takes the {@code isBlank} leg of {@code parseDecimal}, a null integer the null leg, a
     * malformed integer the {@code NumberFormatException} leg and a blank integer the
     * {@code isBlank} leg, all yielding null values.
     */
    @Test
    @DisplayName("create: refusal parses blank/null/malformed fields and redirects to the form")
    void createFailure() {
        when(admin.createCommunity(eq("COM1"), eq("Label"), isNull(), isNull(), isNull(), isNull(),
                eq("crit"))).thenThrow(new AdminException("duplicate"));
        Response response = resource.create("COM1", "Label", "  ", null, "xx", "  ", "crit");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/communities/new", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=duplicate"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // edit
    // --------------------------------------------------

    /**
     * {@code edit}: the {@code community == null} arm redirects to the list with a failure notice.
     */
    @Test
    @DisplayName("edit: unknown community redirects to the list")
    void editUnknown() {
        try (MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class)) {
            communities.when(() -> FidelityCommunity.findByCode("nope")).thenReturn(null);
            Response response = resource.edit("nope", null, false, context(true));
            assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
            assertEquals("/ui/communities", response.getLocation().getPath());
            assertTrue(decodedQuery(response).contains("notice=Unknown community 'nope'"));
            assertTrue(decodedQuery(response).contains("noticeOk=false"));
        }
    }

    /**
     * {@code edit}: the {@code community != null} arm assembles the edition view and the render
     * reaches the native boundary.
     */
    @Test
    @DisplayName("edit: known community renders the edition form")
    void editKnown() {
        FidelityCommunity community = community("COM1");
        try (MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class)) {
            communities.when(() -> FidelityCommunity.findByCode("COM1")).thenReturn(community);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.edit("COM1", "hi", true, context(true)));
        }
    }

    // --------------------------------------------------
    // update
    // --------------------------------------------------

    /**
     * {@code update}: a successful update redirects to the community sheet; a null monthly cap
     * takes the null leg of {@code parseDecimal} while valid integers drive the
     * {@code Integer.valueOf} success leg.
     */
    @Test
    @DisplayName("update: success parses a null cap and redirects to the sheet")
    void updateSuccess() {
        Response response = resource.update("COM1", "Label", null, "150", "2", "8", "crit");
        verify(admin).updateCommunity("COM1", "Label", null, 150, 2, 8, "crit");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/communities/COM1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Community+COM1+updated"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code update}: an {@link AdminException} redirects to the edition form; a malformed monthly
     * cap takes the {@code NumberFormatException} leg of {@code parseDecimal} yielding a null cap.
     */
    @Test
    @DisplayName("update: refusal parses a malformed cap and redirects to the form")
    void updateFailure() {
        when(admin.updateCommunity(eq("COM1"), eq("Label"), isNull(), eq(5), eq(1), eq(12),
                eq("crit"))).thenThrow(new AdminException("unknown"));
        Response response = resource.update("COM1", "Label", "oops", "5", "1", "12", "crit");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/communities/COM1/edit", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=unknown"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // setActive
    // --------------------------------------------------

    /**
     * {@code setActive}: reopening ({@code active == true}) takes the reopened arm of the notice
     * ternary and redirects with a success notice.
     */
    @Test
    @DisplayName("setActive: reopen takes the reopened arm")
    void setActiveReopen() {
        Response response = resource.setActive("COM1", true);
        verify(admin).setCommunityActive("COM1", true);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/communities/COM1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Community+COM1+reopened+to+enrollments"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code setActive}: closing ({@code active == false}) takes the closed arm of the notice
     * ternary and redirects with a success notice.
     */
    @Test
    @DisplayName("setActive: close takes the closed arm")
    void setActiveClose() {
        Response response = resource.setActive("COM1", false);
        verify(admin).setCommunityActive("COM1", false);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/communities/COM1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Community+COM1+closed+to+new+enrollments"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code setActive}: an {@link AdminException} redirects to the community sheet with a failure
     * notice.
     */
    @Test
    @DisplayName("setActive: refusal redirects with a failure notice")
    void setActiveFailure() {
        when(admin.setCommunityActive("COM1", true)).thenThrow(new AdminException("unknown"));
        Response response = resource.setActive("COM1", true);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/communities/COM1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=unknown"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // memberships
    // --------------------------------------------------

    /**
     * {@code memberships}: a null payload takes the first leg of the {@code members == null ||
     * members.isBlank()} ternary, parses to an empty list and redirects with the saved count.
     */
    @Test
    @DisplayName("memberships: null payload takes the null leg and saves zero")
    void membershipsNull() {
        when(admin.replaceMemberships(eq("COM1"), any())).thenReturn(0);
        Response response = resource.memberships("COM1", null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/communities/COM1", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains("notice=0 membership(s) saved"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code memberships}: a blank payload takes the second {@code isBlank} leg of the ternary,
     * parses to an empty list and redirects with the saved count.
     */
    @Test
    @DisplayName("memberships: blank payload takes the isBlank leg and saves zero")
    void membershipsBlank() {
        when(admin.replaceMemberships(eq("COM1"), any())).thenReturn(0);
        Response response = resource.memberships("COM1", "   ");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/communities/COM1", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains("notice=0 membership(s) saved"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code memberships}: a non-blank JSON payload takes the false arm of both ternary legs, is
     * parsed to a membership entry and redirects with the saved count.
     */
    @Test
    @DisplayName("memberships: JSON payload is parsed and saved")
    void membershipsJson() {
        when(admin.replaceMemberships(eq("COM1"), any())).thenReturn(2);
        Response response = resource.memberships("COM1",
                "[{\"card\":\"C1\",\"validFrom\":\"2026-01-01\",\"validTo\":null}]");
        verify(admin).replaceMemberships(eq("COM1"), any());
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/communities/COM1", response.getLocation().getPath());
        assertTrue(decodedQuery(response).contains("notice=2 membership(s) saved"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code memberships}: an {@link AdminException} from the service takes the first catch arm and
     * redirects with the refusal message.
     */
    @Test
    @DisplayName("memberships: AdminException redirects with the refusal message")
    void membershipsAdminException() {
        when(admin.replaceMemberships(eq("COM1"), any())).thenThrow(new AdminException("closed"));
        Response response = resource.memberships("COM1", "[]");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/communities/COM1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=closed"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    /**
     * {@code memberships}: a malformed JSON payload makes {@code readValue} throw, taking the
     * general {@code Exception} catch arm and redirecting with an "Invalid submission" notice.
     */
    @Test
    @DisplayName("memberships: malformed JSON takes the general catch arm")
    void membershipsInvalidJson() {
        Response response = resource.memberships("COM1", "{not json");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/communities/COM1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Invalid+submission"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }
}
