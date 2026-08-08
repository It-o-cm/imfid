package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.intermarche.fidelity.domain.AppUser;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link UiSupport}, the shared admin-UI helpers (§21). The class holds
 * three static helpers with no clock, no Panache access and no boot: {@link UiSupport#canWrite}
 * (the doubled write-right guard, §21.4/§24.1), {@link UiSupport#redirect} (the POST → 303 →
 * notice pattern, §21.3) and {@link UiSupport#clampPage} (the page clamp, §31.3). The
 * {@code &&} guard of {@code canWrite} is covered on each leg — null security context, present
 * but role-less, present and role-bearing — and the clamp is exercised across every value
 * region of its inner {@code Math.max}/{@code Math.min}. No @QuarkusTest, no H2, no real clock
 * (§24.6).
 */
class UiSupportTest {

    /**
     * A neutral list path reused by the redirect assertions.
     */
    private static final String PATH = "/ui/cards";

    /**
     * Asserts that the exposed page size is the §31.3 per-screen constant of 25.
     */
    @Test
    @DisplayName("PAGE_SIZE is the fixed per-screen constant 25")
    void pageSizeConstant() {
        assertEquals(25, UiSupport.PAGE_SIZE);
    }

    /**
     * Covers the first leg of {@code canWrite}: a null security context short-circuits to false
     * without touching the role check.
     */
    @Test
    @DisplayName("canWrite() is false when the security context is null")
    void canWriteNullContext() {
        assertFalse(UiSupport.canWrite(null));
    }

    /**
     * Covers the second leg of {@code canWrite}: a present context whose user lacks the
     * {@code fid-admin} role yields false.
     */
    @Test
    @DisplayName("canWrite() is false when the user lacks the fid-admin role")
    void canWriteWithoutRole() {
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.isUserInRole(AppUser.ROLE_FID_ADMIN)).thenReturn(false);
        assertFalse(UiSupport.canWrite(securityContext));
    }

    /**
     * Covers both legs true: a present context whose user holds the {@code fid-admin} role yields
     * true (§24.1).
     */
    @Test
    @DisplayName("canWrite() is true when the user holds the fid-admin role")
    void canWriteWithRole() {
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.isUserInRole(AppUser.ROLE_FID_ADMIN)).thenReturn(true);
        assertTrue(UiSupport.canWrite(securityContext));
    }

    /**
     * Asserts the success redirect: a 303 See Other pointing at the given path and carrying the
     * one-shot {@code notice}/{@code noticeOk=true} query (§21.3).
     */
    @Test
    @DisplayName("redirect() builds a 303 See Other carrying an ok notice")
    void redirectOk() {
        Response response = UiSupport.redirect(PATH, "Saved", true);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        URI location = response.getLocation();
        assertEquals(PATH, location.getPath());
        assertEquals("notice=Saved&noticeOk=true", location.getQuery());
    }

    /**
     * Asserts the failure redirect: the same 303 shape but {@code noticeOk=false}, exercising the
     * other boolean value of the notice flag.
     */
    @Test
    @DisplayName("redirect() builds a 303 See Other carrying a non-ok notice")
    void redirectNotOk() {
        Response response = UiSupport.redirect(PATH, "Denied", false);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        URI location = response.getLocation();
        assertEquals(PATH, location.getPath());
        assertEquals("notice=Denied&noticeOk=false", location.getQuery());
    }

    /**
     * Covers {@code clampPage} where {@code pageCount} is below the floor: the count is raised to
     * 1 and any in-range page is pulled down to 1.
     */
    @Test
    @DisplayName("clampPage() raises a sub-one page count to a single page")
    void clampPageCountBelowFloor() {
        assertEquals(1, UiSupport.clampPage(5, 0));
    }

    /**
     * Covers {@code clampPage} where the page is below one: it is lifted to the first page while
     * {@code pageCount} stays at its real value.
     */
    @Test
    @DisplayName("clampPage() lifts a sub-one page to the first page")
    void clampPageBelowFloor() {
        assertEquals(1, UiSupport.clampPage(0, 8));
    }

    /**
     * Covers {@code clampPage} where the page sits within bounds: it is returned unchanged.
     */
    @Test
    @DisplayName("clampPage() returns an in-range page unchanged")
    void clampPageInRange() {
        assertEquals(4, UiSupport.clampPage(4, 8));
    }

    /**
     * Covers {@code clampPage} where the page overshoots the last page: it is pulled back to the
     * total page count.
     */
    @Test
    @DisplayName("clampPage() pulls an over-range page down to the last page")
    void clampPageAboveCeiling() {
        assertEquals(8, UiSupport.clampPage(99, 8));
    }
}
