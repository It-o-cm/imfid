package com.intermarche.fidelity.account;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The read DTOs of the account API (§27.2): the account summary, its monthly cap
 * cumulatives, its memberships and its paginated movements. The scope nomenclature of
 * the caps is the closed {@code RULE:<code>} | {@code COMMUNITY:<code>} | {@code GLOBAL}
 * (§27.2), the same as {@code capsApplied} of the projection (§27.1).
 * <p>
 * Plain carriers; fields are public for Jackson.
 */
public final class AccountViews {

    /**
     * Non-instantiable holder of the account read DTOs.
     */
    private AccountViews() {
    }

    /**
     * The account summary returned by {@code GET /api/accounts/{card}} (§27.2).
     */
    public static final class Summary {

        /**
         * The card number.
         */
        public String cardNumber;

        /**
         * The account status (§25.3).
         */
        public String status;

        /**
         * The balance, euro at scale 2.
         */
        public BigDecimal balance;

        /**
         * The available balance = balance − active reservations (I11).
         */
        public BigDecimal availableBalance;

        /**
         * The month's distinct visits (I3, §29.1).
         */
        public int monthVisits;

        /**
         * The monthly cap cumulatives (§27.2). Never null.
         */
        public List<CapView> monthlyCaps = new ArrayList<>();

        /**
         * The community memberships (§27.2). Never null.
         */
        public List<MembershipView> memberships = new ArrayList<>();
    }

    /**
     * One monthly cap cumulative (§27.2).
     */
    public static final class CapView {

        /**
         * The cap scope: {@code RULE:<code>} | {@code COMMUNITY:<code>} | {@code GLOBAL}.
         */
        public String scope;

        /**
         * The cap value, euro at scale 2.
         */
        public BigDecimal cap;

        /**
         * The euro used against the cap this month, euro at scale 2.
         */
        public BigDecimal used;

        /**
         * Builds a cap view.
         *
         * @param scope The cap scope.
         * @param cap   The cap value.
         * @param used  The used amount.
         */
        public CapView(String scope, BigDecimal cap, BigDecimal used) {
            this.scope = scope;
            this.cap = cap;
            this.used = used;
        }
    }

    /**
     * One community membership (§27.2).
     */
    public static final class MembershipView {

        /**
         * The community code.
         */
        public String community;

        /**
         * The membership start date.
         */
        public LocalDate validFrom;

        /**
         * The membership end date, or null while open.
         */
        public LocalDate validTo;

        /**
         * Builds a membership view.
         *
         * @param community The community code.
         * @param validFrom The start date.
         * @param validTo   The end date, or null.
         */
        public MembershipView(String community, LocalDate validFrom, LocalDate validTo) {
            this.community = community;
            this.validFrom = validFrom;
            this.validTo = validTo;
        }
    }

    /**
     * A page of movements returned by {@code GET /api/accounts/{card}/movements} (§27.2).
     */
    public static final class MovementPage {

        /**
         * The total number of movements of the account.
         */
        public long totalCount;

        /**
         * The movements of the requested page. Never null.
         */
        public List<MovementView> items = new ArrayList<>();
    }

    /**
     * One movement row (§27.2).
     */
    public static final class MovementView {

        /**
         * The movement fiscal date.
         */
        public LocalDate date;

        /**
         * The movement type badge (§23.1).
         */
        public String type;

        /**
         * The signed amount, euro at scale 2.
         */
        public BigDecimal amount;

        /**
         * The ticket reference, or null.
         */
        public String ticketRef;

        /**
         * The crediting rule code, or null (§29.4).
         */
        public String ruleCode;

        /**
         * The ADJUSTMENT reason, or null (§32.1).
         */
        public String reason;
    }
}
