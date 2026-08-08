package com.intermarche.fidelity.earn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.rule.CardContext;
import com.intermarche.fidelity.rule.EarnEntry;
import com.intermarche.fidelity.rule.EarnRuleApplier;
import com.intermarche.fidelity.rule.EarnRuleRegistry;
import com.intermarche.fidelity.rule.ValuedLine;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link EarnEngine}: the §15 orchestration outside the appliers
 * — the exclusion/producer split, the burnable base (§22.3), the account guard (§20,
 * §25.3), the descending-priority producer loop with exclusivity (I2) and the rule →
 * community → global cap truncation traced under the closed scope nomenclature (I5,
 * §27.2).
 * <p>
 * Fully isolated: the {@link EarnRuleRegistry} and {@link CardContextBuilder} collaborators
 * are mocked, the appliers are mocks returning in-memory {@link EarnEntry} results, and the
 * Panache static finders ({@link FidelityRule}, {@link FidelityProgramSetting},
 * {@link FidelityCommunity}) are intercepted with {@code mockStatic} in try-with-resources.
 * The fiscal instant is a fixed constant passed to the engine, so no real clock is ever
 * read (§24.6): the engine takes its evaluation instant as a parameter and never touches
 * {@code DateTimeProvider}. The engine is a read and persists nothing, so no
 * {@code mockConstruction} is required.
 */
class EarnEngineTest {

    /**
     * The fixed fiscal evaluation instant at the program zone (mid-month).
     */
    private static final LocalDateTime EVAL_INSTANT = LocalDateTime.of(2026, 3, 15, 10, 0);

    /**
     * A global cap large enough never to truncate, for the tests not exercising it.
     */
    private static final BigDecimal HUGE_CAP = new BigDecimal("100000.00");

    /**
     * The community code embedded in the community-gated fixtures.
     */
    private static final String COMMUNITY = "C1";

    /**
     * The system under test, freshly built per test with mocked collaborators.
     */
    private EarnEngine engine;

    /**
     * The mocked rule registry resolving factories and appliers.
     */
    private EarnRuleRegistry registry;

    /**
     * The mocked card-context builder returning the dated context.
     */
    private CardContextBuilder contextBuilder;

    /**
     * Builds a fresh system under test and wires the mocked collaborators before each test.
     */
    @BeforeEach
    void setUp() {
        engine = new EarnEngine();
        registry = Mockito.mock(EarnRuleRegistry.class);
        contextBuilder = Mockito.mock(CardContextBuilder.class);
        engine.registry = registry;
        engine.contextBuilder = contextBuilder;
    }

    /**
     * Applies the neutral baseline stubs on the three static finders: no rules in force,
     * a non-truncating global cap and no community, so a test overrides only its concern.
     *
     * @param rules       The mocked {@link FidelityRule} statics.
     * @param settings    The mocked {@link FidelityProgramSetting} statics.
     * @param communities The mocked {@link FidelityCommunity} statics.
     */
    private void stubNeutral(MockedStatic<FidelityRule> rules, MockedStatic<FidelityProgramSetting> settings,
                             MockedStatic<FidelityCommunity> communities) {
        rules.when(() -> FidelityRule.listInForceAt(any())).thenReturn(List.of());
        settings.when(() -> FidelityProgramSetting.getDecimal(any(), any())).thenReturn(HUGE_CAP);
        communities.when(() -> FidelityCommunity.findByCode(any())).thenReturn(null);
    }

    /**
     * Builds an account carrying the given status (its {@code canEarn()} is status-driven).
     *
     * @param status The account status.
     * @return The account.
     */
    private FidelityAccount account(AccountStatus status) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = "CARD-1";
        account.status = status;
        return account;
    }

    /**
     * Builds a rule with a backbone and a specification (which founds
     * {@link FidelityRule#communityCodeFromSpec()}).
     *
     * @param code          The stable rule code.
     * @param type          The rule type.
     * @param exclusive     Whether the rule consumes its lines (I2).
     * @param monthlyCap    The per-card monthly cap, or null for none.
     * @param specification The JSON specification.
     * @return The rule.
     */
    private FidelityRule rule(String code, String type, boolean exclusive, BigDecimal monthlyCap, String specification) {
        FidelityRule rule = new FidelityRule();
        rule.code = code;
        rule.type = type;
        rule.label = code + "-label";
        rule.exclusive = exclusive;
        rule.monthlyCapPerCard = monthlyCap;
        rule.specification = specification;
        return rule;
    }

    /**
     * Builds a valued line with the given id and net TTC (the earn assiette, §22.1).
     *
     * @param lineId The valuation line id.
     * @param netTtc The net TTC amount.
     * @return The valued line.
     */
    private ValuedLine line(String lineId, String netTtc) {
        return new ValuedLine(lineId, "EAN-" + lineId, BigDecimal.ONE,
                new BigDecimal(netTtc), new BigDecimal(netTtc), false);
    }

    /**
     * Builds a valuation reading over the given lines and TTC total, with no warnings.
     *
     * @param totalTtc The TTC total price founding the burnable base (§22.3).
     * @param lines    The valued lines.
     * @return The reading.
     */
    private ValuationReading reading(String totalTtc, ValuedLine... lines) {
        return new ValuationReading(List.of(lines), new BigDecimal(totalTtc), List.of());
    }

    /**
     * Registers a producer applier for a rule: {@code hasFactory} true, a bound applier
     * that is a producer and returns the given entry on {@code apply}.
     *
     * @param rule  The producing rule.
     * @param entry The entry the applier returns.
     * @return The registered applier mock.
     */
    private EarnRuleApplier producer(FidelityRule rule, EarnEntry entry) {
        EarnRuleApplier applier = Mockito.mock(EarnRuleApplier.class);
        Mockito.when(applier.isProducer()).thenReturn(true);
        Mockito.when(applier.apply(anyList(), any())).thenReturn(entry);
        Mockito.when(registry.hasFactory(rule.type)).thenReturn(true);
        Mockito.when(registry.createApplier(rule)).thenReturn(applier);
        return applier;
    }

    /**
     * Builds a non-empty earn entry for a rule over a single line.
     *
     * @param rule   The rule the entry is attributed to.
     * @param amount The raw earn amount before caps.
     * @param base   The eligible assiette.
     * @param lineId The contributing line id.
     * @return The entry.
     */
    private EarnEntry entry(FidelityRule rule, String amount, String base, String lineId) {
        return new EarnEntry(rule.code, rule.label, new BigDecimal(amount), new BigDecimal(base), List.of(lineId));
    }

    /**
     * Builds a card context for the account with the given cap cumulatives (per-rule,
     * per-community, global), no memberships, activations or challenge bases.
     *
     * @param account        The resolved account.
     * @param ruleEarn       The per-rule monthly earn cumulative.
     * @param communityEarn  The per-community monthly earn cumulative.
     * @param globalEarn     The global monthly earn cumulative.
     * @return The card context.
     */
    private CardContext context(FidelityAccount account, Map<String, BigDecimal> ruleEarn,
                                Map<String, BigDecimal> communityEarn, BigDecimal globalEarn) {
        return new CardContext(account, 0, ruleEarn, communityEarn, globalEarn, null, null, null, EVAL_INSTANT);
    }

    /**
     * Stubs the context builder to return the given context for any build call.
     *
     * @param context The context to return.
     */
    private void stubContext(CardContext context) {
        Mockito.when(contextBuilder.build(any(), any(), anyBoolean())).thenReturn(context);
    }

    /**
     * Builds a community carrying a code and monthly cap.
     *
     * @param code       The community code.
     * @param monthlyCap The monthly cap, or null for none.
     * @return The community.
     */
    private FidelityCommunity community(String code, BigDecimal monthlyCap) {
        FidelityCommunity community = new FidelityCommunity();
        community.code = code;
        community.monthlyCap = monthlyCap;
        return community;
    }

    /**
     * A rule whose type has no deployed factory is skipped and never interpreted — the
     * {@code hasFactory} false arm (§12).
     */
    @Test
    @DisplayName("evaluate(): a rule with no factory is skipped, never interpreted")
    void ruleWithoutFactorySkipped() {
        FidelityRule noFactory = rule("NF", "NO_FACTORY", false, null, "{}");
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(noFactory));
            Mockito.when(registry.hasFactory("NO_FACTORY")).thenReturn(false);
            stubContext(context(account(AccountStatus.ACTIVE), Map.of(), Map.of(), BigDecimal.ZERO));
            EarnResult result = engine.evaluate(reading("10.00", line("L1", "10.00")),
                    account(AccountStatus.ACTIVE), EVAL_INSTANT, true);
            assertTrue(result.entries.isEmpty());
            Mockito.verify(registry, Mockito.never()).createApplier(any());
        }
    }

    /**
     * A non-producer rule ({@code PROGRAM_EXCLUSION}) collects its covered line ids, which
     * leave the burnable base; an uncovered line stays inside it — the {@code isProducer}
     * false arm and both arms of the {@code contains} guard in the base (§15, §22.3).
     */
    @Test
    @DisplayName("evaluate(): a program exclusion reduces the burnable base by its net")
    void programExclusionReducesBurnableBase() {
        FidelityRule exclusion = rule("EX", FidelityRule.TYPE_PROGRAM_EXCLUSION, false, null, "{}");
        EarnRuleApplier applier = Mockito.mock(EarnRuleApplier.class);
        Mockito.when(applier.isProducer()).thenReturn(false);
        Mockito.when(applier.excludedLineIds(anyList())).thenReturn(Set.of("L1"));
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(exclusion));
            Mockito.when(registry.hasFactory(FidelityRule.TYPE_PROGRAM_EXCLUSION)).thenReturn(true);
            Mockito.when(registry.createApplier(exclusion)).thenReturn(applier);
            EarnResult result = engine.evaluate(reading("15.00", line("L1", "10.00"), line("L2", "5.00")),
                    null, EVAL_INSTANT, true);
            assertEquals(0, result.burnableBase.compareTo(new BigDecimal("5.00")));
            assertTrue(result.entries.isEmpty());
        }
    }

    /**
     * When the excluded net exceeds the total price the burnable base is clamped to zero —
     * the {@code base.signum() < 0} arm (§22.3).
     */
    @Test
    @DisplayName("evaluate(): the burnable base is clamped to zero when exclusions exceed the total")
    void burnableBaseClampedToZero() {
        FidelityRule exclusion = rule("EX", FidelityRule.TYPE_PROGRAM_EXCLUSION, false, null, "{}");
        EarnRuleApplier applier = Mockito.mock(EarnRuleApplier.class);
        Mockito.when(applier.isProducer()).thenReturn(false);
        Mockito.when(applier.excludedLineIds(anyList())).thenReturn(Set.of("L1"));
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(exclusion));
            Mockito.when(registry.hasFactory(FidelityRule.TYPE_PROGRAM_EXCLUSION)).thenReturn(true);
            Mockito.when(registry.createApplier(exclusion)).thenReturn(applier);
            EarnResult result = engine.evaluate(reading("10.00", line("L1", "20.00")),
                    null, EVAL_INSTANT, true);
            assertEquals(0, result.burnableBase.compareTo(new BigDecimal("0.00")));
        }
    }

    /**
     * An unknown card (null account) yields the burnable base and no entries, without
     * building a context — the first leg of the {@code account == null || !canEarn} guard
     * (§20).
     */
    @Test
    @DisplayName("evaluate(): an unknown card returns the burnable base only")
    void unknownCardReturnsBurnableBaseOnly() {
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            EarnResult result = engine.evaluate(reading("30.00", line("L1", "30.00")),
                    null, EVAL_INSTANT, true);
            assertEquals(0, result.burnableBase.compareTo(new BigDecimal("30.00")));
            assertTrue(result.entries.isEmpty());
            Mockito.verify(contextBuilder, Mockito.never()).build(any(), any(), anyBoolean());
        }
    }

    /**
     * A resiliated account cannot earn: the burnable base is still returned but no producer
     * is evaluated — the second leg of the {@code account == null || !canEarn} guard (§25.3).
     */
    @Test
    @DisplayName("evaluate(): a non-earning account returns the burnable base, no entries")
    void nonEarningAccountReturnsBurnableBaseOnly() {
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            EarnResult result = engine.evaluate(reading("30.00", line("L1", "30.00")),
                    account(AccountStatus.RESILIATED), EVAL_INSTANT, true);
            assertEquals(0, result.burnableBase.compareTo(new BigDecimal("30.00")));
            assertTrue(result.entries.isEmpty());
            Mockito.verify(contextBuilder, Mockito.never()).build(any(), any(), anyBoolean());
        }
    }

    /**
     * A single producer under no active cap grants its raw earn: one entry, no cap trace,
     * no community merge — the earning path with every cap guard on its skipping arm (§15).
     */
    @Test
    @DisplayName("evaluate(): a single producer grants its raw earn uncapped")
    void singleProducerGrantsRawEarn() {
        FidelityRule r1 = rule("R1", FidelityRule.TYPE_BRAND_TIERED_EARN, false, null, "{}");
        FidelityAccount account = account(AccountStatus.ACTIVE);
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(r1));
            producer(r1, entry(r1, "2.00", "10.00", "L1"));
            stubContext(context(account, Map.of(), Map.of(), BigDecimal.ZERO));
            EarnResult result = engine.evaluate(reading("10.00", line("L1", "10.00")),
                    account, EVAL_INSTANT, true);
            assertEquals(1, result.entries.size());
            assertEquals("R1", result.entries.get(0).ruleCode);
            assertEquals(0, result.entries.get(0).amount.compareTo(new BigDecimal("2.00")));
            assertEquals(0, result.entries.get(0).baseAmount.compareTo(new BigDecimal("10.00")));
            assertEquals(List.of("L1"), result.entries.get(0).lineIds);
            assertEquals(0, result.total.compareTo(new BigDecimal("2.00")));
            assertTrue(result.capsApplied.isEmpty());
        }
    }

    /**
     * A producer returning an empty entry is filtered out — the {@code raw.isEmpty()} true
     * arm (§15).
     */
    @Test
    @DisplayName("evaluate(): an empty producer entry is filtered out")
    void emptyProducerEntryFiltered() {
        FidelityRule r1 = rule("R1", FidelityRule.TYPE_BRAND_TIERED_EARN, false, null, "{}");
        FidelityAccount account = account(AccountStatus.ACTIVE);
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(r1));
            producer(r1, EarnEntry.none(r1.code, r1.label));
            stubContext(context(account, Map.of(), Map.of(), BigDecimal.ZERO));
            EarnResult result = engine.evaluate(reading("10.00", line("L1", "10.00")),
                    account, EVAL_INSTANT, true);
            assertTrue(result.entries.isEmpty());
            assertEquals(0, result.total.compareTo(new BigDecimal("0.00")));
        }
    }

    /**
     * An exclusive rule consumes its lines, which leave the assiette of the next producer;
     * the following rule sees only the remaining lines — the {@code exclusive} true arm and
     * both arms of the available-lines {@code contains} guard (I2, §15).
     */
    @Test
    @DisplayName("evaluate(): an exclusive rule removes its lines from the next producer's assiette")
    void exclusiveRuleConsumesItsLines() {
        FidelityRule r1 = rule("R1", FidelityRule.TYPE_BRAND_TIERED_EARN, true, null, "{}");
        FidelityRule r2 = rule("R2", FidelityRule.TYPE_CALENDAR_FAMILY_EARN, false, null, "{}");
        FidelityAccount account = account(AccountStatus.ACTIVE);
        EarnRuleApplier a1 = Mockito.mock(EarnRuleApplier.class);
        Mockito.when(a1.isProducer()).thenReturn(true);
        Mockito.when(a1.apply(anyList(), any())).thenReturn(entry(r1, "5.00", "10.00", "L1"));
        EarnRuleApplier a2 = Mockito.mock(EarnRuleApplier.class);
        Mockito.when(a2.isProducer()).thenReturn(true);
        Mockito.when(a2.apply(anyList(), any())).thenReturn(entry(r2, "3.00", "5.00", "L2"));
        ArgumentCaptor<List<ValuedLine>> captor = ArgumentCaptor.forClass(List.class);
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(r1, r2));
            Mockito.when(registry.hasFactory(any())).thenReturn(true);
            Mockito.when(registry.createApplier(r1)).thenReturn(a1);
            Mockito.when(registry.createApplier(r2)).thenReturn(a2);
            stubContext(context(account, Map.of(), Map.of(), BigDecimal.ZERO));
            EarnResult result = engine.evaluate(reading("15.00", line("L1", "10.00"), line("L2", "5.00")),
                    account, EVAL_INSTANT, true);
            Mockito.verify(a2).apply(captor.capture(), any());
            assertEquals(1, captor.getValue().size());
            assertEquals("L2", captor.getValue().get(0).lineId);
            assertEquals(2, result.entries.size());
            assertEquals(0, result.total.compareTo(new BigDecimal("8.00")));
        }
    }

    /**
     * A rule cap truncates the raw earn and traces the cut under its {@code RULE:} scope —
     * the {@code monthlyCapPerCard != null} arm and the truncating arm (I5, §27.2).
     */
    @Test
    @DisplayName("evaluate(): a rule cap truncates and traces under RULE:")
    void ruleCapTruncates() {
        FidelityRule r1 = rule("R1", FidelityRule.TYPE_BRAND_TIERED_EARN, false, new BigDecimal("5.00"), "{}");
        FidelityAccount account = account(AccountStatus.ACTIVE);
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(r1));
            producer(r1, entry(r1, "8.00", "100.00", "L1"));
            stubContext(context(account, Map.of(), Map.of(), BigDecimal.ZERO));
            EarnResult result = engine.evaluate(reading("100.00", line("L1", "100.00")),
                    account, EVAL_INSTANT, true);
            assertEquals(0, result.entries.get(0).amount.compareTo(new BigDecimal("5.00")));
            assertEquals(1, result.capsApplied.size());
            assertEquals("RULE:R1", result.capsApplied.get(0).scope);
            assertEquals(0, result.capsApplied.get(0).capAmount.compareTo(new BigDecimal("5.00")));
            assertEquals(0, result.capsApplied.get(0).truncatedBy.compareTo(new BigDecimal("3.00")));
            assertEquals(0, result.total.compareTo(new BigDecimal("5.00")));
        }
    }

    /**
     * A rule cap already exhausted by the month's prior earn leaves zero headroom: the
     * grant is truncated to zero and no entry is added — the negative-headroom arm of
     * {@code remaining} and the {@code granted.signum() > 0} false arm (I5, §31.2).
     */
    @Test
    @DisplayName("evaluate(): an exhausted rule cap grants zero and adds no entry")
    void exhaustedRuleCapGrantsZero() {
        FidelityRule r1 = rule("R1", FidelityRule.TYPE_BRAND_TIERED_EARN, false, new BigDecimal("5.00"), "{}");
        FidelityAccount account = account(AccountStatus.ACTIVE);
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(r1));
            producer(r1, entry(r1, "4.00", "100.00", "L1"));
            stubContext(context(account, Map.of("R1", new BigDecimal("8.00")), Map.of(), BigDecimal.ZERO));
            EarnResult result = engine.evaluate(reading("100.00", line("L1", "100.00")),
                    account, EVAL_INSTANT, true);
            assertTrue(result.entries.isEmpty());
            assertEquals(1, result.capsApplied.size());
            assertEquals("RULE:R1", result.capsApplied.get(0).scope);
            assertEquals(0, result.capsApplied.get(0).truncatedBy.compareTo(new BigDecimal("4.00")));
            assertEquals(0, result.total.compareTo(new BigDecimal("0.00")));
        }
    }

    /**
     * A community-gated rule truncates by the community cap, traces it under
     * {@code COMMUNITY:} and merges the grant into the community cumulative; the cap
     * cumulative falls back to the context when the community is absent from the running
     * map — the {@code communityCode != null} arm, the AND both-true case, the
     * {@code getOrDefault} fallback and the community merge (I5, §15).
     */
    @Test
    @DisplayName("evaluate(): a community cap truncates and traces under COMMUNITY:")
    void communityCapTruncates() {
        FidelityRule r1 = rule("R1", FidelityRule.TYPE_COMMUNITY_EARN, false, null,
                "{\"communityCode\":\"" + COMMUNITY + "\"}");
        FidelityAccount account = account(AccountStatus.ACTIVE);
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(r1));
            communities.when(() -> FidelityCommunity.findByCode(COMMUNITY))
                    .thenReturn(community(COMMUNITY, new BigDecimal("5.00")));
            producer(r1, entry(r1, "8.00", "100.00", "L1"));
            stubContext(context(account, Map.of(), Map.of(), BigDecimal.ZERO));
            EarnResult result = engine.evaluate(reading("100.00", line("L1", "100.00")),
                    account, EVAL_INSTANT, true);
            assertEquals(0, result.entries.get(0).amount.compareTo(new BigDecimal("5.00")));
            assertEquals(1, result.capsApplied.size());
            assertEquals("COMMUNITY:" + COMMUNITY, result.capsApplied.get(0).scope);
            assertEquals(0, result.capsApplied.get(0).truncatedBy.compareTo(new BigDecimal("3.00")));
        }
    }

    /**
     * A community-gated rule whose community is unknown keeps its raw earn — the AND
     * first-leg false case ({@code community == null}, §15).
     */
    @Test
    @DisplayName("evaluate(): an unknown community leaves the earn uncapped")
    void unknownCommunityLeavesEarnUncapped() {
        FidelityRule r1 = rule("R1", FidelityRule.TYPE_COMMUNITY_EARN, false, null,
                "{\"communityCode\":\"" + COMMUNITY + "\"}");
        FidelityAccount account = account(AccountStatus.ACTIVE);
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(r1));
            communities.when(() -> FidelityCommunity.findByCode(COMMUNITY)).thenReturn(null);
            producer(r1, entry(r1, "8.00", "100.00", "L1"));
            stubContext(context(account, Map.of(), Map.of(), BigDecimal.ZERO));
            EarnResult result = engine.evaluate(reading("100.00", line("L1", "100.00")),
                    account, EVAL_INSTANT, true);
            assertEquals(0, result.entries.get(0).amount.compareTo(new BigDecimal("8.00")));
            assertTrue(result.capsApplied.isEmpty());
        }
    }

    /**
     * A community-gated rule whose community carries no cap keeps its raw earn — the AND
     * second-leg false case ({@code monthlyCap == null}, §15).
     */
    @Test
    @DisplayName("evaluate(): a community without a cap leaves the earn uncapped")
    void communityWithoutCapLeavesEarnUncapped() {
        FidelityRule r1 = rule("R1", FidelityRule.TYPE_COMMUNITY_EARN, false, null,
                "{\"communityCode\":\"" + COMMUNITY + "\"}");
        FidelityAccount account = account(AccountStatus.ACTIVE);
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(r1));
            communities.when(() -> FidelityCommunity.findByCode(COMMUNITY)).thenReturn(community(COMMUNITY, null));
            producer(r1, entry(r1, "8.00", "100.00", "L1"));
            stubContext(context(account, Map.of(), Map.of(), BigDecimal.ZERO));
            EarnResult result = engine.evaluate(reading("100.00", line("L1", "100.00")),
                    account, EVAL_INSTANT, true);
            assertEquals(0, result.entries.get(0).amount.compareTo(new BigDecimal("8.00")));
            assertTrue(result.capsApplied.isEmpty());
        }
    }

    /**
     * The community cap cumulates the month's prior community earn taken from the context —
     * the {@code getOrDefault} present arm: a community already carrying earn this month
     * offers reduced headroom (I5, §15).
     */
    @Test
    @DisplayName("evaluate(): the community cap uses the month's prior community earn")
    void communityCapUsesPriorEarn() {
        FidelityRule r1 = rule("R1", FidelityRule.TYPE_COMMUNITY_EARN, false, null,
                "{\"communityCode\":\"" + COMMUNITY + "\"}");
        FidelityAccount account = account(AccountStatus.ACTIVE);
        Map<String, BigDecimal> communityEarn = new LinkedHashMap<>();
        communityEarn.put(COMMUNITY, new BigDecimal("2.00"));
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(r1));
            communities.when(() -> FidelityCommunity.findByCode(COMMUNITY))
                    .thenReturn(community(COMMUNITY, new BigDecimal("5.00")));
            producer(r1, entry(r1, "5.00", "100.00", "L1"));
            stubContext(context(account, Map.of(), communityEarn, BigDecimal.ZERO));
            EarnResult result = engine.evaluate(reading("100.00", line("L1", "100.00")),
                    account, EVAL_INSTANT, true);
            assertEquals(0, result.entries.get(0).amount.compareTo(new BigDecimal("3.00")));
            assertEquals("COMMUNITY:" + COMMUNITY, result.capsApplied.get(0).scope);
            assertEquals(0, result.capsApplied.get(0).truncatedBy.compareTo(new BigDecimal("2.00")));
        }
    }

    /**
     * The global cap truncates the raw earn and traces the cut under the {@code GLOBAL}
     * scope, reading the cap from the program setting (I5, §25.1, §27.2).
     */
    @Test
    @DisplayName("evaluate(): the global cap truncates and traces under GLOBAL")
    void globalCapTruncates() {
        FidelityRule r1 = rule("R1", FidelityRule.TYPE_BRAND_TIERED_EARN, false, null, "{}");
        FidelityAccount account = account(AccountStatus.ACTIVE);
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(r1));
            settings.when(() -> FidelityProgramSetting.getDecimal(
                    eq(FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP), any())).thenReturn(new BigDecimal("5.00"));
            producer(r1, entry(r1, "8.00", "100.00", "L1"));
            stubContext(context(account, Map.of(), Map.of(), BigDecimal.ZERO));
            EarnResult result = engine.evaluate(reading("100.00", line("L1", "100.00")),
                    account, EVAL_INSTANT, true);
            assertEquals(0, result.entries.get(0).amount.compareTo(new BigDecimal("5.00")));
            assertEquals("GLOBAL", result.capsApplied.get(0).scope);
            assertEquals(0, result.capsApplied.get(0).truncatedBy.compareTo(new BigDecimal("3.00")));
        }
    }

    /**
     * The passed evaluation instant is what founds the rules-in-force query and the context
     * build — it is never read from the real clock (§24.6): the same fixed instant flows to
     * both the static finder and the context builder.
     */
    @Test
    @DisplayName("evaluate(): the fixed evaluation instant drives the rules query and the build")
    void fixedInstantDrivesQueryAndBuild() {
        FidelityRule r1 = rule("R1", FidelityRule.TYPE_BRAND_TIERED_EARN, false, null, "{}");
        FidelityAccount account = account(AccountStatus.ACTIVE);
        CardContext ctx = context(account, Map.of(), Map.of(), BigDecimal.ZERO);
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            stubNeutral(rules, settings, communities);
            rules.when(() -> FidelityRule.listInForceAt(EVAL_INSTANT)).thenReturn(List.of(r1));
            producer(r1, entry(r1, "2.00", "10.00", "L1"));
            stubContext(ctx);
            EarnResult result = engine.evaluate(reading("10.00", line("L1", "10.00")),
                    account, EVAL_INSTANT, false);
            assertSame(EVAL_INSTANT, ctx.evaluationDateTime);
            rules.verify(() -> FidelityRule.listInForceAt(EVAL_INSTANT));
            Mockito.verify(contextBuilder).build(account, EVAL_INSTANT, false);
        }
    }

    /**
     * The {@code remaining} headroom reads a null used amount as zero — the defensive
     * {@code used != null ? used : ZERO} null arm, unreachable through the engine's public
     * path (every caller passes a non-null cumulative) and so exercised directly (§31.2).
     *
     * @throws Exception if the private method cannot be reflected or invoked.
     */
    @Test
    @DisplayName("remaining(): a null used amount is read as zero")
    void remainingReadsNullUsedAsZero() throws Exception {
        Method method = EarnEngine.class.getDeclaredMethod("remaining", BigDecimal.class, BigDecimal.class);
        method.setAccessible(true);
        BigDecimal headroom = (BigDecimal) method.invoke(null, new BigDecimal("5.00"), null);
        assertEquals(0, headroom.compareTo(new BigDecimal("5.00")));
    }
}
