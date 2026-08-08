package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.security.Security;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.wildfly.security.password.WildFlyElytronPasswordProvider;

/**
 * Plain unit coverage for {@link AppUser}: the operator login of the two admin/POS surfaces
 * (§24.1). Every guard, ternary and compound predicate is exercised on both arms and every
 * leg (§29, §29.6). Credential hashing and verification run against the real bcrypt stack
 * (no Quarkus boot needed), while the Panache active-record finders are mocked through
 * {@link PanacheEntityBase} in a try-with-resources per the imfid unit bench. The class holds
 * no temporal logic, so nothing here reads a {@code DateTimeProvider}.
 */
class AppUserTest {

    /**
     * Registers the WildFly Elytron password provider with the JCA so bcrypt
     * {@link java.security.spec.KeySpec} translation and verification resolve outside a
     * Quarkus boot — the runtime registers it for us, the plain unit bench does not.
     */
    @BeforeAll
    static void registerBcryptProvider() {
        Security.addProvider(WildFlyElytronPasswordProvider.getInstance());
    }

    // --------------------------------------------------
    // setPassword() / matchesPassword()
    // --------------------------------------------------

    /**
     * Setting a password stores a bcrypt hash — never the clear text — and the matching hash
     * verifies against it, the nominal round-trip.
     */
    @Test
    @DisplayName("setPassword()/matchesPassword(): bcrypt round-trip verifies the right password")
    void passwordRoundTripMatches() {
        AppUser user = new AppUser();
        user.setPassword("s3cretPass");
        assertNotEquals("s3cretPass", user.password);
        assertTrue(user.password.startsWith("$2"));
        assertTrue(user.matchesPassword("s3cretPass"));
    }

    /**
     * A wrong clear text does not verify against the stored hash — both operands non-null,
     * bcrypt verification returns false.
     */
    @Test
    @DisplayName("matchesPassword(): a wrong password does not verify")
    void matchesWrongPassword() {
        AppUser user = new AppUser();
        user.setPassword("s3cretPass");
        assertFalse(user.matchesPassword("wrongPass"));
    }

    /**
     * A null clear text short-circuits on the first leg of the guard and never touches the
     * hash — {@code clearText == null} true, {@code password == null} irrelevant.
     */
    @Test
    @DisplayName("matchesPassword(): a null clear text returns false (first leg)")
    void matchesNullClearText() {
        AppUser user = new AppUser();
        user.setPassword("s3cretPass");
        assertFalse(user.matchesPassword(null));
    }

    /**
     * A null stored hash fails the second leg of the guard even with a non-null clear text —
     * {@code clearText == null} false, {@code password == null} true.
     */
    @Test
    @DisplayName("matchesPassword(): a null stored hash returns false (second leg)")
    void matchesNullStoredHash() {
        AppUser user = new AppUser();
        user.password = null;
        assertFalse(user.matchesPassword("anything"));
    }

    /**
     * A malformed stored hash makes the wildfly crypto stack raise a
     * {@code GeneralSecurityException}, which is swallowed into a false result — the catch arm.
     */
    @Test
    @DisplayName("matchesPassword(): a malformed hash is caught and returns false")
    void matchesMalformedHash() {
        AppUser user = new AppUser();
        user.password = "not-a-valid-modular-crypt-hash";
        assertFalse(user.matchesPassword("anything"));
    }

    // --------------------------------------------------
    // validatePassword()
    // --------------------------------------------------

    /**
     * A null candidate is rejected as mandatory — the first leg of the blank guard.
     */
    @Test
    @DisplayName("validatePassword(): null is mandatory (first leg)")
    void validateNull() {
        assertEquals("The password is mandatory.", AppUser.validatePassword(null));
    }

    /**
     * A blank candidate is rejected as mandatory — the second leg of the blank guard.
     */
    @Test
    @DisplayName("validatePassword(): blank is mandatory (second leg)")
    void validateBlank() {
        assertEquals("The password is mandatory.", AppUser.validatePassword("   "));
    }

    /**
     * A candidate shorter than the minimum length is rejected — the length guard true arm at
     * seven characters, one below the boundary.
     */
    @Test
    @DisplayName("validatePassword(): too short is rejected")
    void validateTooShort() {
        assertEquals("The password must be at least 8 characters long.",
                AppUser.validatePassword("1234567"));
    }

    /**
     * A candidate exactly at the minimum length passes — the length guard false arm at the
     * eight-character boundary.
     */
    @Test
    @DisplayName("validatePassword(): eight characters is accepted (boundary)")
    void validateBoundaryAccepted() {
        assertNull(AppUser.validatePassword("12345678"));
    }

    // --------------------------------------------------
    // getRoleSet()
    // --------------------------------------------------

    /**
     * A null roles string yields an empty set — the first leg of the blank guard.
     */
    @Test
    @DisplayName("getRoleSet(): null roles yields an empty set (first leg)")
    void roleSetFromNull() {
        AppUser user = new AppUser();
        user.roles = null;
        assertTrue(user.getRoleSet().isEmpty());
    }

    /**
     * A blank roles string yields an empty set — the second leg of the blank guard.
     */
    @Test
    @DisplayName("getRoleSet(): blank roles yields an empty set (second leg)")
    void roleSetFromBlank() {
        AppUser user = new AppUser();
        user.roles = "   ";
        assertTrue(user.getRoleSet().isEmpty());
    }

    /**
     * A well-formed roles string is split, trimmed and preserved in encounter order — the
     * empty-token filter stays true for every real token.
     */
    @Test
    @DisplayName("getRoleSet(): splits and trims the granted roles in order")
    void roleSetFromValue() {
        AppUser user = new AppUser();
        user.roles = " pos , fid-admin ";
        Set<String> expected = new LinkedHashSet<>(List.of("pos", "fid-admin"));
        assertEquals(expected, user.getRoleSet());
    }

    /**
     * An empty token produced by a doubled separator is dropped — the empty-token filter's
     * false leg alongside the true legs of the surrounding roles.
     */
    @Test
    @DisplayName("getRoleSet(): drops empty tokens from doubled separators")
    void roleSetDropsEmptyTokens() {
        AppUser user = new AppUser();
        user.roles = "pos,,fid-admin";
        Set<String> expected = new LinkedHashSet<>(List.of("pos", "fid-admin"));
        assertEquals(expected, user.getRoleSet());
    }

    // --------------------------------------------------
    // setRoleSet()
    // --------------------------------------------------

    /**
     * Both known roles are stored in the canonical {@link AppUser#ALL_ROLES} order regardless
     * of the input iteration order — the contains predicate true for each role.
     */
    @Test
    @DisplayName("setRoleSet(): stores both roles in canonical order")
    void setRoleSetBoth() {
        AppUser user = new AppUser();
        Set<String> input = new LinkedHashSet<>(List.of(AppUser.ROLE_FID_ADMIN, AppUser.ROLE_POS));
        user.setRoleSet(input);
        assertEquals("pos,fid-admin", user.roles);
    }

    /**
     * A single granted role keeps only itself — the contains predicate true for {@code pos}
     * and false for {@code fid-admin}.
     */
    @Test
    @DisplayName("setRoleSet(): a single role excludes the ungranted one (false leg)")
    void setRoleSetSingle() {
        AppUser user = new AppUser();
        user.setRoleSet(new LinkedHashSet<>(List.of(AppUser.ROLE_POS)));
        assertEquals("pos", user.roles);
    }

    /**
     * An empty grant produces an empty roles string — the contains predicate false for every
     * canonical role.
     */
    @Test
    @DisplayName("setRoleSet(): an empty grant produces an empty string")
    void setRoleSetEmpty() {
        AppUser user = new AppUser();
        user.setRoleSet(new LinkedHashSet<>());
        assertEquals("", user.roles);
    }

    // --------------------------------------------------
    // hasRole()
    // --------------------------------------------------

    /**
     * A granted role is reported present — the contains true arm.
     */
    @Test
    @DisplayName("hasRole(): a granted role is present")
    void hasRoleTrue() {
        AppUser user = new AppUser();
        user.roles = "pos,fid-admin";
        assertTrue(user.hasRole(AppUser.ROLE_FID_ADMIN));
    }

    /**
     * An ungranted role is reported absent — the contains false arm.
     */
    @Test
    @DisplayName("hasRole(): an ungranted role is absent")
    void hasRoleFalse() {
        AppUser user = new AppUser();
        user.roles = "pos";
        assertFalse(user.hasRole(AppUser.ROLE_FID_ADMIN));
    }

    // --------------------------------------------------
    // getLabel()
    // --------------------------------------------------

    /**
     * A null display name falls back to the username — the first leg of the label ternary.
     */
    @Test
    @DisplayName("getLabel(): a null display name falls back to the username (first leg)")
    void labelFallsBackOnNull() {
        AppUser user = new AppUser();
        user.username = "operator1";
        user.displayName = null;
        assertEquals("operator1", user.getLabel());
    }

    /**
     * A blank display name falls back to the username — the second leg of the label ternary.
     */
    @Test
    @DisplayName("getLabel(): a blank display name falls back to the username (second leg)")
    void labelFallsBackOnBlank() {
        AppUser user = new AppUser();
        user.username = "operator1";
        user.displayName = "  ";
        assertEquals("operator1", user.getLabel());
    }

    /**
     * A set display name is returned verbatim — the ternary's false arm.
     */
    @Test
    @DisplayName("getLabel(): a set display name is used")
    void labelUsesDisplayName() {
        AppUser user = new AppUser();
        user.username = "operator1";
        user.displayName = "Alice";
        assertEquals("Alice", user.getLabel());
    }

    // --------------------------------------------------
    // findByUsername()
    // --------------------------------------------------

    /**
     * The username finder delegates to the Panache query and returns its first result.
     */
    @Test
    @DisplayName("findByUsername(): returns the matching user")
    void findByUsernameFound() {
        AppUser found = new AppUser();
        @SuppressWarnings("unchecked")
        PanacheQuery<AppUser> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("username", "bob")).thenReturn(query);
            assertSame(found, AppUser.findByUsername("bob"));
        }
    }

    /**
     * The username finder returns null when the query yields nothing.
     */
    @Test
    @DisplayName("findByUsername(): returns null when absent")
    void findByUsernameAbsent() {
        @SuppressWarnings("unchecked")
        PanacheQuery<AppUser> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("username", "ghost")).thenReturn(query);
            assertNull(AppUser.findByUsername("ghost"));
        }
    }

    // --------------------------------------------------
    // findActiveByEmail()
    // --------------------------------------------------

    /**
     * A null e-mail short-circuits the finder to null — the first leg of the blank guard.
     */
    @Test
    @DisplayName("findActiveByEmail(): null e-mail returns null (first leg)")
    void findActiveByEmailNull() {
        assertNull(AppUser.findActiveByEmail(null));
    }

    /**
     * A blank e-mail short-circuits the finder to null — the second leg of the blank guard.
     */
    @Test
    @DisplayName("findActiveByEmail(): blank e-mail returns null (second leg)")
    void findActiveByEmailBlank() {
        assertNull(AppUser.findActiveByEmail("   "));
    }

    /**
     * A non-blank e-mail is trimmed and lower-cased before the active-only lookup, whose first
     * result is returned — the guard's false arm.
     */
    @Test
    @DisplayName("findActiveByEmail(): normalises the e-mail and returns the active user")
    void findActiveByEmailFound() {
        AppUser found = new AppUser();
        @SuppressWarnings("unchecked")
        PanacheQuery<AppUser> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "active = true and lower(email) = ?1", "bob@example.com")).thenReturn(query);
            assertSame(found, AppUser.findActiveByEmail("  Bob@Example.COM "));
        }
    }

    // --------------------------------------------------
    // countActiveAdmins()
    // --------------------------------------------------

    /**
     * The active-admin count delegates to the Panache count with the fid-admin like-pattern.
     */
    @Test
    @DisplayName("countActiveAdmins(): delegates to the Panache count")
    void countActiveAdminsDelegates() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.count(
                    "active = true and roles like ?1", "%fid-admin%")).thenReturn(3L);
            assertEquals(3L, AppUser.countActiveAdmins());
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The checksum is a pure function of the business fields: two users with identical
     * attributes share it.
     */
    @Test
    @DisplayName("getChecksum(): identical business fields share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * Changing any business field — here the password hash — changes the checksum, so a
     * credential change is detected like any other modification.
     */
    @Test
    @DisplayName("getChecksum(): a credential change alters the checksum")
    void checksumChangesWithPassword() {
        AppUser other = sample();
        other.password = "$2a$10$differenthashvalue";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a fully-populated user with fixed business fields for checksum assertions.
     *
     * @return A sample user with deterministic attributes.
     */
    private AppUser sample() {
        AppUser user = new AppUser();
        user.username = "operator1";
        user.password = "$2a$10$fixedhashvalueforchecksum";
        user.roles = "pos,fid-admin";
        user.displayName = "Operator One";
        user.email = "op1@example.com";
        user.active = true;
        user.mustChangePassword = false;
        return user;
    }
}
