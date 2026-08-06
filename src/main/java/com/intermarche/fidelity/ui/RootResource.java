package com.intermarche.fidelity.ui;

import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

import java.net.URI;

/**
 * Redirects the application root to the administration entry point — the Cards screen,
 * the most frequently used object (§21.2). An anonymous visitor is then sent to the login
 * page by form authentication; an existing session goes straight through.
 */
@Path("/")
@RunOnVirtualThread
public class RootResource {

    /**
     * Redirects to the card list.
     *
     * @return A 303 See Other pointing at the entry point.
     */
    @GET
    @PermitAll
    public Response root() {
        return Response.seeOther(URI.create("/ui/cards")).build();
    }
}
