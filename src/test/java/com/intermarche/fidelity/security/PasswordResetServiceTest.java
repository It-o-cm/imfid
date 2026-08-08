package com.intermarche.fidelity.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.PasswordResetToken;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/**
 * Plain unit coverage for {@link PasswordResetService}: the self-service forgot-password
 * flow (§24.1). It exercises every leg of every guard of {@code requestReset} (the
 * unknown-account short-circuit and the full send path) and of {@code resetPassword}
 * (the {@code token == null || token.isBlank()} guard, the
 * {@code reset == null || !reset.isUsableAt(now)} guard, the policy-error guard and the
 * success path), plus the four shapes of the private {@code trimTrailingSlash} helper
 * (null base, blank base, trailing-slash base and slash-less base) observed through the
 * mailed link.
 * <p>
 * Fully isolated: the {@link Mailer} is a Mockito mock injected into the package-private
 * field and {@code tokenTtlMinutes} is set directly. The Panache static finders
 * ({@link AppUser#findActiveByEmail(String)}, {@link PasswordResetToken#deletePendingFor}
 * and {@link PasswordResetToken#findByHash(String)}) are intercepted with
 * {@code mockStatic} in try-with-resources, and the {@code new PasswordResetToken()} the
 * request path performs is neutralized with {@code mockConstruction} so no
 * {@code persist()} ever reaches the (absent) session. Time never comes from the real
 * clock: {@link DateTimeProvider} is fixed to {@link #FIXED} before each test and cleared
 * after, so the token expiry ({@code now().plusMinutes(ttl)}) and the usability check both
 * read a deterministic instant (§24.6, §30.3). {@code AppUser.validatePassword} runs for
 * real — a pure static of another class — so the policy branch reflects the real policy.
 */
class PasswordResetServiceTest {

    /**
     * The fixed program wall time every clock read resolves to (§24.6).
     */
    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 8, 8, 10, 0);

    /**
     * The reset link TTL in minutes wired into the service under test.
     */
    private static final int TTL_MINUTES = 30;

    /**
     * The system under test, freshly built per test with its mocked collaborators.
     */
    private PasswordResetService service;

    /**
     * The mocked mailer capturing the reset e-mail.
     */
    private Mailer mailer;

    /**
     * Wires a fresh service with its mocked mailer, sets the TTL and fixes the clock.
     */
    @BeforeEach
    void setUp() {
        service = new PasswordResetService();
        mailer = mock(Mailer.class);
        service.mailer = mailer;
        service.tokenTtlMinutes = TTL_MINUTES;
        DateTimeProvider.setFixedDateTime(FIXED);
    }

    /**
     * Clears the fixed clock so the fixed instant never leaks into another test.
     */
    @AfterEach
    void tearDown() {
        DateTimeProvider.clear();
    }

    /**
     * Builds a real active user with the given identity fields.
     *
     * @param username    The login name.
     * @param email       The e-mail address.
     * @param displayName The display name, or null to fall back on the username.
     * @return The user.
     */
    private AppUser user(String username, String email, String displayName) {
        AppUser appUser = new AppUser();
        appUser.username = username;
        appUser.email = email;
        appUser.displayName = displayName;
        return appUser;
    }

    /**
     * Drives {@code requestReset} for a known account with the given base URL and returns
     * the text of the single mail sent, so link-building can be asserted.
     *
     * @param baseUrl The base URL passed to the request.
     * @return The captured mail text.
     */
    private String sendAndCaptureText(String baseUrl) {
        AppUser appUser = user("alice", "Alice@Example.com", "Alice A.");
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class);
                MockedStatic<PasswordResetToken> tokens = mockStatic(PasswordResetToken.class);
                MockedConstruction<PasswordResetToken> created =
                        mockConstruction(PasswordResetToken.class)) {
            users.when(() -> AppUser.findActiveByEmail("Alice@Example.com")).thenReturn(appUser);
            service.requestReset("Alice@Example.com", baseUrl);
            ArgumentCaptor<Mail> mailCaptor = ArgumentCaptor.forClass(Mail.class);
            verify(mailer).send(mailCaptor.capture());
            return mailCaptor.getValue().getText();
        }
    }

    /**
     * The unknown-or-e-mail-less account leg: nothing is deleted, stored or mailed and the
     * method returns silently.
     */
    @Test
    @DisplayName("requestReset: unknown account sends nothing")
    void requestResetUnknownAccountSendsNothing() {
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class);
                MockedStatic<PasswordResetToken> tokens = mockStatic(PasswordResetToken.class)) {
            users.when(() -> AppUser.findActiveByEmail("ghost@example.com")).thenReturn(null);
            service.requestReset("ghost@example.com", "http://localhost:8060");
            tokens.verify(() -> PasswordResetToken.deletePendingFor(any()), never());
            verify(mailer, never()).send(any(Mail.class));
        }
    }

    /**
     * The known-account send path: pending tokens are invalidated, a fresh token is stored
     * with the TTL-derived expiry read from the fixed clock, and the mail carries the
     * subject, recipient, label, username, TTL and single-use link.
     */
    @Test
    @DisplayName("requestReset: known account stores token and mails link")
    void requestResetKnownAccountStoresAndMails() {
        AppUser appUser = user("alice", "alice@example.com", "Alice A.");
        try (MockedStatic<AppUser> users = mockStatic(AppUser.class);
                MockedStatic<PasswordResetToken> tokens = mockStatic(PasswordResetToken.class);
                MockedConstruction<PasswordResetToken> created =
                        mockConstruction(PasswordResetToken.class)) {
            users.when(() -> AppUser.findActiveByEmail("alice@example.com")).thenReturn(appUser);
            service.requestReset("alice@example.com", "http://localhost:8060");
            tokens.verify(() -> PasswordResetToken.deletePendingFor(appUser));
            assertEquals(1, created.constructed().size());
            PasswordResetToken stored = created.constructed().get(0);
            assertSame(appUser, stored.user);
            assertEquals(64, stored.tokenHash.length());
            assertEquals(FIXED.plusMinutes(TTL_MINUTES), stored.expiresAt);
            assertNull(stored.usedAt);
            verify(stored).persist();
            ArgumentCaptor<Mail> mailCaptor = ArgumentCaptor.forClass(Mail.class);
            verify(mailer).send(mailCaptor.capture());
            Mail mail = mailCaptor.getValue();
            assertEquals("alice@example.com", mail.getTo().get(0));
            assertEquals("Fidélité admin — réinitialisation de votre mot de passe", mail.getSubject());
            String text = mail.getText();
            assertTrue(text.contains("Bonjour Alice A.,"));
            assertTrue(text.contains("'alice'"));
            assertTrue(text.contains(TTL_MINUTES + " minutes"));
            assertTrue(text.contains("http://localhost:8060/ui/reset?token="));
        }
    }

    /**
     * {@code trimTrailingSlash} leg — a base URL ending with a slash has it removed so the
     * link never doubles the separator.
     */
    @Test
    @DisplayName("requestReset: trailing slash in base URL is trimmed")
    void requestResetTrimsTrailingSlash() {
        String text = sendAndCaptureText("http://localhost:8060/");
        assertTrue(text.contains("http://localhost:8060/ui/reset?token="));
        assertFalse(text.contains("8060//ui/reset"));
    }

    /**
     * {@code trimTrailingSlash} leg — a slash-less base URL is used verbatim.
     */
    @Test
    @DisplayName("requestReset: slash-less base URL is used verbatim")
    void requestResetKeepsSlashLessBaseUrl() {
        String text = sendAndCaptureText("http://localhost:8060");
        assertTrue(text.contains("http://localhost:8060/ui/reset?token="));
    }

    /**
     * {@code trimTrailingSlash} first leg — a null base URL yields an empty prefix.
     */
    @Test
    @DisplayName("requestReset: null base URL yields a root-relative link")
    void requestResetNullBaseUrlYieldsRelativeLink() {
        String text = sendAndCaptureText(null);
        assertTrue(text.contains("\n\n/ui/reset?token="));
    }

    /**
     * {@code trimTrailingSlash} second leg — a blank, non-null base URL yields an empty
     * prefix as well.
     */
    @Test
    @DisplayName("requestReset: blank base URL yields a root-relative link")
    void requestResetBlankBaseUrlYieldsRelativeLink() {
        String text = sendAndCaptureText("   ");
        assertTrue(text.contains("\n\n/ui/reset?token="));
    }

    /**
     * {@code resetPassword} first leg — a null token is rejected without any lookup.
     */
    @Test
    @DisplayName("resetPassword: null token is rejected")
    void resetPasswordNullTokenRejected() {
        try (MockedStatic<PasswordResetToken> tokens = mockStatic(PasswordResetToken.class)) {
            String result = service.resetPassword(null, "whatever8");
            assertEquals("Lien de réinitialisation invalide.", result);
            tokens.verify(() -> PasswordResetToken.findByHash(any()), never());
        }
    }

    /**
     * {@code resetPassword} second leg — a blank, non-null token is rejected without any
     * lookup.
     */
    @Test
    @DisplayName("resetPassword: blank token is rejected")
    void resetPasswordBlankTokenRejected() {
        try (MockedStatic<PasswordResetToken> tokens = mockStatic(PasswordResetToken.class)) {
            String result = service.resetPassword("   ", "whatever8");
            assertEquals("Lien de réinitialisation invalide.", result);
            tokens.verify(() -> PasswordResetToken.findByHash(any()), never());
        }
    }

    /**
     * {@code resetPassword} first leg of the token-state guard — no token matches the hash.
     */
    @Test
    @DisplayName("resetPassword: unknown token is rejected")
    void resetPasswordUnknownTokenRejected() {
        try (MockedStatic<PasswordResetToken> tokens = mockStatic(PasswordResetToken.class)) {
            tokens.when(() -> PasswordResetToken.findByHash(any())).thenReturn(null);
            String result = service.resetPassword("raw-token", "whatever8");
            assertEquals(
                    "Ce lien de réinitialisation est invalide, déjà utilisé ou expiré. Refaites une demande.",
                    result);
        }
    }

    /**
     * {@code resetPassword} second leg of the token-state guard — a found token that is not
     * usable at the fixed instant is rejected, and the usability is checked against the
     * fixed clock.
     */
    @Test
    @DisplayName("resetPassword: unusable token is rejected")
    void resetPasswordUnusableTokenRejected() {
        PasswordResetToken reset = mock(PasswordResetToken.class);
        when(reset.isUsableAt(FIXED)).thenReturn(false);
        try (MockedStatic<PasswordResetToken> tokens = mockStatic(PasswordResetToken.class)) {
            tokens.when(() -> PasswordResetToken.findByHash(any())).thenReturn(reset);
            String result = service.resetPassword("raw-token", "whatever8");
            assertEquals(
                    "Ce lien de réinitialisation est invalide, déjà utilisé ou expiré. Refaites une demande.",
                    result);
            verify(reset).isUsableAt(FIXED);
            verify(reset, never()).persist();
        }
    }

    /**
     * {@code resetPassword} policy-error leg — a usable token but a policy-violating
     * password returns the policy message and consumes nothing.
     */
    @Test
    @DisplayName("resetPassword: usable token but weak password returns policy error")
    void resetPasswordWeakPasswordReturnsPolicyError() {
        PasswordResetToken reset = mock(PasswordResetToken.class);
        when(reset.isUsableAt(FIXED)).thenReturn(true);
        AppUser appUser = mock(AppUser.class);
        reset.user = appUser;
        try (MockedStatic<PasswordResetToken> tokens = mockStatic(PasswordResetToken.class)) {
            tokens.when(() -> PasswordResetToken.findByHash(any())).thenReturn(reset);
            String result = service.resetPassword("raw-token", "short");
            assertEquals("The password must be at least 8 characters long.", result);
            verify(appUser, never()).setPassword(any());
            verify(reset, never()).persist();
        }
    }

    /**
     * {@code resetPassword} success path — a usable token and a compliant password set the
     * new password, clear the change-required flag, stamp the consumption at the fixed
     * instant and persist both entities, returning null.
     */
    @Test
    @DisplayName("resetPassword: usable token and strong password succeeds")
    void resetPasswordSucceeds() {
        PasswordResetToken reset = mock(PasswordResetToken.class);
        when(reset.isUsableAt(FIXED)).thenReturn(true);
        AppUser appUser = mock(AppUser.class);
        appUser.mustChangePassword = true;
        reset.user = appUser;
        try (MockedStatic<PasswordResetToken> tokens = mockStatic(PasswordResetToken.class)) {
            tokens.when(() -> PasswordResetToken.findByHash(any())).thenReturn(reset);
            String result = service.resetPassword("raw-token", "strongPassword1");
            assertNull(result);
            verify(appUser).setPassword("strongPassword1");
            assertFalse(appUser.mustChangePassword);
            verify(appUser).persist();
            assertEquals(FIXED, reset.usedAt);
            verify(reset).persist();
        }
    }
}
