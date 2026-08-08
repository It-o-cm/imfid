package com.intermarche.fidelity.rule.appliers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.rule.CardContext;
import com.intermarche.fidelity.rule.EarnEntry;
import com.intermarche.fidelity.rule.EarnRuleApplier;
import com.intermarche.fidelity.rule.ValuedLine;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link MonthlyDateEarnFactory} and its
 * {@link MonthlyDateEarnApplier} — the 40 % feminine hygiene Labell on the 28th for
 * Students (§12, §15): the day-of-month gate, the optional community gate, the
 * once-per-period uniqueness (an EARN of the code already this month, §15), the
 * net-assiette sum of the eligible lines and the single per-rule centime rounding
 * (I4).
 * <p>
 * Pure logic: the applier's arithmetic is asserted directly on in-memory valued
 * baskets. The assiette scope uses {@code wholeStore} with EAN-less lines, so no
 * Panache product reference is read — no static finder needs mocking (§13, §25.4).
 * Time never comes from the real clock: the fiscal evaluation instant is fixed on
 * the {@link CardContext} (§24.6, §30.3), and the day-of-month border is straddled
 * by the two instants encircling the 28th (the 27th and the 29th), never by the day
 * the campaign runs. Every {@link BigDecimal} is asserted by {@code compareTo}.
 */
class MonthlyDateEarnFactoryTest {

    /**
     * The stable rule code carried by every fixture rule.
     */
    private static final String CODE = "STUDENTS-LABELL-28";

    /**
     * The printed rule label carried by every fixture rule.
     */
    private static final String LABEL = "40% Labell feminine hygiene on the 28th";

    /**
     * The community code the standard specification requires membership of (§12).
     */
    private static final String COMMUNITY = "STUDENTS";

    /**
     * The configured day of month the rule fires on (§12).
     */
    private static final int DAY = 28;

    /**
     * A fixed fiscal instant at the program zone landing on the configured day
     * (2026-01-28); the firing instant.
     */
    private static final LocalDateTime ON_DAY = LocalDateTime.of(2026, 1, DAY, 10, 0);

    /**
     * The fixed fiscal instant on the eve of the configured day (2026-01-27); the low
     * side of the day-of-month border.
     */
    private static final LocalDateTime DAY_BEFORE = LocalDateTime.of(2026, 1, DAY - 1, 23, 59);

    /**
     * The fixed fiscal instant on the morrow of the configured day (2026-01-29); the
     * high side of the day-of-month border.
     */
    private static final LocalDateTime DAY_AFTER = LocalDateTime.of(2026, 1, DAY + 1, 0, 1);

    /**
     * Builds a monthly-date rule carrying the given JSON specification.
     *
     * @param specification The rule specification JSON.
     * @return A fidelity rule ready for the applier.
     */
    private static FidelityRule ruleWith(String specification) {
        FidelityRule rule = new FidelityRule();
        rule.code = CODE;
        rule.label = LABEL;
        rule.type = FidelityRule.TYPE_MONTHLY_DATE_EARN;
        rule.specification = specification;
        return rule;
    }

    /**
     * Builds the standard specification: whole-store assiette, the {@link #DAY} day of
     * month, the {@link #COMMUNITY} membership requirement and a 40 % rate.
     *
     * @return The standard specification JSON.
     */
    private static String standardSpec() {
        return "{\"scope\":{\"wholeStore\":true},\"dayOfMonth\":" + DAY
                + ",\"communityCode\":\"" + COMMUNITY + "\",\"rate\":0.40}";
    }

    /**
     * Builds a specification without a {@code communityCode}: whole-store assiette,
     * the {@link #DAY} day of month and a 40 % rate — the non-gated variant (§12).
     *
     * @return The gateless specification JSON.
     */
    private static String gatelessSpec() {
        return "{\"scope\":{\"wholeStore\":true},\"dayOfMonth\":" + DAY + ",\"rate\":0.40}";
    }

    /**
     * Builds a community-gated specification with a 5 % rate, tuned to exercise the
     * centime rounding at the exact half (I4).
     *
     * @return The rounding specification JSON.
     */
    private static String roundingSpec() {
        return "{\"scope\":{\"wholeStore\":true},\"dayOfMonth\":" + DAY
                + ",\"communityCode\":\"" + COMMUNITY + "\",\"rate\":0.05}";
    }

    /**
     * Builds an EAN-less, unconsumed, positive-net valued line — one eligible item
     * under the whole-store assiette (§13, §22.1).
     *
     * @param lineId The line id.
     * @param netTtc The net TTC amount that founds the assiette.
     * @return A valued line eligible for the mechanic.
     */
    private static ValuedLine line(String lineId, String netTtc) {
        return new ValuedLine(lineId, null, BigDecimal.ONE,
                new BigDecimal(netTtc), new BigDecimal(netTtc), false);
    }

    /**
     * Builds a card context on the given fiscal instant, holding the given membership
     * communities and per-rule monthly cumulatives, with a mocked account (§14, §15).
     *
     * @param instant     The fixed fiscal evaluation instant.
     * @param communities The membership community codes the card holds; may be null.
     * @param ruleEarn    The per-rule monthly earn cumulative; may be null.
     * @return The dated card context.
     */
    private static CardContext context(LocalDateTime instant, Set<String> communities,
                                       Map<String, BigDecimal> ruleEarn) {
        FidelityAccount account = Mockito.mock(FidelityAccount.class);
        return new CardContext(account, 0, ruleEarn, null, null, null, communities, null, instant);
    }

    /**
     * The factory reports the monthly-date rule type it interprets.
     */
    @Test
    @DisplayName("getRuleType returns MONTHLY_DATE_EARN")
    void getRuleTypeReturnsMonthlyDate() {
        assertEquals(FidelityRule.TYPE_MONTHLY_DATE_EARN, new MonthlyDateEarnFactory().getRuleType());
    }

    /**
     * The factory creates a monthly-date applier bound to the rule.
     */
    @Test
    @DisplayName("create returns a MonthlyDateEarnApplier")
    void createReturnsApplier() {
        EarnRuleApplier applier = new MonthlyDateEarnFactory().create(ruleWith(standardSpec()));
        assertNotNull(applier);
        assertTrue(applier instanceof MonthlyDateEarnApplier);
    }

    /**
     * A null card context short-circuits to the empty entry (context == null true leg
     * of the day-gate guard).
     */
    @Test
    @DisplayName("apply returns none when the context is null")
    void applyReturnsNoneWhenContextNull() {
        EarnRuleApplier applier = new MonthlyDateEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), null);
        assertTrue(entry.isEmpty());
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(0, entry.amount.compareTo(BigDecimal.ZERO));
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * On the eve of the configured day the day-of-month differs and the rule grants
     * nothing (context == null false arm, day-mismatch true leg — low side of the
     * border).
     */
    @Test
    @DisplayName("apply returns none on the day before the configured day")
    void applyReturnsNoneOnDayBefore() {
        EarnRuleApplier applier = new MonthlyDateEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")),
                context(DAY_BEFORE, Set.of(COMMUNITY), null));
        assertTrue(entry.isEmpty());
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * On the morrow of the configured day the day-of-month differs and the rule grants
     * nothing (day-mismatch true leg — high side of the border).
     */
    @Test
    @DisplayName("apply returns none on the day after the configured day")
    void applyReturnsNoneOnDayAfter() {
        EarnRuleApplier applier = new MonthlyDateEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")),
                context(DAY_AFTER, Set.of(COMMUNITY), null));
        assertTrue(entry.isEmpty());
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * On the configured day a card that is not a member of the required community
     * grants nothing (communityCode != null true leg, !isMemberOf true leg of the
     * membership guard).
     */
    @Test
    @DisplayName("apply returns none when the card is not a member")
    void applyReturnsNoneWhenNotMember() {
        EarnRuleApplier applier = new MonthlyDateEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")),
                context(ON_DAY, Set.of("BABIES"), null));
        assertTrue(entry.isEmpty());
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * On the configured day, when the rule has already earned this period, the
     * uniqueness gate grants nothing (hasEarnedThisPeriod true leg — a positive
     * per-rule monthly cumulative for the code, §15).
     */
    @Test
    @DisplayName("apply returns none when the rule already earned this period")
    void applyReturnsNoneWhenAlreadyEarnedThisPeriod() {
        EarnRuleApplier applier = new MonthlyDateEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")),
                context(ON_DAY, Set.of(COMMUNITY), Map.of(CODE, new BigDecimal("2.00"))));
        assertTrue(entry.isEmpty());
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * On the configured day a member with an empty basket has no eligible line and
     * grants nothing (eligible.isEmpty true leg).
     */
    @Test
    @DisplayName("apply returns none for a member with no eligible lines")
    void applyReturnsNoneWhenNoEligibleLines() {
        EarnRuleApplier applier = new MonthlyDateEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(), context(ON_DAY, Set.of(COMMUNITY), null));
        assertTrue(entry.isEmpty());
        assertEquals(0, entry.baseAmount.compareTo(BigDecimal.ZERO));
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * On the configured day a member whose every line is consumed by a commercial
     * offer has an empty assiette and grants nothing (eligible.isEmpty true leg via I2
     * filtering).
     */
    @Test
    @DisplayName("apply returns none when every line is consumed by an offer")
    void applyReturnsNoneWhenAllLinesConsumed() {
        EarnRuleApplier applier = new MonthlyDateEarnFactory().create(ruleWith(standardSpec()));
        List<ValuedLine> lines = new ArrayList<>();
        lines.add(new ValuedLine("L1", null, BigDecimal.ONE, new BigDecimal("10.00"), new BigDecimal("10.00"), true));
        lines.add(new ValuedLine("L2", null, BigDecimal.ONE, new BigDecimal("20.00"), new BigDecimal("20.00"), true));
        EarnEntry entry = applier.apply(lines, context(ON_DAY, Set.of(COMMUNITY), null));
        assertTrue(entry.isEmpty());
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * On the configured day a member with eligible lines and no prior earn this period
     * earns the rate over the summed net assiette (day-gate false, member branch,
     * hasEarnedThisPeriod false leg, eligible.isEmpty false leg → producer path).
     */
    @Test
    @DisplayName("apply grants 40 % on the net assiette for a member on the day")
    void applyGrantsForMemberOnTheDay() {
        EarnRuleApplier applier = new MonthlyDateEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00"), line("L2", "20.00")),
                context(ON_DAY, Set.of(COMMUNITY), null));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("30.00")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("12.00")));
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(List.of("L1", "L2"), entry.lineIds);
    }

    /**
     * A zero per-rule cumulative for the code is not a prior earn, so the uniqueness
     * gate lets the rule fire (hasEarnedThisPeriod false leg reached through a
     * non-positive cumulative, §15).
     */
    @Test
    @DisplayName("apply grants when the period cumulative is zero")
    void applyGrantsWhenPeriodCumulativeZero() {
        EarnRuleApplier applier = new MonthlyDateEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")),
                context(ON_DAY, Set.of(COMMUNITY), Map.of(CODE, BigDecimal.ZERO)));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("10.00")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("4.00")));
        assertEquals(List.of("L1"), entry.lineIds);
    }

    /**
     * A specification without a {@code communityCode} skips the membership gate: a
     * non-member card still earns on the configured day (communityCode == null false
     * short-circuit of the membership guard, §12).
     */
    @Test
    @DisplayName("apply grants without a community gate when communityCode is absent")
    void applyGrantsWhenNoCommunityGate() {
        EarnRuleApplier applier = new MonthlyDateEarnFactory().create(ruleWith(gatelessSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "25.00")),
                context(ON_DAY, Set.of("BABIES"), null));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("25.00")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("10.00")));
        assertEquals(List.of("L1"), entry.lineIds);
    }

    /**
     * The rate applies to the whole assiette and rounds once at the centime HALF_UP
     * (I4): 4.50 € at 5 % is 0.225 €, rounded to 0.23 €.
     */
    @Test
    @DisplayName("apply rounds the earn at the centime HALF_UP")
    void applyRoundsAtCentimeHalfUp() {
        EarnRuleApplier applier = new MonthlyDateEarnFactory().create(ruleWith(roundingSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "4.50")),
                context(ON_DAY, Set.of(COMMUNITY), null));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("4.50")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("0.23")));
    }
}
