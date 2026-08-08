package com.intermarche.fidelity.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.AppUser;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link UserAdminService}: the operator-account write service
 * (§24.1). Every leg of every guard is exercised — the mandatory/unique username and the
 * {@code null}/trim ternary of {@code createUser}, the sanitized-role emptiness gate, the
 * password policy, the {@code passwordGiven = password != null && !isBlank()} conjunction,
 * the {@code losesAdmin}/{@code losesAccess} conjunctions, the
 * {@code (losesAdmin || losesAccess) && isLastActiveAdmin} last-administrator guard, the
 * self-deletion conjunction, and both {@code normalize}/{@code sanitizeRoles} arms.
 * <p>
 * Fully isolated: the service has no collaborators. The Panache inherited finders
 * ({@code count}, {@code findById}) are intercepted with {@code mockStatic(PanacheEntityBase.class)}
 * in try-with-resources; the entity-declared statics {@link AppUser#validatePassword} and
 * {@link AppUser#countActiveAdmins} are stubbed with {@code mockStatic(AppUser.class)}; the
 * {@code new AppUser()} of {@code createUser} is neutralized with
 * {@code mockConstruction(AppUser.class)}. The class holds no clock read, so no
 * {@code DateTimeProvider} fixation is needed (§24.6). Roles are compared by set content.
 */
class UserAdminServiceTest {

    /**
     * The system under test, freshly built per test (it carries no injected collaborator).
     */
    private final UserAdminService service = new UserAdminService();

    // --------------------------------------------------
    // Fixtures
    // --------------------------------------------------

    /**
     * Builds an {@link AppUser} mock carrying a login, an active flag and a stubbed
     * {@code hasRole(fid-admin)} answer, so the guards read deterministic values while
     * {@code setRoleSet}/{@code setPassword}/{@code persist}/{@code delete} stay no-ops.
     *
     * @param username The login name.
     * @param active   Whether the account may currently sign in.
     * @param admin    The answer of {@code hasRole(fid-admin)}.
     * @return The user mock.
     */
    private AppUser userMock(String username, boolean active, boolean admin) {
        AppUser user = Mockito.mock(AppUser.class);
        user.username = username;
        user.active = active;
        Mockito.when(user.hasRole(AppUser.ROLE_FID_ADMIN)).thenReturn(admin);
        return user;
    }

    // --------------------------------------------------
    // createUser (§24.1)
    // --------------------------------------------------

    /**
     * A null username resolves to an empty login through the ternary null arm, so
     * {@code login.isBlank()} refuses it (§24.1).
     */
    @Test
    @DisplayName("createUser(): a null username is refused")
    void createUserNullUsername() {
        assertThrows(AdminException.class, () -> service.createUser(
                null, "password1", "L", "e@x.com", Set.of(AppUser.ROLE_POS), true));
    }

    /**
     * A blank username is trimmed to empty through the ternary non-null arm, so
     * {@code login.isBlank()} refuses it (§24.1).
     */
    @Test
    @DisplayName("createUser(): a blank username is refused")
    void createUserBlankUsername() {
        assertThrows(AdminException.class, () -> service.createUser(
                "   ", "password1", "L", "e@x.com", Set.of(AppUser.ROLE_POS), true));
    }

    /**
     * An already existing login is refused — the {@code count > 0} true arm (§24.1).
     */
    @Test
    @DisplayName("createUser(): an existing username is refused")
    void createUserExistingUsername() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count("username", "bob")).thenReturn(1L);
            assertThrows(AdminException.class, () -> service.createUser(
                    "bob", "password1", "L", "e@x.com", Set.of(AppUser.ROLE_POS), true));
        }
    }

    /**
     * A null role set sanitizes to empty through the {@code submitted != null} false leg, so
     * the {@code granted.isEmpty()} arm refuses it (§24.1).
     */
    @Test
    @DisplayName("createUser(): a null role set is refused")
    void createUserNullRoles() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count("username", "bob")).thenReturn(0L);
            assertThrows(AdminException.class, () -> service.createUser(
                    "bob", "password1", "L", "e@x.com", null, true));
        }
    }

    /**
     * A role set holding only unknown names sanitizes to empty through the
     * {@code submitted.contains(role)} false legs, so it is refused (§24.1).
     */
    @Test
    @DisplayName("createUser(): a role set with only unknown roles is refused")
    void createUserUnknownRolesOnly() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count("username", "bob")).thenReturn(0L);
            assertThrows(AdminException.class, () -> service.createUser(
                    "bob", "password1", "L", "e@x.com", Set.of("bogus"), true));
        }
    }

    /**
     * A password rejected by the policy is refused — the {@code policyError != null} true arm
     * (§24.1).
     */
    @Test
    @DisplayName("createUser(): a policy-violating password is refused")
    void createUserBadPassword() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<AppUser> appUser = Mockito.mockStatic(AppUser.class)) {
            panache.when(() -> PanacheEntityBase.count("username", "bob")).thenReturn(0L);
            appUser.when(() -> AppUser.validatePassword("short")).thenReturn("too short");
            assertThrows(AdminException.class, () -> service.createUser(
                    "bob", "short", "L", "e@x.com", Set.of(AppUser.ROLE_POS), true));
        }
    }

    /**
     * A valid creation persists an account with the trimmed login, the sanitized roles (the
     * {@code contains(role)} true leg for {@code pos}, false leg for {@code fid-admin}), the
     * normalized display name (trim arm), the normalized null e-mail (null arm) and the
     * {@code mustChangePassword} flag (§24.1).
     */
    @Test
    @DisplayName("createUser(): a valid account is created and flagged mustChangePassword")
    void createUserSuccess() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<AppUser> appUser = Mockito.mockStatic(AppUser.class);
             MockedConstruction<AppUser> cons = Mockito.mockConstruction(AppUser.class)) {
            panache.when(() -> PanacheEntityBase.count("username", "bob")).thenReturn(0L);
            appUser.when(() -> AppUser.validatePassword("password1")).thenReturn(null);
            AppUser created = service.createUser(
                    "  bob  ", "password1", "  John  ", null, Set.of(AppUser.ROLE_POS), true);
            assertSame(cons.constructed().get(0), created);
            assertEquals("bob", created.username);
            assertEquals("John", created.displayName);
            assertNull(created.email);
            assertTrue(created.active);
            assertTrue(created.mustChangePassword);
            Mockito.verify(created).setPassword("password1");
            Mockito.verify(created).setRoleSet(Set.of(AppUser.ROLE_POS));
            Mockito.verify(created).persist();
        }
    }

    // --------------------------------------------------
    // updateUser (§24.1)
    // --------------------------------------------------

    /**
     * An unknown identifier is refused — the {@code user == null} arm (§24.1).
     */
    @Test
    @DisplayName("updateUser(): an unknown account is refused")
    void updateUserNotFound() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(null);
            assertThrows(AdminException.class, () -> service.updateUser(
                    7L, null, "L", "e@x.com", Set.of(AppUser.ROLE_POS), true, "admin"));
        }
    }

    /**
     * An empty sanitized role set is refused — the {@code granted.isEmpty()} arm (§24.1).
     */
    @Test
    @DisplayName("updateUser(): an empty role set is refused")
    void updateUserEmptyRoles() {
        AppUser user = userMock("bob", true, false);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(user);
            assertThrows(AdminException.class, () -> service.updateUser(
                    7L, null, "L", "e@x.com", Set.of(), true, "admin"));
        }
    }

    /**
     * A supplied policy-violating password is refused — the {@code password != null} true,
     * {@code !isBlank()} true and {@code policyError != null} true legs (§24.1).
     */
    @Test
    @DisplayName("updateUser(): a supplied bad password is refused")
    void updateUserBadPassword() {
        AppUser user = userMock("bob", true, false);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<AppUser> appUser = Mockito.mockStatic(AppUser.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(user);
            appUser.when(() -> AppUser.validatePassword("short")).thenReturn("too short");
            assertThrows(AdminException.class, () -> service.updateUser(
                    7L, "short", "L", "e@x.com", Set.of(AppUser.ROLE_POS), true, "admin"));
        }
    }

    /**
     * Demoting the last administrator is refused — the {@code losesAdmin} true leg (which
     * short-circuits the {@code ||}), the {@code isLastActiveAdmin} true value and its
     * {@code countActiveAdmins() <= 1} true leg (§24.1).
     */
    @Test
    @DisplayName("updateUser(): demoting the last administrator is refused")
    void updateUserDemoteLastAdmin() {
        AppUser user = userMock("bob", true, true);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<AppUser> appUser = Mockito.mockStatic(AppUser.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(user);
            appUser.when(AppUser::countActiveAdmins).thenReturn(1L);
            assertThrows(AdminException.class, () -> service.updateUser(
                    7L, null, "L", "e@x.com", Set.of(AppUser.ROLE_POS), true, "admin"));
        }
    }

    /**
     * Disabling the last administrator is refused — the {@code losesAdmin} false leg (role
     * kept), the {@code losesAccess} true leg ({@code active} true and {@code !active} true)
     * and the {@code isLastActiveAdmin} true value (§24.1).
     */
    @Test
    @DisplayName("updateUser(): disabling the last administrator is refused")
    void updateUserDisableLastAdmin() {
        AppUser user = userMock("bob", true, true);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<AppUser> appUser = Mockito.mockStatic(AppUser.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(user);
            appUser.when(AppUser::countActiveAdmins).thenReturn(1L);
            assertThrows(AdminException.class, () -> service.updateUser(
                    7L, null, "L", "e@x.com", Set.of(AppUser.ROLE_POS, AppUser.ROLE_FID_ADMIN), false, "admin"));
        }
    }

    /**
     * Demoting an administrator who is not the last one succeeds — the
     * {@code countActiveAdmins() <= 1} false leg makes {@code isLastActiveAdmin} false, so the
     * {@code (losesAdmin || losesAccess) && isLastActiveAdmin} guard passes (§24.1).
     */
    @Test
    @DisplayName("updateUser(): demoting a non-last administrator succeeds")
    void updateUserDemoteNonLastAdmin() {
        AppUser user = userMock("bob", true, true);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<AppUser> appUser = Mockito.mockStatic(AppUser.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(user);
            appUser.when(AppUser::countActiveAdmins).thenReturn(2L);
            AppUser updated = service.updateUser(
                    7L, null, "L", "e@x.com", Set.of(AppUser.ROLE_POS), true, "admin");
            assertSame(user, updated);
            Mockito.verify(user).setRoleSet(Set.of(AppUser.ROLE_POS));
            Mockito.verify(user).persist();
        }
    }

    /**
     * Disabling a non-administrator succeeds — the {@code losesAccess} true leg reaches
     * {@code isLastActiveAdmin}, whose {@code hasRole(fid-admin)} false leg returns false, so
     * the account is disabled (§24.1).
     */
    @Test
    @DisplayName("updateUser(): disabling a non-administrator succeeds")
    void updateUserDisableNonAdmin() {
        AppUser user = userMock("bob", true, false);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(user);
            AppUser updated = service.updateUser(
                    7L, null, "L", "e@x.com", Set.of(AppUser.ROLE_POS), false, "admin");
            assertSame(user, updated);
            assertFalse(user.active);
            Mockito.verify(user).persist();
        }
    }

    /**
     * A disabled administrator being demoted passes the guard — {@code losesAdmin} true, but
     * {@code isLastActiveAdmin}'s {@code user.active} false leg returns false, so the update
     * succeeds (§24.1).
     */
    @Test
    @DisplayName("updateUser(): demoting an already disabled administrator succeeds")
    void updateUserDemoteDisabledAdmin() {
        AppUser user = userMock("bob", false, true);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(user);
            AppUser updated = service.updateUser(
                    7L, null, "L", "e@x.com", Set.of(AppUser.ROLE_POS), false, "admin");
            assertSame(user, updated);
            Mockito.verify(user).persist();
        }
    }

    /**
     * A blank password keeps the current secret — the {@code password != null} true and
     * {@code !isBlank()} false legs leave {@code passwordGiven} false — while the
     * {@code losesAdmin} {@code hasRole} false leg and the {@code losesAccess} {@code !active}
     * false leg leave the guard false, and {@code normalize} applies its isBlank arm to the
     * display name and its trim arm to the e-mail (§24.1).
     */
    @Test
    @DisplayName("updateUser(): a blank password keeps the current secret")
    void updateUserBlankPasswordKept() {
        AppUser user = userMock("bob", true, false);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(user);
            AppUser updated = service.updateUser(
                    7L, "   ", "   ", "  a@b.com  ", Set.of(AppUser.ROLE_POS), true, "admin");
            assertSame(user, updated);
            assertNull(user.displayName);
            assertEquals("a@b.com", user.email);
            assertFalse(user.mustChangePassword);
            Mockito.verify(user, Mockito.never()).setPassword(Mockito.anyString());
            Mockito.verify(user).persist();
        }
    }

    /**
     * A null password keeps the current secret — the {@code password != null} false leg
     * leaves {@code passwordGiven} false — while an administrator keeping the {@code fid-admin}
     * role exercises the {@code losesAdmin} {@code !granted.contains(fid-admin)} false leg, so
     * the update succeeds without a reset flag (§24.1).
     */
    @Test
    @DisplayName("updateUser(): a null password keeps the current secret")
    void updateUserNullPasswordKept() {
        AppUser user = userMock("bob", true, true);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(user);
            AppUser updated = service.updateUser(
                    7L, null, "L", "e@x.com", Set.of(AppUser.ROLE_POS, AppUser.ROLE_FID_ADMIN), true, "admin");
            assertSame(user, updated);
            assertFalse(user.mustChangePassword);
            Mockito.verify(user, Mockito.never()).setPassword(Mockito.anyString());
            Mockito.verify(user).persist();
        }
    }

    /**
     * A supplied valid password resets the secret — the {@code passwordGiven} true path and
     * the {@code policyError != null} false leg — and flags {@code mustChangePassword} (§24.1).
     */
    @Test
    @DisplayName("updateUser(): a valid password resets the secret and flags a change")
    void updateUserResetPassword() {
        AppUser user = userMock("bob", true, true);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<AppUser> appUser = Mockito.mockStatic(AppUser.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(user);
            appUser.when(() -> AppUser.validatePassword("password1")).thenReturn(null);
            AppUser updated = service.updateUser(
                    7L, "password1", "L", "e@x.com", Set.of(AppUser.ROLE_POS, AppUser.ROLE_FID_ADMIN), true, "admin");
            assertSame(user, updated);
            assertTrue(user.mustChangePassword);
            Mockito.verify(user).setPassword("password1");
            Mockito.verify(user).persist();
        }
    }

    // --------------------------------------------------
    // deleteUser (§24.1)
    // --------------------------------------------------

    /**
     * An unknown identifier is refused — the {@code user == null} arm (§24.1).
     */
    @Test
    @DisplayName("deleteUser(): an unknown account is refused")
    void deleteUserNotFound() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(null);
            assertThrows(AdminException.class, () -> service.deleteUser(7L, "admin"));
        }
    }

    /**
     * Deleting one's own account is refused — the {@code currentUsername != null} true and
     * {@code equals(user.username)} true legs (§24.1).
     */
    @Test
    @DisplayName("deleteUser(): deleting your own account is refused")
    void deleteUserSelf() {
        AppUser user = userMock("bob", true, false);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(user);
            assertThrows(AdminException.class, () -> service.deleteUser(7L, "bob"));
        }
    }

    /**
     * Deleting the last administrator is refused — the {@code isLastActiveAdmin} true value
     * reached through the {@code equals} false leg (a different operator) (§24.1).
     */
    @Test
    @DisplayName("deleteUser(): deleting the last administrator is refused")
    void deleteUserLastAdmin() {
        AppUser user = userMock("bob", true, true);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<AppUser> appUser = Mockito.mockStatic(AppUser.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(user);
            appUser.when(AppUser::countActiveAdmins).thenReturn(1L);
            assertThrows(AdminException.class, () -> service.deleteUser(7L, "alice"));
        }
    }

    /**
     * Deleting a deletable account with no signed-in operator succeeds — the
     * {@code currentUsername != null} false leg skips the self-check and the disabled account
     * makes {@code isLastActiveAdmin} false, so it is deleted and its login returned (§24.1).
     */
    @Test
    @DisplayName("deleteUser(): a deletable account is deleted and its login returned")
    void deleteUserSuccess() {
        AppUser user = userMock("bob", false, false);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(user);
            String login = service.deleteUser(7L, null);
            assertEquals("bob", login);
            Mockito.verify(user).delete();
        }
    }
}
