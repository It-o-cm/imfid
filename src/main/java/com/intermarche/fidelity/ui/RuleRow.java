package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.domain.FidelityRule;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of the rule list (§23.3): code, type, label, validity window, priority and a
 * state badge (active / closed / upcoming).
 * <p>
 * Public fields and getters are resolved by Qute.
 */
public final class RuleRow {

    /**
     * The rule code.
     */
    public String code;

    /**
     * The rule type.
     */
    public String type;

    /**
     * The printed label.
     */
    public String label;

    /**
     * The window start.
     */
    public LocalDateTime validFrom;

    /**
     * The window end, or null.
     */
    public LocalDateTime validTo;

    /**
     * The evaluation priority.
     */
    public int priority;

    /**
     * The state: ACTIVE, CLOSED or UPCOMING.
     */
    public String state;

    /**
     * Builds a rule row from an entity, computing its state at the given instant.
     *
     * @param rule The rule entity.
     * @param now  The current program instant.
     * @return The rule row.
     */
    public static RuleRow of(FidelityRule rule, LocalDateTime now) {
        RuleRow row = new RuleRow();
        row.code = rule.code;
        row.type = rule.type;
        row.label = rule.label;
        row.validFrom = rule.validFrom;
        row.validTo = rule.validTo;
        row.priority = rule.priority;
        if (rule.validFrom != null && now.isBefore(rule.validFrom)) {
            row.state = "UPCOMING";
        } else if (rule.validTo != null && !now.isBefore(rule.validTo)) {
            row.state = "CLOSED";
        } else if (rule.active) {
            row.state = "ACTIVE";
        } else {
            row.state = "INACTIVE";
        }
        return row;
    }

    /**
     * Returns the CSS badge class for the state.
     *
     * @return The badge modifier class.
     */
    public String getBadgeClass() {
        return switch (state) {
            case "ACTIVE" -> "badge-ok";
            case "CLOSED" -> "badge-off";
            default -> "badge";
        };
    }
}
