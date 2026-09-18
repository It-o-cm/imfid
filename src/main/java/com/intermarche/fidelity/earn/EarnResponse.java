package com.intermarche.fidelity.earn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code POST /api/earn} projection response (§15, §27.1): the earn total, the
 * per-rule {@link #entries}, the truncations of the caps ({@link #capsApplied}), the
 * burnable base and the non-blocking {@link #warnings}.
 * <p>
 * A projection credits nothing (§15, §30.2); an empty earn (total 0, no entries) is
 * returned for an absent or unknown card (§20, §27.1). The warnings follow the closed
 * nomenclature of {@link WarningCode} (§27.1). Fields are public to keep the DTO a
 * plain Jackson carrier.
 */
public class EarnResponse {

    /**
     * The nominal projection mode: the presented card's context joins the evaluation
     * (§15, §27.1).
     */
    public static final String MODE_CARD = "CARD";

    /**
     * The anonymous projection mode — "had you carried the card" (§20,
     * RFP BO-03-03-28): card-independent rules only, nothing credited, no trace.
     */
    public static final String MODE_ANONYMOUS = "ANONYMOUS";

    /**
     * The projection mode of this response, echoing the request's (closed
     * nomenclature {@link #MODE_CARD} | {@link #MODE_ANONYMOUS}); {@code CARD} when
     * the request carried none.
     */
    public String projectionMode = MODE_CARD;

    /**
     * The earn total = Σ of the {@link #entries} amounts after caps, euro at scale 2.
     */
    public BigDecimal total = BigDecimal.ZERO;

    /**
     * The per-rule earn entries, in evaluation order; empty for an empty earn. Never
     * null (§31.2).
     */
    public List<Entry> entries = new ArrayList<>();

    /**
     * The cap truncations, each tracing which cap cut how much (§15, I5). Never null
     * (§31.2).
     */
    public List<CapApplied> capsApplied = new ArrayList<>();

    /**
     * The burnable base = {@code totalPrice} minus the net of the lines covered by the
     * program exclusions (§22.3); the POS bounds the burn to it. Euro at scale 2.
     */
    public BigDecimal burnableBase = BigDecimal.ZERO;

    /**
     * The non-blocking warnings raised by the projection (§25.4, §27.1). Never null
     * (§31.2).
     */
    public List<Warning> warnings = new ArrayList<>();

    /**
     * The card balances at projection time (RFP BO-03-03-31/-34): the available
     * balance and its projection after this earn — a projection credits nothing
     * (§15, §30.2). Null when no card was resolved (§20).
     */
    public Balances balances;

    /**
     * The transaction balances block (RFP BO-03-03-31/-34) — figures read from the
     * ledger keeper, never recomputed by the POS.
     */
    public static class Balances {

        /**
         * The available balance = balance − active reservations (I11, §27.2), euro
         * at scale 2.
         */
        public BigDecimal available;

        /**
         * The available balance projected after this earn ({@code available +
         * total}); a projection, it credits nothing (§15, §30.2) — the actual
         * credit happens at ingestion.
         */
        public BigDecimal projectedAfterEarn;

        /**
         * The program instant the figures were read at (§30.3).
         */
        public java.time.LocalDateTime asOf;

        /**
         * Builds a balances block.
         *
         * @param available          The available balance.
         * @param projectedAfterEarn The projection after this earn.
         * @param asOf               The read instant.
         */
        public Balances(BigDecimal available, BigDecimal projectedAfterEarn, java.time.LocalDateTime asOf) {
            this.available = available;
            this.projectedAfterEarn = projectedAfterEarn;
            this.asOf = asOf;
        }

        /**
         * Default constructor for Jackson.
         */
        public Balances() {
        }
    }

    /**
     * One per-rule earn entry (§27.1).
     */
    public static class Entry {

        /**
         * The stable code of the crediting rule (§13).
         */
        public String ruleCode;

        /**
         * The printed rule label echoed to the POS (§18).
         */
        public String label;

        /**
         * The earn amount after caps, euro at scale 2 (I4).
         */
        public BigDecimal amount;

        /**
         * The eligible net assiette the calculation applied to, euro at scale 2 (§15).
         */
        public BigDecimal baseAmount;

        /**
         * The ids of the lines that fed the assiette (§16, §29.4). Never null (§31.2).
         */
        public List<String> lineIds = new ArrayList<>();

        /**
         * The advantage-type code the POS groups this entry under on the ticket
         * (closed nomenclature, RFP BO-03-03-25); always served on a crediting entry.
         */
        public String advantageType;

        /**
         * The printable label of the advantage-type group ("Vos avantages produits");
         * the POS prints it, never translates a code (§18).
         */
        public String advantageTypeLabel;

        /**
         * The administered order of the advantage-type group on the ticket
         * (RFP BO-03-03-25); always served on a crediting entry.
         */
        public Integer advantageTypeOrder;

        /**
         * The advantage-category code (RFP BO-03-03-33), or null when the rule
         * carries none.
         */
        public String advantageCategory;

        /**
         * The printable advantage-category label, or null when the rule carries no
         * category.
         */
        public String advantageCategoryLabel;

        /**
         * Builds an entry from an internal earn entry and its post-cap amount.
         *
         * @param ruleCode   The rule code.
         * @param label      The rule label.
         * @param amount     The post-cap earn amount.
         * @param baseAmount The eligible assiette.
         * @param lineIds    The contributing line ids.
         */
        public Entry(String ruleCode, String label, BigDecimal amount, BigDecimal baseAmount, List<String> lineIds) {
            this.ruleCode = ruleCode;
            this.label = label;
            this.amount = amount;
            this.baseAmount = baseAmount;
            this.lineIds = lineIds != null ? new ArrayList<>(lineIds) : new ArrayList<>();
        }

        /**
         * Default constructor for Jackson.
         */
        public Entry() {
        }
    }

    /**
     * One cap truncation trace (§15, §27.1): the scope that cut, its cap value and the
     * amount it removed.
     */
    public static class CapApplied {

        /**
         * The cap scope in the closed nomenclature {@code RULE:<code>} |
         * {@code COMMUNITY:<code>} | {@code GLOBAL} (§27.1, §27.2).
         */
        public String scope;

        /**
         * The cap value, euro at scale 2.
         */
        public BigDecimal capAmount;

        /**
         * The amount this truncation removed, euro at scale 2.
         */
        public BigDecimal truncatedBy;

        /**
         * Builds a cap trace.
         *
         * @param scope       The cap scope.
         * @param capAmount   The cap value.
         * @param truncatedBy The removed amount.
         */
        public CapApplied(String scope, BigDecimal capAmount, BigDecimal truncatedBy) {
            this.scope = scope;
            this.capAmount = capAmount;
            this.truncatedBy = truncatedBy;
        }

        /**
         * Default constructor for Jackson.
         */
        public CapApplied() {
        }
    }

    /**
     * One non-blocking warning (§27.1).
     */
    public static class Warning {

        /**
         * The warning code from the closed nomenclature (§27.1).
         */
        public String code;

        /**
         * The EAN the warning concerns, when applicable (§25.4); null otherwise.
         */
        public String ean;

        /**
         * The human-readable warning message.
         */
        public String message;

        /**
         * Builds a warning.
         *
         * @param code    The warning code.
         * @param ean     The concerned EAN, or null.
         * @param message The human message.
         */
        public Warning(String code, String ean, String message) {
            this.code = code;
            this.ean = ean;
            this.message = message;
        }

        /**
         * Default constructor for Jackson.
         */
        public Warning() {
        }
    }
}
