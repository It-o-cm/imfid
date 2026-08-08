package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.FidelityRuleTier;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link RuleDetailView}, the read-only rule sheet view model (§23.3). The
 * class carries no clock of its own: the instant is passed in as a parameter and only forwarded to
 * {@link RuleRow#of}, so time is fixed with deterministic {@link LocalDateTime} constants and no
 * real clock is ever read (§24.6). Rules and tiers are built in memory (no boot, no H2, no static
 * finder). Coverage exercises both arms of every nullable ternary in {@link RuleDetailView.TierRow}
 * and in the {@code of} mapper, the empty and non-empty tier loop, and each leg of the compound and
 * simple guards on the sheet's predicate accessors.
 */
class RuleDetailViewTest {

    /**
     * A fixed program instant that keeps the fabricated rule ACTIVE (past start, no end).
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 9, 12, 0);

    /**
     * A fixed rule-window start well before {@link #NOW}.
     */
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);

    /**
     * A fixed creation instant for the fabricated rule.
     */
    private static final LocalDateTime CREATED = LocalDateTime.of(2025, 12, 1, 8, 30);

    /**
     * A fixed update instant for the fabricated rule.
     */
    private static final LocalDateTime UPDATED = LocalDateTime.of(2026, 2, 2, 9, 15);

    /**
     * Builds an in-memory rule with the supplied nullable monthly cap and active flag; the window
     * starts at {@link #FROM} with no end so the state resolves to ACTIVE at {@link #NOW}.
     *
     * @param monthlyCap The per-card monthly cap, or null.
     * @param active     Whether the rule is active.
     * @return The rule, its business fields populated.
     */
    private static FidelityRule rule(BigDecimal monthlyCap, boolean active) {
        FidelityRule rule = new FidelityRule();
        rule.code = "WELCOME_EARN";
        rule.type = FidelityRule.TYPE_BRAND_TIERED_EARN;
        rule.label = "Welcome earn";
        rule.validFrom = FROM;
        rule.validTo = null;
        rule.priority = 5;
        rule.exclusive = true;
        rule.monthlyCapPerCard = monthlyCap;
        rule.active = active;
        rule.createdAt = CREATED;
        rule.updatedAt = UPDATED;
        return rule;
    }

    /**
     * Builds an in-memory tier with the supplied nullable numeric fields.
     *
     * @param rank            The zero-based ordinal.
     * @param threshold       The threshold, or null.
     * @param rate            The stored rate fraction, or null.
     * @param rewardAmount    The fixed gain, or null.
     * @param missionRequired Whether a completed mission is also required.
     * @return The tier, its fields populated.
     */
    private static FidelityRuleTier tier(int rank, BigDecimal threshold, BigDecimal rate,
            BigDecimal rewardAmount, boolean missionRequired) {
        FidelityRuleTier tier = new FidelityRuleTier();
        tier.rank = rank;
        tier.threshold = threshold;
        tier.rate = rate;
        tier.rewardAmount = rewardAmount;
        tier.missionRequired = missionRequired;
        return tier;
    }

    /**
     * A fully populated rule with a non-null cap and two tiers maps every field verbatim; the
     * populated tier exercises the non-null arm of all three {@link RuleDetailView.TierRow}
     * ternaries and the non-empty branch of the tier loop.
     */
    @Test
    @DisplayName("of maps a populated rule and its tiers verbatim")
    void ofMapsPopulatedRule() {
        FidelityRule rule = rule(new BigDecimal("12.50"), true);
        rule.tiers = List.of(
                tier(0, new BigDecimal("3"), new BigDecimal("0.10"), new BigDecimal("5.00"), true),
                tier(1, null, null, null, false));
        RuleDetailView view = RuleDetailView.of(rule, NOW, "{\n  \"k\": 1\n}", "Saved", true, true);
        assertSame(rule.code, view.row.code);
        assertEquals("ACTIVE", view.row.state);
        assertTrue(view.exclusive);
        assertEquals("12.50", view.monthlyCapPerCard);
        assertTrue(view.active);
        assertEquals("{\n  \"k\": 1\n}", view.specification);
        assertSame(CREATED, view.createdAt);
        assertSame(UPDATED, view.updatedAt);
        assertEquals("Saved", view.notice);
        assertTrue(view.noticeOk);
        assertTrue(view.canWrite);
        assertEquals(2, view.tiers.size());
        RuleDetailView.TierRow first = view.tiers.get(0);
        assertEquals(0, first.rank);
        assertEquals("3", first.threshold);
        assertEquals("0.10", first.rate);
        assertEquals("5.00", first.rewardAmount);
        assertTrue(first.missionRequired);
    }

    /**
     * The second, bare tier of the populated rule carries null threshold, rate and reward, driving
     * the null arm of all three {@link RuleDetailView.TierRow} ternaries.
     */
    @Test
    @DisplayName("TierRow.of leaves null numeric fields null")
    void tierRowLeavesNullFieldsNull() {
        FidelityRule rule = rule(new BigDecimal("12.50"), true);
        rule.tiers = List.of(tier(1, null, null, null, false));
        RuleDetailView view = RuleDetailView.of(rule, NOW, "{}", null, false, false);
        RuleDetailView.TierRow bare = view.tiers.get(0);
        assertEquals(1, bare.rank);
        assertNull(bare.threshold);
        assertNull(bare.rate);
        assertNull(bare.rewardAmount);
        assertFalse(bare.missionRequired);
    }

    /**
     * A bare rule with a null cap and no tiers drives the null arm of the cap ternary and the empty
     * branch of the tier loop; a null notice and false flags flow through untouched.
     */
    @Test
    @DisplayName("of maps a bare rule with a null cap and no tiers")
    void ofMapsBareRule() {
        FidelityRule rule = rule(null, false);
        RuleDetailView view = RuleDetailView.of(rule, NOW, "{}", null, false, false);
        assertNull(view.monthlyCapPerCard);
        assertFalse(view.active);
        assertTrue(view.tiers.isEmpty());
        assertNull(view.notice);
        assertFalse(view.noticeOk);
        assertFalse(view.canWrite);
    }

    /**
     * {@link RuleDetailView#isHasNotice} is false when the notice is null (first {@code &&} leg
     * false, short-circuiting the blank check).
     */
    @Test
    @DisplayName("isHasNotice is false on a null notice")
    void hasNoticeNullNotice() {
        RuleDetailView view = new RuleDetailView();
        view.notice = null;
        assertFalse(view.isHasNotice());
    }

    /**
     * {@link RuleDetailView#isHasNotice} is false when the notice is present but blank (second
     * {@code &&} leg false).
     */
    @Test
    @DisplayName("isHasNotice is false on a blank notice")
    void hasNoticeBlankNotice() {
        RuleDetailView view = new RuleDetailView();
        view.notice = "   ";
        assertFalse(view.isHasNotice());
    }

    /**
     * {@link RuleDetailView#isHasNotice} is true when the notice is present and non-blank (both
     * {@code &&} legs true).
     */
    @Test
    @DisplayName("isHasNotice is true on a non-blank notice")
    void hasNoticeNonBlankNotice() {
        RuleDetailView view = new RuleDetailView();
        view.notice = "Saved";
        assertTrue(view.isHasNotice());
    }

    /**
     * {@link RuleDetailView#isOpen} and {@link RuleDetailView#isEditable} on an ACTIVE state: the
     * first {@code ||} leg is true so the sheet is open, but ACTIVE is not UPCOMING so it is not
     * editable.
     */
    @Test
    @DisplayName("ACTIVE rule is open but not editable")
    void openActive() {
        RuleDetailView view = new RuleDetailView();
        view.row = new RuleRow();
        view.row.state = "ACTIVE";
        assertTrue(view.isOpen());
        assertFalse(view.isEditable());
    }

    /**
     * {@link RuleDetailView#isOpen} and {@link RuleDetailView#isEditable} on an UPCOMING state: the
     * first {@code ||} leg is false and the second true, so the sheet is open and, being UPCOMING,
     * also editable.
     */
    @Test
    @DisplayName("UPCOMING rule is open and editable")
    void openUpcoming() {
        RuleDetailView view = new RuleDetailView();
        view.row = new RuleRow();
        view.row.state = "UPCOMING";
        assertTrue(view.isOpen());
        assertTrue(view.isEditable());
    }

    /**
     * {@link RuleDetailView#isOpen} and {@link RuleDetailView#isEditable} on a CLOSED state: both
     * {@code ||} legs are false so the sheet is neither open nor editable.
     */
    @Test
    @DisplayName("CLOSED rule is neither open nor editable")
    void closedRule() {
        RuleDetailView view = new RuleDetailView();
        view.row = new RuleRow();
        view.row.state = "CLOSED";
        assertFalse(view.isOpen());
        assertFalse(view.isEditable());
    }

    /**
     * {@link RuleDetailView#isHasTiers} is false when no tier was materialized (empty list arm).
     */
    @Test
    @DisplayName("isHasTiers is false without tiers")
    void hasTiersEmpty() {
        RuleDetailView view = new RuleDetailView();
        assertFalse(view.isHasTiers());
    }

    /**
     * {@link RuleDetailView#isHasTiers} is true once at least one tier row is present (non-empty
     * list arm).
     */
    @Test
    @DisplayName("isHasTiers is true with a tier")
    void hasTiersNonEmpty() {
        RuleDetailView view = new RuleDetailView();
        view.tiers.add(new RuleDetailView.TierRow());
        assertTrue(view.isHasTiers());
    }
}
