package com.intermarche.fidelity.earn;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.util.ProgramClock;
import com.intermarche.fidelity.rule.CardContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds the dated {@link CardContext} an earn evaluation reads (§15, §31.1): the
 * month's visits, the per-rule / per-community / global cap cumulatives, the active
 * memberships and activations, and the running challenge bases — every fenced datum
 * read as of the fiscal evaluation date, so a membership or activation created after
 * a ticket does not make it earn on replay (§31.1).
 * <p>
 * The build is a read (§30.2). The projection ({@code /earn}) counts the current,
 * not-yet-ingested visit ({@code countCurrentVisit} true); the ingestion recalc reads
 * the visit it already wrote ({@code countCurrentVisit} false, §29.1).
 */
@ApplicationScoped
public class CardContextBuilder {

    /**
     * The program clock resolving fiscal dates and month bounds at the program zone
     * (§25.1, §30.3).
     */
    @Inject
    ProgramClock clock;

    /**
     * Builds the card context for an account at a fiscal evaluation instant (§15, §31.1).
     *
     * @param account            The resolved account; must not be null.
     * @param evaluationDateTime The fiscal evaluation instant at the program zone.
     * @param countCurrentVisit  Whether to add the current, not-yet-recorded visit to
     *                           the month's visit count (true for the projection,
     *                           false for the ingestion recalc, §29.1).
     * @return The dated card context, never null.
     */
    public CardContext build(FidelityAccount account, LocalDateTime evaluationDateTime, boolean countCurrentVisit) {
        LocalDate evalDate = evaluationDateTime.toLocalDate();
        LocalDate monthStart = clock.monthStart(evalDate);
        LocalDate monthEnd = clock.monthEnd(evalDate);

        int monthlyVisits = countVisits(account.cardNumber, monthStart, monthEnd, evalDate, countCurrentVisit);

        Map<String, BigDecimal> ruleMonthlyEarn = FidelityMovement.monthlyEarnByRule(account, monthStart, monthEnd);
        BigDecimal globalMonthlyEarn = FidelityMovement.monthlyEarnTotal(account, monthStart, monthEnd);
        Map<String, BigDecimal> communityMonthlyEarn = aggregateByCommunity(ruleMonthlyEarn);

        Set<String> membershipCodes = activeMembershipCodes(account, evalDate);
        List<FidelityActivation> activations = FidelityActivation.listForAccount(account);
        Map<String, BigDecimal> challengeBase = challengeBases(account.cardNumber, activations, evalDate);

        return new CardContext(account, monthlyVisits, ruleMonthlyEarn, communityMonthlyEarn,
                globalMonthlyEarn, challengeBase, membershipCodes, activations, evaluationDateTime);
    }

    /**
     * Counts the month's distinct visit days, optionally adding the current visit when
     * it is not yet recorded (§29.1).
     *
     * @param cardNumber        The card number.
     * @param monthStart        First day of the evaluation month.
     * @param monthEnd          Last day of the evaluation month.
     * @param evalDate          The evaluation date.
     * @param countCurrentVisit Whether to add the current visit if the day is unrecorded.
     * @return The month's visit count.
     */
    private int countVisits(String cardNumber, LocalDate monthStart, LocalDate monthEnd,
                            LocalDate evalDate, boolean countCurrentVisit) {
        long recorded = com.intermarche.fidelity.domain.EarnTrace.countVisits(cardNumber, monthStart, monthEnd);
        if (!countCurrentVisit) {
            return (int) recorded;
        }
        boolean todayAlreadyRecorded = com.intermarche.fidelity.domain.EarnTrace.countVisits(cardNumber, evalDate, evalDate) > 0;
        return (int) recorded + (todayAlreadyRecorded ? 0 : 1);
    }

    /**
     * Aggregates the per-rule monthly earn into a per-community cumulative, mapping each
     * rule to its community through the specification (§15, I5).
     *
     * @param ruleMonthlyEarn The per-rule monthly earn.
     * @return The per-community monthly earn, never null.
     */
    private Map<String, BigDecimal> aggregateByCommunity(Map<String, BigDecimal> ruleMonthlyEarn) {
        Map<String, BigDecimal> byCommunity = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : ruleMonthlyEarn.entrySet()) {
            FidelityRule rule = FidelityRule.findByCode(entry.getKey());
            String communityCode = rule != null ? rule.communityCodeFromSpec() : null;
            if (communityCode == null) {
                continue;
            }
            byCommunity.merge(communityCode, entry.getValue(), BigDecimal::add);
        }
        return byCommunity;
    }

    /**
     * Resolves the codes of the communities the card is a member of on the evaluation
     * date (§14, §31.1).
     *
     * @param account  The account.
     * @param evalDate The evaluation date.
     * @return The active community codes, never null.
     */
    private Set<String> activeMembershipCodes(FidelityAccount account, LocalDate evalDate) {
        Set<String> codes = new TreeSet<>();
        for (FidelityMembership membership : FidelityMembership.listActiveForAccount(account, evalDate)) {
            if (membership.community != null && membership.community.code != null) {
                codes.add(membership.community.code);
            }
        }
        return codes;
    }

    /**
     * Computes the running challenge purchase base per activated rule over its period,
     * from the ingested traces (§12, §15).
     *
     * @param cardNumber  The card number.
     * @param activations The card activations.
     * @param evalDate    The evaluation date (upper bound of the running base).
     * @return The per-rule running challenge base, never null.
     */
    private Map<String, BigDecimal> challengeBases(String cardNumber, List<FidelityActivation> activations, LocalDate evalDate) {
        Map<String, BigDecimal> bases = new LinkedHashMap<>();
        for (FidelityActivation activation : activations) {
            if (activation.ruleCode == null || !activation.isActiveOn(evalDate)) {
                continue;
            }
            FidelityRule rule = FidelityRule.findByCode(activation.ruleCode);
            if (rule == null || !FidelityRule.TYPE_CHALLENGE_EARN.equals(rule.type)) {
                continue;
            }
            LocalDate from = activation.periodStart != null ? activation.periodStart : evalDate;
            BigDecimal base = com.intermarche.fidelity.domain.EarnTraceLine.sumBaseForRule(
                    cardNumber, activation.ruleCode, from, evalDate);
            bases.put(activation.ruleCode, base);
        }
        return bases;
    }
}
