package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.security.PasswordResetService;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.util.Objects;

/**
 * The pre-authentication screens and the logout action (§24.1) — same pattern as
 * imvaluation. The login page is public and served standalone (it cannot use the
 * authenticated chrome); the form posts to {@code /j_security_check}, handled by the
 * Quarkus form authentication mechanism.
 * <p>
 * The "forgot password" flow is self-service by e-mail: a public request page sends a
 * single-use, time-boxed link (mocked to the log outside production), and a public
 * reset page consumes it. The request page never reveals whether an address exists.
 */
@Path("/ui")
@RunOnVirtualThread
public class AuthUiResource {

    /**
     * The name of the session cookie form authentication issues; cleared on logout.
     */
    private static final String SESSION_COOKIE = "quarkus-credential";

    /**
     * The password-reset flow service (§24.1).
     */
    @Inject
    PasswordResetService passwordReset;

    /**
     * The type-safe templates of this resource.
     */
    @CheckedTemplate
    static class Templates {

        /**
         * The login page template.
         *
         * @param error Whether to show the invalid-credentials alert.
         * @param reset Whether to confirm a just-completed password reset.
         * @return The login page instance.
         */
        static native TemplateInstance login(boolean error, boolean reset);

        /**
         * The "forgot password" request page template.
         *
         * @param sent Whether to show the neutral "link sent" confirmation.
         * @return The request page instance.
         */
        static native TemplateInstance forgot(boolean sent);

        /**
         * The "new password" page template, reached through the e-mailed link.
         *
         * @param token The raw reset token carried by the link.
         * @param error The error message to display, or null.
         * @return The reset page instance.
         */
        static native TemplateInstance reset(String token, String error);

        /**
         * The authenticated password-change page template.
         *
         * @param forced Whether the change is imposed ({@code mustChangePassword}), which
         *               hides the current-password field and the cancel link.
         * @param error  The error message to display, or null.
         * @return The password-change page instance.
         */
        static native TemplateInstance password(boolean forced, String error);
    }

    /**
     * Renders the login page (§24.1).
     *
     * @param error Present when the previous attempt failed.
     * @param reset Present when a password reset just completed.
     * @return The login page.
     */
    @GET
    @Path("/login")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance login(@QueryParam("error") String error, @QueryParam("reset") String reset) {
        return Templates.login(error != null, reset != null);
    }

    /**
     * Renders the public "forgot password" request page (§24.1).
     *
     * @param sent Present after a request was submitted, whatever the address.
     * @return The request page.
     */
    @GET
    @Path("/forgot")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance forgot(@QueryParam("sent") String sent) {
        return Templates.forgot(sent != null);
    }

    /**
     * Handles a "forgot password" request: mails a reset link when the address matches
     * an active account, silently does nothing otherwise, and always answers the same
     * neutral confirmation (no account enumeration, §24.1).
     *
     * @param email   The e-mail address typed on the form.
     * @param uriInfo The request URI info the link base URL is derived from.
     * @return A redirect to the neutral confirmation.
     */
    @POST
    @Path("/forgot")
    @PermitAll
    public Response requestReset(@FormParam("email") String email, @Context UriInfo uriInfo) {
        String baseUrl = Objects.toString(uriInfo.getBaseUri(), "");
        passwordReset.requestReset(email, baseUrl);
        return Response.seeOther(URI.create("/ui/forgot?sent=true")).build();
    }

    /**
     * Renders the public "new password" page reached through the e-mailed link (§24.1).
     *
     * @param token The raw reset token carried by the link.
     * @param error An error message from a previous attempt, or null.
     * @return The reset page.
     */
    @GET
    @Path("/reset")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance reset(@QueryParam("token") String token, @QueryParam("error") String error) {
        return Templates.reset(token == null ? "" : token, error);
    }

    /**
     * Consumes a reset link and sets the new password (§24.1); on success sends the
     * user to the login page with a confirmation, on failure back to the reset page
     * with the reason.
     *
     * @param token    The raw reset token.
     * @param password The new password.
     * @param confirm  The new password confirmation.
     * @return A redirect to the login page or back to the reset page.
     */
    @POST
    @Path("/reset")
    @PermitAll
    public Response doReset(@FormParam("token") String token, @FormParam("password") String password,
                            @FormParam("confirm") String confirm) {
        if (password == null || !password.equals(confirm)) {
            return redirectToReset(token, "Les deux saisies ne correspondent pas.");
        }
        String error = passwordReset.resetPassword(token, password);
        if (error != null) {
            return redirectToReset(token, error);
        }
        return Response.seeOther(URI.create("/ui/login?reset=true")).build();
    }

    /**
     * Renders the authenticated password-change page (§24.1): a voluntary change from the
     * header link, or a change imposed on an operator whose {@code mustChangePassword} flag
     * confines them here (set on creation or an administrator reset). The imposed case hides
     * the current-password field — the operator has just typed it at login — and the cancel
     * link.
     *
     * @param error An error message from a previous attempt, or null.
     * @param sc    The security context resolving the current account.
     * @return The password-change page.
     */
    @GET
    @Path("/password")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance password(@QueryParam("error") String error, @Context SecurityContext sc) {
        AppUser user = currentUser(sc);
        boolean forced = user != null && user.mustChangePassword;
        return Templates.password(forced, error);
    }

    /**
     * Applies an authenticated password change (§24.1): a forced change does not ask for
     * the current password (the operator just signed in with it), a voluntary one does.
     * The new password must satisfy the policy, match its confirmation and differ from the
     * current one. On success it clears {@code mustChangePassword} and the operator reaches
     * the admin UI; on failure it returns to the page with the reason.
     *
     * @param currentPassword The current password (ignored on a forced change).
     * @param newPassword     The new password.
     * @param confirmation    The new password confirmation.
     * @param sc              The security context resolving the current account.
     * @return A redirect to the admin UI, or back to the page with the error.
     */
    @POST
    @Path("/password")
    @Authenticated
    @Transactional
    public Response changePassword(@FormParam("currentPassword") String currentPassword,
                                   @FormParam("newPassword") String newPassword,
                                   @FormParam("confirmation") String confirmation,
                                   @Context SecurityContext sc) {
        AppUser user = currentUser(sc);
        if (user == null) {
            return Response.seeOther(URI.create("/ui/login")).build();
        }
        String error = validateChange(user, currentPassword, newPassword, confirmation);
        if (error != null) {
            return Response.seeOther(UriBuilder.fromPath("/ui/password").queryParam("error", error).build()).build();
        }
        user.setPassword(newPassword);
        user.mustChangePassword = false;
        user.persist();
        return UiSupport.redirect("/ui/cards", "Mot de passe mis à jour.", true);
    }

    /**
     * Signs the user out by clearing the session cookie and redirecting to the login page.
     *
     * @return A 303 See Other to the login page, clearing the session cookie.
     */
    @POST
    @Path("/logout")
    @Authenticated
    public Response logout() {
        NewCookie cleared = new NewCookie.Builder(SESSION_COOKIE)
                .path("/").maxAge(0).value("").build();
        return Response.seeOther(URI.create("/ui/login")).cookie(cleared).build();
    }

    /**
     * Builds the redirect back to the reset page, carrying the token and the encoded
     * error message.
     *
     * @param token The raw reset token, may be null.
     * @param error The error message to display.
     * @return The See Other response.
     */
    private Response redirectToReset(String token, String error) {
        URI uri = UriBuilder.fromPath("/ui/reset")
                .queryParam("token", token == null ? "" : token)
                .queryParam("error", error)
                .build();
        return Response.seeOther(uri).build();
    }

    /**
     * Validates a password change (§24.1): a voluntary change must present the correct
     * current password; the new password must satisfy the policy, match its confirmation
     * and differ from the current one.
     *
     * @param user            The current account.
     * @param currentPassword The current password (ignored on a forced change).
     * @param newPassword     The new password.
     * @param confirmation    The new password confirmation.
     * @return An error message when the change is refused, null otherwise.
     */
    private String validateChange(AppUser user, String currentPassword, String newPassword, String confirmation) {
        if (!user.mustChangePassword && !user.matchesPassword(currentPassword)) {
            return "Le mot de passe actuel est incorrect.";
        }
        String policyError = AppUser.validatePassword(newPassword);
        if (policyError != null) {
            return policyError;
        }
        if (!newPassword.equals(confirmation)) {
            return "Les deux saisies ne correspondent pas.";
        }
        if (user.matchesPassword(newPassword)) {
            return "Le nouveau mot de passe doit être différent de l'actuel.";
        }
        return null;
    }

    /**
     * Resolves the signed-in account from the security context, or null when
     * unauthenticated or unknown.
     *
     * @param sc The security context.
     * @return The current account, or null.
     */
    private AppUser currentUser(SecurityContext sc) {
        if (sc == null || sc.getUserPrincipal() == null) {
            return null;
        }
        return AppUser.findByUsername(sc.getUserPrincipal().getName());
    }
}
