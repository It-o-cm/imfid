package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.FidelityRuleTier;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The read-only rule sheet (§23.3): the backbone, the materialized tiers and the
 * pretty-printed JSON specification, with the same state badge as the list.
 * <p>
 * Everything is materialized here in plain fields — the template renders after the
 * request transaction, so no lazy entity state may leak into it. Public fields and
 * getters are resolved by Qute.
 */
public final class RuleDetailView {

    /**
     * The backbone of the rule, shared with the list (code, type, label, window,
     * priority, state badge).
     */
    public RuleRow row;

    /**
     * Whether the rule consumes its lines (§15, I2).
     */
    public boolean exclusive;

    /**
     * The per-card monthly cap in euro, or null when the rule carries none.
     */
    public String monthlyCapPerCard;

    /**
     * Whether the rule is active.
     */
    public boolean active;

    /**
     * The pretty-printed JSON specification.
     */
    public String specification;

    /**
     * The materialized tiers, in rank order; empty for rules without tiers.
     */
    public List<TierRow> tiers = new ArrayList<>();

    /**
     * When the rule row was created.
     */
    public LocalDateTime createdAt;

    /**
     * When the rule row was last modified.
     */
    public LocalDateTime updatedAt;

    /**
     * Whether the signed-in user may write (§21.4).
     */
    public boolean canWrite;

    /**
     * The one-shot notice, or null.
     */
    public String notice;

    /**
     * Whether the notice reports a success.
     */
    public boolean noticeOk;

    /**
     * One materialized tier of the sheet — plain strings, no entity state.
     */
    public static final class TierRow {

        /**
         * Zero-based ordinal of the tier.
         */
        public int rank;

        /**
         * The threshold (visit count or amount), or null.
         */
        public String threshold;

        /**
         * The rate as a stored fraction (0.10 meaning 10 %), or null.
         */
        public String rate;

        /**
         * The fixed gain in euro, or null.
         */
        public String rewardAmount;

        /**
         * Whether the tier also requires a completed mission (§12).
         */
        public boolean missionRequired;

        /**
         * Builds a tier row from the entity.
         *
         * @param tier The tier entity.
         * @return The materialized row.
         */
        static TierRow of(FidelityRuleTier tier) {
            TierRow row = new TierRow();
            row.rank = tier.rank;
            row.threshold = tier.threshold != null ? tier.threshold.toPlainString() : null;
            row.rate = tier.rate != null ? tier.rate.toPlainString() : null;
            row.rewardAmount = tier.rewardAmount != null ? tier.rewardAmount.toPlainString() : null;
            row.missionRequired = tier.missionRequired;
            return row;
        }
    }

    /**
     * Builds the sheet from a rule entity; the tiers are copied out eagerly.
     *
     * @param rule          The rule entity.
     * @param now           The current program instant, resolving the state badge.
     * @param prettySpec    The pretty-printed specification.
     * @param notice        The one-shot notice, or null.
     * @param noticeOk      Whether the notice reports a success.
     * @param canWrite      Whether the signed-in user may write.
     * @return The rule sheet view.
     */
    public static RuleDetailView of(FidelityRule rule, LocalDateTime now, String prettySpec,
                                    String notice, boolean noticeOk, boolean canWrite) {
        RuleDetailView view = new RuleDetailView();
        view.row = RuleRow.of(rule, now);
        view.exclusive = rule.exclusive;
        view.monthlyCapPerCard = rule.monthlyCapPerCard != null ? rule.monthlyCapPerCard.toPlainString() : null;
        view.active = rule.active;
        view.specification = prettySpec;
        view.createdAt = rule.createdAt;
        view.updatedAt = rule.updatedAt;
        view.notice = notice;
        view.noticeOk = noticeOk;
        view.canWrite = canWrite;
        for (FidelityRuleTier tier : rule.tiers) {
            view.tiers.add(TierRow.of(tier));
        }
        return view;
    }

    /**
     * Returns whether a notice must be displayed.
     *
     * @return true when a notice is present.
     */
    public boolean isHasNotice() {
        return notice != null && !notice.isBlank();
    }

    /**
     * Returns whether the rule window is still open (ACTIVE or UPCOMING), i.e.
     * whether the "Fermer" gesture applies (§18).
     *
     * @return true when the rule may still be closed.
     */
    public boolean isOpen() {
        return "ACTIVE".equals(row.state) || "UPCOMING".equals(row.state);
    }

    /**
     * Returns whether the rule may be edited in place: only a rule not yet entered
     * into force (UPCOMING) has no history and no replay depending on it (§18).
     *
     * @return true when the rule is editable.
     */
    public boolean isEditable() {
        return "UPCOMING".equals(row.state);
    }

    /**
     * Returns whether the rule carries tiers.
     *
     * @return true when at least one tier exists.
     */
    public boolean isHasTiers() {
        return !tiers.isEmpty();
    }
}
