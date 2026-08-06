package com.intermarche.fidelity.ui;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * The pre-authentication login screen and the logout action (§24.1) — same pattern as
 * imvaluation. The login page is public and served standalone (it cannot use the
 * authenticated chrome); the form posts to {@code /j_security_check}, handled by the
 * Quarkus form authentication mechanism.
 */
@Path("/ui")
@RunOnVirtualThread
public class AuthUiResource {

    /**
     * The name of the session cookie form authentication issues; cleared on logout.
     */
    private static final String SESSION_COOKIE = "quarkus-credential";

    /**
     * The type-safe templates of this resource.
     */
    @CheckedTemplate
    static class Templates {

        /**
         * The login page template.
         *
         * @param error Whether to show the invalid-credentials alert.
         * @return The login page instance.
         */
        static native TemplateInstance login(boolean error);
    }

    /**
     * Renders the login page (§24.1).
     *
     * @param error Present when the previous attempt failed.
     * @return The login page.
     */
    @GET
    @Path("/login")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance login(@QueryParam("error") String error) {
        return Templates.login(error != null);
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
}
