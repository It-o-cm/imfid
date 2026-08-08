package com.intermarche.fidelity.rule.appliers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.rule.CardContext;
import com.intermarche.fidelity.rule.EarnEntry;
import com.intermarche.fidelity.rule.EarnRuleApplier;
import com.intermarche.fidelity.rule.ValuedLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link ChallengeEarnFactory} and its
 * {@link ChallengeEarnApplier} — "my winning challenges" (§12): the ascending
 * purchase-amount tier ladder, the running challenge base carried by the context,
 * the mission gate at a mission-required tier and the "grant only the delta over the
 * already-reached reward" rule (re-crossing a passed tier grants nothing).
 * <p>
 * Pure logic: the applier's arithmetic is asserted directly on in-memory valued
 * baskets. The assiette scope uses {@code wholeStore} with EAN-less lines, so no
 * Panache product reference is read — no static finder needs mocking (§13, §25.4).
 * The card context is built on a fixed fiscal instant with a mocked account and a
 * real activation whose period straddles that instant, so the activation window is
 * tested by the two dates around its border and never read from the real clock
 * (§24.6, §31.1). Every {@link BigDecimal} is asserted by {@code compareTo}.
 */
class ChallengeEarnFactoryTest {

    /**
     * The stable rule code carried by every fixture rule.
     */
    private static final String CODE = "CHALLENGE-2026";

    /**
     * The printed rule label carried by every fixture rule.
     */
    private static final String LABEL = "My winning challenges";

    /**
     * The fixed fiscal evaluation instant at the program zone (mid-month); no test
     * reads the real clock (§24.6).
     */
    private static final LocalDateTime EVAL_INSTANT = LocalDateTime.of(2026, 3, 15, 10, 0);

    /**
     * The evaluation civil date derived from {@link #EVAL_INSTANT}.
     */
    private static final LocalDate EVAL_DATE = EVAL_INSTANT.toLocalDate();

    /**
     * An open activation period start well before the evaluation date.
     */
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);

    /**
     * Builds a challenge rule carrying the given JSON specification.
     *
     * @param specification The rule specification JSON.
     * @return A fidelity rule ready for the applier.
     */
    private static FidelityRule ruleWith(String specification) {
        FidelityRule rule = new FidelityRule();
        rule.code = CODE;
        rule.label = LABEL;
        rule.type = FidelityRule.TYPE_CHALLENGE_EARN;
        rule.specification = specification;
        return rule;
    }

    /**
     * Builds the standard three-tier challenge specification, deliberately listed in
     * descending threshold order to exercise the ascending sort: 50 €→5 €, 100 €→12 €
     * and a mission-gated 200 €→30 € top tier.
     *
     * @return The challenge specification JSON.
     */
    private static String standardSpec() {
        return "{\"scope\":{\"wholeStore\":true},\"tiers\":["
                + "{\"threshold\":200,\"reward\":30,\"missionRequired\":true},"
                + "{\"threshold\":100,\"reward\":12},"
                + "{\"threshold\":50,\"reward\":5}]}";
    }

    /**
     * Builds an EAN-less, unconsumed, positive-net valued line — one eligible unit
     * under the whole-store assiette (§13, §22.1).
     *
     * @param lineId The line id.
     * @param netTtc The net TTC amount that founds the assiette.
     * @return A valued line eligible for the challenge.
     */
    private static ValuedLine line(String lineId, String netTtc) {
        return new ValuedLine(lineId, null, BigDecimal.ONE,
                new BigDecimal(netTtc), new BigDecimal(netTtc), false);
    }

    /**
     * Builds an EAN-less line already consumed by a commercial offer (I2) — never an
     * earn candidate.
     *
     * @param lineId The line id.
     * @param netTtc The net TTC amount.
     * @return A consumed valued line.
     */
    private static ValuedLine consumedLine(String lineId, String netTtc) {
        return new ValuedLine(lineId, null, BigDecimal.ONE,
                new BigDecimal(netTtc), new BigDecimal(netTtc), true);
    }

    /**
     * Builds a real activation for the fixture rule over an open period.
     *
     * @param start       The activation period start (inclusive).
     * @param missionDone Whether the challenge mission is completed.
     * @return The activation.
     */
    private static FidelityActivation activation(LocalDate start, boolean missionDone) {
        FidelityActivation activation = new FidelityActivation();
        activation.ruleCode = CODE;
        activation.periodStart = start;
        activation.periodEnd = null;
        activation.missionDone = missionDone;
        return activation;
    }

    /**
     * Builds a card context on the fixed fiscal instant with the given prior challenge
     * base and activations (§15).
     *
     * @param priorBase   The running challenge base before this basket; null read as zero.
     * @param activations The active card activations.
     * @return The dated card context.
     */
    private static CardContext context(BigDecimal priorBase, List<FidelityActivation> activations) {
        FidelityAccount account = Mockito.mock(FidelityAccount.class);
        Map<String, BigDecimal> challenge = priorBase == null ? null : Map.of(CODE, priorBase);
        return new CardContext(account, 0, null, null, null, challenge, null, activations, EVAL_INSTANT);
    }

    /**
     * The factory reports the challenge rule type it interprets.
     */
    @Test
    @DisplayName("getRuleType returns CHALLENGE_EARN")
    void getRuleTypeReturnsChallenge() {
        assertEquals(FidelityRule.TYPE_CHALLENGE_EARN, new ChallengeEarnFactory().getRuleType());
    }

    /**
     * The factory creates a challenge applier bound to the rule.
     */
    @Test
    @DisplayName("create returns a ChallengeEarnApplier")
    void createReturnsApplier() {
        EarnRuleApplier applier = new ChallengeEarnFactory().create(ruleWith(standardSpec()));
        assertNotNull(applier);
        assertTrue(applier instanceof ChallengeEarnApplier);
    }

    /**
     * A null context short-circuits to the empty entry (context == null true leg of
     * the opening guard).
     */
    @Test
    @DisplayName("apply returns none when the context is null")
    void applyReturnsNoneWhenContextNull() {
        EarnRuleApplier applier = new ChallengeEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "60.00")), null);
        assertTrue(entry.isEmpty());
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(0, entry.amount.compareTo(BigDecimal.ZERO));
    }

    /**
     * An empty tier ladder short-circuits to the empty entry with a non-null context
     * (context == null false leg, tiers.isEmpty() true leg).
     */
    @Test
    @DisplayName("apply returns none when the tier ladder is empty")
    void applyReturnsNoneWhenTiersEmpty() {
        EarnRuleApplier applier = new ChallengeEarnFactory()
                .create(ruleWith("{\"scope\":{\"wholeStore\":true},\"tiers\":[]}"));
        EarnEntry entry = applier.apply(List.of(line("L1", "60.00")),
                context(null, List.of(activation(PERIOD_START, false))));
        assertTrue(entry.isEmpty());
    }

    /**
     * With no activation at all, the challenge grants nothing (activation == null true
     * arm) — a rule requires an active card activation (§14).
     */
    @Test
    @DisplayName("apply returns none when the card has no activation")
    void applyReturnsNoneWhenNoActivation() {
        EarnRuleApplier applier = new ChallengeEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "60.00")), context(null, List.of()));
        assertTrue(entry.isEmpty());
    }

    /**
     * An activation whose period starts the day after the evaluation date is inactive,
     * so {@code activationFor} yields null and the challenge grants nothing (activation
     * == null true arm) — the upper instant straddling the activation-start border
     * (§24.6, §31.1); its lower counterpart is {@link #applyGrantsFirstTierAtActivationStart()}.
     */
    @Test
    @DisplayName("apply returns none when the activation starts after the evaluation date")
    void applyReturnsNoneWhenActivationStartsAfterEvaluation() {
        EarnRuleApplier applier = new ChallengeEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "60.00")),
                context(null, List.of(activation(EVAL_DATE.plusDays(1), false))));
        assertTrue(entry.isEmpty());
    }

    /**
     * An empty basket yields no eligible line, so the challenge grants nothing
     * (activation == null false arm, eligible.isEmpty() true leg).
     */
    @Test
    @DisplayName("apply returns none when there are no eligible lines")
    void applyReturnsNoneWhenNoEligibleLines() {
        EarnRuleApplier applier = new ChallengeEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(), context(null, List.of(activation(PERIOD_START, false))));
        assertTrue(entry.isEmpty());
        assertEquals(0, entry.baseAmount.compareTo(BigDecimal.ZERO));
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * A basket whose every line is consumed by a commercial offer leaves the assiette
     * empty, so the challenge grants nothing (eligible.isEmpty() true leg via I2).
     */
    @Test
    @DisplayName("apply returns none when every line is consumed by an offer")
    void applyReturnsNoneWhenAllLinesConsumed() {
        EarnRuleApplier applier = new ChallengeEarnFactory().create(ruleWith(standardSpec()));
        List<ValuedLine> lines = new ArrayList<>();
        lines.add(consumedLine("L1", "40.00"));
        lines.add(consumedLine("L2", "40.00"));
        EarnEntry entry = applier.apply(lines, context(null, List.of(activation(PERIOD_START, false))));
        assertTrue(entry.isEmpty());
    }

    /**
     * From a zero base, a 60 € ticket crosses the first tier (threshold 50 €) at an
     * activation active from the evaluation date itself — the lower instant straddling
     * the activation-start border (activation == null false arm, eligible.isEmpty()
     * false leg, granted &gt; 0). The reward is the first tier's 5 €.
     */
    @Test
    @DisplayName("apply grants the first tier when the activation opens on the evaluation date")
    void applyGrantsFirstTierAtActivationStart() {
        EarnRuleApplier applier = new ChallengeEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "60.00")),
                context(null, List.of(activation(EVAL_DATE, false))));
        assertFalse(entry.isEmpty());
        assertEquals(0, entry.amount.compareTo(new BigDecimal("5.00")));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("60.00")));
        assertEquals(List.of("L1"), entry.lineIds);
    }

    /**
     * From a prior base already inside the first tier (60 €, reward 5 €), a 60 € ticket
     * pushes the cumulative to 120 € and crosses the second tier (threshold 100 €,
     * reward 12 €); only the delta 12 − 5 = 7 € is granted (granted &gt; 0). This
     * exercises the ascending sort of the descending-listed ladder and the tier-break
     * (base &lt; threshold true leg for the mission tier).
     */
    @Test
    @DisplayName("apply grants only the reward delta when crossing into a higher tier")
    void applyGrantsDeltaWhenCrossingHigherTier() {
        EarnRuleApplier applier = new ChallengeEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "60.00")),
                context(new BigDecimal("60.00"), List.of(activation(PERIOD_START, false))));
        assertFalse(entry.isEmpty());
        assertEquals(0, entry.amount.compareTo(new BigDecimal("7.00")));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("60.00")));
    }

    /**
     * Re-crossing an already-reached tier grants nothing: from a 60 € prior base
     * (reward 5 €) a 10 € ticket lands at 70 €, still inside the first tier, so the
     * reward delta is zero (granted.signum() &lt;= 0 true arm).
     */
    @Test
    @DisplayName("apply returns none when no new tier is crossed")
    void applyReturnsNoneWhenNoNewTierCrossed() {
        EarnRuleApplier applier = new ChallengeEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")),
                context(new BigDecimal("60.00"), List.of(activation(PERIOD_START, false))));
        assertTrue(entry.isEmpty());
    }

    /**
     * Reaching the mission-gated top tier without the mission grants nothing beyond
     * the second tier: from a 100 € prior base (reward 12 €) a 120 € ticket reaches
     * 220 €, but the 200 € tier requires a mission that is not done, so the reward
     * stays at 12 € and the delta is zero (missionRequired &amp;&amp; !missionDone true
     * → tier skipped; granted.signum() &lt;= 0 true arm).
     */
    @Test
    @DisplayName("apply returns none at the mission tier when the mission is not done")
    void applyReturnsNoneAtMissionTierWithoutMission() {
        EarnRuleApplier applier = new ChallengeEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "120.00")),
                context(new BigDecimal("100.00"), List.of(activation(PERIOD_START, false))));
        assertTrue(entry.isEmpty());
    }

    /**
     * Reaching the mission-gated top tier with the mission done unlocks its 30 €: from
     * a 100 € prior base (reward 12 €) a 120 € ticket reaches 220 €, and the 200 € tier
     * now applies (missionRequired &amp;&amp; !missionDone false because the mission is
     * done), so the delta 30 − 12 = 18 € is granted (granted &gt; 0). The base sums two
     * lines to 120 €.
     */
    @Test
    @DisplayName("apply grants the mission tier when the mission is done")
    void applyGrantsMissionTierWhenMissionDone() {
        EarnRuleApplier applier = new ChallengeEarnFactory().create(ruleWith(standardSpec()));
        List<ValuedLine> lines = List.of(line("L1", "60.00"), line("L2", "60.00"));
        EarnEntry entry = applier.apply(lines,
                context(new BigDecimal("100.00"), List.of(activation(PERIOD_START, true))));
        assertFalse(entry.isEmpty());
        assertEquals(0, entry.amount.compareTo(new BigDecimal("18.00")));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("120.00")));
        assertEquals(List.of("L1", "L2"), entry.lineIds);
    }

    /**
     * A malformed tier ladder is pruned down to its one well-formed tier: non-object
     * elements ({@code !node.isObject()}), a missing threshold ({@code value == null}),
     * a JSON-null threshold ({@code value.isNull()}), a non-numeric threshold
     * ({@code !value.isNumber()}) and the same three malformations on {@code reward}
     * (the second leg of {@code threshold == null || reward == null}) are all dropped.
     * The single valid 80 €→8 € tier still fires for a 90 € ticket, proving only it
     * survived parsing (§31.2).
     */
    @Test
    @DisplayName("apply ignores malformed tiers and keeps the well-formed one")
    void applyIgnoresMalformedTiers() {
        String messy = "{\"scope\":{\"wholeStore\":true},\"tiers\":["
                + "5,"
                + "null,"
                + "\"nope\","
                + "{\"reward\":10},"
                + "{\"threshold\":null,\"reward\":10},"
                + "{\"threshold\":\"x\",\"reward\":10},"
                + "{\"threshold\":50},"
                + "{\"threshold\":50,\"reward\":null},"
                + "{\"threshold\":50,\"reward\":\"y\"},"
                + "{\"threshold\":80,\"reward\":8}]}";
        EarnRuleApplier applier = new ChallengeEarnFactory().create(ruleWith(messy));
        EarnEntry entry = applier.apply(List.of(line("L1", "90.00")),
                context(null, List.of(activation(PERIOD_START, false))));
        assertFalse(entry.isEmpty());
        assertEquals(0, entry.amount.compareTo(new BigDecimal("8.00")));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("90.00")));
    }
}
