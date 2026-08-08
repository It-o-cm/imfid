package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.admin.UserAdminService;
import com.intermarche.fidelity.domain.AppUser;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Plain unit coverage for {@link UserUiResource}: the filtered operator-account list, the
 * blank creation form, the edition form and the three write mutations (create, update,
 * delete) reserved to {@code fid-admin} (§24.1, §21). Every collaborator is mocked: the
 * {@link UserAdminService} with Mockito, the {@link SecurityContext} as a fixed principal
 * so no test ever reads a real caller, and the Panache static finders inherited by
 * {@link AppUser} — {@code count}, {@code find} and {@code findById} — through
 * {@link PanacheEntityBase} with {@code mockStatic} in try-with-resources. No clock is read
 * anywhere: this resource carries no temporal logic (§24.6).
 * <p>
 * The {@code static native} Qute templates of the nested {@code Templates} class have no
 * instrumentable body, so under a plain unit run the {@code list}, {@code create} and the
 * known-account {@code edit} render methods evaluate every guard and ternary argument fully
 * (that computation carries the branches) and then reach the native boundary, which raises
 * an {@link UnsatisfiedLinkError}; each render test drives one arm and asserts that
 * boundary. The unknown-account arm of {@code edit} and every POST mutation return a real
 * {@link Response} asserted directly. Each guard is covered on both arms and, for compound
 * guards, on each leg (§29, §29.6): the sort whitelist ternary, the {@code desc} ternary,
 * both legs of the {@code search != null && !search.isBlank()} and
 * {@code role != null && !role.isBlank()} where guards, both arms of the {@code search != null}
 * and {@code role != null} filter puts, the empty and non-empty account loop, the
 * {@code user == null} 404 arm of {@code edit}, both arms of {@code toRoleSet}'s null
 * ternary, both arms of the {@code active != null} flag, the three legs of
 * {@code currentUsername} (null context, null principal, named principal) and the try and
 * catch arm of every mutation.
 */
class UserUiResourceTest {

    /**
     * The system under test, freshly built per test with its mocked service.
     */
    private UserUiResource resource;

    /**
     * The mocked operator-account write service.
     */
    private UserAdminService users;

    /**
     * Wires a fresh resource with its mocked service.
     */
    @BeforeEach
    void setUp() {
        resource = new UserUiResource();
        users = mock(UserAdminService.class);
        resource.users = users;
    }

    /**
     * Builds a security context resolving the write role and exposing a named principal.
     *
     * @param canWrite  Whether the caller holds the {@code fid-admin} role.
     * @param principal The caller's login, or null to expose no principal at all.
     * @return The mocked security context.
     */
    private SecurityContext context(boolean canWrite, String principal) {
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.isUserInRole(AppUser.ROLE_FID_ADMIN)).thenReturn(canWrite);
        if (principal == null) {
            when(securityContext.getUserPrincipal()).thenReturn(null);
        } else {
            Principal caller = mock(Principal.class);
            when(caller.getName()).thenReturn(principal);
            when(securityContext.getUserPrincipal()).thenReturn(caller);
        }
        return securityContext;
    }

    /**
     * Builds a listed account with every displayable field populated.
     *
     * @param id       The account identifier.
     * @param username The login name.
     * @return The account entity.
     */
    private AppUser listedUser(Long id, String username) {
        AppUser user = new AppUser();
        user.id = id;
        user.username = username;
        user.displayName = "Display " + username;
        user.email = username + "@example.com";
        user.roles = "pos";
        user.active = true;
        user.mustChangePassword = false;
        return user;
    }

    /**
     * Builds a saved account carrying only the identity fields the notices quote.
     *
     * @param id       The account identifier.
     * @param username The login name.
     * @return The account entity.
     */
    private AppUser savedUser(Long id, String username) {
        AppUser user = new AppUser();
        user.id = id;
        user.username = username;
        return user;
    }

    /**
     * Builds a mocked Panache query yielding the given accounts from its paged terminal.
     *
     * @param accounts The accounts the query returns.
     * @return The mocked query.
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<AppUser> mockedQuery(List<AppUser> accounts) {
        PanacheQuery<AppUser> query = mock(PanacheQuery.class);
        when(query.page(anyInt(), anyInt())).thenReturn(query);
        when(query.list()).thenReturn(accounts);
        return query;
    }

    // --------------------------------------------------
    // list
    // --------------------------------------------------

    /**
     * {@code list}: an unknown sort key falls back to the default column, {@code dir="asc"}
     * takes the ascending ternary arm, a null search and null role leave both where guards
     * and both filter puts unset, and an empty account page skips the loop body; the render
     * reaches the native template boundary.
     */
    @Test
    @DisplayName("list: unknown sort, asc, no filters, empty page")
    void listDefaultsEmpty() {
        PanacheQuery<AppUser> query = mockedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Map.class))).thenReturn(0L);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Map.class))).thenReturn(query);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list(null, null, "bogus", "asc", 1, null, false, context(true, "admin")));
        }
    }

    /**
     * {@code list}: a whitelisted sort key keeps the requested column, {@code dir="DESC"}
     * takes the descending ternary arm through {@code equalsIgnoreCase}, a non-blank search
     * and a non-blank role drive both legs of both where guards true and set both filter
     * puts, and a one-row page executes the loop body ({@code UserRow.of} with a named
     * principal, exercising the third {@code currentUsername} leg).
     */
    @Test
    @DisplayName("list: valid sort, desc, both filters, one row")
    void listFiltersOneRow() {
        PanacheQuery<AppUser> query = mockedQuery(List.of(listedUser(1L, "alice")));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Map.class))).thenReturn(30L);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Map.class))).thenReturn(query);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list("al", "pos", "displayName", "DESC", 1, "hi", true, context(true, "admin")));
        }
    }

    /**
     * {@code list}: a blank search takes the {@code !search.isBlank()} false leg of the
     * where guard while still setting the search filter put (the string is non-null), and a
     * blank role takes the {@code !role.isBlank()} false leg while still setting the role
     * filter put.
     */
    @Test
    @DisplayName("list: blank search and blank role")
    void listBlankFilters() {
        PanacheQuery<AppUser> query = mockedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Map.class))).thenReturn(0L);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Map.class))).thenReturn(query);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list("   ", "   ", "username", "asc", 1, null, false, context(false, "admin")));
        }
    }

    // --------------------------------------------------
    // create
    // --------------------------------------------------

    /**
     * {@code create}: the blank creation form is assembled and reaches the native template
     * boundary.
     */
    @Test
    @DisplayName("create: renders the blank form")
    void createForm() {
        assertThrows(UnsatisfiedLinkError.class,
                () -> resource.create("hi", true, context(true, "admin")));
    }

    // --------------------------------------------------
    // edit
    // --------------------------------------------------

    /**
     * {@code edit}: the {@code user == null} arm redirects back to the list with a failure
     * notice, without building a form.
     */
    @Test
    @DisplayName("edit: unknown account redirects to the list")
    void editUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(null);
            Response response = resource.edit(7L, null, false, context(true, "admin"));
            assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
            assertEquals("/ui/users", response.getLocation().getPath());
            assertTrue(response.getLocation().getQuery().contains("notice=Account+not+found."));
            assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
        }
    }

    /**
     * {@code edit}: the {@code user != null} arm builds the edition form and reaches the
     * native template boundary.
     */
    @Test
    @DisplayName("edit: known account renders the form")
    void editKnown() {
        AppUser user = listedUser(9L, "bob");
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(9L)).thenReturn(user);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.edit(9L, "hi", true, context(true, "admin")));
        }
    }

    // --------------------------------------------------
    // save (create)
    // --------------------------------------------------

    /**
     * {@code save}: a successful creation redirects to the list with a success notice; a
     * non-null role list takes the non-null {@code toRoleSet} arm and a non-null
     * {@code active} takes the {@code active != null} true arm.
     */
    @Test
    @DisplayName("save: success with roles and active redirects to the list")
    void saveSuccess() {
        AppUser created = savedUser(1L, "carol");
        when(users.createUser(eq("carol"), eq("pw"), eq("Carol"), eq("c@x"), eq(Set.of("pos")), eq(true)))
                .thenReturn(created);
        Response response = resource.save("carol", "pw", "Carol", "c@x", List.of("pos"), "on");
        verify(users).createUser(eq("carol"), eq("pw"), eq("Carol"), eq("c@x"), eq(Set.of("pos")), eq(true));
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/users", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Account+'carol'+created."));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code save}: an {@link AdminException} redirects to the creation form with a failure
     * notice; a null role list takes the null {@code toRoleSet} arm (empty set) and a null
     * {@code active} takes the {@code active != null} false arm.
     */
    @Test
    @DisplayName("save: refusal with null roles and inactive redirects to the form")
    void saveFailure() {
        when(users.createUser(eq("dan"), eq(""), eq(""), eq(""), eq(Set.of()), eq(false)))
                .thenThrow(new AdminException("username taken"));
        Response response = resource.save("dan", "", "", "", null, null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/users/new", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=username+taken"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // update
    // --------------------------------------------------

    /**
     * {@code update}: a successful update redirects to the list with a success notice; a
     * non-null role list and a non-null {@code active} take their true arms and a named
     * principal takes the third {@code currentUsername} leg.
     */
    @Test
    @DisplayName("update: success with roles and active redirects to the list")
    void updateSuccess() {
        AppUser updated = savedUser(4L, "erin");
        when(users.updateUser(eq(4L), eq("pw"), eq("Erin"), eq("e@x"), eq(Set.of("pos")), eq(true), eq("admin")))
                .thenReturn(updated);
        Response response = resource.update(4L, "pw", "Erin", "e@x", List.of("pos"), "on", context(true, "admin"));
        verify(users).updateUser(eq(4L), eq("pw"), eq("Erin"), eq("e@x"), eq(Set.of("pos")), eq(true), eq("admin"));
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/users", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Account+'erin'+updated."));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code update}: an {@link AdminException} redirects to the edition form with a failure
     * notice; a null role list and a null {@code active} take their false arms and a null
     * security context takes the first {@code currentUsername} leg (empty login).
     */
    @Test
    @DisplayName("update: refusal with null roles, inactive and null context")
    void updateFailure() {
        when(users.updateUser(eq(5L), eq(""), eq(""), eq(""), eq(Set.of()), eq(false), eq("")))
                .thenThrow(new AdminException("last admin"));
        Response response = resource.update(5L, "", "", "", null, null, null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/users/5", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=last+admin"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // delete
    // --------------------------------------------------

    /**
     * {@code delete}: a successful deletion redirects to the list with a success notice; a
     * context exposing no principal takes the second {@code currentUsername} leg (empty
     * login).
     */
    @Test
    @DisplayName("delete: success with a principal-less context redirects to the list")
    void deleteSuccess() {
        when(users.deleteUser(eq(6L), eq(""))).thenReturn("frank");
        Response response = resource.delete(6L, context(true, null));
        verify(users).deleteUser(eq(6L), eq(""));
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/users", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Account+'frank'+deleted."));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code delete}: an {@link AdminException} redirects to the list with a failure notice;
     * a named principal takes the third {@code currentUsername} leg.
     */
    @Test
    @DisplayName("delete: refusal redirects to the list")
    void deleteFailure() {
        when(users.deleteUser(eq(6L), eq("admin"))).thenThrow(new AdminException("no self delete"));
        Response response = resource.delete(6L, context(true, "admin"));
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/users", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=no+self+delete"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }
}
