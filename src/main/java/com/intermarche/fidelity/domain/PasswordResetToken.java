package com.intermarche.fidelity.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A single-use password-reset token (§24.1).
 * <p>
 * The raw token travels only inside the e-mailed link; the database stores its SHA-256
 * hash, so a database read never yields a usable link. A token is consumed on first use
 * ({@link #usedAt}) and expires after its TTL ({@link #expiresAt}); requesting a new
 * reset invalidates the previous pending tokens of the account.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "password_reset_tokens",
        indexes = {
                @Index(name = "idx_reset_token_hash", columnList = "token_hash"),
                @Index(name = "idx_reset_token_user", columnList = "user_id")
        }
)
public class PasswordResetToken extends BaseEntity {

    /**
     * The account the token resets.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    public AppUser user;

    /**
     * The SHA-256 hash (hex) of the raw token; the raw value is never stored.
     */
    @Column(name = "token_hash", unique = true, nullable = false, length = 64)
    public String tokenHash;

    /**
     * When the token expires; a token is unusable at or after this instant.
     */
    @Column(name = "expires_at", nullable = false)
    public LocalDateTime expiresAt;

    /**
     * When the token was consumed; null while still pending. A consumed token is
     * unusable forever.
     */
    @Column(name = "used_at")
    public LocalDateTime usedAt;

    // --------------------------------------------------
    // Domain helpers
    // --------------------------------------------------

    /**
     * Indicates whether the token is still usable at the given instant: never
     * consumed and not yet expired.
     *
     * @param at The instant to test.
     * @return true when the token may still reset the password.
     */
    public boolean isUsableAt(LocalDateTime at) {
        return usedAt == null && expiresAt != null && at != null && at.isBefore(expiresAt);
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a token by the hash of its raw value.
     *
     * @param tokenHash The SHA-256 hash (hex) of the raw token.
     * @return The token, or null when none matches.
     */
    public static PasswordResetToken findByHash(String tokenHash) {
        return find("tokenHash", tokenHash).firstResult();
    }

    /**
     * Deletes the pending tokens of a user — called when a new reset is requested, so
     * at most one link is live per account.
     *
     * @param user The account whose pending tokens are invalidated.
     * @return The number of deleted tokens.
     */
    public static long deletePendingFor(AppUser user) {
        return delete("user = ?1 and usedAt is null", user);
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum from the token's business fields, using the user's login
     * name as the stable account reference.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        String username = user != null ? user.username : null;
        return Objects.hash(username, tokenHash, expiresAt, usedAt);
    }
}
