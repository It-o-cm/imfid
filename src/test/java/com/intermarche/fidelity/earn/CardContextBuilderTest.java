package com.intermarche.fidelity.earn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.EarnTraceLine;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.util.ProgramClock;
import com.intermarche.fidelity.rule.CardContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link CardContextBuilder}: the visit count with and without
 * the current not-yet-recorded visit (§29.1), the per-community aggregation of the rule
 * cumulatives (§15, I5), the active-membership collection (§14) and the running challenge
 * bases (§12, §15), every datum read as of the fiscal evaluation date (§31.1).
 * <p>
 * Fully isolated: the {@link ProgramClock} collaborator is mocked and the fiscal instant
 * is fixed, so month bounds and the derived evaluation date are deterministic and never
 * read from the real clock (§24.6). The Panache static finders of the domain entities are
 * intercepted with {@code mockStatic} in try-with-resources; the builder persists nothing,
 * so no {@code mockConstruction} is required.
 */
class CardContextBuilderTest {

    /**
     * The card number used across the fixtures.
     */
    private static final String CARD = "CARD-1";

    /**
     * The fixed fiscal evaluation instant at the program zone (mid-month).
     */
    private static final LocalDateTime EVAL_INSTANT = LocalDateTime.of(2026, 3, 15, 10, 0);

    /**
     * The evaluation date derived from {@link #EVAL_INSTANT}.
     */
    private static final LocalDate EVAL_DATE = LocalDate.of(2026, 3, 15);

    /**
     * The first day of the evaluation month returned by the mocked clock.
     */
    private static final LocalDate MONTH_START = LocalDate.of(2026, 3, 1);

    /**
     * The last day of the evaluation month returned by the mocked clock.
     */
    private static final LocalDate MONTH_END = LocalDate.of(2026, 3, 31);

    /**
     * The system under test, freshly built per test with a mocked clock.
     */
    private CardContextBuilder builder;

    /**
     * The mocked program clock resolving the month bounds of the evaluation date.
     */
    private ProgramClock clock;

    /**
     * Builds a fresh system under test and wires the mocked clock before each test.
     */
    @BeforeEach
    void setUp() {
        builder = new CardContextBuilder();
        clock = Mockito.mock(ProgramClock.class);
        Mockito.when(clock.monthStart(EVAL_DATE)).thenReturn(MONTH_START);
        Mockito.when(clock.monthEnd(EVAL_DATE)).thenReturn(MONTH_END);
        builder.clock = clock;
    }

    /**
     * Applies the neutral baseline stubs on every domain static finder, so a test only
     * overrides the concern it exercises and the others resolve to empty/zero.
     *
     * @param trace The mocked {@link EarnTrace} statics.
     * @param mov   The mocked {@link FidelityMovement} statics.
     * @param mem   The mocked {@link FidelityMembership} statics.
     * @param act   The mocked {@link FidelityActivation} statics.
     * @param rule  The mocked {@link FidelityRule} statics.
     * @param line  The mocked {@link EarnTraceLine} statics.
     */
    private void stubNeutral(MockedStatic<EarnTrace> trace, MockedStatic<FidelityMovement> mov,
                             MockedStatic<FidelityMembership> mem, MockedStatic<FidelityActivation> act,
                             MockedStatic<FidelityRule> rule, MockedStatic<EarnTraceLine> line) {
        trace.when(() -> EarnTrace.countVisits(any(), any(), any())).thenReturn(0L);
        mov.when(() -> FidelityMovement.monthlyEarnByRule(any(), any(), any())).thenReturn(Map.of());
        mov.when(() -> FidelityMovement.monthlyEarnTotal(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        mem.when(() -> FidelityMembership.listActiveForAccount(any(), any())).thenReturn(List.of());
        act.when(() -> FidelityActivation.listForAccount(any())).thenReturn(List.of());
        rule.when(() -> FidelityRule.findByCode(any())).thenReturn(null);
        line.when(() -> EarnTraceLine.sumBaseForRule(any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
    }

    /**
     * Builds a resolved account carrying the fixture card number.
     *
     * @return A non-null account with {@link #CARD} as its card number.
     */
    private FidelityAccount account() {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = CARD;
        return account;
    }

    /**
     * Builds a rule whose specification carries the given community code (or none).
     *
     * @param communityCode The community code to embed, or null for a spec without one.
     * @return The rule.
     */
    private FidelityRule communityRule(String communityCode) {
        FidelityRule rule = new FidelityRule();
        rule.specification = communityCode != null
                ? "{\"communityCode\":\"" + communityCode + "\"}"
                : "{\"rate\":0.05}";
        return rule;
    }

    /**
     * Builds a rule of the given type with an empty-object specification.
     *
     * @param type The rule type.
     * @return The rule.
     */
    private FidelityRule typedRule(String type) {
        FidelityRule rule = new FidelityRule();
        rule.type = type;
        rule.specification = "{}";
        return rule;
    }

    /**
     * Builds a membership over the given community (which may be null or code-less).
     *
     * @param community The community, or null.
     * @return The membership.
     */
    private FidelityMembership membership(FidelityCommunity community) {
        FidelityMembership membership = new FidelityMembership();
        membership.community = community;
        return membership;
    }

    /**
     * Builds a community carrying the given code.
     *
     * @param code The community code, or null.
     * @return The community.
     */
    private FidelityCommunity community(String code) {
        FidelityCommunity community = new FidelityCommunity();
        community.code = code;
        return community;
    }

    /**
     * Builds an activation for a rule over the given inclusive period.
     *
     * @param ruleCode    The rule code, or null.
     * @param periodStart The period start, or null.
     * @param periodEnd   The period end, or null for an open period.
     * @return The activation.
     */
    private FidelityActivation activation(String ruleCode, LocalDate periodStart, LocalDate periodEnd) {
        FidelityActivation activation = new FidelityActivation();
        activation.ruleCode = ruleCode;
        activation.periodStart = periodStart;
        activation.periodEnd = periodEnd;
        return activation;
    }

    /**
     * Projection with the current visit unrecorded adds one to the recorded month count
     * ({@code countCurrentVisit} true, today not yet traced, §29.1).
     */
    @Test
    @DisplayName("countVisits(): projection adds the current visit when today is unrecorded")
    void projectionAddsCurrentVisitWhenTodayUnrecorded() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            trace.when(() -> EarnTrace.countVisits(CARD, MONTH_START, MONTH_END)).thenReturn(3L);
            trace.when(() -> EarnTrace.countVisits(CARD, EVAL_DATE, EVAL_DATE)).thenReturn(0L);
            CardContext context = builder.build(account(), EVAL_INSTANT, true);
            assertEquals(4, context.monthlyVisits);
        }
    }

    /**
     * Projection with today already traced does not double-count the current visit
     * ({@code countCurrentVisit} true, today already recorded, §29.1).
     */
    @Test
    @DisplayName("countVisits(): projection does not add when today is already recorded")
    void projectionDoesNotAddWhenTodayRecorded() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            trace.when(() -> EarnTrace.countVisits(CARD, MONTH_START, MONTH_END)).thenReturn(3L);
            trace.when(() -> EarnTrace.countVisits(CARD, EVAL_DATE, EVAL_DATE)).thenReturn(1L);
            CardContext context = builder.build(account(), EVAL_INSTANT, true);
            assertEquals(3, context.monthlyVisits);
        }
    }

    /**
     * The ingestion recalc reads only the recorded visits and never probes the current
     * day ({@code countCurrentVisit} false, §29.1).
     */
    @Test
    @DisplayName("countVisits(): ingestion counts only recorded visits, never today")
    void ingestionCountsOnlyRecordedVisits() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            trace.when(() -> EarnTrace.countVisits(CARD, MONTH_START, MONTH_END)).thenReturn(5L);
            CardContext context = builder.build(account(), EVAL_INSTANT, false);
            assertEquals(5, context.monthlyVisits);
            trace.verify(() -> EarnTrace.countVisits(CARD, EVAL_DATE, EVAL_DATE), Mockito.never());
        }
    }

    /**
     * A rule found and carrying a community code maps its monthly earn onto that
     * community (§15, I5).
     */
    @Test
    @DisplayName("aggregateByCommunity(): a found community rule maps its earn to the community")
    void aggregateMapsFoundCommunityRule() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            mov.when(() -> FidelityMovement.monthlyEarnByRule(any(), any(), any()))
                    .thenReturn(Map.of("R1", new BigDecimal("10.00")));
            rule.when(() -> FidelityRule.findByCode("R1")).thenReturn(communityRule("C1"));
            CardContext context = builder.build(account(), EVAL_INSTANT, false);
            assertEquals(0, context.communityMonthlyEarn.get("C1").compareTo(new BigDecimal("10.00")));
        }
    }

    /**
     * A rule not found (null finder result) contributes nothing to the community
     * cumulative — the ternary null arm (§31.2).
     */
    @Test
    @DisplayName("aggregateByCommunity(): an unknown rule is skipped")
    void aggregateSkipsUnknownRule() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            mov.when(() -> FidelityMovement.monthlyEarnByRule(any(), any(), any()))
                    .thenReturn(Map.of("R1", new BigDecimal("10.00")));
            rule.when(() -> FidelityRule.findByCode("R1")).thenReturn(null);
            CardContext context = builder.build(account(), EVAL_INSTANT, false);
            assertTrue(context.communityMonthlyEarn.isEmpty());
        }
    }

    /**
     * A found rule whose specification carries no community code is skipped — the
     * {@code communityCode == null} arm.
     */
    @Test
    @DisplayName("aggregateByCommunity(): a rule without a community code is skipped")
    void aggregateSkipsRuleWithoutCommunityCode() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            mov.when(() -> FidelityMovement.monthlyEarnByRule(any(), any(), any()))
                    .thenReturn(Map.of("R1", new BigDecimal("10.00")));
            rule.when(() -> FidelityRule.findByCode("R1")).thenReturn(communityRule(null));
            CardContext context = builder.build(account(), EVAL_INSTANT, false);
            assertTrue(context.communityMonthlyEarn.isEmpty());
        }
    }

    /**
     * Two rules of the same community accumulate their earn onto that community, covering
     * the {@code merge} addition (§15, I5).
     */
    @Test
    @DisplayName("aggregateByCommunity(): two rules of one community sum their earn")
    void aggregateMergesTwoRulesOfSameCommunity() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            Map<String, BigDecimal> byRule = new LinkedHashMap<>();
            byRule.put("R1", new BigDecimal("10.00"));
            byRule.put("R2", new BigDecimal("5.00"));
            mov.when(() -> FidelityMovement.monthlyEarnByRule(any(), any(), any())).thenReturn(byRule);
            rule.when(() -> FidelityRule.findByCode("R1")).thenReturn(communityRule("C1"));
            rule.when(() -> FidelityRule.findByCode("R2")).thenReturn(communityRule("C1"));
            CardContext context = builder.build(account(), EVAL_INSTANT, false);
            assertEquals(0, context.communityMonthlyEarn.get("C1").compareTo(new BigDecimal("15.00")));
        }
    }

    /**
     * A membership over a community with a code contributes that code — both legs of the
     * {@code community != null && code != null} guard true (§14).
     */
    @Test
    @DisplayName("activeMembershipCodes(): a coded community membership is collected")
    void membershipWithCommunityAndCodeCollected() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            mem.when(() -> FidelityMembership.listActiveForAccount(any(), eq(EVAL_DATE)))
                    .thenReturn(List.of(membership(community("C1"))));
            CardContext context = builder.build(account(), EVAL_INSTANT, false);
            assertTrue(context.membershipCommunityCodes.contains("C1"));
            assertEquals(1, context.membershipCommunityCodes.size());
        }
    }

    /**
     * A membership with a null community is skipped — the first leg of the guard false.
     */
    @Test
    @DisplayName("activeMembershipCodes(): a null-community membership is skipped")
    void membershipWithNullCommunitySkipped() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            mem.when(() -> FidelityMembership.listActiveForAccount(any(), any()))
                    .thenReturn(List.of(membership(null)));
            CardContext context = builder.build(account(), EVAL_INSTANT, false);
            assertTrue(context.membershipCommunityCodes.isEmpty());
        }
    }

    /**
     * A membership over a code-less community is skipped — the second leg of the guard
     * false.
     */
    @Test
    @DisplayName("activeMembershipCodes(): a code-less community membership is skipped")
    void membershipWithNullCodeSkipped() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            mem.when(() -> FidelityMembership.listActiveForAccount(any(), any()))
                    .thenReturn(List.of(membership(community(null))));
            CardContext context = builder.build(account(), EVAL_INSTANT, false);
            assertTrue(context.membershipCommunityCodes.isEmpty());
        }
    }

    /**
     * An active challenge activation with a set period start computes its running base
     * over that period up to the evaluation date, keying the base by rule code (§12, §15).
     */
    @Test
    @DisplayName("challengeBases(): an active challenge computes its running base from periodStart")
    void challengeComputesBaseFromPeriodStart() {
        LocalDate periodStart = LocalDate.of(2026, 3, 1);
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            act.when(() -> FidelityActivation.listForAccount(any()))
                    .thenReturn(List.of(activation("CH1", periodStart, null)));
            rule.when(() -> FidelityRule.findByCode("CH1")).thenReturn(typedRule(FidelityRule.TYPE_CHALLENGE_EARN));
            line.when(() -> EarnTraceLine.sumBaseForRule(CARD, "CH1", periodStart, EVAL_DATE))
                    .thenReturn(new BigDecimal("20.00"));
            CardContext context = builder.build(account(), EVAL_INSTANT, false);
            assertEquals(0, context.challengePeriodBase.get("CH1").compareTo(new BigDecimal("20.00")));
            line.verify(() -> EarnTraceLine.sumBaseForRule(CARD, "CH1", periodStart, EVAL_DATE));
        }
    }

    /**
     * An activation with a null rule code is skipped — the first leg of the
     * {@code ruleCode == null || !isActiveOn} guard true.
     */
    @Test
    @DisplayName("challengeBases(): a null-rule-code activation is skipped")
    void challengeSkipsNullRuleCode() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            act.when(() -> FidelityActivation.listForAccount(any()))
                    .thenReturn(List.of(activation(null, EVAL_DATE, null)));
            CardContext context = builder.build(account(), EVAL_INSTANT, false);
            assertTrue(context.challengePeriodBase.isEmpty());
        }
    }

    /**
     * An activation not active on the evaluation date is skipped — the second leg of the
     * {@code ruleCode == null || !isActiveOn} guard true (period starts after the date).
     */
    @Test
    @DisplayName("challengeBases(): an activation not yet active is skipped")
    void challengeSkipsInactiveActivation() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            act.when(() -> FidelityActivation.listForAccount(any()))
                    .thenReturn(List.of(activation("CH1", EVAL_DATE.plusDays(1), null)));
            CardContext context = builder.build(account(), EVAL_INSTANT, false);
            assertTrue(context.challengePeriodBase.isEmpty());
        }
    }

    /**
     * An active activation whose rule is unknown is skipped — the first leg of the
     * {@code rule == null || !CHALLENGE.equals(type)} guard true.
     */
    @Test
    @DisplayName("challengeBases(): an active activation with an unknown rule is skipped")
    void challengeSkipsUnknownRule() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            act.when(() -> FidelityActivation.listForAccount(any()))
                    .thenReturn(List.of(activation("CH1", EVAL_DATE, null)));
            rule.when(() -> FidelityRule.findByCode("CH1")).thenReturn(null);
            CardContext context = builder.build(account(), EVAL_INSTANT, false);
            assertTrue(context.challengePeriodBase.isEmpty());
        }
    }

    /**
     * An active activation whose rule is not a challenge is skipped — the second leg of
     * the {@code rule == null || !CHALLENGE.equals(type)} guard true.
     */
    @Test
    @DisplayName("challengeBases(): an active activation with a non-challenge rule is skipped")
    void challengeSkipsNonChallengeRule() {
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            act.when(() -> FidelityActivation.listForAccount(any()))
                    .thenReturn(List.of(activation("CH1", EVAL_DATE, null)));
            rule.when(() -> FidelityRule.findByCode("CH1")).thenReturn(typedRule(FidelityRule.TYPE_ECOUPON_EARN));
            CardContext context = builder.build(account(), EVAL_INSTANT, false);
            assertTrue(context.challengePeriodBase.isEmpty());
        }
    }

    /**
     * The build derives the evaluation date from the instant and feeds it to the clock —
     * lower straddle of the fiscal month border (31 January 23:59, §30.3, §25.1).
     */
    @Test
    @DisplayName("build(): the eve of the month border resolves to January")
    void buildResolvesJanuaryOnEveOfBorder() {
        LocalDateTime eve = LocalDateTime.of(2026, 1, 31, 23, 59);
        LocalDate eveDate = LocalDate.of(2026, 1, 31);
        Mockito.when(clock.monthStart(eveDate)).thenReturn(LocalDate.of(2026, 1, 1));
        Mockito.when(clock.monthEnd(eveDate)).thenReturn(LocalDate.of(2026, 1, 31));
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            CardContext context = builder.build(account(), eve, false);
            assertEquals(eveDate, context.evaluationDate);
            Mockito.verify(clock).monthStart(eveDate);
            Mockito.verify(clock).monthEnd(eveDate);
            trace.verify(() -> EarnTrace.countVisits(CARD, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        }
    }

    /**
     * The build derives the evaluation date from the instant and feeds it to the clock —
     * upper straddle of the fiscal month border (1 February 00:00, §30.3, §25.1).
     */
    @Test
    @DisplayName("build(): the dawn of the month border resolves to February")
    void buildResolvesFebruaryOnDawnOfBorder() {
        LocalDateTime dawn = LocalDateTime.of(2026, 2, 1, 0, 0);
        LocalDate dawnDate = LocalDate.of(2026, 2, 1);
        Mockito.when(clock.monthStart(dawnDate)).thenReturn(LocalDate.of(2026, 2, 1));
        Mockito.when(clock.monthEnd(dawnDate)).thenReturn(LocalDate.of(2026, 2, 28));
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            CardContext context = builder.build(account(), dawn, false);
            assertEquals(dawnDate, context.evaluationDate);
            Mockito.verify(clock).monthStart(dawnDate);
            Mockito.verify(clock).monthEnd(dawnDate);
            trace.verify(() -> EarnTrace.countVisits(CARD, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));
        }
    }

    /**
     * The build carries the account, the global monthly earn, the per-rule cumulative and
     * the fiscal instant unchanged into the returned context (§15, §31.1).
     */
    @Test
    @DisplayName("build(): carries account, global earn, rule cumulative and instant into the context")
    void buildCarriesEveryDatumIntoContext() {
        FidelityAccount account = account();
        try (MockedStatic<EarnTrace> trace = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> mov = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityMembership> mem = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityActivation> act = Mockito.mockStatic(FidelityActivation.class);
             MockedStatic<FidelityRule> rule = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<EarnTraceLine> line = Mockito.mockStatic(EarnTraceLine.class)) {
            stubNeutral(trace, mov, mem, act, rule, line);
            mov.when(() -> FidelityMovement.monthlyEarnByRule(any(), any(), any()))
                    .thenReturn(Map.of("R1", new BigDecimal("12.00")));
            mov.when(() -> FidelityMovement.monthlyEarnTotal(any(), any(), any()))
                    .thenReturn(new BigDecimal("42.00"));
            rule.when(() -> FidelityRule.findByCode("R1")).thenReturn(communityRule(null));
            CardContext context = builder.build(account, EVAL_INSTANT, false);
            assertNotNull(context);
            assertSame(account, context.account);
            assertEquals(EVAL_INSTANT, context.evaluationDateTime);
            assertEquals(EVAL_DATE, context.evaluationDate);
            assertEquals(0, context.globalMonthlyEarn.compareTo(new BigDecimal("42.00")));
            assertEquals(0, context.ruleMonthlyEarn.get("R1").compareTo(new BigDecimal("12.00")));
            assertFalse(context.ruleMonthlyEarn.isEmpty());
        }
    }
}
