package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.AppUser;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link UserFormView}, the operator-account form view model (§24.1).
 * The class carries no clock and no Panache access: {@link UserFormView#creation} seeds the
 * creation-mode labels, {@link UserFormView#edition} copies an existing account through two
 * null-guarded ternaries (display name, e-mail), {@link UserFormView#granted(String)} tests
 * set membership, and {@link UserFormView#isHasNotice()} is a compound guard
 * {@code notice != null && !notice.isBlank()}. Accounts are built in memory (no boot, no H2,
 * no static finder). Both arms of every ternary and each leg of the {@code isHasNotice} guard
 * are exercised, nullities included (§29, §29.6).
 */
class UserFormViewTest {

    /**
     * Builds an in-memory account with both optional fields populated (non-null arms).
     *
     * @return The fully populated account.
     */
    private static AppUser fullUser() {
        AppUser user = new AppUser();
        user.id = 42L;
        user.username = "alice";
        user.displayName = "Alice Martin";
        user.email = "alice@intermarche.test";
        user.active = true;
        user.roles = "pos,fid-admin";
        return user;
    }

    /**
     * Builds an in-memory account with both optional fields left null (empty arms) and no role.
     *
     * @return The account carrying only its mandatory login.
     */
    private static AppUser bareUser() {
        AppUser user = new AppUser();
        user.id = 7L;
        user.username = "bob";
        user.displayName = null;
        user.email = null;
        user.active = false;
        user.roles = null;
        return user;
    }

    /**
     * Asserts that creation mode seeds the create labels, leaves edit mode off, keeps the
     * fields at their blank defaults and propagates a writable user. Covers the
     * {@code canWrite == true} path of {@link UserFormView#creation(boolean)}.
     */
    @Test
    @DisplayName("creation seeds the create-mode labels for a writable user")
    void creationWritable() {
        UserFormView view = UserFormView.creation(true);
        assertEquals("Nouvel utilisateur", view.title);
        assertEquals("/ui/users/create", view.action);
        assertEquals("Créer l'utilisateur", view.submitLabel);
        assertFalse(view.editMode);
        assertTrue(view.canWrite);
        assertEquals("", view.username);
        assertEquals("", view.displayName);
        assertEquals("", view.email);
        assertTrue(view.active);
        assertTrue(view.roles.isEmpty());
        assertEquals(AppUser.ALL_ROLES, view.allRoles);
    }

    /**
     * Asserts that creation mode propagates a read-only user. Covers the
     * {@code canWrite == false} path of {@link UserFormView#creation(boolean)}.
     */
    @Test
    @DisplayName("creation propagates a read-only user")
    void creationReadOnly() {
        UserFormView view = UserFormView.creation(false);
        assertFalse(view.canWrite);
        assertFalse(view.editMode);
    }

    /**
     * Asserts that edition mode copies a fully populated account, exercising the non-null arm
     * of both ternaries and the {@code canWrite == true} path. The login is frozen from the
     * account and the roles are propagated.
     */
    @Test
    @DisplayName("edition copies a fully populated account (non-null arms)")
    void editionFull() {
        UserFormView view = UserFormView.edition(fullUser(), true);
        assertEquals("Éditer l'utilisateur alice", view.title);
        assertEquals("/ui/users/42/update", view.action);
        assertEquals("Enregistrer", view.submitLabel);
        assertTrue(view.editMode);
        assertTrue(view.canWrite);
        assertEquals("alice", view.username);
        assertEquals("Alice Martin", view.displayName);
        assertEquals("alice@intermarche.test", view.email);
        assertTrue(view.active);
        assertEquals(Set.of("pos", "fid-admin"), view.roles);
    }

    /**
     * Asserts that edition mode renders a null display name and a null e-mail as the empty
     * string, exercising the null arm of both ternaries, propagates the inactive flag and an
     * empty role set, and propagates a read-only user ({@code canWrite == false}).
     */
    @Test
    @DisplayName("edition renders null optional fields as empty (null arms)")
    void editionBare() {
        UserFormView view = UserFormView.edition(bareUser(), false);
        assertEquals("Éditer l'utilisateur bob", view.title);
        assertEquals("/ui/users/7/update", view.action);
        assertTrue(view.editMode);
        assertFalse(view.canWrite);
        assertEquals("bob", view.username);
        assertEquals("", view.displayName);
        assertEquals("", view.email);
        assertFalse(view.active);
        assertTrue(view.roles.isEmpty());
    }

    /**
     * Asserts that a granted role is reported. Covers the {@code contains == true} path of
     * {@link UserFormView#granted(String)}.
     */
    @Test
    @DisplayName("granted is true for a role held by the account")
    void grantedHeld() {
        UserFormView view = UserFormView.edition(fullUser(), true);
        assertTrue(view.granted("pos"));
        assertTrue(view.granted("fid-admin"));
    }

    /**
     * Asserts that an ungranted role is not reported. Covers the {@code contains == false}
     * path of {@link UserFormView#granted(String)}.
     */
    @Test
    @DisplayName("granted is false for a role the account does not hold")
    void grantedNotHeld() {
        UserFormView view = UserFormView.edition(bareUser(), true);
        assertFalse(view.granted("pos"));
        assertFalse(view.granted("fid-admin"));
    }

    /**
     * Asserts that a null notice yields no notice. Covers the first leg
     * ({@code notice != null} false) of {@link UserFormView#isHasNotice()}.
     */
    @Test
    @DisplayName("isHasNotice is false when the notice is null")
    void hasNoticeNull() {
        UserFormView view = UserFormView.creation(true);
        assertNull(view.notice);
        assertFalse(view.isHasNotice());
    }

    /**
     * Asserts that a blank notice yields no notice. Covers the second leg
     * ({@code !notice.isBlank()} false) of {@link UserFormView#isHasNotice()}.
     */
    @Test
    @DisplayName("isHasNotice is false when the notice is blank")
    void hasNoticeBlank() {
        UserFormView view = UserFormView.creation(true);
        view.notice = "   ";
        assertFalse(view.isHasNotice());
    }

    /**
     * Asserts that a non-blank notice yields a notice. Covers the true path (both legs true)
     * of {@link UserFormView#isHasNotice()}.
     */
    @Test
    @DisplayName("isHasNotice is true when the notice is non-blank")
    void hasNoticePresent() {
        UserFormView view = UserFormView.creation(true);
        view.notice = "Utilisateur enregistré";
        assertTrue(view.isHasNotice());
    }
}
