package com.intermarche.fidelity.rule.appliers;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link EcouponEarnFactory} and its
 * {@link EcouponEarnApplier} — the weekly e-coupon mechanic (≥ 20 %, §12): the
 * per-card activation gate resolved on the fiscal date against the card context
 * (§14, §31.1), the net-assiette sum of the eligible lines and the single per-rule
 * centime rounding (I4).
 * <p>
 * Pure logic: the applier's arithmetic is asserted directly on in-memory valued
 * baskets. The assiette scope uses {@code wholeStore} with EAN-less lines, so no
 * Panache product reference is read — no static finder needs mocking (§13, §25.4).
 * The activation is a dated snapshot resolved through {@link CardContext} against a
 * fixed fiscal instant; the applier reads no clock. The activation window is tested
 * at both instants straddling each border (§24.6): the day before the window opens,
 * the exact inclusive start, the exact inclusive end and the day after it closes —
 * never the day the campaign runs. Every {@link BigDecimal} is asserted by
 * {@code compareTo}.
 */
class EcouponEarnFactoryTest {

    /**
     * The stable rule code carried by every fixture rule.
     */
    private static final String CODE = "ECOUPON-DAIRY";

    /**
     * The printed rule label carried by every fixture rule.
     */
    private static final String LABEL = "Weekly dairy e-coupon";

    /**
     * A fixed fiscal instant at the program zone; the activation window is resolved
     * as of this date, so it stamps the context and founds every boundary (2026-01-15).
     */
    private static final LocalDateTime EVAL_INSTANT = LocalDateTime.of(2026, 1, 15, 10, 0);

    /**
     * The fiscal evaluation date the {@link #EVAL_INSTANT} derives from (2026-01-15).
     */
    private static final LocalDate EVAL_DATE = EVAL_INSTANT.toLocalDate();

    /**
     * Builds an e-coupon rule carrying the given JSON specification.
     *
     * @param specification The rule specification JSON.
     * @return A fidelity rule ready for the applier.
     */
    private static FidelityRule ruleWith(String specification) {
        FidelityRule rule = new FidelityRule();
        rule.code = CODE;
        rule.label = LABEL;
        rule.type = FidelityRule.TYPE_ECOUPON_EARN;
        rule.specification = specification;
        return rule;
    }

    /**
     * Builds the standard specification: whole-store assiette and a 20 % rate (§12).
     *
     * @return The standard specification JSON.
     */
    private static String standardSpec() {
        return "{\"scope\":{\"wholeStore\":true},\"rate\":0.20}";
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
     * Builds an activation for the given rule over the given inclusive period.
     *
     * @param ruleCode The rule code the activation enables.
     * @param start    The inclusive period start.
     * @param end      The inclusive period end, or null while open.
     * @return The activation.
     */
    private static FidelityActivation activation(String ruleCode, LocalDate start, LocalDate end) {
        FidelityActivation activation = new FidelityActivation();
        activation.ruleCode = ruleCode;
        activation.periodStart = start;
        activation.periodEnd = end;
        return activation;
    }

    /**
     * Builds a card context carrying the given activations on the fixed fiscal
     * instant with a mocked account (§14, §15).
     *
     * @param activations The card activations; null read as empty by the context.
     * @return The dated card context.
     */
    private static CardContext contextWith(List<FidelityActivation> activations) {
        FidelityAccount account = Mockito.mock(FidelityAccount.class);
        return new CardContext(account, 0, null, null, null, null, null, activations, EVAL_INSTANT);
    }

    /**
     * The factory reports the e-coupon rule type it interprets.
     */
    @Test
    @DisplayName("getRuleType returns ECOUPON_EARN")
    void getRuleTypeReturnsEcoupon() {
        assertEquals(FidelityRule.TYPE_ECOUPON_EARN, new EcouponEarnFactory().getRuleType());
    }

    /**
     * The factory creates an e-coupon applier bound to the rule.
     */
    @Test
    @DisplayName("create returns an EcouponEarnApplier")
    void createReturnsApplier() {
        EarnRuleApplier applier = new EcouponEarnFactory().create(ruleWith(standardSpec()));
        assertNotNull(applier);
        assertTrue(applier instanceof EcouponEarnApplier);
    }

    /**
     * A null card context short-circuits to the empty entry (context == null true leg
     * of the compound guard).
     */
    @Test
    @DisplayName("apply returns none when the context is null")
    void applyReturnsNoneWhenContextNull() {
        EarnRuleApplier applier = new EcouponEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), null);
        assertTrue(entry.isEmpty());
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(0, entry.amount.compareTo(BigDecimal.ZERO));
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * A context carrying no activation at all grants nothing (context == null false
     * arm, activationFor == null true leg through an empty activation list).
     */
    @Test
    @DisplayName("apply returns none when the card holds no activation")
    void applyReturnsNoneWhenNoActivation() {
        EarnRuleApplier applier = new EcouponEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), contextWith(List.of()));
        assertTrue(entry.isEmpty());
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * An activation for another rule never matches this rule's code, so the card
     * grants nothing (activationFor == null true leg through a code mismatch).
     */
    @Test
    @DisplayName("apply returns none when the activation targets another rule")
    void applyReturnsNoneWhenActivationForAnotherRule() {
        EarnRuleApplier applier = new EcouponEarnFactory().create(ruleWith(standardSpec()));
        List<FidelityActivation> activations =
                List.of(activation("ECOUPON-OTHER", EVAL_DATE.minusDays(3), EVAL_DATE.plusDays(3)));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), contextWith(activations));
        assertTrue(entry.isEmpty());
    }

    /**
     * On the day before the activation window opens the activation is not yet active,
     * so the card grants nothing (activationFor == null true leg, lower boundary
     * straddled from below: start is the day after the fiscal date, §24.6).
     */
    @Test
    @DisplayName("apply returns none the day before the activation opens")
    void applyReturnsNoneDayBeforeActivationOpens() {
        EarnRuleApplier applier = new EcouponEarnFactory().create(ruleWith(standardSpec()));
        List<FidelityActivation> activations =
                List.of(activation(CODE, EVAL_DATE.plusDays(1), EVAL_DATE.plusDays(7)));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), contextWith(activations));
        assertTrue(entry.isEmpty());
    }

    /**
     * On the day after the activation window closes the activation is no longer
     * active, so the card grants nothing (activationFor == null true leg, upper
     * boundary straddled from above: end is the day before the fiscal date, §24.6).
     */
    @Test
    @DisplayName("apply returns none the day after the activation closes")
    void applyReturnsNoneDayAfterActivationCloses() {
        EarnRuleApplier applier = new EcouponEarnFactory().create(ruleWith(standardSpec()));
        List<FidelityActivation> activations =
                List.of(activation(CODE, EVAL_DATE.minusDays(7), EVAL_DATE.minusDays(1)));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), contextWith(activations));
        assertTrue(entry.isEmpty());
    }

    /**
     * An activated card with an empty basket has no eligible line and grants nothing
     * (both guard legs false, eligible.isEmpty true leg).
     */
    @Test
    @DisplayName("apply returns none for an activated card with no eligible lines")
    void applyReturnsNoneWhenActivatedButNoEligibleLines() {
        EarnRuleApplier applier = new EcouponEarnFactory().create(ruleWith(standardSpec()));
        List<FidelityActivation> activations =
                List.of(activation(CODE, EVAL_DATE.minusDays(3), EVAL_DATE.plusDays(3)));
        EarnEntry entry = applier.apply(List.of(), contextWith(activations));
        assertTrue(entry.isEmpty());
        assertEquals(0, entry.baseAmount.compareTo(BigDecimal.ZERO));
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * An activated card whose every line is consumed by a commercial offer has an
     * empty assiette and grants nothing (eligible.isEmpty true leg via I2 filtering).
     */
    @Test
    @DisplayName("apply returns none when every line is consumed by an offer")
    void applyReturnsNoneWhenAllLinesConsumed() {
        EarnRuleApplier applier = new EcouponEarnFactory().create(ruleWith(standardSpec()));
        List<FidelityActivation> activations =
                List.of(activation(CODE, EVAL_DATE.minusDays(3), EVAL_DATE.plusDays(3)));
        List<ValuedLine> lines = new ArrayList<>();
        lines.add(new ValuedLine("L1", null, BigDecimal.ONE, new BigDecimal("10.00"), new BigDecimal("10.00"), true));
        lines.add(new ValuedLine("L2", null, BigDecimal.ONE, new BigDecimal("20.00"), new BigDecimal("20.00"), true));
        EarnEntry entry = applier.apply(lines, contextWith(activations));
        assertTrue(entry.isEmpty());
    }

    /**
     * An activated card with eligible lines earns the rate over the summed net
     * assiette (both guard legs false, eligible.isEmpty false leg → producer path):
     * 20 % of 30.00 € is 6.00 €.
     */
    @Test
    @DisplayName("apply grants 20 % on the net assiette for an activated card")
    void applyGrantsForActivatedCard() {
        EarnRuleApplier applier = new EcouponEarnFactory().create(ruleWith(standardSpec()));
        List<FidelityActivation> activations =
                List.of(activation(CODE, EVAL_DATE.minusDays(3), EVAL_DATE.plusDays(3)));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00"), line("L2", "20.00")),
                contextWith(activations));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("30.00")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("6.00")));
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(List.of("L1", "L2"), entry.lineIds);
    }

    /**
     * An activation whose period starts exactly on the fiscal date is active
     * (inclusive lower boundary straddled from the border itself, §24.6): the card
     * earns.
     */
    @Test
    @DisplayName("apply grants when the activation starts on the fiscal date")
    void applyGrantsWhenActivationStartsOnEvalDate() {
        EarnRuleApplier applier = new EcouponEarnFactory().create(ruleWith(standardSpec()));
        List<FidelityActivation> activations =
                List.of(activation(CODE, EVAL_DATE, EVAL_DATE.plusDays(6)));
        EarnEntry entry = applier.apply(List.of(line("L1", "50.00")), contextWith(activations));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("50.00")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("10.00")));
        assertEquals(List.of("L1"), entry.lineIds);
    }

    /**
     * An activation whose period ends exactly on the fiscal date is active (inclusive
     * upper boundary straddled from the border itself, §24.6): the card earns. An
     * open-ended activation (null end) is also exercised on the lower side here by the
     * fixed start.
     */
    @Test
    @DisplayName("apply grants when the activation ends on the fiscal date")
    void applyGrantsWhenActivationEndsOnEvalDate() {
        EarnRuleApplier applier = new EcouponEarnFactory().create(ruleWith(standardSpec()));
        List<FidelityActivation> activations =
                List.of(activation(CODE, EVAL_DATE.minusDays(6), EVAL_DATE));
        EarnEntry entry = applier.apply(List.of(line("L1", "50.00")), contextWith(activations));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("50.00")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("10.00")));
        assertEquals(List.of("L1"), entry.lineIds);
    }

    /**
     * The rate applies to the whole assiette and rounds once at the centime HALF_UP
     * (I4): 25 % of 10.10 € is 2.5250 €, rounded up to 2.53 €.
     */
    @Test
    @DisplayName("apply rounds the earn at the centime HALF_UP")
    void applyRoundsAtCentimeHalfUp() {
        EarnRuleApplier applier =
                new EcouponEarnFactory().create(ruleWith("{\"scope\":{\"wholeStore\":true},\"rate\":0.25}"));
        List<FidelityActivation> activations =
                List.of(activation(CODE, EVAL_DATE.minusDays(3), EVAL_DATE.plusDays(3)));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.10")), contextWith(activations));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("10.10")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("2.53")));
    }

    /**
     * A specification without a {@code rate} field falls back to a zero rate: the
     * eligible assiette is summed but the earn is zero, so the entry is empty (rate
     * default read, producer path with a non-positive amount).
     */
    @Test
    @DisplayName("apply grants nothing when the rate is absent")
    void applyGrantsNothingWhenRateAbsent() {
        EarnRuleApplier applier =
                new EcouponEarnFactory().create(ruleWith("{\"scope\":{\"wholeStore\":true}}"));
        List<FidelityActivation> activations =
                List.of(activation(CODE, EVAL_DATE.minusDays(3), EVAL_DATE.plusDays(3)));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), contextWith(activations));
        assertTrue(entry.isEmpty());
        assertEquals(0, entry.amount.compareTo(BigDecimal.ZERO));
    }
}
