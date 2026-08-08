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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link BrandTieredEarnFactory} and its
 * {@link BrandTieredEarnApplier} — the "everyday brands" socle (§12): the
 * N-eligible-items threshold gate, the visit-threshold rate switch (base vs boosted)
 * and the single per-rule centime rounding (I4).
 * <p>
 * Pure logic: the applier's arithmetic is asserted directly on in-memory valued
 * baskets. The assiette scope uses {@code wholeStore} with EAN-less lines, so no
 * Panache product reference is read — no static finder needs mocking (§13, §25.4).
 * The card context is built on a fixed fiscal instant with a mocked account, so the
 * visit-threshold border is straddled by two visit counts and never read from the
 * real clock (§24.6). Every {@link BigDecimal} is asserted by {@code compareTo}.
 */
class BrandTieredEarnFactoryTest {

    /**
     * The stable rule code carried by every fixture rule.
     */
    private static final String CODE = "SOCLE-BRANDS";

    /**
     * The printed rule label carried by every fixture rule.
     */
    private static final String LABEL = "Everyday brands socle";

    /**
     * The fixed fiscal evaluation instant at the program zone (mid-month); no test
     * reads the real clock (§24.6).
     */
    private static final LocalDateTime EVAL_INSTANT = LocalDateTime.of(2026, 3, 15, 10, 0);

    /**
     * The visit threshold at or above which the boosted rate applies (§12, I3).
     */
    private static final int VISIT_THRESHOLD = 4;

    /**
     * The minimum eligible items required for the rule to grant anything (§12, §22.2).
     */
    private static final int MIN_ITEMS = 3;

    /**
     * Builds a rule of the socle type carrying the given JSON specification.
     *
     * @param specification The rule specification JSON.
     * @return A fidelity rule ready for the applier.
     */
    private static FidelityRule ruleWith(String specification) {
        FidelityRule rule = new FidelityRule();
        rule.code = CODE;
        rule.label = LABEL;
        rule.type = FidelityRule.TYPE_BRAND_TIERED_EARN;
        rule.specification = specification;
        return rule;
    }

    /**
     * Builds the standard socle specification: whole-store assiette, 5 %/10 % rates,
     * a visit threshold of 4 and a minimum of 3 eligible items.
     *
     * @return The socle specification JSON.
     */
    private static String standardSpec() {
        return "{\"scope\":{\"wholeStore\":true},\"baseRate\":0.05,\"boostedRate\":0.10,"
                + "\"visitThreshold\":" + VISIT_THRESHOLD + ",\"minEligibleItems\":" + MIN_ITEMS + "}";
    }

    /**
     * Builds an EAN-less, unconsumed, positive-net valued line — one eligible item
     * under the whole-store assiette (§13, §22.2).
     *
     * @param lineId  The line id.
     * @param netTtc  The net TTC amount that founds the assiette.
     * @return A valued line eligible for the socle.
     */
    private static ValuedLine line(String lineId, String netTtc) {
        return new ValuedLine(lineId, null, BigDecimal.ONE,
                new BigDecimal(netTtc), new BigDecimal(netTtc), false);
    }

    /**
     * Builds a card context on the fixed fiscal instant with the given month visits
     * and a mocked account (§15).
     *
     * @param monthlyVisits The month's distinct visits.
     * @return The dated card context.
     */
    private static CardContext contextWithVisits(int monthlyVisits) {
        FidelityAccount account = Mockito.mock(FidelityAccount.class);
        return new CardContext(account, monthlyVisits, null, null, null, null, null, null, EVAL_INSTANT);
    }

    /**
     * The factory reports the socle rule type it interprets.
     */
    @Test
    @DisplayName("getRuleType returns BRAND_TIERED_EARN")
    void getRuleTypeReturnsBrandTiered() {
        assertEquals(FidelityRule.TYPE_BRAND_TIERED_EARN, new BrandTieredEarnFactory().getRuleType());
    }

    /**
     * The factory creates a socle applier bound to the rule.
     */
    @Test
    @DisplayName("create returns a BrandTieredEarnApplier")
    void createReturnsApplier() {
        EarnRuleApplier applier = new BrandTieredEarnFactory().create(ruleWith(standardSpec()));
        assertNotNull(applier);
        assertTrue(applier instanceof BrandTieredEarnApplier);
    }

    /**
     * A null card context short-circuits to the empty entry (context == null, true arm).
     */
    @Test
    @DisplayName("apply returns none when the context is null")
    void applyReturnsNoneWhenContextNull() {
        EarnRuleApplier applier = new BrandTieredEarnFactory().create(ruleWith(standardSpec()));
        List<ValuedLine> lines = List.of(line("L1", "10.00"), line("L2", "10.00"), line("L3", "10.00"));
        EarnEntry entry = applier.apply(lines, null);
        assertTrue(entry.isEmpty());
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(0, entry.amount.compareTo(BigDecimal.ZERO));
    }

    /**
     * An empty basket yields no eligible lines and grants nothing (eligible.isEmpty()
     * true leg; context != null false arm).
     */
    @Test
    @DisplayName("apply returns none when there are no eligible lines")
    void applyReturnsNoneWhenNoEligibleLines() {
        EarnRuleApplier applier = new BrandTieredEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(), contextWithVisits(0));
        assertTrue(entry.isEmpty());
        assertEquals(0, entry.baseAmount.compareTo(BigDecimal.ZERO));
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * Lines that are all consumed by a commercial offer leave the assiette empty, so
     * the rule grants nothing (eligible.isEmpty() true leg via I2 filtering).
     */
    @Test
    @DisplayName("apply returns none when every line is consumed by an offer")
    void applyReturnsNoneWhenAllLinesConsumed() {
        EarnRuleApplier applier = new BrandTieredEarnFactory().create(ruleWith(standardSpec()));
        List<ValuedLine> lines = new ArrayList<>();
        lines.add(new ValuedLine("L1", null, BigDecimal.ONE, new BigDecimal("10.00"), new BigDecimal("10.00"), true));
        lines.add(new ValuedLine("L2", null, BigDecimal.ONE, new BigDecimal("10.00"), new BigDecimal("10.00"), true));
        EarnEntry entry = applier.apply(lines, contextWithVisits(0));
        assertTrue(entry.isEmpty());
    }

    /**
     * A non-empty assiette below the minimum item count grants nothing
     * (eligible.isEmpty() false leg, count &lt; minEligibleItems true leg).
     */
    @Test
    @DisplayName("apply returns none below the minimum eligible items")
    void applyReturnsNoneBelowMinimumItems() {
        EarnRuleApplier applier = new BrandTieredEarnFactory().create(ruleWith(standardSpec()));
        List<ValuedLine> lines = List.of(line("L1", "10.00"), line("L2", "10.00"));
        EarnEntry entry = applier.apply(lines, contextWithVisits(0));
        assertTrue(entry.isEmpty());
    }

    /**
     * At the minimum item count and below the visit threshold, the base rate applies
     * (both guard legs false, ternary false arm → base rate).
     */
    @Test
    @DisplayName("apply grants the base rate just below the visit threshold")
    void applyGrantsBaseRateBelowVisitThreshold() {
        EarnRuleApplier applier = new BrandTieredEarnFactory().create(ruleWith(standardSpec()));
        List<ValuedLine> lines = List.of(line("L1", "10.00"), line("L2", "20.00"), line("L3", "30.00"));
        EarnEntry entry = applier.apply(lines, contextWithVisits(VISIT_THRESHOLD - 1));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("60.00")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("3.00")));
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(List.of("L1", "L2", "L3"), entry.lineIds);
    }

    /**
     * At the visit threshold the boosted rate applies (ternary true arm → boosted
     * rate); the border is straddled with {@link #applyGrantsBaseRateBelowVisitThreshold()}.
     */
    @Test
    @DisplayName("apply grants the boosted rate at the visit threshold")
    void applyGrantsBoostedRateAtVisitThreshold() {
        EarnRuleApplier applier = new BrandTieredEarnFactory().create(ruleWith(standardSpec()));
        List<ValuedLine> lines = List.of(line("L1", "10.00"), line("L2", "20.00"), line("L3", "30.00"));
        EarnEntry entry = applier.apply(lines, contextWithVisits(VISIT_THRESHOLD));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("60.00")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("6.00")));
        assertEquals(List.of("L1", "L2", "L3"), entry.lineIds);
    }

    /**
     * The rate applies to the whole assiette and rounds once at the centime HALF_UP
     * (I4): 10.10 € at 5 % is 0.505 €, rounded to 0.51 €.
     */
    @Test
    @DisplayName("apply rounds the earn at the centime HALF_UP")
    void applyRoundsAtCentimeHalfUp() {
        EarnRuleApplier applier = new BrandTieredEarnFactory().create(ruleWith(standardSpec()));
        List<ValuedLine> lines = List.of(line("L1", "3.36"), line("L2", "3.37"), line("L3", "3.37"));
        EarnEntry entry = applier.apply(lines, contextWithVisits(0));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("10.10")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("0.51")));
    }
}
