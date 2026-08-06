package com.intermarche.fidelity.domain;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.security.jpa.Password;
import io.quarkus.security.jpa.Roles;
import io.quarkus.security.jpa.UserDefinition;
import io.quarkus.security.jpa.Username;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import org.wildfly.security.password.PasswordFactory;
import org.wildfly.security.password.interfaces.BCryptPassword;
import org.wildfly.security.password.util.ModularCrypt;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An application user allowed to sign in, authenticated by {@code quarkus-security-jpa}
 * against this table ({@link UserDefinition}) — same pattern as imvaluation's
 * {@code AppUser}. Two roles, two surfaces (§24.1): {@code pos} for the POS API and
 * {@code fid-admin} for every administration write.
 * <p>
 * Passwords are stored as bcrypt hashes only, set through {@link #setPassword(String)}.
 * imfid is pseudonymous (§33.3): these accounts are operator logins, not loyalty
 * cardholders — the two are unrelated.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "app_users",
        indexes = @Index(name = "idx_app_user_username", columnList = "username")
)
@UserDefinition
public class AppUser extends BaseEntity {

    /**
     * Role granting access to the POS API — {@code /earn}, reservations, accounts, event
     * ingestion (§24.1, §27).
     */
    public static final String ROLE_POS = "pos";

    /**
     * Role granting every administration write — imports, GraphQL mutations, batches and
     * the admin UI (§24.1).
     */
    public static final String ROLE_FID_ADMIN = "fid-admin";

    /**
     * Every role the application recognises.
     */
    public static final List<String> ALL_ROLES = List.of(ROLE_POS, ROLE_FID_ADMIN);

    /**
     * Minimum number of characters a password must contain.
     */
    public static final int MIN_PASSWORD_LENGTH = 8;

    // --------------------------------------------------
    // Credentials
    // --------------------------------------------------

    /**
     * The login name, unique across the application.
     */
    @Username
    @Column(name = "username", unique = true, nullable = false, length = 60)
    @NotBlank(message = "Username is mandatory")
    public String username;

    /**
     * The bcrypt hash of the password; set only through {@link #setPassword(String)}.
     */
    @Password
    @Column(name = "password", nullable = false, length = 100)
    @NotBlank(message = "Password is mandatory")
    public String password;

    /**
     * The roles granted to the user, comma separated (e.g. "pos,fid-admin").
     */
    @Roles
    @Column(name = "roles", nullable = false, length = 200)
    @NotBlank(message = "At least one role is mandatory")
    public String roles;

    /**
     * The name displayed in the interface, falling back to the username when absent.
     */
    @Column(name = "display_name", length = 100)
    public String displayName;

    /**
     * Whether the account may sign in; disabling is preferred over deleting for the audit
     * trail.
     */
    @Column(name = "is_active", nullable = false)
    public boolean active = true;

    /**
     * Whether the user must change their password before reaching any other screen.
     */
    @Column(name = "must_change_password", nullable = false)
    public boolean mustChangePassword = false;

    // --------------------------------------------------
    // Credentials handling
    // --------------------------------------------------

    /**
     * Hashes a clear-text password with bcrypt and stores the result — the only supported
     * way to set a password.
     *
     * @param clearText The password as typed by the user.
     */
    public void setPassword(String clearText) {
        this.password = BcryptUtil.bcryptHash(clearText);
    }

    /**
     * Verifies a clear-text password against the stored bcrypt hash.
     *
     * @param clearText The password to verify.
     * @return {@code true} when the password matches.
     */
    public boolean matchesPassword(String clearText) {
        if (clearText == null || password == null) {
            return false;
        }
        try {
            PasswordFactory factory = PasswordFactory.getInstance(BCryptPassword.ALGORITHM_BCRYPT);
            org.wildfly.security.password.Password stored = factory.translate(
                    ModularCrypt.decode(password.toCharArray()));
            return factory.verify(stored, clearText.toCharArray());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * Validates a candidate password against the application policy.
     *
     * @param clearText The password to validate, may be null.
     * @return An error message when the password is unacceptable, {@code null} otherwise.
     */
    public static String validatePassword(String clearText) {
        if (clearText == null || clearText.isBlank()) {
            return "The password is mandatory.";
        }
        if (clearText.length() < MIN_PASSWORD_LENGTH) {
            return "The password must be at least " + MIN_PASSWORD_LENGTH + " characters long.";
        }
        return null;
    }

    // --------------------------------------------------
    // Roles handling
    // --------------------------------------------------

    /**
     * Returns the granted roles as a set.
     *
     * @return The roles, never null.
     */
    public Set<String> getRoleSet() {
        if (roles == null || roles.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Replaces the granted roles, stored in the canonical {@link #ALL_ROLES} order so the
     * checksum stays stable across updates.
     *
     * @param newRoles The roles to grant.
     */
    public void setRoleSet(Set<String> newRoles) {
        this.roles = ALL_ROLES.stream()
                .filter(newRoles::contains)
                .collect(Collectors.joining(","));
    }

    /**
     * Indicates whether the user holds a given role.
     *
     * @param role The role to test.
     * @return {@code true} when the role is granted.
     */
    public boolean hasRole(String role) {
        return getRoleSet().contains(role);
    }

    /**
     * Returns the label identifying the user in the interface.
     *
     * @return The display name, or the username when none is set.
     */
    public String getLabel() {
        return displayName == null || displayName.isBlank() ? username : displayName;
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a user by login name.
     *
     * @param username The login name to search for.
     * @return The user, or null when not found.
     */
    public static AppUser findByUsername(String username) {
        return find("username", username).firstResult();
    }

    /**
     * Counts the active users holding the {@code fid-admin} role — used to refuse removing
     * the last administrator, which would lock everyone out.
     *
     * @return The number of active administrators.
     */
    public static long countActiveAdmins() {
        return count("active = true and roles like ?1", "%" + ROLE_FID_ADMIN + "%");
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum from the user's key attributes; the password hash is included
     * so a credential change is detected like any other modification.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(username, password, roles, displayName, active, mustChangePassword);
    }
}
