package com.intermarche.fidelity.security;

import com.intermarche.fidelity.domain.AppUser;
import io.quarkus.qute.TemplateData;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Exposes the signed-in identity to Qute templates through {@code inject:currentUser}
 * (§21.2) — the shared layout adapts its navigation and identity bar to the granted
 * roles without every resource passing it down. Same pattern as imvaluation.
 * <p>
 * This only drives what the interface displays; access is enforced server-side by the
 * {@code @RolesAllowed} annotations on the resources — hiding a link is never a security
 * measure (§21.4, §24.1).
 */
@Named("currentUser")
@RequestScoped
@TemplateData
public class CurrentUser {

    /**
     * The identity resolved by Quarkus Security for the current request.
     */
    @Inject
    SecurityIdentity identity;

    /**
     * Returns the login name of the signed-in user.
     *
     * @return The login name, or an empty string when unauthenticated.
     */
    public String getName() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return "";
        }
        return identity.getPrincipal().getName();
    }

    /**
     * Indicates whether someone is signed in.
     *
     * @return {@code true} when the request carries an identity.
     */
    public boolean isAuthenticated() {
        return !getName().isEmpty();
    }

    /**
     * Indicates whether the signed-in user holds a given role.
     *
     * @param role The role to test.
     * @return {@code true} when the role is granted.
     */
    public boolean hasRole(String role) {
        return identity != null && identity.hasRole(role);
    }

    /**
     * Indicates whether the signed-in user may write in the administration (§24.1) — the
     * server-side twin of the UI {@code canWrite} (§21.4).
     *
     * @return {@code true} when the {@code fid-admin} role is granted.
     */
    public boolean isAdmin() {
        return hasRole(AppUser.ROLE_FID_ADMIN);
    }
}
