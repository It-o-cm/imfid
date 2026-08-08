package com.intermarche.fidelity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intermarche.fidelity.domain.AppUser;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.security.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * Plain unit coverage for {@link PasswordChangeFilter}: the request filter that confines an
 * operator owing a password change to the change screen (§24.1). The surface is the {@code
 * enforced} short-circuit, the {@code isAllowed || !isBrowserNavigation} exemption guard and
 * each of its legs, the {@code securityContext} ternary, the {@code principal == null} guard,
 * the {@code user == null || !mustChangePassword} guard and each of its legs, plus the {@code
 * normalize}, {@code isAllowed} and {@code isBrowserNavigation} helpers exercised through the
 * public filter. Collaborators are mocked; no application boot, no clock, no Panache session —
 * the static finder {@link AppUser#findByUsername(String)} is stubbed with {@code mockStatic}.
 */
class PasswordChangeFilterTest {

    /**
     * Builds a filter with the {@code enforced} flag set, reaching the package-private field
     * directly from the same package.
     *
     * @param enforced Whether the redirect is enforced.
     * @return A ready-to-exercise filter.
     */
    private PasswordChangeFilter filter(boolean enforced) {
        PasswordChangeFilter created = new PasswordChangeFilter();
        created.enforced = enforced;
        return created;
    }

    /**
     * Builds a mocked request context reporting the given path, headers and security context.
     *
     * @param path            The path reported by the container, possibly {@code null}.
     * @param authorization   The {@code Authorization} header, possibly {@code null}.
     * @param accept          The {@code Accept} header, possibly {@code null}.
     * @param securityContext The security context, possibly {@code null}.
     * @return The stubbed request context.
     */
    private ContainerRequestContext context(String path, String authorization, String accept,
            SecurityContext securityContext) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn(path);
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(ctx.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(authorization);
        when(ctx.getHeaderString(HttpHeaders.ACCEPT)).thenReturn(accept);
        when(ctx.getSecurityContext()).thenReturn(securityContext);
        return ctx;
    }

    /**
     * Builds a security context whose user principal carries the given name.
     *
     * @param name The principal name, or {@code null} for an anonymous context.
     * @return The stubbed security context.
     */
    private SecurityContext securityContext(String name) {
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

    /**
     * Builds a user with the given pending-change flag.
     *
     * @param mustChange Whether the account still owes a password change.
     * @return The user instance.
     */
    private AppUser user(boolean mustChange) {
        AppUser appUser = new AppUser();
        appUser.mustChangePassword = mustChange;
        return appUser;
    }

    /**
     * {@code enforced} false arm: the filter returns before inspecting the request, so nothing
     * is aborted.
     */
    @Test
    @DisplayName("does nothing when enforcement is relaxed")
    void relaxedEnforcementSkips() {
        ContainerRequestContext ctx = context("/ui/dashboard", null, "text/html", securityContext("bob"));
        filter(false).filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    /**
     * First leg of the exemption guard through {@code isAllowed}'s equals leg: a path equal to
     * an allowed prefix is exempt and never redirected.
     */
    @Test
    @DisplayName("allows a path equal to an exempt prefix")
    void allowedByExactPrefix() {
        ContainerRequestContext ctx = context("/ui/password", null, "text/html", securityContext("bob"));
        filter(true).filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    /**
     * First leg of the exemption guard through {@code isAllowed}'s {@code startsWith} leg: a
     * path nested under an allowed prefix is exempt.
     */
    @Test
    @DisplayName("allows a path nested under an exempt prefix")
    void allowedByNestedPrefix() {
        ContainerRequestContext ctx = context("/ui/reset/token123", null, "text/html", securityContext("bob"));
        filter(true).filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    /**
     * Second leg of the exemption guard: a non-exempt path carried by a Basic-authenticated
     * machine client is not browser navigation, so it is never redirected. Covers both true
     * legs of the {@code isBrowserNavigation} authorization guard.
     */
    @Test
    @DisplayName("skips a Basic-authenticated machine call on a guarded path")
    void basicAuthIsNotBrowser() {
        ContainerRequestContext ctx = context("/ui/dashboard", "Basic dXNlcjpwYXNz", "text/html",
                securityContext("bob"));
        filter(true).filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    /**
     * Second leg of the exemption guard with the {@code isBrowserNavigation} authorization leg
     * A false ({@code null} header) and a {@code null} {@code Accept}: not browser navigation,
     * so no redirect.
     */
    @Test
    @DisplayName("skips a request with no Accept header")
    void missingAcceptIsNotBrowser() {
        ContainerRequestContext ctx = context("/ui/dashboard", null, null, securityContext("bob"));
        filter(true).filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    /**
     * Second leg of the exemption guard with the {@code isBrowserNavigation} authorization leg
     * B false (non-Basic scheme) and an {@code Accept} lacking {@code text/html}: not browser
     * navigation, so no redirect.
     */
    @Test
    @DisplayName("skips a Bearer JSON request on a guarded path")
    void bearerJsonIsNotBrowser() {
        ContainerRequestContext ctx = context("/ui/dashboard", "Bearer xyz", "application/json",
                securityContext("bob"));
        filter(true).filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    /**
     * First ternary arm ({@code securityContext == null}) leading to a {@code null} principal:
     * an unauthenticated browser navigation is left to the security layer, not redirected.
     */
    @Test
    @DisplayName("skips browser navigation with no security context")
    void nullSecurityContextSkips() {
        ContainerRequestContext ctx = context("/ui/dashboard", null, "text/html", null);
        filter(true).filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    /**
     * Second ternary arm (non-null security context) with a {@code null} user principal: the
     * {@code principal == null} guard returns, so no redirect.
     */
    @Test
    @DisplayName("skips browser navigation with an anonymous principal")
    void nullPrincipalSkips() {
        ContainerRequestContext ctx = context("/ui/dashboard", null, "text/html", securityContext(null));
        filter(true).filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    /**
     * First leg of the {@code user == null || !mustChangePassword} guard: an unknown user is
     * left alone. The static finder is stubbed to return {@code null}.
     */
    @Test
    @DisplayName("skips when the principal maps to no user")
    void unknownUserSkips() {
        ContainerRequestContext ctx = context("/ui/dashboard", null, "text/html", securityContext("ghost"));
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            users.when(() -> AppUser.findByUsername("ghost")).thenReturn(null);
            filter(true).filter(ctx);
        }
        verify(ctx, never()).abortWith(any());
    }

    /**
     * Second leg of the {@code user == null || !mustChangePassword} guard: a known user who
     * owes no change is left alone.
     */
    @Test
    @DisplayName("skips when the user owes no password change")
    void settledUserSkips() {
        ContainerRequestContext ctx = context("/ui/dashboard", null, "text/html", securityContext("bob"));
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            users.when(() -> AppUser.findByUsername("bob")).thenReturn(user(false));
            filter(true).filter(ctx);
        }
        verify(ctx, never()).abortWith(any());
    }

    /**
     * Both legs false: a known browser-navigating user who still owes a password change is
     * redirected to {@code /ui/password} with a 303 See Other.
     */
    @Test
    @DisplayName("redirects a pending user to the change screen")
    void pendingUserRedirected() {
        ContainerRequestContext ctx = context("/ui/dashboard", null, "text/html", securityContext("bob"));
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            users.when(() -> AppUser.findByUsername("bob")).thenReturn(user(true));
            filter(true).filter(ctx);
        }
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), captor.getValue().getStatus());
        assertEquals(URI.create("/ui/password"), captor.getValue().getLocation());
    }

    /**
     * {@code normalize} first leg ({@code path == null}): a {@code null} path collapses to
     * {@code "/"}, which is not exempt; the request then falls through the non-browser branch.
     */
    @Test
    @DisplayName("normalizes a null path to root")
    void nullPathNormalized() {
        ContainerRequestContext ctx = context(null, null, null, securityContext("bob"));
        filter(true).filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    /**
     * {@code normalize} second leg ({@code path.isEmpty()}): an empty path collapses to {@code
     * "/"}, which is not exempt; the request then falls through the non-browser branch.
     */
    @Test
    @DisplayName("normalizes an empty path to root")
    void emptyPathNormalized() {
        ContainerRequestContext ctx = context("", null, null, securityContext("bob"));
        filter(true).filter(ctx);
        verify(ctx, never()).abortWith(any());
    }

    /**
     * {@code normalize} else arm: a path reported without a leading slash gains one, so a
     * pending user on it is still redirected.
     */
    @Test
    @DisplayName("normalizes a slash-less path and still redirects")
    void slashlessPathRedirected() {
        ContainerRequestContext ctx = context("ui/dashboard", null, "text/html", securityContext("bob"));
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class)) {
            users.when(() -> AppUser.findByUsername("bob")).thenReturn(user(true));
            filter(true).filter(ctx);
        }
        verify(ctx).abortWith(any());
    }
}
