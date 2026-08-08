package com.intermarche.fidelity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.intermarche.fidelity.domain.AppUser;
import io.quarkus.security.identity.SecurityIdentity;
import java.security.Principal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link CurrentUser}: the request-scoped identity bean exposed to
 * Qute templates (§21.2). Every guard is a display concern only, so the surface is the
 * three legs of the {@code getName} authentication guard, the {@code isAuthenticated}
 * emptiness test, the {@code hasRole} null short-circuit, and the {@code isAdmin}
 * delegation. Each collaborator is mocked; no application boot, no clock, no Panache.
 */
class CurrentUserTest {

    /**
     * Builds a {@link CurrentUser} whose injected {@link SecurityIdentity} field is set to
     * the given value, reaching the package-private field directly from the same package.
     *
     * @param identity The identity to inject, possibly {@code null}.
     * @return A ready-to-exercise bean.
     */
    private CurrentUser withIdentity(SecurityIdentity identity) {
        CurrentUser bean = new CurrentUser();
        bean.identity = identity;
        return bean;
    }

    /**
     * First leg of the {@code getName} guard: a {@code null} identity yields the empty
     * name, and {@code isAuthenticated} reports absence.
     */
    @Test
    @DisplayName("getName returns empty and unauthenticated when identity is null")
    void nullIdentityGivesEmptyName() {
        CurrentUser bean = withIdentity(null);
        assertEquals("", bean.getName());
        assertFalse(bean.isAuthenticated());
    }

    /**
     * Second leg of the {@code getName} guard: a non-null but anonymous identity yields the
     * empty name, and {@code isAuthenticated} reports absence.
     */
    @Test
    @DisplayName("getName returns empty when identity is anonymous")
    void anonymousIdentityGivesEmptyName() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        CurrentUser bean = withIdentity(identity);
        assertEquals("", bean.getName());
        assertFalse(bean.isAuthenticated());
    }

    /**
     * Third leg of the {@code getName} guard: a non-null, non-anonymous identity with a
     * {@code null} principal yields the empty name.
     */
    @Test
    @DisplayName("getName returns empty when principal is null")
    void nullPrincipalGivesEmptyName() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(null);
        CurrentUser bean = withIdentity(identity);
        assertEquals("", bean.getName());
        assertFalse(bean.isAuthenticated());
    }

    /**
     * Pass-through of {@code getName}: all guard legs false yields the principal name, and
     * {@code isAuthenticated} reports presence.
     */
    @Test
    @DisplayName("getName returns the principal name when signed in")
    void signedInGivesPrincipalName() {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("alice");
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(principal);
        CurrentUser bean = withIdentity(identity);
        assertEquals("alice", bean.getName());
        assertTrue(bean.isAuthenticated());
    }

    /**
     * Null short-circuit leg of {@code hasRole}: a {@code null} identity denies every role
     * without dereferencing it.
     */
    @Test
    @DisplayName("hasRole denies when identity is null")
    void hasRoleNullIdentity() {
        CurrentUser bean = withIdentity(null);
        assertFalse(bean.hasRole(AppUser.ROLE_FID_ADMIN));
    }

    /**
     * Granted leg of {@code hasRole}: a non-null identity that holds the role returns true.
     */
    @Test
    @DisplayName("hasRole grants when the identity holds the role")
    void hasRoleGranted() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.hasRole(AppUser.ROLE_POS)).thenReturn(true);
        CurrentUser bean = withIdentity(identity);
        assertTrue(bean.hasRole(AppUser.ROLE_POS));
    }

    /**
     * Denied leg of {@code hasRole}: a non-null identity that lacks the role returns false.
     */
    @Test
    @DisplayName("hasRole denies when the identity lacks the role")
    void hasRoleDenied() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.hasRole(AppUser.ROLE_POS)).thenReturn(false);
        CurrentUser bean = withIdentity(identity);
        assertFalse(bean.hasRole(AppUser.ROLE_POS));
    }

    /**
     * Granted branch of {@code isAdmin}: the {@code fid-admin} role delegates through
     * {@code hasRole} to a true result.
     */
    @Test
    @DisplayName("isAdmin grants when the fid-admin role is held")
    void isAdminGranted() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.hasRole(AppUser.ROLE_FID_ADMIN)).thenReturn(true);
        CurrentUser bean = withIdentity(identity);
        assertTrue(bean.isAdmin());
    }

    /**
     * Denied branch of {@code isAdmin}: without the {@code fid-admin} role the delegation
     * returns false.
     */
    @Test
    @DisplayName("isAdmin denies when the fid-admin role is absent")
    void isAdminDenied() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.hasRole(AppUser.ROLE_FID_ADMIN)).thenReturn(false);
        CurrentUser bean = withIdentity(identity);
        assertFalse(bean.isAdmin());
    }
}
