package com.intermarche.fidelity.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of a batch run — dry-run or execution (§23.4, §32.3). It carries what the
 * two-step "Simulate then Execute" of the admin UI shows: how many accounts would be
 * (or were) touched and the total amount that would be (or was) moved, plus a per-account
 * breakdown for the confirmation dialog ("would expire 43 € on 12 accounts").
 * <p>
 * Plain carrier; fields are public for serialization.
 */
public final class BatchResult {

    /**
     * The batch that ran.
     */
    public String batch;

    /**
     * Whether this was a dry-run simulation (no write) (§23.4).
     */
    public boolean dryRun;

    /**
     * The number of accounts touched.
     */
    public int accountsAffected;

    /**
     * The total amount moved (positive magnitude), euro at scale 2.
     */
    public BigDecimal totalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /**
     * The per-account breakdown of the run. Never null.
     */
    public List<Line> lines = new ArrayList<>();

    /**
     * Builds a batch result.
     *
     * @param batch  The batch type name.
     * @param dryRun Whether it was a dry-run.
     */
    public BatchResult(String batch, boolean dryRun) {
        this.batch = batch;
        this.dryRun = dryRun;
    }

    /**
     * Records a touched account and adds its amount to the total.
     *
     * @param cardNumber The account card number.
     * @param amount     The amount moved on the account (positive magnitude).
     */
    public void add(String cardNumber, BigDecimal amount) {
        BigDecimal magnitude = amount != null ? amount.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        lines.add(new Line(cardNumber, magnitude));
        accountsAffected++;
        totalAmount = totalAmount.add(magnitude);
    }

    /**
     * One touched account of a batch run.
     */
    public static final class Line {

        /**
         * The account card number.
         */
        public String cardNumber;

        /**
         * The amount moved on the account (positive magnitude), euro at scale 2.
         */
        public BigDecimal amount;

        /**
         * Builds a line.
         *
         * @param cardNumber The card number.
         * @param amount     The amount moved.
         */
        public Line(String cardNumber, BigDecimal amount) {
            this.cardNumber = cardNumber;
            this.amount = amount;
        }
    }
}
