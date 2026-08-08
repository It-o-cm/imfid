package com.intermarche.fidelity.rule.appliers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link ProgramExclusionFactory} and its
 * {@link ProgramExclusionApplier} — the whole-programme assiette filter (§12, §15,
 * §22.3): a non-producer that never grants an earn and only exposes the lines its
 * scopes cover, so the orchestration removes them from every earn assiette and
 * founds the burnable base.
 * <p>
 * Pure logic: the covered set is asserted directly on in-memory valued baskets. The
 * assiette scope uses {@code wholeStore} with EAN-less lines to reach the in-scope
 * arm and an empty scope to reach the out-of-scope arm, so no Panache product
 * reference is read — no static finder needs mocking (§13, §25.4). The applier
 * reads no clock; a fixed fiscal instant only stamps the context supplied to
 * {@link #apply}. Every {@link BigDecimal} is asserted by {@code compareTo}.
 */
class ProgramExclusionFactoryTest {

    /**
     * The stable rule code carried by every fixture rule.
     */
    private static final String CODE = "PROGRAM-EXCLUSION";

    /**
     * The printed rule label carried by every fixture rule.
     */
    private static final String LABEL = "Programme exclusion (gas, gift cards, press)";

    /**
     * A fixed fiscal instant at the program zone; the filter reads no clock, so this
     * only stamps the context (2026-01-15).
     */
    private static final LocalDateTime EVAL_INSTANT = LocalDateTime.of(2026, 1, 15, 10, 0);

    /**
     * A whole-store specification: every EAN-less line is in scope of the exclusion.
     */
    private static final String WHOLE_STORE_SPEC = "{\"scope\":{\"wholeStore\":true}}";

    /**
     * An empty specification: no include and no {@code wholeStore}, so no EAN-less
     * line is ever in scope.
     */
    private static final String EMPTY_SPEC = "{}";

    /**
     * Builds a program-exclusion rule carrying the given JSON specification.
     *
     * @param specification The rule specification JSON.
     * @return A fidelity rule ready for the applier.
     */
    private static FidelityRule ruleWith(String specification) {
        FidelityRule rule = new FidelityRule();
        rule.code = CODE;
        rule.label = LABEL;
        rule.type = FidelityRule.TYPE_PROGRAM_EXCLUSION;
        rule.specification = specification;
        return rule;
    }

    /**
     * Builds an applier bound to a rule carrying the given specification.
     *
     * @param specification The rule specification JSON.
     * @return The program-exclusion applier under test.
     */
    private static ProgramExclusionApplier applierWith(String specification) {
        return new ProgramExclusionApplier(ruleWith(specification));
    }

    /**
     * Builds an EAN-less, unconsumed, positive-net valued line — one item in scope
     * under the whole-store assiette (§13, §22.1).
     *
     * @param lineId The line id.
     * @param netTtc The net TTC amount that founds the assiette.
     * @return A valued line.
     */
    private static ValuedLine line(String lineId, String netTtc) {
        return new ValuedLine(lineId, null, BigDecimal.ONE,
                new BigDecimal(netTtc), new BigDecimal(netTtc), false);
    }

    /**
     * Builds a bare card context on the fixed fiscal instant with a mocked account
     * (§14, §15).
     *
     * @return The dated card context.
     */
    private static CardContext context() {
        FidelityAccount account = Mockito.mock(FidelityAccount.class);
        return new CardContext(account, EVAL_INSTANT);
    }

    /**
     * The factory reports the program-exclusion rule type it interprets.
     */
    @Test
    @DisplayName("getRuleType returns PROGRAM_EXCLUSION")
    void getRuleTypeReturnsProgramExclusion() {
        assertEquals(FidelityRule.TYPE_PROGRAM_EXCLUSION, new ProgramExclusionFactory().getRuleType());
    }

    /**
     * The factory creates a program-exclusion applier bound to the rule.
     */
    @Test
    @DisplayName("create returns a ProgramExclusionApplier")
    void createReturnsApplier() {
        EarnRuleApplier applier = new ProgramExclusionFactory().create(ruleWith(WHOLE_STORE_SPEC));
        assertNotNull(applier);
        assertTrue(applier instanceof ProgramExclusionApplier);
    }

    /**
     * A program exclusion is a filter, not a producer (§12, §15).
     */
    @Test
    @DisplayName("isProducer is false")
    void isProducerIsFalse() {
        assertFalse(applierWith(WHOLE_STORE_SPEC).isProducer());
    }

    /**
     * The applier never grants an earn: even a fully in-scope non-empty basket with a
     * valid context returns the empty entry carrying the rule code and label (§12).
     */
    @Test
    @DisplayName("apply returns the empty entry for an in-scope basket")
    void applyReturnsNoneForInScopeBasket() {
        EarnEntry entry = applierWith(WHOLE_STORE_SPEC)
                .apply(List.of(line("L1", "10.00"), line("L2", "20.00")), context());
        assertTrue(entry.isEmpty());
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(0, entry.amount.compareTo(BigDecimal.ZERO));
        assertEquals(0, entry.baseAmount.compareTo(BigDecimal.ZERO));
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * The applier grants nothing even with a null line list and a null context — the
     * non-producer path is unconditional (§12).
     */
    @Test
    @DisplayName("apply returns the empty entry for null lines and null context")
    void applyReturnsNoneForNullInputs() {
        EarnEntry entry = applierWith(WHOLE_STORE_SPEC).apply(null, null);
        assertTrue(entry.isEmpty());
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
    }

    /**
     * A null line list yields an empty covered list (the {@code lines == null} true
     * arm of {@link ProgramExclusionApplier#coveredLines}).
     */
    @Test
    @DisplayName("coveredLines returns empty for a null list")
    void coveredLinesNullYieldsEmpty() {
        List<ValuedLine> covered = applierWith(WHOLE_STORE_SPEC).coveredLines(null);
        assertNotNull(covered);
        assertTrue(covered.isEmpty());
    }

    /**
     * A whole-store exclusion covers every EAN-less line, in encounter order (the
     * {@code lines == null} false arm and the {@code isInScope} true arm).
     */
    @Test
    @DisplayName("coveredLines retains the in-scope lines in encounter order")
    void coveredLinesRetainsInScopeLines() {
        ValuedLine first = line("L1", "10.00");
        ValuedLine second = line("L2", "20.00");
        List<ValuedLine> covered = applierWith(WHOLE_STORE_SPEC).coveredLines(List.of(first, second));
        assertEquals(2, covered.size());
        assertTrue(first == covered.get(0));
        assertTrue(second == covered.get(1));
    }

    /**
     * An empty-scope exclusion covers no EAN-less line (the {@code isInScope} false
     * arm): the loop runs but retains nothing.
     */
    @Test
    @DisplayName("coveredLines skips the out-of-scope lines")
    void coveredLinesSkipsOutOfScopeLines() {
        List<ValuedLine> covered = applierWith(EMPTY_SPEC)
                .coveredLines(List.of(line("L1", "10.00"), line("L2", "20.00")));
        assertTrue(covered.isEmpty());
    }

    /**
     * A null line list yields an empty covered-id set (the {@code lines == null} true
     * arm reached through {@link ProgramExclusionApplier#coveredLineIds}).
     */
    @Test
    @DisplayName("coveredLineIds returns empty for a null list")
    void coveredLineIdsNullYieldsEmpty() {
        Set<String> ids = applierWith(WHOLE_STORE_SPEC).coveredLineIds(null);
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    /**
     * A whole-store exclusion collects the ids of every covered line, in encounter
     * order (the {@code isInScope} true arm feeding a non-empty id set).
     */
    @Test
    @DisplayName("coveredLineIds collects the in-scope ids in order")
    void coveredLineIdsRetainsIdsInOrder() {
        Set<String> ids = applierWith(WHOLE_STORE_SPEC)
                .coveredLineIds(List.of(line("L1", "10.00"), line("L2", "20.00")));
        assertEquals(List.of("L1", "L2"), List.copyOf(ids));
    }

    /**
     * An empty-scope exclusion collects no id (the {@code isInScope} false arm feeding
     * an empty id set).
     */
    @Test
    @DisplayName("coveredLineIds returns empty when no line is in scope")
    void coveredLineIdsEmptyWhenNoneInScope() {
        Set<String> ids = applierWith(EMPTY_SPEC).coveredLineIds(List.of(line("L1", "10.00")));
        assertTrue(ids.isEmpty());
    }

    /**
     * The SPI {@link ProgramExclusionApplier#excludedLineIds} delegates to the covered
     * ids: a whole-store exclusion exposes every line to the orchestration (§15,
     * §22.3).
     */
    @Test
    @DisplayName("excludedLineIds exposes the covered ids to the orchestration")
    void excludedLineIdsExposesCoveredIds() {
        Set<String> ids = applierWith(WHOLE_STORE_SPEC)
                .excludedLineIds(List.of(line("L1", "10.00"), line("L2", "20.00")));
        assertEquals(List.of("L1", "L2"), List.copyOf(ids));
    }

    /**
     * The SPI {@link ProgramExclusionApplier#excludedLineIds} yields an empty set for a
     * null list (§22.3): the filter exposes nothing when there is no basket.
     */
    @Test
    @DisplayName("excludedLineIds returns empty for a null list")
    void excludedLineIdsNullYieldsEmpty() {
        Set<String> ids = applierWith(WHOLE_STORE_SPEC).excludedLineIds(null);
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }
}
