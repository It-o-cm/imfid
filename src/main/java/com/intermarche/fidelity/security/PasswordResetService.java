package com.intermarche.fidelity.security;

import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.PasswordResetToken;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The self-service "forgot password" flow (§24.1): request a reset by e-mail, then set
 * a new password through the single-use link.
 * <p>
 * The request path never reveals whether an address exists (no account enumeration):
 * an unknown, blank or e-mail-less account silently sends nothing, and the screen
 * always answers the same neutral message. The raw token travels only in the mailed
 * link; the database stores its SHA-256 hash. Outside production the Quarkus mailer is
 * mocked — the mail (and thus the link) is written to the log, so the flow is testable
 * in dev without an SMTP server.
 */
@ApplicationScoped
public class PasswordResetService {

    private static final Logger LOGGER = Logger.getLogger(PasswordResetService.class);

    /**
     * The number of random bytes of a raw token (32 bytes → 64 hex chars).
     */
    private static final int TOKEN_BYTES = 32;

    /**
     * The random source of the raw tokens.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The mailer sending the reset links (mocked outside production).
     */
    @Inject
    Mailer mailer;

    /**
     * The validity of a reset link, in minutes.
     */
    @ConfigProperty(name = "imfid.reset.token-ttl-minutes", defaultValue = "30")
    int tokenTtlMinutes;

    /**
     * Handles a "forgot password" request for an e-mail address (§24.1): when an
     * active account carries the address, invalidates its pending tokens, stores a
     * fresh hashed token and mails the reset link; otherwise does nothing. Always
     * returns silently so the caller cannot distinguish the two cases.
     *
     * @param email   The e-mail address as typed on the public form.
     * @param baseUrl The application base URL (scheme://host[:port]) the link is
     *                built from, derived from the incoming request.
     */
    @Transactional
    public void requestReset(String email, String baseUrl) {
        AppUser user = AppUser.findActiveByEmail(email);
        if (user == null) {
            LOGGER.debugf("Password reset requested for an unknown or e-mail-less account; nothing sent");
            return;
        }
        PasswordResetToken.deletePendingFor(user);
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        PasswordResetToken reset = new PasswordResetToken();
        reset.user = user;
        reset.tokenHash = sha256Hex(token);
        reset.expiresAt = DateTimeProvider.now().plusMinutes(tokenTtlMinutes);
        reset.persist();

        String link = trimTrailingSlash(baseUrl) + "/ui/reset?token=" + token;
        mailer.send(Mail.withText(user.email,
                "Fidélité admin — réinitialisation de votre mot de passe",
                "Bonjour " + user.getLabel() + ",\n\n"
                        + "Une réinitialisation du mot de passe de votre compte '" + user.username
                        + "' a été demandée.\n\n"
                        + "Pour choisir un nouveau mot de passe, ouvrez ce lien (valable "
                        + tokenTtlMinutes + " minutes, à usage unique) :\n\n"
                        + link + "\n\n"
                        + "Si vous n'êtes pas à l'origine de cette demande, ignorez ce message : "
                        + "votre mot de passe reste inchangé."));
        LOGGER.infof("Password reset link sent to the address of account '%s'", user.username);
    }

    /**
     * Consumes a reset link and sets the new password (§24.1): the token must exist,
     * be unconsumed and unexpired, and the new password must satisfy the policy
     * ({@link AppUser#validatePassword(String)}).
     *
     * @param token       The raw token carried by the link.
     * @param newPassword The new password as typed by the user.
     * @return null on success, or the error message to display.
     */
    @Transactional
    public String resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            return "Lien de réinitialisation invalide.";
        }
        PasswordResetToken reset = PasswordResetToken.findByHash(sha256Hex(token.trim()));
        if (reset == null || !reset.isUsableAt(DateTimeProvider.now())) {
            return "Ce lien de réinitialisation est invalide, déjà utilisé ou expiré. Refaites une demande.";
        }
        String policyError = AppUser.validatePassword(newPassword);
        if (policyError != null) {
            return policyError;
        }
        AppUser user = reset.user;
        user.setPassword(newPassword);
        user.mustChangePassword = false;
        user.persist();
        reset.usedAt = DateTimeProvider.now();
        reset.persist();
        LOGGER.infof("Password reset completed for account '%s'", user.username);
        return null;
    }

    /**
     * Hashes a raw token with SHA-256, hex encoded — the only form ever stored.
     *
     * @param value The raw token.
     * @return The hex-encoded SHA-256 hash.
     */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Removes a trailing slash from the base URL so the link concatenation never
     * doubles it.
     *
     * @param baseUrl The base URL, may end with a slash.
     * @return The base URL without trailing slash.
     */
    private static String trimTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
