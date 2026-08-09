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
     * The bootstrap administrator e-mail address, receiving the password-reset links
     * (§24.1); the {@code pos} machine account carries none on purpose.
     */
    @ConfigProperty(name = "imfid.bootstrap.admin.email", defaultValue = "admin@imfid.local")
    String adminEmail;

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
        // The pos account is a machine login (HTTP Basic, no browser): it never reaches the
        // change screen, so it keeps its seeded credential. The admin account is a human
        // login whose password is versioned/known here, so it must be replaced at first
        // sign-in (§24.1) — the redirect is relaxed in dev/test.
        createUser(posUsername, posPassword, AppUser.ROLE_POS, "POS API", null, false);
        createUser(adminUsername, adminPassword, AppUser.ROLE_FID_ADMIN, "Fidelity administrator", adminEmail, true);
        LOGGER.infof("Bootstrap users created: '%s' (pos), '%s' (fid-admin)", posUsername, adminUsername);
    }

    /**
     * Creates and persists one operator account with a hashed password.
     *
     * @param username           The login name.
     * @param password           The clear-text password (hashed before storage).
     * @param role               The granted role.
     * @param displayName        The display name.
     * @param email              The password-reset e-mail address, or null for machine accounts.
     * @param mustChangePassword Whether the operator must replace this password at first sign-in.
     */
    private void createUser(String username, String password, String role, String displayName, String email,
                            boolean mustChangePassword) {
        AppUser user = new AppUser();
        user.username = username;
        user.setPassword(password);
        user.roles = role;
        user.displayName = displayName;
        user.email = email;
        user.active = true;
        user.mustChangePassword = mustChangePassword;
        user.persist();
    }
}
