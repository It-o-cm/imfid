package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.ws.rs.core.Response;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link RootResource}, the application-root redirect (§21.2). The class
 * carries no clock, no Panache access and no branch: {@link RootResource#root()} unconditionally
 * emits a 303 See Other pointing at the Cards screen. The single behaviour is asserted on both
 * the status code and the {@code Location} header. No boot, no H2, no static finder and no real
 * clock (§24.6).
 */
class RootResourceTest {

    /**
     * Asserts that {@link RootResource#root()} returns a 303 See Other whose {@code Location}
     * header points at {@code /ui/cards}, the administration entry point (§21.2).
     */
    @Test
    @DisplayName("root() redirects to the card list with a 303 See Other")
    void rootRedirectsToCardList() {
        RootResource resource = new RootResource();
        Response response = resource.root();
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals(URI.create("/ui/cards"), response.getLocation());
    }
}
