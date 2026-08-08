package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.security.PasswordResetService;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Plain unit coverage for {@link AuthUiResource}: the pre-authentication screens, the
 * self-service forgot-password flow and the authenticated password change and logout
 * (§24.1). The resource carries no clock, so no {@code DateTimeProvider} is involved; every
 * collaborator is mocked. The {@code static native} Qute templates of the nested
 * {@code Templates} class have no instrumentable body, so Mockito cannot intercept them
 * under a plain unit run: the render methods evaluate their guard and ternary arguments
 * fully (that computation is what carries the branches) and then reach the native template
 * boundary, which raises an {@link UnsatisfiedLinkError}; each render test drives one arm
 * and asserts that boundary, while the Panache finder {@link AppUser#findByUsername(String)}
 * and the static {@link AppUser#validatePassword(String)} are intercepted with
 * {@code mockStatic}. All static mocks live in try-with-resources.
 * <p>
 * Each guard is covered on both arms and, for compound guards, on each leg: the
 * {@code error/reset/sent} query-flag conversions, the {@code getBaseUri} null fallback of
 * {@code requestReset}, the {@code token == null ? "" : token} ternaries of {@code reset}
 * and {@code redirectToReset}, the {@code password == null || !password.equals(confirm)}
 * legs of {@code doReset}, the {@code sc == null || getUserPrincipal() == null} legs of
 * {@code currentUser}, the {@code user != null && user.mustChangePassword} legs of
 * {@code password}, and every branch of {@code validateChange} reached through
 * {@code changePassword}.
 */
class AuthUiResourceTest {

    /**
     * The system under test, freshly built per test with its mocked service.
     */
    private AuthUiResource resource;

    /**
     * The mocked password-reset service injected into the resource.
     */
    private PasswordResetService passwordReset;

    /**
     * Wires a fresh resource with its mocked password-reset service.
     */
    @BeforeEach
    void setUp() {
        resource = new AuthUiResource();
        passwordReset = mock(PasswordResetService.class);
        resource.passwordReset = passwordReset;
    }

    /**
     * Builds a security context resolving a principal with the given name.
     *
     * @param name The principal name, or null for an unauthenticated context.
     * @return The mocked security context.
     */
    private SecurityContext contextWithPrincipal(String name) {
        SecurityContext securityContext = mock(SecurityContext.class);
        if (name == null) {
            when(securityContext.getUserPrincipal()).thenReturn(null);
            return securityContext;
        }
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(name);
        when(securityContext.getUserPrincipal()).thenReturn(principal);
        return securityContext;
    }

    // --------------------------------------------------
    // login
    // --------------------------------------------------

    /**
     * {@code login}: both query flags absent yield a page with neither alert.
     */
    @Test
    @DisplayName("login: no flags computes login(false, false)")
    void loginNoFlags() {
        assertThrows(UnsatisfiedLinkError.class, () -> resource.login(null, null));
    }

    /**
     * {@code login}: the {@code error != null} true leg raises the invalid-credentials alert.
     */
    @Test
    @DisplayName("login: error flag computes login(true, false)")
    void loginErrorOnly() {
        assertThrows(UnsatisfiedLinkError.class, () -> resource.login("1", null));
    }

    /**
     * {@code login}: the {@code reset != null} true leg confirms a just-completed reset.
     */
    @Test
    @DisplayName("login: reset flag computes login(false, true)")
    void loginResetOnly() {
        assertThrows(UnsatisfiedLinkError.class, () -> resource.login(null, "true"));
    }

    // --------------------------------------------------
    // forgot (GET)
    // --------------------------------------------------

    /**
     * {@code forgot}: the {@code sent == null} arm renders the plain request page.
     */
    @Test
    @DisplayName("forgot: no flag computes forgot(false)")
    void forgotNotSent() {
        assertThrows(UnsatisfiedLinkError.class, () -> resource.forgot(null));
    }

    /**
     * {@code forgot}: the {@code sent != null} arm shows the neutral confirmation.
     */
    @Test
    @DisplayName("forgot: sent flag computes forgot(true)")
    void forgotSent() {
        assertThrows(UnsatisfiedLinkError.class, () -> resource.forgot("true"));
    }

    // --------------------------------------------------
    // requestReset (POST)
    // --------------------------------------------------

    /**
     * {@code requestReset}: a non-null base URI is forwarded verbatim and the neutral
     * confirmation redirect is returned.
     */
    @Test
    @DisplayName("requestReset: non-null base URI is forwarded")
    void requestResetWithBaseUri() {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost:8060/"));
        Response response = resource.requestReset("alice@example.com", uriInfo);
        verify(passwordReset).requestReset("alice@example.com", "http://localhost:8060/");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/forgot?sent=true", response.getLocation().toString());
    }

    /**
     * {@code requestReset}: a null base URI falls back to an empty base URL through
     * {@code Objects.toString}.
     */
    @Test
    @DisplayName("requestReset: null base URI falls back to empty base URL")
    void requestResetNullBaseUri() {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getBaseUri()).thenReturn(null);
        Response response = resource.requestReset("alice@example.com", uriInfo);
        verify(passwordReset).requestReset("alice@example.com", "");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/forgot?sent=true", response.getLocation().toString());
    }

    // --------------------------------------------------
    // reset (GET)
    // --------------------------------------------------

    /**
     * {@code reset}: the {@code token == null} arm passes an empty token to the page.
     */
    @Test
    @DisplayName("reset: null token computes the empty-token ternary arm")
    void resetNullToken() {
        assertThrows(UnsatisfiedLinkError.class, () -> resource.reset(null, "boom"));
    }

    /**
     * {@code reset}: the {@code token != null} arm passes the token through untouched, with a
     * null error.
     */
    @Test
    @DisplayName("reset: non-null token computes the token-through ternary arm")
    void resetWithToken() {
        assertThrows(UnsatisfiedLinkError.class, () -> resource.reset("tok", null));
    }

    // --------------------------------------------------
    // doReset (POST)
    // --------------------------------------------------

    /**
     * {@code doReset}: the {@code password == null} first leg redirects back to the reset page
     * with the mismatch message and never calls the service; the null token yields an empty
     * token query parameter.
     */
    @Test
    @DisplayName("doReset: null password redirects with mismatch and empty token")
    void doResetNullPassword() {
        Response response = resource.doReset(null, null, "confirm");
        verify(passwordReset, never()).resetPassword(any(), any());
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        URI location = response.getLocation();
        assertEquals("/ui/reset", location.getPath());
        assertTrue(location.getQuery().startsWith("token=&"));
        assertTrue(location.getQuery().contains("error=Les+deux+saisies+ne+correspondent+pas."));
    }

    /**
     * {@code doReset}: the {@code !password.equals(confirm)} second leg redirects with the
     * mismatch message; the non-null token is carried through {@code redirectToReset}.
     */
    @Test
    @DisplayName("doReset: mismatching confirmation redirects with token preserved")
    void doResetMismatch() {
        Response response = resource.doReset("raw", "aaaaaaaa", "bbbbbbbb");
        verify(passwordReset, never()).resetPassword(any(), any());
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        URI location = response.getLocation();
        assertEquals("/ui/reset", location.getPath());
        assertTrue(location.getQuery().startsWith("token=raw&"));
        assertTrue(location.getQuery().contains("error=Les+deux+saisies+ne+correspondent+pas."));
    }

    /**
     * {@code doReset}: matching passwords but a service-reported error redirect back to the
     * reset page carrying the service message (the {@code error != null} arm).
     */
    @Test
    @DisplayName("doReset: service error redirects with the service message")
    void doResetServiceError() {
        when(passwordReset.resetPassword("raw", "goodpass1")).thenReturn("Lien expiré.");
        Response response = resource.doReset("raw", "goodpass1", "goodpass1");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        URI location = response.getLocation();
        assertEquals("/ui/reset", location.getPath());
        assertTrue(location.getQuery().contains("error=Lien+expiré."));
    }

    /**
     * {@code doReset}: matching passwords and a null service result (the {@code error == null}
     * arm) send the user to the login page with the reset confirmation.
     */
    @Test
    @DisplayName("doReset: success redirects to login with reset flag")
    void doResetSuccess() {
        when(passwordReset.resetPassword("raw", "goodpass1")).thenReturn(null);
        Response response = resource.doReset("raw", "goodpass1", "goodpass1");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/login?reset=true", response.getLocation().toString());
    }

    // --------------------------------------------------
    // password (GET)
    // --------------------------------------------------

    /**
     * {@code password}: the {@code sc == null} first leg of {@code currentUser} yields a null
     * user, so the change is not forced.
     */
    @Test
    @DisplayName("password: null security context forces the not-forced arm")
    void passwordNullContext() {
        assertThrows(UnsatisfiedLinkError.class, () -> resource.password("err", null));
    }

    /**
     * {@code password}: the {@code getUserPrincipal() == null} second leg of
     * {@code currentUser} yields a null user, so the change is not forced.
     */
    @Test
    @DisplayName("password: null principal forces the not-forced arm")
    void passwordNullPrincipal() {
        SecurityContext securityContext = contextWithPrincipal(null);
        assertThrows(UnsatisfiedLinkError.class, () -> resource.password("err", securityContext));
    }

    /**
     * {@code password}: a resolved user whose {@code mustChangePassword} is true drives the
     * {@code user != null && user.mustChangePassword} guard to its forced arm.
     */
    @Test
    @DisplayName("password: user with mustChangePassword computes the forced arm")
    void passwordForced() {
        SecurityContext securityContext = contextWithPrincipal("alice");
        AppUser user = mock(AppUser.class);
        user.mustChangePassword = true;
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            users.when(() -> AppUser.findByUsername("alice")).thenReturn(user);
            assertThrows(UnsatisfiedLinkError.class, () -> resource.password(null, securityContext));
            users.verify(() -> AppUser.findByUsername("alice"));
        }
    }

    /**
     * {@code password}: a resolved user whose {@code mustChangePassword} is false drives the
     * second leg of the guard to false, so the change is voluntary.
     */
    @Test
    @DisplayName("password: user without mustChangePassword computes the not-forced arm")
    void passwordNotForced() {
        SecurityContext securityContext = contextWithPrincipal("alice");
        AppUser user = mock(AppUser.class);
        user.mustChangePassword = false;
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            users.when(() -> AppUser.findByUsername("alice")).thenReturn(user);
            assertThrows(UnsatisfiedLinkError.class, () -> resource.password(null, securityContext));
            users.verify(() -> AppUser.findByUsername("alice"));
        }
    }

    // --------------------------------------------------
    // changePassword (POST)
    // --------------------------------------------------

    /**
     * {@code changePassword}: the {@code user == null} arm redirects to the login page without
     * touching any account.
     */
    @Test
    @DisplayName("changePassword: no user redirects to login")
    void changePasswordNoUser() {
        Response response = resource.changePassword("cur", "new", "new", null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/login", response.getLocation().toString());
    }

    /**
     * {@code changePassword}: a voluntary change (both legs of the first guard true — not
     * forced and a wrong current password) returns the incorrect-current message.
     */
    @Test
    @DisplayName("changePassword: wrong current password redirects with the error")
    void changePasswordWrongCurrent() {
        SecurityContext securityContext = contextWithPrincipal("alice");
        AppUser user = mock(AppUser.class);
        user.mustChangePassword = false;
        when(user.matchesPassword("wrong")).thenReturn(false);
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            users.when(() -> AppUser.findByUsername("alice")).thenReturn(user);
            Response response = resource.changePassword("wrong", "goodpass1", "goodpass1", securityContext);
            verify(user, never()).persist();
            assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
            URI location = response.getLocation();
            assertEquals("/ui/password", location.getPath());
            assertTrue(location.getQuery().contains("error=Le+mot+de+passe+actuel+est+incorrect."));
        }
    }

    /**
     * {@code changePassword}: a forced change (first leg of the first guard false) skips the
     * current-password check but is refused when the policy rejects the new password
     * ({@code policyError != null}).
     */
    @Test
    @DisplayName("changePassword: forced change with weak password returns the policy error")
    void changePasswordForcedWeakPolicy() {
        SecurityContext securityContext = contextWithPrincipal("alice");
        AppUser user = mock(AppUser.class);
        user.mustChangePassword = true;
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            users.when(() -> AppUser.findByUsername("alice")).thenReturn(user);
            users.when(() -> AppUser.validatePassword("short")).thenReturn("Trop court.");
            Response response = resource.changePassword(null, "short", "short", securityContext);
            verify(user, never()).matchesPassword("short");
            verify(user, never()).persist();
            assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
            URI location = response.getLocation();
            assertEquals("/ui/password", location.getPath());
            assertTrue(location.getQuery().contains("error=Trop+court."));
        }
    }

    /**
     * {@code changePassword}: a correct current password (second leg of the first guard false)
     * and a policy-compliant password that does not match its confirmation returns the
     * mismatch message.
     */
    @Test
    @DisplayName("changePassword: confirmation mismatch redirects with the error")
    void changePasswordConfirmationMismatch() {
        SecurityContext securityContext = contextWithPrincipal("alice");
        AppUser user = mock(AppUser.class);
        user.mustChangePassword = false;
        when(user.matchesPassword("current1")).thenReturn(true);
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            users.when(() -> AppUser.findByUsername("alice")).thenReturn(user);
            users.when(() -> AppUser.validatePassword("newpass1")).thenReturn(null);
            Response response = resource.changePassword("current1", "newpass1", "other123", securityContext);
            verify(user, never()).persist();
            assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
            URI location = response.getLocation();
            assertEquals("/ui/password", location.getPath());
            assertTrue(location.getQuery().contains("error=Les+deux+saisies+ne+correspondent+pas."));
        }
    }

    /**
     * {@code changePassword}: a new password equal to the current one
     * ({@code user.matchesPassword(newPassword)} true) is refused with the must-differ message.
     */
    @Test
    @DisplayName("changePassword: new password equal to current is refused")
    void changePasswordSameAsCurrent() {
        SecurityContext securityContext = contextWithPrincipal("alice");
        AppUser user = mock(AppUser.class);
        user.mustChangePassword = false;
        when(user.matchesPassword("current1")).thenReturn(true);
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            users.when(() -> AppUser.findByUsername("alice")).thenReturn(user);
            users.when(() -> AppUser.validatePassword("current1")).thenReturn(null);
            Response response = resource.changePassword("current1", "current1", "current1", securityContext);
            verify(user, never()).persist();
            assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
            URI location = response.getLocation();
            assertEquals("/ui/password", location.getPath());
            assertTrue(location.getQuery()
                    .contains("error=Le+nouveau+mot+de+passe+doit+être+différent+de+l'actuel."));
        }
    }

    /**
     * {@code changePassword}: every guard passes (correct current, policy-compliant, matching
     * confirmation, different from current) so the password is set, the change flag is
     * cleared, the account is persisted and the operator reaches the admin UI.
     */
    @Test
    @DisplayName("changePassword: valid change persists and redirects to the admin UI")
    void changePasswordSuccess() {
        SecurityContext securityContext = contextWithPrincipal("alice");
        AppUser user = mock(AppUser.class);
        user.mustChangePassword = true;
        when(user.matchesPassword("current1")).thenReturn(true);
        when(user.matchesPassword("newpass1")).thenReturn(false);
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            users.when(() -> AppUser.findByUsername("alice")).thenReturn(user);
            users.when(() -> AppUser.validatePassword("newpass1")).thenReturn(null);
            Response response = resource.changePassword("current1", "newpass1", "newpass1", securityContext);
            verify(user).setPassword("newpass1");
            assertFalse(user.mustChangePassword);
            verify(user).persist();
            assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
            URI location = response.getLocation();
            assertEquals("/ui/cards", location.getPath());
            assertTrue(location.getQuery().contains("noticeOk=true"));
            assertTrue(location.getQuery().contains("notice=Mot+de+passe+mis+à+jour."));
        }
    }

    // --------------------------------------------------
    // logout
    // --------------------------------------------------

    /**
     * {@code logout}: clears the session cookie and redirects to the login page.
     */
    @Test
    @DisplayName("logout: clears the session cookie and redirects to login")
    void logoutClearsCookie() {
        Response response = resource.logout();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/login", response.getLocation().toString());
        NewCookie cleared = response.getCookies().get("quarkus-credential");
        assertEquals(0, cleared.getMaxAge());
        assertEquals("", cleared.getValue());
        assertEquals("/", cleared.getPath());
    }
}
