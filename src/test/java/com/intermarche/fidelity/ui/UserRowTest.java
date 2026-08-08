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
 * Plain unit coverage for {@link UserRow}, the operator-account list row view model (§24.1).
 * The class carries no clock and no Panache access: {@link UserRow#of} copies the account's
 * business fields verbatim, derives the role set and flags the signed-in operator's own row,
 * while {@link UserRow#getLabel()} falls back from the display name to the login. Accounts are
 * built in memory (no boot, no H2, no static finder). Every leg of the {@code self} guard
 * ({@code currentUsername != null && currentUsername.equals(...)}) and every arm of the
 * {@code getLabel} ternary ({@code null}, blank, and non-blank display name) are exercised.
 */
class UserRowTest {

    /**
     * Builds an in-memory account with the supplied login and display name, other fields set
     * to deterministic non-null values.
     *
     * @param username    The login name.
     * @param displayName The display name, possibly null or blank.
     * @return The account, its business fields populated.
     */
    private static AppUser user(String username, String displayName) {
        AppUser user = new AppUser();
        user.id = 42L;
        user.username = username;
        user.displayName = displayName;
        user.email = "op@example.com";
        user.roles = "pos,fid-admin";
        user.active = true;
        user.mustChangePassword = true;
        return user;
    }

    /**
     * Asserts that {@link UserRow#of} copies every business field of the account and derives
     * the role set from the comma-separated roles string.
     */
    @Test
    @DisplayName("of copies the account's fields verbatim")
    void ofCopiesFields() {
        AppUser user = user("alice", "Alice");
        UserRow row = UserRow.of(user, "alice");
        assertEquals(42L, row.id);
        assertEquals("alice", row.username);
        assertEquals("Alice", row.displayName);
        assertEquals("op@example.com", row.email);
        assertEquals(Set.of("pos", "fid-admin"), row.roles);
        assertTrue(row.active);
        assertTrue(row.mustChangePassword);
    }

    /**
     * Asserts that {@link UserRow#of} copies the {@code false} states of the boolean fields,
     * covering the opposite value of {@code active} and {@code mustChangePassword}.
     */
    @Test
    @DisplayName("of copies false booleans verbatim")
    void ofCopiesFalseBooleans() {
        AppUser user = user("bob", "Bob");
        user.active = false;
        user.mustChangePassword = false;
        UserRow row = UserRow.of(user, "bob");
        assertFalse(row.active);
        assertFalse(row.mustChangePassword);
    }

    /**
     * Asserts that the {@code self} flag is {@code true} when the signed-in login equals the
     * account login: the right leg of the {@code &&} guard is reached and holds.
     */
    @Test
    @DisplayName("of flags the operator's own row as self")
    void ofFlagsSelf() {
        UserRow row = UserRow.of(user("carol", "Carol"), "carol");
        assertTrue(row.self);
    }

    /**
     * Asserts that the {@code self} flag is {@code false} when a non-null signed-in login
     * differs from the account login: the right leg of the {@code &&} guard is reached and
     * fails.
     */
    @Test
    @DisplayName("of does not flag a different login as self")
    void ofDoesNotFlagDifferentLogin() {
        UserRow row = UserRow.of(user("carol", "Carol"), "dave");
        assertFalse(row.self);
    }

    /**
     * Asserts that the {@code self} flag is {@code false} when the signed-in login is null:
     * the left leg of the {@code &&} short-circuits.
     */
    @Test
    @DisplayName("of does not flag self when the current login is null")
    void ofDoesNotFlagSelfWhenCurrentNull() {
        UserRow row = UserRow.of(user("carol", "Carol"), null);
        assertFalse(row.self);
    }

    /**
     * Asserts that {@link UserRow#getLabel()} returns the display name when it is present and
     * non-blank: the non-blank arm of the ternary.
     */
    @Test
    @DisplayName("getLabel returns the display name when present")
    void getLabelReturnsDisplayName() {
        UserRow row = UserRow.of(user("erin", "Erin M."), "erin");
        assertEquals("Erin M.", row.getLabel());
    }

    /**
     * Asserts that {@link UserRow#getLabel()} falls back to the login when the display name is
     * null: the left leg of the {@code ||} guard.
     */
    @Test
    @DisplayName("getLabel falls back to the login when the display name is null")
    void getLabelFallsBackWhenNull() {
        UserRow row = UserRow.of(user("frank", null), "frank");
        assertEquals("frank", row.getLabel());
    }

    /**
     * Asserts that {@link UserRow#getLabel()} falls back to the login when the display name is
     * blank: the right leg of the {@code ||} guard, reached because the left leg is false.
     */
    @Test
    @DisplayName("getLabel falls back to the login when the display name is blank")
    void getLabelFallsBackWhenBlank() {
        UserRow row = UserRow.of(user("grace", "   "), "grace");
        assertEquals("grace", row.getLabel());
    }

    /**
     * Asserts that {@link UserRow#of} yields an empty role set when the account carries no
     * roles string.
     */
    @Test
    @DisplayName("of derives an empty role set from a blank roles string")
    void ofDerivesEmptyRoleSet() {
        AppUser user = user("heidi", "Heidi");
        user.roles = null;
        UserRow row = UserRow.of(user, "heidi");
        assertTrue(row.roles.isEmpty());
    }

    /**
     * Asserts that {@link UserRow#of} copies a null e-mail and a null display name verbatim,
     * covering the null value of the optional fields.
     */
    @Test
    @DisplayName("of copies null optional fields verbatim")
    void ofCopiesNullOptionalFields() {
        AppUser user = user("ivan", null);
        user.email = null;
        UserRow row = UserRow.of(user, "ivan");
        assertNull(row.displayName);
        assertNull(row.email);
    }

    /**
     * Asserts that {@link UserRow#of} stores the same role-set instance the account produces,
     * confirming no defensive copy is interposed.
     */
    @Test
    @DisplayName("of stores the account's own role set instance")
    void ofStoresRoleSetInstance() {
        AppUser user = user("judy", "Judy");
        Set<String> expected = user.getRoleSet();
        UserRow row = UserRow.of(user, "judy");
        assertEquals(expected, row.roles);
    }
}
