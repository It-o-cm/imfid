package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link PasswordResetToken}: a single-use password-reset token (§24.1). The
 * usability guard is a four-leg {@code &&}, covered leg by leg with the expiry border tested by the
 * two instants straddling it (§29.6), never the wall clock (§24.6). The Panache active-record finders
 * are driven through a {@link PanacheEntityBase} static mock in a try-with-resources per the imfid
 * unit bench, and the checksum is asserted as the pure function of the business fields it is, both
 * arms of its {@code user != null} ternary exercised.
 */
class PasswordResetTokenTest {

    // --------------------------------------------------
    // isUsableAt()
    // --------------------------------------------------

    /**
     * The first leg false: a consumed token ({@code usedAt} non-null) is unusable forever, the
     * {@code &&} short-circuiting before the expiry and instant checks (§24.1, §29.6).
     */
    @Test
    @DisplayName("isUsableAt(): a consumed token is never usable")
    void isUsableAtConsumed() {
        PasswordResetToken token = new PasswordResetToken();
        token.usedAt = LocalDateTime.of(2026, 3, 1, 10, 0);
        token.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        assertFalse(token.isUsableAt(LocalDateTime.of(2026, 3, 1, 11, 0)));
    }

    /**
     * The second leg false: a pending token with no expiry set is unusable, the guard never
     * dereferencing a null {@code expiresAt} for the {@code isBefore} comparison (§31.2, §29.6).
     */
    @Test
    @DisplayName("isUsableAt(): a null expiry is never usable")
    void isUsableAtNullExpiry() {
        PasswordResetToken token = new PasswordResetToken();
        token.usedAt = null;
        token.expiresAt = null;
        assertFalse(token.isUsableAt(LocalDateTime.of(2026, 3, 1, 11, 0)));
    }

    /**
     * The third leg false: a null test instant is unusable, so the guard never calls {@code isBefore}
     * on a null argument (§31.2, §29.6).
     */
    @Test
    @DisplayName("isUsableAt(): a null instant is never usable")
    void isUsableAtNullInstant() {
        PasswordResetToken token = new PasswordResetToken();
        token.usedAt = null;
        token.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        assertFalse(token.isUsableAt(null));
    }

    /**
     * The fourth leg false at the exact expiry instant: {@code isBefore} is false, so a token is
     * unusable at or after its TTL — the closing side of the frontier (§24.1).
     */
    @Test
    @DisplayName("isUsableAt(): the expiry instant itself is not usable")
    void isUsableAtOnBorder() {
        PasswordResetToken token = new PasswordResetToken();
        token.usedAt = null;
        token.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        assertFalse(token.isUsableAt(LocalDateTime.of(2026, 3, 1, 12, 0)));
    }

    /**
     * All four legs true, one second before the border: a pending, unexpired token tested by an
     * instant strictly before its expiry is usable (§24.1), the open side of the frontier.
     */
    @Test
    @DisplayName("isUsableAt(): a pending token before expiry is usable")
    void isUsableAtBeforeBorder() {
        PasswordResetToken token = new PasswordResetToken();
        token.usedAt = null;
        token.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        assertTrue(token.isUsableAt(LocalDateTime.of(2026, 3, 1, 11, 59, 59)));
    }

    // --------------------------------------------------
    // findByHash()
    // --------------------------------------------------

    /**
     * The finder queries the {@code tokenHash} column and returns its first result — the token
     * whose stored hash matches the hash of the raw value carried in the e-mailed link (§24.1).
     */
    @Test
    @DisplayName("findByHash(): returns the token matching the hash")
    void findByHashFound() {
        PasswordResetToken match = new PasswordResetToken();
        @SuppressWarnings("unchecked")
        PanacheQuery<PasswordResetToken> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(match);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("tokenHash", "deadbeef")).thenReturn(query);
            assertSame(match, PasswordResetToken.findByHash("deadbeef"));
        }
    }

    /**
     * An unknown hash yields a null first result, so the caller distinguishes the absence of any
     * matching token (§24.1).
     */
    @Test
    @DisplayName("findByHash(): returns null when no token matches")
    void findByHashAbsent() {
        @SuppressWarnings("unchecked")
        PanacheQuery<PasswordResetToken> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("tokenHash", "unknown")).thenReturn(query);
            assertNull(PasswordResetToken.findByHash("unknown"));
        }
    }

    // --------------------------------------------------
    // deletePendingFor()
    // --------------------------------------------------

    /**
     * The invalidation deletes the account's pending ({@code usedAt is null}) tokens and returns the
     * count, so at most one live link exists per account after a new reset request (§24.1).
     */
    @Test
    @DisplayName("deletePendingFor(): deletes the pending tokens and returns the count")
    void deletePendingForReturnsCount() {
        AppUser user = new AppUser();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.delete(
                    "user = ?1 and usedAt is null", user)).thenReturn(3L);
            assertEquals(3L, PasswordResetToken.deletePendingFor(user));
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The non-null-user arm folds the account's login name into the checksum: two tokens with
     * identical business fields share it.
     */
    @Test
    @DisplayName("getChecksum(): identical business fields share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * Changing any business field — here the stored hash — changes the checksum, so any token
     * divergence is detected.
     */
    @Test
    @DisplayName("getChecksum(): a different hash alters the checksum")
    void checksumChangesWithHash() {
        PasswordResetToken other = sample();
        other.tokenHash = "cafebabe";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * The null-user arm folds a null login name: a token whose account is not yet attached still
     * computes a checksum without throwing, and it differs from the attached sample.
     */
    @Test
    @DisplayName("getChecksum(): a null user folds a null login name")
    void checksumNullUser() {
        PasswordResetToken detached = sample();
        detached.user = null;
        PasswordResetToken detachedTwin = sample();
        detachedTwin.user = null;
        assertEquals(detached.getChecksum(), detachedTwin.getChecksum());
        assertNotEquals(sample().getChecksum(), detached.getChecksum());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a fully-populated token with fixed business fields for checksum assertions.
     *
     * @return A sample token with deterministic attributes and an attached user.
     */
    private PasswordResetToken sample() {
        AppUser user = new AppUser();
        user.username = "admin";
        PasswordResetToken token = new PasswordResetToken();
        token.user = user;
        token.tokenHash = "deadbeef";
        token.expiresAt = LocalDateTime.of(2026, 3, 1, 12, 0);
        token.usedAt = null;
        return token;
    }
}
