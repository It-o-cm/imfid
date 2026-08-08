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
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link CommunityEarnFactory} and its
 * {@link CommunityEarnApplier} — the community advantage mechanic (Babies, Large
 * Families, Small Budgets, Students, §12): the membership gate against the card
 * context, the net-assiette sum of the eligible lines and the single per-rule
 * centime rounding (I4).
 * <p>
 * Pure logic: the applier's arithmetic is asserted directly on in-memory valued
 * baskets. The assiette scope uses {@code wholeStore} with EAN-less lines, so no
 * Panache product reference is read — no static finder needs mocking (§13, §25.4).
 * Membership is a dated snapshot resolved upstream and carried by the
 * {@link CardContext} (§14, §31.1), so the applier reads no clock; the fixed fiscal
 * instant is nonetheless supplied to the context. Every {@link BigDecimal} is
 * asserted by {@code compareTo}.
 */
class CommunityEarnFactoryTest {

    /**
     * The stable rule code carried by every fixture rule.
     */
    private static final String CODE = "BABIES-ADVANTAGE";

    /**
     * The printed rule label carried by every fixture rule.
     */
    private static final String LABEL = "Babies community advantage";

    /**
     * The community code the standard specification requires membership of (§13).
     */
    private static final String COMMUNITY = "BABIES";

    /**
     * A fixed fiscal instant at the program zone; membership is dated upstream, so
     * this only stamps the context (2026-01-15).
     */
    private static final LocalDateTime EVAL_INSTANT = LocalDateTime.of(2026, 1, 15, 10, 0);

    /**
     * Builds a community rule carrying the given JSON specification.
     *
     * @param specification The rule specification JSON.
     * @return A fidelity rule ready for the applier.
     */
    private static FidelityRule ruleWith(String specification) {
        FidelityRule rule = new FidelityRule();
        rule.code = CODE;
        rule.label = LABEL;
        rule.type = FidelityRule.TYPE_COMMUNITY_EARN;
        rule.specification = specification;
        return rule;
    }

    /**
     * Builds the standard specification: whole-store assiette, the {@link #COMMUNITY}
     * membership requirement and a 5 % rate.
     *
     * @return The standard specification JSON.
     */
    private static String standardSpec() {
        return "{\"scope\":{\"wholeStore\":true},\"communityCode\":\"" + COMMUNITY + "\",\"rate\":0.05}";
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
     * Builds a card context whose card belongs to the given communities on the fixed
     * fiscal instant with a mocked account (§14, §15).
     *
     * @param communities The membership community codes the card holds.
     * @return The dated card context.
     */
    private static CardContext contextMemberOf(Set<String> communities) {
        FidelityAccount account = Mockito.mock(FidelityAccount.class);
        return new CardContext(account, 0, null, null, null, null, communities, null, EVAL_INSTANT);
    }

    /**
     * The factory reports the community rule type it interprets.
     */
    @Test
    @DisplayName("getRuleType returns COMMUNITY_EARN")
    void getRuleTypeReturnsCommunity() {
        assertEquals(FidelityRule.TYPE_COMMUNITY_EARN, new CommunityEarnFactory().getRuleType());
    }

    /**
     * The factory creates a community applier bound to the rule.
     */
    @Test
    @DisplayName("create returns a CommunityEarnApplier")
    void createReturnsApplier() {
        EarnRuleApplier applier = new CommunityEarnFactory().create(ruleWith(standardSpec()));
        assertNotNull(applier);
        assertTrue(applier instanceof CommunityEarnApplier);
    }

    /**
     * A null card context short-circuits to the empty entry (context == null true leg
     * of the compound guard).
     */
    @Test
    @DisplayName("apply returns none when the context is null")
    void applyReturnsNoneWhenContextNull() {
        EarnRuleApplier applier = new CommunityEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), null);
        assertTrue(entry.isEmpty());
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(0, entry.amount.compareTo(BigDecimal.ZERO));
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * A card that is not a member of the required community grants nothing
     * (context == null false arm, !isMemberOf true leg).
     */
    @Test
    @DisplayName("apply returns none when the card is not a member")
    void applyReturnsNoneWhenNotMember() {
        EarnRuleApplier applier = new CommunityEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), contextMemberOf(Set.of("STUDENTS")));
        assertTrue(entry.isEmpty());
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * A specification without a {@code communityCode} field never matches any
     * membership, so a member card still grants nothing (!isMemberOf true leg reached
     * through a null community code).
     */
    @Test
    @DisplayName("apply returns none when the communityCode is absent")
    void applyReturnsNoneWhenCommunityCodeAbsent() {
        String spec = "{\"scope\":{\"wholeStore\":true},\"rate\":0.05}";
        EarnRuleApplier applier = new CommunityEarnFactory().create(ruleWith(spec));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), contextMemberOf(Set.of(COMMUNITY)));
        assertTrue(entry.isEmpty());
    }

    /**
     * A member card with an empty basket has no eligible line and grants nothing
     * (both guard legs false, eligible.isEmpty true leg).
     */
    @Test
    @DisplayName("apply returns none for a member with no eligible lines")
    void applyReturnsNoneWhenMemberButNoEligibleLines() {
        EarnRuleApplier applier = new CommunityEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(), contextMemberOf(Set.of(COMMUNITY)));
        assertTrue(entry.isEmpty());
        assertEquals(0, entry.baseAmount.compareTo(BigDecimal.ZERO));
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * A member card whose every line is consumed by a commercial offer has an empty
     * assiette and grants nothing (eligible.isEmpty true leg via I2 filtering).
     */
    @Test
    @DisplayName("apply returns none when every line is consumed by an offer")
    void applyReturnsNoneWhenAllLinesConsumed() {
        EarnRuleApplier applier = new CommunityEarnFactory().create(ruleWith(standardSpec()));
        List<ValuedLine> lines = new ArrayList<>();
        lines.add(new ValuedLine("L1", null, BigDecimal.ONE, new BigDecimal("10.00"), new BigDecimal("10.00"), true));
        lines.add(new ValuedLine("L2", null, BigDecimal.ONE, new BigDecimal("20.00"), new BigDecimal("20.00"), true));
        EarnEntry entry = applier.apply(lines, contextMemberOf(Set.of(COMMUNITY)));
        assertTrue(entry.isEmpty());
    }

    /**
     * A member card with eligible lines earns the rate over the summed net assiette
     * (both guard legs false, eligible.isEmpty false leg → producer path).
     */
    @Test
    @DisplayName("apply grants 5 % on the net assiette for a member")
    void applyGrantsForMember() {
        EarnRuleApplier applier = new CommunityEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00"), line("L2", "20.00")),
                contextMemberOf(Set.of(COMMUNITY)));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("30.00")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("1.50")));
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(List.of("L1", "L2"), entry.lineIds);
    }

    /**
     * A member holding several communities including the required one still earns
     * (isMemberOf true through the contains leg over a multi-community set).
     */
    @Test
    @DisplayName("apply grants when the card holds several communities including the required one")
    void applyGrantsWhenMemberOfSeveralCommunities() {
        EarnRuleApplier applier = new CommunityEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "40.00")),
                contextMemberOf(Set.of("STUDENTS", COMMUNITY, "SMALL-BUDGETS")));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("40.00")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("2.00")));
        assertEquals(List.of("L1"), entry.lineIds);
    }

    /**
     * The rate applies to the whole assiette and rounds once at the centime HALF_UP
     * (I4): 4.50 € at 5 % is 0.225 €, rounded to 0.23 €.
     */
    @Test
    @DisplayName("apply rounds the earn at the centime HALF_UP")
    void applyRoundsAtCentimeHalfUp() {
        EarnRuleApplier applier = new CommunityEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "4.50")), contextMemberOf(Set.of(COMMUNITY)));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("4.50")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("0.23")));
    }
}
