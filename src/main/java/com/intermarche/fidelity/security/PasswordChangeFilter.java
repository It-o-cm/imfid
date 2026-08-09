package com.intermarche.fidelity.security;

import com.intermarche.fidelity.domain.AppUser;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.security.Principal;
import java.util.List;

/**
 * Confines an operator who still owes a password change to the change screen (§24.1) —
 * ported from imvaluation. When an administrator creates an account or resets its
 * password, {@code mustChangePassword} is set; until the operator chooses their own
 * password on {@code /ui/password}, every other browser page is redirected there.
 * <p>
 * Only browser navigation is intercepted: a machine client authenticating with HTTP Basic
 * (the POS API, GraphQL, CSV imports) or any request not asking for HTML is never
 * redirected, so the API surface is untouched. The redirect is relaxed in dev and test
 * through {@code imfid.password-change.enforced}, so the seeded accounts stay directly
 * usable while developing.
 */
@Provider
public class PasswordChangeFilter implements ContainerRequestFilter {

    /**
     * The paths reachable while a password change is pending: the change screen itself,
     * the logout and login endpoints, the form-auth callback, and the public self-service
     * reset pages.
     */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "/ui/password", "/ui/logout", "/ui/login", "/j_security_check", "/ui/forgot", "/ui/reset");

    /**
     * Whether the redirect is enforced; relaxed in dev and test.
     */
    @ConfigProperty(name = "imfid.password-change.enforced", defaultValue = "true")
    boolean enforced;

    /**
     * Redirects a pending browser navigation to the password-change screen (§24.1).
     *
     * @param requestContext The incoming request context.
     */
    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!enforced) {
            return;
        }
        String path = normalize(requestContext.getUriInfo().getPath());
        if (isAllowed(path) || !isBrowserNavigation(requestContext)) {
            return;
        }
        SecurityContext securityContext = requestContext.getSecurityContext();
        Principal principal = securityContext == null ? null : securityContext.getUserPrincipal();
        if (principal == null) {
            return;
        }
        AppUser user = AppUser.findByUsername(principal.getName());
        if (user == null || !user.mustChangePassword) {
            return;
        }
        requestContext.abortWith(Response.seeOther(URI.create("/ui/password")).build());
    }

    /**
     * Normalizes a request path to a single leading slash so it compares against the
     * allowed prefixes regardless of how the container reports it.
     *
     * @param path The request path, may lack a leading slash.
     * @return The path with exactly one leading slash.
     */
    private String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * Indicates whether a path is reachable while a password change is pending.
     *
     * @param path The normalized request path.
     * @return true when the path is exempt from the redirect.
     */
    private boolean isAllowed(String path) {
        for (String prefix : ALLOWED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Indicates whether the request is browser navigation rather than a machine call: an
     * HTTP Basic credential or a non-HTML {@code Accept} marks an API client, which is
     * never redirected.
     *
     * @param requestContext The incoming request context.
     * @return true when the request is a browser navigating to an HTML page.
     */
    private boolean isBrowserNavigation(ContainerRequestContext requestContext) {
        String authorization = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Basic", 0, 5)) {
            return false;
        }
        String accept = requestContext.getHeaderString(HttpHeaders.ACCEPT);
        return accept != null && accept.contains("text/html");
    }
}
