package com.intermarche.fidelity.rule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The earn produced by one rule for one valued basket — the internal SPI result
 * of {@link EarnRuleApplier#apply(List, CardContext)} and the projection unit of
 * the {@code /earn} response {@code entries[]} (§15, §27.1).
 * <p>
 * An entry carries the crediting rule's {@link #ruleCode} and printed
 * {@link #label}, the rounded {@link #amount} (a single per-rule HALF_UP rounding,
 * I4), the {@link #baseAmount} the calculation applied to (the net eligible
 * assiette, §15), and the {@link #lineIds} that fed the assiette so a partial
 * return debit stays computable line by line (§16, §29.4). It is a side-effect
 * free value object: {@code /earn} is a read (§30.2) and orchestration (priorities,
 * exclusivity, caps) consumes these entries downstream (§15).
 * <p>
 * A rule that produces nothing — a failed predicate, or a non-producer such as
 * {@code PROGRAM_EXCLUSION} — returns {@link #none(String, String)} rather than
 * null; callers filter with {@link #isEmpty()}.
 * <p>
 * Fields are public and final: an entry is an immutable carrier.
 */
public final class EarnEntry {

    /**
     * The stable code of the rule that produced this earn (§13).
     */
    public final String ruleCode;

    /**
     * The human label printed on the ticket and echoed to the POS (§18).
     */
    public final String label;

    /**
     * The rounded earn amount, euro at scale 2 (I4, §30.5); zero for an empty entry.
     */
    public final BigDecimal amount;

    /**
     * The net eligible assiette the calculation applied to, euro at scale 2 (§15,
     * §30.5); zero for an empty entry.
     */
    public final BigDecimal baseAmount;

    /**
     * The ids of the valued lines that fed the assiette, in encounter order; never
     * null (empty for an empty entry).
     */
    public final List<String> lineIds;

    /**
     * Builds an earn entry, normalizing the amounts to scale 2 HALF_UP and taking a
     * defensive, unmodifiable copy of the line ids.
     *
     * @param ruleCode   The stable rule code; must not be null.
     * @param label      The printed rule label; must not be null.
     * @param amount     The earn amount; null is read as zero.
     * @param baseAmount The eligible assiette; null is read as zero.
     * @param lineIds    The contributing line ids; null is read as empty.
     */
    public EarnEntry(String ruleCode, String label, BigDecimal amount, BigDecimal baseAmount, List<String> lineIds) {
        this.ruleCode = Objects.requireNonNull(ruleCode, "ruleCode");
        this.label = Objects.requireNonNull(label, "label");
        this.amount = scale(amount);
        this.baseAmount = scale(baseAmount);
        this.lineIds = lineIds == null ? List.of() : List.copyOf(lineIds);
    }

    /**
     * Returns the empty entry of a rule: zero amount, zero assiette, no lines. Used
     * for a failed predicate or a non-producer rule so callers never see a null.
     *
     * @param ruleCode The stable rule code.
     * @param label    The printed rule label.
     * @return An empty, side-effect-free entry.
     */
    public static EarnEntry none(String ruleCode, String label) {
        return new EarnEntry(ruleCode, label, BigDecimal.ZERO, BigDecimal.ZERO, Collections.emptyList());
    }

    /**
     * Indicates whether the entry produced no earn — a zero (or non-positive) amount
     * or no contributing line; such entries are filtered out by the orchestration.
     *
     * @return true when the entry grants nothing.
     */
    public boolean isEmpty() {
        return amount.signum() <= 0 || lineIds.isEmpty();
    }

    /**
     * Normalizes an amount to euro scale 2 HALF_UP, reading null as zero (§30.5).
     *
     * @param value The raw amount; may be null.
     * @return The amount at scale 2, never null.
     */
    private static BigDecimal scale(BigDecimal value) {
        BigDecimal base = value != null ? value : BigDecimal.ZERO;
        return base.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns a debug representation of the entry.
     *
     * @return The rule code, amount, base and line ids.
     */
    @Override
    public String toString() {
        return "EarnEntry{ruleCode=" + ruleCode + ", amount=" + amount
                + ", baseAmount=" + baseAmount + ", lineIds=" + lineIds + '}';
    }
}
