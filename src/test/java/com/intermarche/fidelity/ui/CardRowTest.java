package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.FidelityAccount;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link CardRow}, the card-list row view model (§23.1). The class
 * carries no clock and no Panache access: {@link CardRow#of} copies the account's business
 * fields verbatim and {@link CardRow#getBadgeClass()} maps the status string to a badge
 * modifier class. Accounts are built in memory (no boot, no H2, no static finder) and the
 * temporal field is fixed to a deterministic instant, never the real clock (§24.6). Every arm
 * of the {@code getBadgeClass} switch is exercised: {@code "ACTIVE"}, {@code "RESILIATED"}
 * and the {@code default} leg reached by {@code PENDING_ACTIVATION}.
 */
class CardRowTest {

    /** A fixed, deterministic instant standing in for the last fiscal use (§24.6). */
    private static final LocalDateTime FIXED_LAST_USED = LocalDateTime.of(2026, 8, 8, 10, 30);

    /**
     * Builds an in-memory account with the supplied status and a non-null last use.
     *
     * @param status The account status to set.
     * @return The account, its business fields populated.
     */
    private static FidelityAccount account(AccountStatus status) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = "3450000000001";
        account.status = status;
        account.balance = new BigDecimal("12.34").setScale(2, RoundingMode.HALF_UP);
        account.lastUsedAt = FIXED_LAST_USED;
        return account;
    }

    /**
     * Asserts that {@link CardRow#of} copies every business field of the account and stores
     * the supplied month-visit count, the status being rendered through its enum name.
     */
    @Test
    @DisplayName("of copies the account's fields verbatim")
    void ofCopiesFields() {
        FidelityAccount account = account(AccountStatus.ACTIVE);
        CardRow row = CardRow.of(account, 7);
        assertEquals("3450000000001", row.cardNumber);
        assertEquals("ACTIVE", row.status);
        assertEquals(0, new BigDecimal("12.34").compareTo(row.balance));
        assertSame(FIXED_LAST_USED, row.lastUsedAt);
        assertEquals(7, row.monthVisits);
    }

    /**
     * Asserts that {@link CardRow#of} preserves a null last use, the absent value the template
     * renders as an em dash (§21.4).
     */
    @Test
    @DisplayName("of preserves a null last use")
    void ofPreservesNullLastUse() {
        FidelityAccount account = account(AccountStatus.ACTIVE);
        account.lastUsedAt = null;
        CardRow row = CardRow.of(account, 0);
        assertNull(row.lastUsedAt);
        assertEquals(0, row.monthVisits);
    }

    /**
     * Asserts that an ACTIVE status maps to the success badge. Covers the {@code "ACTIVE"} arm
     * of the {@link CardRow#getBadgeClass()} switch.
     */
    @Test
    @DisplayName("getBadgeClass maps ACTIVE to badge-ok")
    void badgeClassActive() {
        CardRow row = CardRow.of(account(AccountStatus.ACTIVE), 1);
        assertEquals("badge-ok", row.getBadgeClass());
    }

    /**
     * Asserts that a RESILIATED status maps to the error badge. Covers the
     * {@code "RESILIATED"} arm of the {@link CardRow#getBadgeClass()} switch.
     */
    @Test
    @DisplayName("getBadgeClass maps RESILIATED to badge-error")
    void badgeClassResiliated() {
        CardRow row = CardRow.of(account(AccountStatus.RESILIATED), 1);
        assertEquals("badge-error", row.getBadgeClass());
    }

    /**
     * Asserts that any other status maps to the off badge. Covers the {@code default} leg of
     * the {@link CardRow#getBadgeClass()} switch, reached here by PENDING_ACTIVATION.
     */
    @Test
    @DisplayName("getBadgeClass maps PENDING_ACTIVATION to badge-off")
    void badgeClassDefault() {
        CardRow row = CardRow.of(account(AccountStatus.PENDING_ACTIVATION), 1);
        assertEquals("badge-off", row.getBadgeClass());
    }
}
