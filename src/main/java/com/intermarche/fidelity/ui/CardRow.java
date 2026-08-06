package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.domain.FidelityAccount;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of the card list (§23.1): the number, the status badge, the balance, the last
 * use and the month's visits. Absent values are rendered as an em dash by the template,
 * never a blank cell (§21.4).
 * <p>
 * Public fields are resolved by Qute.
 */
public final class CardRow {

    /**
     * The card number.
     */
    public String cardNumber;

    /**
     * The account status (drives the badge).
     */
    public String status;

    /**
     * The balance, euro at scale 2.
     */
    public BigDecimal balance;

    /**
     * The last fiscal use, or null.
     */
    public LocalDateTime lastUsedAt;

    /**
     * The month's distinct visits.
     */
    public int monthVisits;

    /**
     * Builds a card row from an account and its month-visit count.
     *
     * @param account     The account.
     * @param monthVisits The month's visits.
     * @return The card row.
     */
    public static CardRow of(FidelityAccount account, int monthVisits) {
        CardRow row = new CardRow();
        row.cardNumber = account.cardNumber;
        row.status = account.status.name();
        row.balance = account.balance;
        row.lastUsedAt = account.lastUsedAt;
        row.monthVisits = monthVisits;
        return row;
    }

    /**
     * Returns the CSS badge class for the status.
     *
     * @return The badge modifier class.
     */
    public String getBadgeClass() {
        return switch (status) {
            case "ACTIVE" -> "badge-ok";
            case "RESILIATED" -> "badge-error";
            default -> "badge-off";
        };
    }
}
