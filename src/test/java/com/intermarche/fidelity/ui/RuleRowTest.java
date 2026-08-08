package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.intermarche.fidelity.domain.FidelityRule;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link RuleRow}, the rule-list row view model (§23.3). The class carries
 * no clock: {@link RuleRow#of} receives the program instant as a parameter, so time stays fixed and
 * deterministic — window boundaries are exercised by the two instants straddling each border, never
 * by the real clock (§24.6). Rules are built in memory (no boot, no H2, no static finder). Coverage
 * drives every arm of the state cascade (UPCOMING / CLOSED / ACTIVE / INACTIVE), including each leg
 * of the two compound guards, and every arm of the badge-class switch.
 */
class RuleRowTest {

    /**
     * A fixed window start used as the {@code isBefore(validFrom)} border.
     */
    private static final LocalDateTime START = LocalDateTime.of(2026, 6, 1, 0, 0);

    /**
     * A fixed window end used as the {@code isBefore(validTo)} border.
     */
    private static final LocalDateTime END = LocalDateTime.of(2026, 7, 1, 0, 0);

    /**
     * Builds an in-memory rule with the supplied window and active flag; the backbone identity
     * fields are populated so the verbatim copy performed by {@link RuleRow#of} can be asserted.
     *
     * @param validFrom The window start, or null.
     * @param validTo   The window end, or null.
     * @param active    Whether the rule is active.
     * @return The rule, its backbone fields populated.
     */
    private static FidelityRule rule(LocalDateTime validFrom, LocalDateTime validTo, boolean active) {
        FidelityRule rule = new FidelityRule();
        rule.code = "SUMMER_BOOST";
        rule.type = FidelityRule.TYPE_BRAND_TIERED_EARN;
        rule.label = "Summer boost";
        rule.validFrom = validFrom;
        rule.validTo = validTo;
        rule.priority = 7;
        rule.active = active;
        return rule;
    }

    /**
     * Asserts that a rule whose window has not opened yet is UPCOMING: the first guard is taken with
     * both legs true ({@code validFrom} non-null and {@code now} strictly before it), tested one
     * instant before the border. The verbatim copy of every backbone field is asserted here, and the
     * badge class falls to the switch default.
     */
    @Test
    @DisplayName("of marks an unopened window as UPCOMING and copies the backbone")
    void ofUpcomingBeforeStart() {
        RuleRow row = RuleRow.of(rule(START, END, true), START.minusNanos(1));
        assertEquals("SUMMER_BOOST", row.code);
        assertEquals(FidelityRule.TYPE_BRAND_TIERED_EARN, row.type);
        assertEquals("Summer boost", row.label);
        assertEquals(START, row.validFrom);
        assertEquals(END, row.validTo);
        assertEquals(7, row.priority);
        assertEquals("UPCOMING", row.state);
        assertEquals("badge", row.getBadgeClass());
    }

    /**
     * Asserts that at the exact window start the rule is no longer UPCOMING: the first guard's
     * {@code isBefore(validFrom)} leg is false at the border while its non-null leg is true. With a
     * null {@code validTo} the second guard's non-null leg is false, so an active rule resolves to
     * ACTIVE with the {@code badge-ok} class.
     */
    @Test
    @DisplayName("of is ACTIVE at the exact window start with an open end")
    void ofActiveAtExactStart() {
        RuleRow row = RuleRow.of(rule(START, null, true), START);
        assertEquals("ACTIVE", row.state);
        assertEquals("badge-ok", row.getBadgeClass());
    }

    /**
     * Asserts that at the exact window end the rule is CLOSED: the first guard's non-null leg is
     * false ({@code validFrom} null), and the second guard is taken with {@code validTo} non-null
     * and {@code !now.isBefore(validTo)} true at the border. The badge class is {@code badge-off}.
     */
    @Test
    @DisplayName("of is CLOSED at the exact window end")
    void ofClosedAtExactEnd() {
        RuleRow row = RuleRow.of(rule(null, END, true), END);
        assertEquals("CLOSED", row.state);
        assertEquals("badge-off", row.getBadgeClass());
    }

    /**
     * Asserts that one instant before the window end the rule is not CLOSED: the second guard's
     * {@code !now.isBefore(validTo)} leg is false at the near side of the border. With a null
     * {@code validFrom} the first guard's non-null leg is false, so an active rule resolves to
     * ACTIVE.
     */
    @Test
    @DisplayName("of is ACTIVE one instant before the window end")
    void ofActiveJustBeforeEnd() {
        RuleRow row = RuleRow.of(rule(null, END, true), END.minusNanos(1));
        assertEquals("ACTIVE", row.state);
        assertEquals("badge-ok", row.getBadgeClass());
    }

    /**
     * Asserts that an inactive rule sitting inside its window is INACTIVE: the first guard's
     * {@code isBefore(validFrom)} leg is false (now after the start), the second guard's
     * {@code !now.isBefore(validTo)} leg is false (now before the end), and the {@code active} flag
     * is false, reaching the final else. The badge class falls to the switch default.
     */
    @Test
    @DisplayName("of marks an inactive in-window rule as INACTIVE")
    void ofInactiveInsideWindow() {
        RuleRow row = RuleRow.of(rule(START, END, false), LocalDateTime.of(2026, 6, 15, 12, 0));
        assertEquals("INACTIVE", row.state);
        assertEquals("badge", row.getBadgeClass());
    }
}
