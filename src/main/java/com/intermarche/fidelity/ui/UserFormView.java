package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.domain.AppUser;

import java.util.List;
import java.util.Set;

/**
 * The view model of the operator-account form (§24.1), serving its two modes: creation of
 * a blank account and edition of an existing one (the login is then frozen). Ported from
 * imvaluation's user form, adapted to imfid's {@code pos}/{@code fid-admin} role matrix.
 * <p>
 * Public fields and getters are resolved by Qute.
 */
public final class UserFormView {

    /**
     * The page title ("Nouvel utilisateur" or "Éditer l'utilisateur X").
     */
    public String title;

    /**
     * The form POST action ({@code /ui/users/create} or {@code /ui/users/X/update}).
     */
    public String action;

    /**
     * The submit button label.
     */
    public String submitLabel;

    /**
     * Whether the form edits an existing account (login frozen) rather than creating one.
     */
    public boolean editMode;

    /**
     * The login name (empty in creation, frozen in edition).
     */
    public String username = "";

    /**
     * The display name.
     */
    public String displayName = "";

    /**
     * The password-reset e-mail address.
     */
    public String email = "";

    /**
     * Whether the account may sign in.
     */
    public boolean active = true;

    /**
     * The roles granted to the edited account, for the checkbox state.
     */
    public Set<String> roles = Set.of();

    /**
     * Every role the form offers, in canonical order (§24.1).
     */
    public List<String> allRoles = AppUser.ALL_ROLES;

    /**
     * Whether the signed-in user may write (§21.4).
     */
    public boolean canWrite;

    /**
     * The one-shot notice, or null.
     */
    public String notice;

    /**
     * Whether the notice reports a success.
     */
    public boolean noticeOk;

    /**
     * Builds the creation-mode view.
     *
     * @param canWrite Whether the user may write.
     * @return The creation-mode view.
     */
    public static UserFormView creation(boolean canWrite) {
        UserFormView view = new UserFormView();
        view.title = "Nouvel utilisateur";
        view.action = "/ui/users/create";
        view.submitLabel = "Créer l'utilisateur";
        view.editMode = false;
        view.canWrite = canWrite;
        return view;
    }

    /**
     * Builds the edition-mode view from an existing account: every field is prefilled and
     * the login is frozen.
     *
     * @param user     The account to edit.
     * @param canWrite Whether the user may write.
     * @return The edition-mode view.
     */
    public static UserFormView edition(AppUser user, boolean canWrite) {
        UserFormView view = new UserFormView();
        view.title = "Éditer l'utilisateur " + user.username;
        view.action = "/ui/users/" + user.id + "/update";
        view.submitLabel = "Enregistrer";
        view.editMode = true;
        view.username = user.username;
        view.displayName = user.displayName == null ? "" : user.displayName;
        view.email = user.email == null ? "" : user.email;
        view.active = user.active;
        view.roles = user.getRoleSet();
        view.canWrite = canWrite;
        return view;
    }

    /**
     * Indicates whether a role is granted to the edited account, driving the checkbox
     * checked state.
     *
     * @param role The role to test.
     * @return true when the role is granted.
     */
    public boolean granted(String role) {
        return roles.contains(role);
    }

    /**
     * Returns whether a notice must be displayed.
     *
     * @return true when a notice is present.
     */
    public boolean isHasNotice() {
        return notice != null && !notice.isBlank();
    }
}
