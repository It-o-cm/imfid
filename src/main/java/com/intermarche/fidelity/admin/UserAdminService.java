package com.intermarche.fidelity.admin;

import com.intermarche.fidelity.domain.AppUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The operator-account write service (§24.1) — the single home of the user-management
 * mutation logic, same separation of concerns as {@link AdminService}: the
 * {@code UserUiResource} POST handlers never mutate directly, they delegate here so every
 * integrity rule is enforced in one place. Ported from imvaluation's user management,
 * adapted to imfid's {@code pos}/{@code fid-admin} role matrix.
 * <p>
 * Two guards protect availability: the last active administrator can never be deleted,
 * demoted or disabled (it would lock everyone out of the admin UI), and no operator can
 * delete their own account. Every account the administrator creates or whose password the
 * administrator resets is flagged {@code mustChangePassword} — the operator, whose password
 * the administrator momentarily knows, must choose a fresh one before reaching any screen
 * (enforced by {@code PasswordChangeFilter}). A refused mutation throws an
 * {@link AdminException}, so the failure surfaces as a UI notice like every other admin
 * refusal (§21.3).
 */
@ApplicationScoped
public class UserAdminService {

    /**
     * Creates an operator account (§24.1); the password is hashed and the account is
     * flagged {@code mustChangePassword} since the administrator sets the initial secret.
     *
     * @param username    The login name (mandatory, unique).
     * @param password    The initial clear-text password (mandatory, policy-checked).
     * @param displayName The display name, or blank.
     * @param email       The password-reset e-mail address, or blank for none.
     * @param roles       The granted roles (at least one, sanitized to the known roles).
     * @param active      Whether the account may sign in.
     * @return The created account.
     */
    @Transactional
    public AppUser createUser(String username, String password, String displayName, String email,
                              Set<String> roles, boolean active) {
        String login = username == null ? "" : username.trim();
        if (login.isBlank()) {
            throw new AdminException("The username is mandatory.");
        }
        if (AppUser.count("username", login) > 0) {
            throw new AdminException("An account named '" + login + "' already exists.");
        }
        Set<String> granted = sanitizeRoles(roles);
        if (granted.isEmpty()) {
            throw new AdminException("At least one role must be granted.");
        }
        String policyError = AppUser.validatePassword(password);
        if (policyError != null) {
            throw new AdminException(policyError);
        }
        AppUser user = new AppUser();
        user.username = login;
        user.setPassword(password);
        user.setRoleSet(granted);
        user.displayName = normalize(displayName);
        user.email = normalize(email);
        user.active = active;
        user.mustChangePassword = true;
        user.persist();
        return user;
    }

    /**
     * Updates an operator account (§24.1); the username is frozen (it is the login). The
     * password is changed only when a new one is supplied, and then the account is flagged
     * {@code mustChangePassword} (an administrator reset forces the operator to pick their
     * own). Refuses removing the {@code fid-admin} role from, or disabling, the last active
     * administrator.
     *
     * @param id              The account identifier.
     * @param password        A new clear-text password, or blank to keep the current one.
     * @param displayName     The display name, or blank.
     * @param email           The password-reset e-mail address, or blank for none.
     * @param roles           The granted roles (at least one, sanitized to the known roles).
     * @param active          Whether the account may sign in.
     * @param currentUsername The signed-in operator's login (never used to weaken the guard).
     * @return The updated account.
     */
    @Transactional
    public AppUser updateUser(Long id, String password, String displayName, String email,
                              Set<String> roles, boolean active, String currentUsername) {
        AppUser user = AppUser.findById(id);
        if (user == null) {
            throw new AdminException("Account not found.");
        }
        Set<String> granted = sanitizeRoles(roles);
        if (granted.isEmpty()) {
            throw new AdminException("At least one role must be granted.");
        }
        boolean passwordGiven = password != null && !password.isBlank();
        if (passwordGiven) {
            String policyError = AppUser.validatePassword(password);
            if (policyError != null) {
                throw new AdminException(policyError);
            }
        }
        boolean losesAdmin = user.hasRole(AppUser.ROLE_FID_ADMIN) && !granted.contains(AppUser.ROLE_FID_ADMIN);
        boolean losesAccess = user.active && !active;
        if ((losesAdmin || losesAccess) && isLastActiveAdmin(user)) {
            throw new AdminException("This is the last administrator: keep the fid-admin role and the account enabled.");
        }
        user.setRoleSet(granted);
        user.displayName = normalize(displayName);
        user.email = normalize(email);
        user.active = active;
        if (passwordGiven) {
            user.setPassword(password);
            user.mustChangePassword = true;
        }
        user.persist();
        return user;
    }

    /**
     * Deletes an operator account (§24.1); refuses deleting the signed-in operator's own
     * account and the last active administrator. Disabling is preferred over deleting for
     * the audit trail, but the gesture is kept, mirroring imvaluation.
     *
     * @param id              The account identifier.
     * @param currentUsername The signed-in operator's login, protected from self-deletion.
     * @return The deleted account's login, for the confirmation notice.
     */
    @Transactional
    public String deleteUser(Long id, String currentUsername) {
        AppUser user = AppUser.findById(id);
        if (user == null) {
            throw new AdminException("Account not found.");
        }
        if (currentUsername != null && currentUsername.equals(user.username)) {
            throw new AdminException("You cannot delete your own account.");
        }
        if (isLastActiveAdmin(user)) {
            throw new AdminException("The last administrator cannot be deleted.");
        }
        String login = user.username;
        user.delete();
        return login;
    }

    /**
     * Keeps only the submitted values that are real application roles, so an unknown or
     * forged role name posted by the form is silently dropped rather than granted.
     *
     * @param submitted The roles posted by the form, may be null.
     * @return The sanitized role set, never null.
     */
    private Set<String> sanitizeRoles(Set<String> submitted) {
        Set<String> granted = new LinkedHashSet<>();
        if (submitted != null) {
            for (String role : AppUser.ALL_ROLES) {
                if (submitted.contains(role)) {
                    granted.add(role);
                }
            }
        }
        return granted;
    }

    /**
     * Indicates whether an account is the last active administrator, whose demotion,
     * disabling or deletion would lock everyone out of the admin UI.
     *
     * @param user The account to test.
     * @return true when it is the last active administrator.
     */
    private boolean isLastActiveAdmin(AppUser user) {
        return user.active && user.hasRole(AppUser.ROLE_FID_ADMIN) && AppUser.countActiveAdmins() <= 1;
    }

    /**
     * Trims an optional text field, returning null when blank so an empty form input is
     * stored as an absent value rather than an empty string.
     *
     * @param value The raw form value, may be null.
     * @return The trimmed value, or null when blank.
     */
    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
