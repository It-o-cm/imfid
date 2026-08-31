package com.intermarche.fidelity.rule.appliers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.rule.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link TicketThresholdEarnFactory} and its
 * {@link TicketThresholdEarnApplier} — "ticket thresholds" (§12): a fixed gain
 * unlocked by the highest amount threshold reached by the eligible assiette of the
 * single current ticket. Unlike the challenge mechanic, nothing cumulates across
 * tickets, no card activation is required and the card context is ignored, so a 45 €
 * assiette against 20 €→1 € and 40 €→2 € grants the 40 € tier's 2 € alone (not the
 * 3 € sum).
 * <p>
 * Pure logic: the arithmetic is asserted directly on in-memory valued baskets. The
 * assiette scope uses {@code wholeStore} with EAN-less lines, so
 * {@link com.intermarche.fidelity.domain.Product#findByEan(String)} is never reached
 * and no Panache static finder needs mocking (§13, §25.4). No test reads the real
 * clock: the ignored context, when supplied, is built on a fixed fiscal instant
 * (§24.6). Every {@link BigDecimal} is asserted by {@code compareTo}, at the centime
 * (I4).
 */
class TicketThresholdEarnFactoryTest {

    /**
     * The stable rule code carried by every fixture rule.
     */
    private static final String CODE = "TICKET-THRESHOLD-2026";

    /**
     * The printed rule label carried by every fixture rule.
     */
    private static final String LABEL = "Paliers de ticket";

    /**
     * A fixed fiscal instant at the program zone, for the one context-ignored test;
     * no test reads the real clock (§24.6).
     */
    private static final LocalDateTime EVAL_INSTANT = LocalDateTime.of(2026, 3, 15, 10, 0);

    /**
     * Builds a ticket-threshold rule carrying the given JSON specification.
     *
     * @param specification The rule specification JSON.
     * @return A fidelity rule ready for the applier.
     */
    private static FidelityRule ruleWith(String specification) {
        FidelityRule rule = new FidelityRule();
        rule.code = CODE;
        rule.label = LABEL;
        rule.type = FidelityRule.TYPE_TICKET_THRESHOLD_EARN;
        rule.specification = specification;
        return rule;
    }

    /**
     * Builds the standard two-tier specification, deliberately listed in descending
     * threshold order to exercise the ascending sort: 20 €→1 € and 40 €→2 €.
     *
     * @return The ticket-threshold specification JSON.
     */
    private static String standardSpec() {
        return "{\"scope\":{\"wholeStore\":true},\"tiers\":["
                + "{\"threshold\":40,\"reward\":2},"
                + "{\"threshold\":20,\"reward\":1}]}";
    }

    /**
     * Builds an EAN-less, unconsumed, positive-net valued line — one eligible unit
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
     * Builds a card context on the fixed fiscal instant with a mocked account — used
     * only to prove the mechanic ignores it (§15).
     *
     * @return The dated card context.
     */
    private static CardContext context() {
        FidelityAccount account = Mockito.mock(FidelityAccount.class);
        return new CardContext(account, 0, null, null, null, null, null, List.of(), EVAL_INSTANT);
    }

    /**
     * The factory reports the ticket-threshold rule type it interprets.
     */
    @Test
    @DisplayName("getRuleType returns TICKET_THRESHOLD_EARN")
    void getRuleTypeReturnsTicketThreshold() {
        assertEquals(FidelityRule.TYPE_TICKET_THRESHOLD_EARN,
                new TicketThresholdEarnFactory().getRuleType());
    }

    /**
     * The factory creates a ticket-threshold applier bound to the rule.
     */
    @Test
    @DisplayName("create returns a TicketThresholdEarnApplier")
    void createReturnsApplier() {
        EarnRuleApplier applier = new TicketThresholdEarnFactory().create(ruleWith(standardSpec()));
        assertNotNull(applier);
        assertTrue(applier instanceof TicketThresholdEarnApplier);
    }

    /**
     * An explicitly empty tier ladder short-circuits to the empty entry
     * ({@code tiers.isEmpty()} true leg), even with an eligible basket.
     */
    @Test
    @DisplayName("apply returns none when the tier ladder is empty")
    void applyReturnsNoneWhenTiersEmpty() {
        EarnRuleApplier applier = new TicketThresholdEarnFactory()
                .create(ruleWith("{\"scope\":{\"wholeStore\":true},\"tiers\":[]}"));
        EarnEntry entry = applier.apply(List.of(line("L1", "60.00")), context());
        assertTrue(entry.isEmpty());
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(0, entry.amount.compareTo(BigDecimal.ZERO));
    }

    /**
     * An absent {@code tiers} field leaves the ladder empty too ({@code tiers.isEmpty()}
     * true leg via the null-array path of the specification), so nothing is granted.
     */
    @Test
    @DisplayName("apply returns none when the tiers field is absent")
    void applyReturnsNoneWhenTiersAbsent() {
        EarnRuleApplier applier = new TicketThresholdEarnFactory()
                .create(ruleWith("{\"scope\":{\"wholeStore\":true}}"));
        EarnEntry entry = applier.apply(List.of(line("L1", "60.00")), context());
        assertTrue(entry.isEmpty());
    }

    /**
     * An empty basket yields no eligible line, so the mechanic grants nothing
     * ({@code tiers.isEmpty()} false leg, {@code eligible.isEmpty()} true leg). A null
     * context is accepted, proving the card context plays no part.
     */
    @Test
    @DisplayName("apply returns none when there are no eligible lines")
    void applyReturnsNoneWhenNoEligibleLines() {
        EarnRuleApplier applier = new TicketThresholdEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(), null);
        assertTrue(entry.isEmpty());
        assertEquals(0, entry.baseAmount.compareTo(BigDecimal.ZERO));
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * A basket whose every line is consumed by a commercial offer leaves the assiette
     * empty, so the mechanic grants nothing ({@code eligible.isEmpty()} true leg via I2).
     */
    @Test
    @DisplayName("apply returns none when every line is consumed by an offer")
    void applyReturnsNoneWhenAllLinesConsumed() {
        EarnRuleApplier applier = new TicketThresholdEarnFactory().create(ruleWith(standardSpec()));
        List<ValuedLine> lines = new ArrayList<>();
        lines.add(consumedLine("L1", "30.00"));
        lines.add(consumedLine("L2", "30.00"));
        EarnEntry entry = applier.apply(lines, context());
        assertTrue(entry.isEmpty());
    }

    /**
     * An assiette below the first threshold reaches no tier, so the reward stays zero
     * and the entry is empty ({@code granted.signum() <= 0} true arm; the
     * {@code assiette < threshold} break fires on the very first tier). 19,99 € against
     * a 20 € first tier grants nothing.
     */
    @Test
    @DisplayName("apply returns none when the assiette is below the first threshold")
    void applyReturnsNoneBelowFirstThreshold() {
        EarnRuleApplier applier = new TicketThresholdEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "19.99")), context());
        assertTrue(entry.isEmpty());
    }

    /**
     * An assiette exactly at the first threshold reaches that tier ({@code compareTo}
     * equal, the {@code assiette < threshold} break false at the border, assign leg),
     * granting its 1 € — the lower instant of the threshold border, complementing the
     * 19,99 € case above.
     */
    @Test
    @DisplayName("apply grants the tier reward when the assiette equals the threshold")
    void applyGrantsRewardAtExactThreshold() {
        EarnRuleApplier applier = new TicketThresholdEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "20.00")), context());
        assertFalse(entry.isEmpty());
        assertEquals(0, entry.amount.compareTo(new BigDecimal("1.00")));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("20.00")));
        assertEquals(List.of("L1"), entry.lineIds);
    }

    /**
     * An assiette between the two tiers grants the highest reached tier alone: 30 €
     * reaches the 20 €→1 € tier (assign leg) and breaks on the 40 € tier
     * ({@code assiette < threshold} true leg mid-ladder), so the reward is 1 €, never
     * the 3 € sum. The assiette sums two lines to 30 €, exercising the multi-line
     * assiette and its line ids.
     */
    @Test
    @DisplayName("apply grants only the highest reached tier between two thresholds")
    void applyGrantsHighestReachedTierBetweenThresholds() {
        EarnRuleApplier applier = new TicketThresholdEarnFactory().create(ruleWith(standardSpec()));
        List<ValuedLine> lines = List.of(line("L1", "15.00"), line("L2", "15.00"));
        EarnEntry entry = applier.apply(lines, context());
        assertFalse(entry.isEmpty());
        assertEquals(0, entry.amount.compareTo(new BigDecimal("1.00")));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("30.00")));
        assertEquals(List.of("L1", "L2"), entry.lineIds);
    }

    /**
     * An assiette beyond the last threshold grants the last tier's reward alone: 45 €
     * reaches both tiers (assign on each, no break), so the reward is the 40 € tier's
     * 2 €, not the 1 € + 2 € = 3 € sum (§12). This is the spec's worked example and
     * proves the descending-listed ladder was sorted ascending.
     */
    @Test
    @DisplayName("apply grants the last tier reward beyond the last threshold, not the sum")
    void applyGrantsLastTierBeyondLastThreshold() {
        EarnRuleApplier applier = new TicketThresholdEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "45.00")), context());
        assertFalse(entry.isEmpty());
        assertEquals(0, entry.amount.compareTo(new BigDecimal("2.00")));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("45.00")));
    }

    /**
     * A tier reached but carrying a zero reward grants nothing ({@code granted.signum()
     * <= 0} true arm while the tier is reached — distinct from the below-threshold
     * case): a 20 €→0 € tier against a 25 € assiette yields the empty entry.
     */
    @Test
    @DisplayName("apply returns none when the reached tier's reward is zero")
    void applyReturnsNoneWhenReachedRewardIsZero() {
        EarnRuleApplier applier = new TicketThresholdEarnFactory()
                .create(ruleWith("{\"scope\":{\"wholeStore\":true},\"tiers\":[{\"threshold\":20,\"reward\":0}]}"));
        EarnEntry entry = applier.apply(List.of(line("L1", "25.00")), context());
        assertTrue(entry.isEmpty());
    }

    /**
     * A sub-centime reward is rounded once at the centime (I4): a 20 €→1,005 € tier
     * against a 25 € assiette grants 1,01 € (HALF_UP), never the raw 1,005.
     */
    @Test
    @DisplayName("apply rounds the reward at the centime")
    void applyRoundsRewardAtCentime() {
        EarnRuleApplier applier = new TicketThresholdEarnFactory()
                .create(ruleWith("{\"scope\":{\"wholeStore\":true},\"tiers\":[{\"threshold\":20,\"reward\":1.005}]}"));
        EarnEntry entry = applier.apply(List.of(line("L1", "25.00")), context());
        assertFalse(entry.isEmpty());
        assertEquals(0, entry.amount.compareTo(new BigDecimal("1.01")));
    }

    /**
     * A malformed tier ladder is pruned to its one well-formed tier: a non-object
     * element ({@code !node.isObject()}), a missing threshold ({@code value == null}),
     * a JSON-null threshold ({@code value.isNull()}), a non-numeric threshold
     * ({@code !value.isNumber()}) and the same three malformations on {@code reward}
     * (the second leg of {@code threshold == null || reward == null}) are all dropped.
     * The single valid 20 €→1 € tier still fires for a 25 € ticket, proving only it
     * survived parsing (§31.2).
     */
    @Test
    @DisplayName("apply ignores malformed tiers and keeps the well-formed one")
    void applyIgnoresMalformedTiers() {
        String messy = "{\"scope\":{\"wholeStore\":true},\"tiers\":["
                + "5,"
                + "null,"
                + "\"nope\","
                + "{\"reward\":1},"
                + "{\"threshold\":null,\"reward\":1},"
                + "{\"threshold\":\"x\",\"reward\":1},"
                + "{\"threshold\":20},"
                + "{\"threshold\":20,\"reward\":null},"
                + "{\"threshold\":20,\"reward\":\"y\"},"
                + "{\"threshold\":20,\"reward\":1}]}";
        EarnRuleApplier applier = new TicketThresholdEarnFactory().create(ruleWith(messy));
        EarnEntry entry = applier.apply(List.of(line("L1", "25.00")), context());
        assertFalse(entry.isEmpty());
        assertEquals(0, entry.amount.compareTo(new BigDecimal("1.00")));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("25.00")));
    }
}
