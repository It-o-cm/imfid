package com.intermarche.fidelity.rule;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The card-side context an {@link EarnRuleApplier} evaluates a rule against,
 * joined to the valued basket at {@code /earn} time (§15). It is a dated snapshot
 * (§31.1): everything is read as of {@link #evaluationDate}, so a membership or an
 * activation created after a ticket does not make that ticket earn on replay.
 * <p>
 * It bundles what §15 lists as the card context — the account, the month's visits,
 * the cap cumulatives, the community memberships and the card activations — plus
 * the two period cumulatives the "once per period" and challenge mechanics read
 * from the same source as the caps (§15): a {@code MONTHLY_DATE_EARN} rule is
 * unique per period iff an EARN of its code already exists this month
 * ({@link #hasEarnedThisPeriod(String)}), and a {@code CHALLENGE_EARN} rule reads
 * its running purchase base over the challenge period ({@link #challengeBaseSoFar(String)}).
 * <p>
 * The caps themselves are applied by the orchestration downstream (§15, I5); this
 * context only carries the cumulatives the orchestration and the predicate-bearing
 * appliers read. Fields are public and final: the context is an immutable carrier;
 * collections are unmodifiable copies.
 */
public final class CardContext {

    /**
     * The resolved loyalty account (never auto-created, §20); must not be null — an
     * unknown card yields an empty earn upstream and never builds a context.
     */
    public final FidelityAccount account;

    /**
     * The number of distinct civil-day visits of the card in the evaluation month
     * (I3, §29.1); read by {@code BRAND_TIERED_EARN} to pick the boosted rate.
     */
    public final int monthlyVisits;

    /**
     * Euro already earned this month per rule code — the per-rule cap cumulative
     * (§15, I5) and the uniqueness source of {@code MONTHLY_DATE_EARN} (§15). Never
     * null.
     */
    public final Map<String, BigDecimal> ruleMonthlyEarn;

    /**
     * Euro already earned this month per community code — the per-community cap
     * cumulative (§15, I5). Never null.
     */
    public final Map<String, BigDecimal> communityMonthlyEarn;

    /**
     * Euro already earned this month across all rules — the global cap cumulative
     * (400 €/month, §15, I5). Never null.
     */
    public final BigDecimal globalMonthlyEarn;

    /**
     * Running eligible purchase base per {@code CHALLENGE_EARN} rule code, cumulated
     * over the challenge period before this basket (§12, §15); read to place the
     * basket on the challenge scale. Never null.
     */
    public final Map<String, BigDecimal> challengePeriodBase;

    /**
     * The codes of the communities the card belongs to as of {@link #evaluationDate}
     * (§14); read by the community-gated mechanics. Never null.
     */
    public final Set<String> membershipCommunityCodes;

    /**
     * The card activations active as of {@link #evaluationDate} (§14): e-coupon
     * activations, engaged challenges and completed "gold level" missions; read by
     * {@code ECOUPON_EARN} and {@code CHALLENGE_EARN}. Never null.
     */
    public final List<FidelityActivation> activations;

    /**
     * The fiscal evaluation date at the program zone (§25.1, §30.3): the calendar
     * reference of the weekday, day-of-month and window predicates.
     */
    public final LocalDate evaluationDate;

    /**
     * The fiscal evaluation instant the {@link #evaluationDate} derives from, kept
     * for time-of-day sensitive readings; at the program zone (§30.3).
     */
    public final LocalDateTime evaluationDateTime;

    /**
     * Builds a card context, taking defensive unmodifiable copies of the collections
     * and reading a null cumulative as zero (§31.2).
     *
     * @param account                  The resolved account; must not be null.
     * @param monthlyVisits            The month's distinct visits (negative read as 0).
     * @param ruleMonthlyEarn          Per-rule monthly earn cumulative; null read as empty.
     * @param communityMonthlyEarn     Per-community monthly earn cumulative; null read as empty.
     * @param globalMonthlyEarn        Global monthly earn cumulative; null read as zero.
     * @param challengePeriodBase      Per-challenge running purchase base; null read as empty.
     * @param membershipCommunityCodes Active membership community codes; null read as empty.
     * @param activations              Active card activations; null read as empty.
     * @param evaluationDateTime       The fiscal evaluation instant; must not be null.
     */
    public CardContext(FidelityAccount account,
                       int monthlyVisits,
                       Map<String, BigDecimal> ruleMonthlyEarn,
                       Map<String, BigDecimal> communityMonthlyEarn,
                       BigDecimal globalMonthlyEarn,
                       Map<String, BigDecimal> challengePeriodBase,
                       Set<String> membershipCommunityCodes,
                       List<FidelityActivation> activations,
                       LocalDateTime evaluationDateTime) {
        if (account == null) {
            throw new IllegalArgumentException("account is mandatory");
        }
        if (evaluationDateTime == null) {
            throw new IllegalArgumentException("evaluationDateTime is mandatory");
        }
        this.account = account;
        this.monthlyVisits = Math.max(0, monthlyVisits);
        this.ruleMonthlyEarn = ruleMonthlyEarn == null ? Map.of() : Map.copyOf(ruleMonthlyEarn);
        this.communityMonthlyEarn = communityMonthlyEarn == null ? Map.of() : Map.copyOf(communityMonthlyEarn);
        this.globalMonthlyEarn = globalMonthlyEarn != null ? globalMonthlyEarn : BigDecimal.ZERO;
        this.challengePeriodBase = challengePeriodBase == null ? Map.of() : Map.copyOf(challengePeriodBase);
        this.membershipCommunityCodes = membershipCommunityCodes == null ? Set.of() : Set.copyOf(membershipCommunityCodes);
        this.activations = activations == null ? List.of() : List.copyOf(activations);
        this.evaluationDateTime = evaluationDateTime;
        this.evaluationDate = evaluationDateTime.toLocalDate();
    }

    /**
     * Builds a bare context for an account with no cumulatives, memberships or
     * activations — the empty-history case, and a convenience for tests.
     *
     * @param account            The resolved account; must not be null.
     * @param evaluationDateTime The fiscal evaluation instant; must not be null.
     */
    public CardContext(FidelityAccount account, LocalDateTime evaluationDateTime) {
        this(account, 0, null, null, null, null, null, null, evaluationDateTime);
    }

    /**
     * Indicates whether the card is a member of the given community as of the
     * evaluation date (§14).
     *
     * @param communityCode The community code; a null code matches nothing.
     * @return true when the card belongs to the community.
     */
    public boolean isMemberOf(String communityCode) {
        return communityCode != null && membershipCommunityCodes.contains(communityCode);
    }

    /**
     * Returns the activation of the card for the given rule active on the evaluation
     * date, or null when none applies (§14, §31.1).
     *
     * @param ruleCode The rule code; a null code matches nothing.
     * @return The active activation, or null.
     */
    public FidelityActivation activationFor(String ruleCode) {
        if (ruleCode == null) {
            return null;
        }
        for (FidelityActivation activation : activations) {
            if (ruleCode.equals(activation.ruleCode) && activation.isActiveOn(evaluationDate)) {
                return activation;
            }
        }
        return null;
    }

    /**
     * Indicates whether an EARN of the rule already exists this period — the
     * uniqueness test of {@code MONTHLY_DATE_EARN} (§15): a positive per-rule monthly
     * cumulative means the once-per-period rule has already fired this month.
     *
     * @param ruleCode The rule code; a null code is read as "not yet earned".
     * @return true when the rule has already earned this period.
     */
    public boolean hasEarnedThisPeriod(String ruleCode) {
        if (ruleCode == null) {
            return false;
        }
        BigDecimal earned = ruleMonthlyEarn.get(ruleCode);
        return earned != null && earned.signum() > 0;
    }

    /**
     * Returns the euro already earned this month by the given rule, or zero (§15, I5).
     *
     * @param ruleCode The rule code; a null code returns zero.
     * @return The per-rule monthly cumulative, never null.
     */
    public BigDecimal ruleEarnSoFar(String ruleCode) {
        BigDecimal earned = ruleCode == null ? null : ruleMonthlyEarn.get(ruleCode);
        return earned != null ? earned : BigDecimal.ZERO;
    }

    /**
     * Returns the euro already earned this month by the given community, or zero
     * (§15, I5).
     *
     * @param communityCode The community code; a null code returns zero.
     * @return The per-community monthly cumulative, never null.
     */
    public BigDecimal communityEarnSoFar(String communityCode) {
        BigDecimal earned = communityCode == null ? null : communityMonthlyEarn.get(communityCode);
        return earned != null ? earned : BigDecimal.ZERO;
    }

    /**
     * Returns the running eligible purchase base of the given challenge before this
     * basket, or zero (§12, §15).
     *
     * @param ruleCode The challenge rule code; a null code returns zero.
     * @return The challenge running purchase base, never null.
     */
    public BigDecimal challengeBaseSoFar(String ruleCode) {
        BigDecimal base = ruleCode == null ? null : challengePeriodBase.get(ruleCode);
        return base != null ? base : BigDecimal.ZERO;
    }
}
