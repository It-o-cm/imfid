package com.intermarche.fidelity.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One line of the earn detail hanging off an {@link EarnTrace} (§24.2).
 * <p>
 * Records, per valuation line and per crediting rule, the eligible net base and
 * the earn granted. This is what makes a partial return debit computable — the
 * cumulated return debit of an origin line is bounded by its earn (§29.4) — and
 * what makes the connector debuggable line by line.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "earn_trace_lines",
        indexes = {
                @Index(name = "idx_trace_line_trace", columnList = "trace_id"),
                @Index(name = "idx_trace_line_line_id", columnList = "line_id")
        }
)
public class EarnTraceLine extends BaseEntity {

    /**
     * The trace header this line belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trace_id", nullable = false)
    public EarnTrace trace;

    /**
     * The valuation line id this earn detail is attached to.
     */
    @Column(name = "line_id", nullable = false, length = 60)
    public String lineId;

    /**
     * The EAN of the line, copied out for debugging; may be null when unresolved.
     */
    @Column(length = 13)
    public String ean;

    /**
     * The code of the rule that credited this line.
     */
    @Column(name = "rule_code", nullable = false, length = 50)
    public String ruleCode;

    /**
     * The eligible net base of the line for this rule, euro at scale 2 (§15, §30.5).
     */
    @Column(name = "base_amount", nullable = false, precision = 19, scale = 2)
    public BigDecimal baseAmount;

    /**
     * The earn granted on this line by this rule, euro at scale 2 (§30.5).
     */
    @Column(name = "earn_amount", nullable = false, precision = 19, scale = 2)
    public BigDecimal earnAmount;

    /**
     * Sums the eligible base credited to a card by a rule over a fiscal date range —
     * the running purchase base of a {@code CHALLENGE_EARN} rule over its period (§12,
     * §15). Reads from the ingested traces only, never the projection (§30.2). The
     * range bounds are inclusive.
     *
     * @param cardNumber The card number.
     * @param ruleCode   The rule code.
     * @param from       First fiscal date of the range (inclusive).
     * @param to         Last fiscal date of the range (inclusive).
     * @return The cumulated base, euro at scale 2, never null (zero when none).
     */
    public static BigDecimal sumBaseForRule(String cardNumber, String ruleCode, LocalDate from, LocalDate to) {
        BigDecimal sum = getEntityManager().createQuery(
                        "select coalesce(sum(l.baseAmount), 0) from EarnTraceLine l "
                                + "where l.ruleCode = :r and l.trace.cardNumber = :c "
                                + "and l.trace.fiscalDate >= :f and l.trace.fiscalDate <= :to",
                        BigDecimal.class)
                .setParameter("r", ruleCode).setParameter("c", cardNumber)
                .setParameter("f", from).setParameter("to", to)
                .getSingleResult();
        BigDecimal total = sum != null ? sum : BigDecimal.ZERO;
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Calculates a checksum from the line's business fields.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(lineId, ean, ruleCode, baseAmount, earnAmount);
    }
}
