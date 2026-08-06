package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.domain.AppUser;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriBuilder;

import java.net.URI;

/**
 * Small shared helpers for the admin UI resources (§21) — the write-right check doubled
 * server-side (§21.4, §24.1), the POST → 303 → notice redirect (§21.3), and the page/size
 * clamping (§31.3). Keeps the resources thin and the behaviour uniform across screens.
 */
public final class UiSupport {

    /**
     * The admin list page size (§31.3), a per-screen constant, never an URL parameter.
     */
    public static final int PAGE_SIZE = 25;

    /**
     * Non-instantiable helper holder.
     */
    private UiSupport() {
    }

    /**
     * Returns whether the signed-in user may write — holds the {@code fid-admin} role
     * (§24.1). The server enforces access through {@code @RolesAllowed}; this only hides
     * controls that would be rejected (§21.4).
     *
     * @param securityContext The request security context.
     * @return true when the user may write.
     */
    public static boolean canWrite(SecurityContext securityContext) {
        return securityContext != null && securityContext.isUserInRole(AppUser.ROLE_FID_ADMIN);
    }

    /**
     * Builds a 303 redirect to a list path carrying a one-shot notice (§21.3).
     *
     * @param path     The list path (e.g. "/ui/cards").
     * @param message  The notice message.
     * @param ok       Whether the notice reports a success.
     * @return The See Other response.
     */
    public static Response redirect(String path, String message, boolean ok) {
        URI uri = UriBuilder.fromPath(path)
                .queryParam("notice", message)
                .queryParam("noticeOk", ok)
                .build();
        return Response.seeOther(uri).build();
    }

    /**
     * Clamps a one-based page number into the real bounds (§31.3, guide §5.1).
     *
     * @param page      The requested page number.
     * @param pageCount The total number of pages (at least 1).
     * @return The clamped one-based page number.
     */
    public static int clampPage(int page, int pageCount) {
        int count = Math.max(1, pageCount);
        return Math.min(Math.max(page, 1), count);
    }
}
