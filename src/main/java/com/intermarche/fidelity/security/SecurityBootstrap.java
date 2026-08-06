package com.intermarche.fidelity.security;

import com.intermarche.fidelity.domain.AppUser;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Seeds the two operator accounts when the {@code app_users} table is empty (§24.1) — a
 * {@code pos} machine account for the POS API and a {@code fid-admin} account for the
 * administration, same bootstrap pattern as imvaluation. Credentials come from the
 * configuration (from the environment in production); nothing is versioned.
 * <p>
 * These are operator logins, unrelated to loyalty cards — imfid stays pseudonymous
 * (§33.3).
 */
@ApplicationScoped
public class SecurityBootstrap {

    private static final Logger LOGGER = Logger.getLogger(SecurityBootstrap.class);

    /**
     * The bootstrap POS account username.
     */
    @ConfigProperty(name = "imfid.bootstrap.pos.username", defaultValue = "pos")
    String posUsername;

    /**
     * The bootstrap POS account password.
     */
    @ConfigProperty(name = "imfid.bootstrap.pos.password", defaultValue = "pos-password")
    String posPassword;

    /**
     * The bootstrap administrator username.
     */
    @ConfigProperty(name = "imfid.bootstrap.admin.username", defaultValue = "admin")
    String adminUsername;

    /**
     * The bootstrap administrator password.
     */
    @ConfigProperty(name = "imfid.bootstrap.admin.password", defaultValue = "admin-password")
    String adminPassword;

    /**
     * Creates the bootstrap accounts at startup when none exists yet (§24.1).
     *
     * @param event The Quarkus startup event.
     */
    @Transactional
    void onStart(@Observes StartupEvent event) {
        if (AppUser.count() > 0) {
            return;
        }
        createUser(posUsername, posPassword, AppUser.ROLE_POS, "POS API");
        createUser(adminUsername, adminPassword, AppUser.ROLE_FID_ADMIN, "Fidelity administrator");
        LOGGER.infof("Bootstrap users created: '%s' (pos), '%s' (fid-admin)", posUsername, adminUsername);
    }

    /**
     * Creates and persists one operator account with a hashed password.
     *
     * @param username    The login name.
     * @param password    The clear-text password (hashed before storage).
     * @param role        The granted role.
     * @param displayName The display name.
     */
    private void createUser(String username, String password, String role, String displayName) {
        AppUser user = new AppUser();
        user.username = username;
        user.setPassword(password);
        user.roles = role;
        user.displayName = displayName;
        user.active = true;
        user.mustChangePassword = false;
        user.persist();
    }
}
