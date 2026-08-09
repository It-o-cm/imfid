package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.domain.AppUser;

import java.util.Set;

/**
 * One row of the operator-account list (§24.1): identifier, login, display name, e-mail,
 * granted roles and status. The {@code self} flag marks the signed-in operator's own row,
 * whose delete control is withheld (an operator never deletes their own account).
 * <p>
 * Public fields and getters are resolved by Qute.
 */
public final class UserRow {

    /**
     * The account identifier, used to build the edit and delete links.
     */
    public Long id;

    /**
     * The login name.
     */
    public String username;

    /**
     * The display name, or null.
     */
    public String displayName;

    /**
     * The password-reset e-mail address, or null.
     */
    public String email;

    /**
     * The granted roles, for the role chips.
     */
    public Set<String> roles;

    /**
     * Whether the account may sign in.
     */
    public boolean active;

    /**
     * Whether the account still owes a password change (set by an administrator, not yet
     * chosen by the operator).
     */
    public boolean mustChangePassword;

    /**
     * Whether this row is the signed-in operator's own account.
     */
    public boolean self;

    /**
     * Builds a row from an account, flagging the signed-in operator's own row.
     *
     * @param user            The account entity.
     * @param currentUsername The signed-in operator's login.
     * @return The account row.
     */
    public static UserRow of(AppUser user, String currentUsername) {
        UserRow row = new UserRow();
        row.id = user.id;
        row.username = user.username;
        row.displayName = user.displayName;
        row.email = user.email;
        row.roles = user.getRoleSet();
        row.active = user.active;
        row.mustChangePassword = user.mustChangePassword;
        row.self = currentUsername != null && currentUsername.equals(user.username);
        return row;
    }

    /**
     * Returns the display name, falling back to the login when none is set.
     *
     * @return The label identifying the account.
     */
    public String getLabel() {
        return displayName == null || displayName.isBlank() ? username : displayName;
    }
}
