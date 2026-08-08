package com.intermarche.fidelity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.intermarche.fidelity.domain.AppUser;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/**
 * Plain unit coverage for {@link SecurityBootstrap}: the startup seeding of the two
 * operator accounts (§24.1). It exercises both arms of the sole guard
 * {@code AppUser.count() > 0} — the non-empty table short-circuit that seeds nothing and
 * the empty table path that creates the {@code pos} machine account and the
 * {@code fid-admin} human account — and asserts, through the two constructed instances,
 * the full field wiring of the private {@code createUser} helper for its two call shapes
 * (e-mail-less machine account never forced to change its password, versus e-mailed admin
 * account forced to change it).
 * <p>
 * Fully isolated: the {@code imfid.bootstrap.*} configuration fields are set directly on a
 * freshly built instance. The Panache static {@link AppUser#count()} is intercepted with
 * {@code mockStatic} in try-with-resources, and every {@code new AppUser()} the seeding
 * performs is neutralized with {@code mockConstruction} so its {@code setPassword} and
 * {@code persist} never reach the (absent) session; the mocked instances are inspected to
 * verify the exact seeded state. No clock is read: this bootstrap carries no temporal
 * logic.
 */
class SecurityBootstrapTest {

    /**
     * The configured POS account username.
     */
    private static final String POS_USERNAME = "pos-user";

    /**
     * The configured POS account password.
     */
    private static final String POS_PASSWORD = "pos-secret";

    /**
     * The configured administrator username.
     */
    private static final String ADMIN_USERNAME = "admin-user";

    /**
     * The configured administrator password.
     */
    private static final String ADMIN_PASSWORD = "admin-secret";

    /**
     * The configured administrator e-mail address.
     */
    private static final String ADMIN_EMAIL = "boss@imfid.local";

    /**
     * The system under test, freshly built and configured per test.
     */
    private SecurityBootstrap bootstrap;

    /**
     * Wires a fresh bootstrap with the test configuration values.
     */
    @BeforeEach
    void setUp() {
        bootstrap = new SecurityBootstrap();
        bootstrap.posUsername = POS_USERNAME;
        bootstrap.posPassword = POS_PASSWORD;
        bootstrap.adminUsername = ADMIN_USERNAME;
        bootstrap.adminPassword = ADMIN_PASSWORD;
        bootstrap.adminEmail = ADMIN_EMAIL;
    }

    /**
     * The non-empty table arm — an existing user makes {@code count()} strictly positive,
     * so the bootstrap returns immediately without constructing or persisting anything.
     */
    @Test
    @DisplayName("onStart: existing users seed nothing")
    void onStartExistingUsersSeedNothing() {
        try (MockedStatic<PanacheEntityBase> users =
                        org.mockito.Mockito.mockStatic(PanacheEntityBase.class);
                MockedConstruction<AppUser> created =
                        org.mockito.Mockito.mockConstruction(AppUser.class)) {
            users.when(AppUser::count).thenReturn(1L);
            bootstrap.onStart(new StartupEvent());
            assertTrue(created.constructed().isEmpty());
        }
    }

    /**
     * The empty table arm — a zero count seeds exactly two accounts: the {@code pos}
     * machine account and the {@code fid-admin} human account, each with the wiring dictated
     * by {@code createUser}.
     */
    @Test
    @DisplayName("onStart: empty table seeds the two operator accounts")
    void onStartEmptyTableSeedsBothAccounts() {
        try (MockedStatic<PanacheEntityBase> users =
                        org.mockito.Mockito.mockStatic(PanacheEntityBase.class);
                MockedConstruction<AppUser> created =
                        org.mockito.Mockito.mockConstruction(AppUser.class)) {
            users.when(AppUser::count).thenReturn(0L);
            bootstrap.onStart(new StartupEvent());
            assertEquals(2, created.constructed().size());
            AppUser pos = created.constructed().get(0);
            assertEquals(POS_USERNAME, pos.username);
            verify(pos).setPassword(POS_PASSWORD);
            assertEquals(AppUser.ROLE_POS, pos.roles);
            assertEquals("POS API", pos.displayName);
            assertNull(pos.email);
            assertTrue(pos.active);
            assertFalse(pos.mustChangePassword);
            verify(pos).persist();
            AppUser admin = created.constructed().get(1);
            assertEquals(ADMIN_USERNAME, admin.username);
            verify(admin).setPassword(ADMIN_PASSWORD);
            assertEquals(AppUser.ROLE_FID_ADMIN, admin.roles);
            assertEquals("Fidelity administrator", admin.displayName);
            assertEquals(ADMIN_EMAIL, admin.email);
            assertTrue(admin.active);
            assertTrue(admin.mustChangePassword);
            verify(admin).persist();
        }
    }
}
