package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityRule;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link RuleFormView}, the schema-driven rule form view model (§21.3,
 * §23.3). The class carries no clock of its own: the program instant is passed in as a parameter and
 * only forwarded to {@link RuleRow#of}, so time is fixed with deterministic {@link LocalDateTime}
 * constants and no real clock is ever read (§24.6). Rules are built in memory (no boot, no H2, no
 * static finder). Coverage exercises both arms of the {@code edition} cap ternary, both arms of the
 * {@code format} window-bound ternary, each leg of the consultation {@code open} disjunction and of
 * the {@code editable} predicate across the four rule states, and each leg of the {@code &&} guards
 * behind {@link RuleFormView#isHasNotice} and {@link RuleFormView#isHasSpecification}.
 */
class RuleFormViewTest {

    /**
     * The aggregated schemas JSON forwarded verbatim to every builder.
     */
    private static final String SCHEMAS = "{\"BRAND_TIERED_EARN\":{}}";

    /**
     * The community codes JSON forwarded verbatim to every builder.
     */
    private static final String COMMUNITIES = "[\"COMMU\"]";

    /**
     * The registered rule types forwarded verbatim to every builder.
     */
    private static final Set<String> TYPES = Set.of(FidelityRule.TYPE_BRAND_TIERED_EARN);

    /**
     * A fixed program instant used to resolve the consultation state badge.
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 9, 12, 0);

    /**
     * A fixed window start well before {@link #NOW}.
     */
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 8, 30);

    /**
     * A fixed window end before {@link #NOW}, used to close a rule.
     */
    private static final LocalDateTime PAST_TO = LocalDateTime.of(2026, 6, 1, 0, 0);

    /**
     * A fixed window start after {@link #NOW}, used to keep a rule upcoming.
     */
    private static final LocalDateTime FUTURE_FROM = LocalDateTime.of(2026, 12, 1, 0, 0);

    /**
     * Builds an in-memory rule with the supplied backbone fields; all other business fields are
     * fixed to stable, non-null constants so the builders map them verbatim.
     *
     * @param validFrom  The window start, may be null.
     * @param validTo    The window end, may be null.
     * @param monthlyCap The per-card monthly cap, may be null.
     * @param active     Whether the rule is active.
     * @return The rule, its business fields populated.
     */
    private static FidelityRule rule(LocalDateTime validFrom, LocalDateTime validTo,
            BigDecimal monthlyCap, boolean active) {
        FidelityRule rule = new FidelityRule();
        rule.code = "WELCOME_EARN";
        rule.type = FidelityRule.TYPE_BRAND_TIERED_EARN;
        rule.label = "Welcome earn";
        rule.validFrom = validFrom;
        rule.validTo = validTo;
        rule.priority = 5;
        rule.monthlyCapPerCard = monthlyCap;
        rule.exclusive = true;
        rule.active = active;
        rule.specification = "{\"rate\":0.1}";
        return rule;
    }

    /**
     * The creation builder fills the blank-form backbone and forwards the injected collaborators
     * while leaving every prefilled field at its default (no rule to read).
     */
    @Test
    @DisplayName("creation builds a blank creation-mode form")
    void creationBuildsBlankForm() {
        RuleFormView view = RuleFormView.creation(SCHEMAS, COMMUNITIES, TYPES, true);
        assertEquals("Nouvelle règle", view.title);
        assertEquals("/ui/rules/create", view.action);
        assertEquals("Créer la règle", view.submitLabel);
        assertFalse(view.editMode);
        assertFalse(view.readOnly);
        assertSame(SCHEMAS, view.schemasJson);
        assertSame(COMMUNITIES, view.communitiesJson);
        assertSame(TYPES, view.types);
        assertTrue(view.canWrite);
        assertEquals("", view.code);
        assertEquals("", view.type);
        assertEquals("", view.validFrom);
        assertEquals("", view.validTo);
        assertEquals("", view.monthlyCapPerCard);
        assertTrue(view.active);
    }

    /**
     * The creation builder honours a false write permission.
     */
    @Test
    @DisplayName("creation carries a read-only permission")
    void creationCarriesReadOnlyPermission() {
        RuleFormView view = RuleFormView.creation(SCHEMAS, COMMUNITIES, TYPES, false);
        assertFalse(view.canWrite);
    }

    /**
     * A rule with a non-null cap and both window bounds set drives the non-null arm of the cap
     * ternary and the non-null arm of the {@code format} ternary for both {@code validFrom} and
     * {@code validTo}; the code is echoed into the frozen-code action.
     */
    @Test
    @DisplayName("edition prefills a bounded, capped rule")
    void editionPrefillsBoundedRule() {
        FidelityRule rule = rule(FROM, PAST_TO, new BigDecimal("12.50"), true);
        RuleFormView view = RuleFormView.edition(rule, SCHEMAS, COMMUNITIES, TYPES, true);
        assertEquals("Éditer la règle WELCOME_EARN", view.title);
        assertEquals("/ui/rules/WELCOME_EARN/update", view.action);
        assertEquals("Enregistrer", view.submitLabel);
        assertTrue(view.editMode);
        assertFalse(view.readOnly);
        assertEquals("WELCOME_EARN", view.code);
        assertEquals(FidelityRule.TYPE_BRAND_TIERED_EARN, view.type);
        assertEquals("Welcome earn", view.label);
        assertEquals("2026-01-01T08:30", view.validFrom);
        assertEquals("2026-06-01T00:00", view.validTo);
        assertEquals(5, view.priority);
        assertEquals("12.50", view.monthlyCapPerCard);
        assertTrue(view.exclusive);
        assertTrue(view.active);
        assertEquals("{\"rate\":0.1}", view.specification);
    }

    /**
     * A rule with a null cap and null window bounds drives the null arm of the cap ternary and the
     * null arm of the {@code format} ternary for both bounds, each yielding an empty string.
     */
    @Test
    @DisplayName("edition leaves a null cap and null bounds empty")
    void editionLeavesNullFieldsEmpty() {
        FidelityRule rule = rule(null, null, null, false);
        RuleFormView view = RuleFormView.edition(rule, SCHEMAS, COMMUNITIES, TYPES, false);
        assertEquals("", view.monthlyCapPerCard);
        assertEquals("", view.validFrom);
        assertEquals("", view.validTo);
        assertFalse(view.active);
        assertFalse(view.canWrite);
    }

    /**
     * Consulting an ACTIVE rule (past start, no end, active) freezes the form and drives the first
     * leg of the {@code open} disjunction true, so the rule is open but, not being UPCOMING, not
     * editable; the badge resolves to {@code badge-ok}.
     */
    @Test
    @DisplayName("consultation of an ACTIVE rule is open, not editable")
    void consultationActiveRule() {
        FidelityRule rule = rule(FROM, null, new BigDecimal("12.50"), true);
        RuleFormView view = RuleFormView.consultation(rule, NOW, SCHEMAS, COMMUNITIES, TYPES, true);
        assertEquals("Règle WELCOME_EARN", view.title);
        assertEquals("", view.action);
        assertEquals("", view.submitLabel);
        assertFalse(view.editMode);
        assertTrue(view.readOnly);
        assertEquals("ACTIVE", view.state);
        assertEquals("badge-ok", view.badgeClass);
        assertTrue(view.open);
        assertFalse(view.editable);
    }

    /**
     * Consulting an UPCOMING rule (start after {@link #NOW}) drives the first leg of the
     * {@code open} disjunction false and the second true, so the rule is open, and the
     * {@code editable} predicate true, so it may be edited; the badge falls through to
     * {@code badge}.
     */
    @Test
    @DisplayName("consultation of an UPCOMING rule is open and editable")
    void consultationUpcomingRule() {
        FidelityRule rule = rule(FUTURE_FROM, null, null, true);
        RuleFormView view = RuleFormView.consultation(rule, NOW, SCHEMAS, COMMUNITIES, TYPES, true);
        assertEquals("UPCOMING", view.state);
        assertEquals("badge", view.badgeClass);
        assertTrue(view.open);
        assertTrue(view.editable);
    }

    /**
     * Consulting a CLOSED rule (end at or before {@link #NOW}) drives both legs of the {@code open}
     * disjunction false and the {@code editable} predicate false; the badge resolves to
     * {@code badge-off}.
     */
    @Test
    @DisplayName("consultation of a CLOSED rule is neither open nor editable")
    void consultationClosedRule() {
        FidelityRule rule = rule(FROM, PAST_TO, null, true);
        RuleFormView view = RuleFormView.consultation(rule, NOW, SCHEMAS, COMMUNITIES, TYPES, true);
        assertEquals("CLOSED", view.state);
        assertEquals("badge-off", view.badgeClass);
        assertFalse(view.open);
        assertFalse(view.editable);
    }

    /**
     * Consulting an INACTIVE rule (in-window but not active) also drives both {@code open} legs and
     * the {@code editable} predicate false, exercising the disjunction's both-false outcome by the
     * active-flag path; the badge falls through to {@code badge}.
     */
    @Test
    @DisplayName("consultation of an INACTIVE rule is neither open nor editable")
    void consultationInactiveRule() {
        FidelityRule rule = rule(FROM, null, null, false);
        RuleFormView view = RuleFormView.consultation(rule, NOW, SCHEMAS, COMMUNITIES, TYPES, true);
        assertEquals("INACTIVE", view.state);
        assertEquals("badge", view.badgeClass);
        assertFalse(view.open);
        assertFalse(view.editable);
    }

    /**
     * {@link RuleFormView#isHasNotice} is false when the notice is null (first {@code &&} leg false,
     * short-circuiting the blank check).
     */
    @Test
    @DisplayName("isHasNotice is false on a null notice")
    void hasNoticeNullNotice() {
        RuleFormView view = new RuleFormView();
        view.notice = null;
        assertFalse(view.isHasNotice());
    }

    /**
     * {@link RuleFormView#isHasNotice} is false when the notice is present but blank (second
     * {@code &&} leg false).
     */
    @Test
    @DisplayName("isHasNotice is false on a blank notice")
    void hasNoticeBlankNotice() {
        RuleFormView view = new RuleFormView();
        view.notice = "   ";
        assertFalse(view.isHasNotice());
    }

    /**
     * {@link RuleFormView#isHasNotice} is true when the notice is present and non-blank (both
     * {@code &&} legs true).
     */
    @Test
    @DisplayName("isHasNotice is true on a non-blank notice")
    void hasNoticeNonBlankNotice() {
        RuleFormView view = new RuleFormView();
        view.notice = "Saved";
        assertTrue(view.isHasNotice());
    }

    /**
     * {@link RuleFormView#isHasSpecification} is false when the specification is null (first
     * {@code &&} leg false, short-circuiting the blank check).
     */
    @Test
    @DisplayName("isHasSpecification is false on a null specification")
    void hasSpecificationNull() {
        RuleFormView view = new RuleFormView();
        view.specification = null;
        assertFalse(view.isHasSpecification());
    }

    /**
     * {@link RuleFormView#isHasSpecification} is false when the specification is present but blank
     * (second {@code &&} leg false).
     */
    @Test
    @DisplayName("isHasSpecification is false on a blank specification")
    void hasSpecificationBlank() {
        RuleFormView view = new RuleFormView();
        view.specification = "  ";
        assertFalse(view.isHasSpecification());
    }

    /**
     * {@link RuleFormView#isHasSpecification} is true when the specification is present and
     * non-blank (both {@code &&} legs true).
     */
    @Test
    @DisplayName("isHasSpecification is true on a non-blank specification")
    void hasSpecificationNonBlank() {
        RuleFormView view = new RuleFormView();
        view.specification = "{}";
        assertTrue(view.isHasSpecification());
    }
}
