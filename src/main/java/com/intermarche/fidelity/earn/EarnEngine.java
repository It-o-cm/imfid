package com.intermarche.fidelity.earn;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.AdvantageCategory;
import com.intermarche.fidelity.domain.AdvantageType;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.rule.CardContext;
import com.intermarche.fidelity.rule.EarnEntry;
import com.intermarche.fidelity.rule.EarnRuleApplier;
import com.intermarche.fidelity.rule.EarnRuleRegistry;
import com.intermarche.fidelity.rule.ValuedLine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The earn orchestration engine (§15): evaluates the rules in force at the fiscal date
 * over a valued basket and a card context, and produces the projection result.
 * <p>
 * The orchestration is the whole of §15 outside the appliers:
 * <ul>
 *   <li>{@code PROGRAM_EXCLUSION} rules are evaluated upstream — their covered lines
 *       leave every earn assiette and found the burnable base ({@code totalPrice} −
 *       their net, §22.3);</li>
 *   <li>the producers are evaluated by descending priority; a rule marked exclusive
 *       consumes its lines, which leave the assiettes of the following rules — the
 *       loyalty non-cumulation arbitration is entirely data-borne (I2);</li>
 *   <li>the caps truncate in order rule &rarr; community &rarr; global, each truncation
 *       traced in {@code capsApplied} under the closed scope nomenclature
 *       {@code RULE:} / {@code COMMUNITY:} / {@code GLOBAL} (I5, §27.2);</li>
 *   <li>the once-per-period mechanics read their uniqueness from the same cumulatives
 *       as the caps, no new counter (§15).</li>
 * </ul>
 * The engine is a read (§30.2); it serves both the {@code /earn} projection (§27.1)
 * and the ingestion recalc (§26.1), which reuses the produced {@link EarnResult}.
 */
@ApplicationScoped
public class EarnEngine {

    /**
     * The default global monthly cap in euro when the setting is absent (400 €, §25.1).
     */
    private static final BigDecimal DEFAULT_GLOBAL_CAP = new BigDecimal("400.00");

    /**
     * The registry creating an applier per rule and validating the extension contract
     * (§12).
     */
    @Inject
    EarnRuleRegistry registry;

    /**
     * The builder of the dated card context (§15, §31.1).
     */
    @Inject
    CardContextBuilder contextBuilder;

    /**
     * Evaluates the earn over a valued reading for an account at a fiscal instant (§15).
     *
     * @param reading            The valued basket reading (§22.1); must not be null.
     * @param account            The resolved account; null (unknown card) or a
     *                           non-earning account yields no entries, but the burnable
     *                           base is still computed (§20, §25.3).
     * @param evaluationDateTime The fiscal evaluation instant at the program zone (§31.1).
     * @param countCurrentVisit  Whether the current visit is not yet recorded (true for
     *                           the projection, false for the ingestion recalc, §29.1).
     * @return The evaluation result, never null.
     */
    public EarnResult evaluate(ValuationReading reading, FidelityAccount account,
                               java.time.LocalDateTime evaluationDateTime, boolean countCurrentVisit) {
        List<ValuedLine> lines = reading.lines;
        EarnResult result = new EarnResult(lines);
        result.warnings.addAll(reading.warnings);

        // 1. Split exclusions from producers; collect the program-excluded line ids.
        Set<String> excludedLineIds = new LinkedHashSet<>();
        List<RuleApplier> producers = splitRules(evaluationDateTime, lines, excludedLineIds);

        // 2. Burnable base = totalPrice − net of the excluded lines, clamped to zero (§22.3).
        result.burnableBase = burnableBase(reading, lines, excludedLineIds);

        // 3. No account or a non-earning account: burnable base only, no entries (§20, §25.3).
        if (account == null || !account.canEarn()) {
            return result;
        }

        CardContext context = contextBuilder.build(account, evaluationDateTime, countCurrentVisit);
        applyProducers(result, producers, lines, excludedLineIds, context, false);
        return result;
    }

    /**
     * Evaluates the anonymous projection — "had you carried the card" (§20 respected,
     * RFP BO-03-03-28): no account is resolved or created, nothing is credited and no
     * trace is written. Only the appliers that declare
     * {@link EarnRuleApplier#appliesAnonymously()} run, against a zero-history context
     * (a fresh card: no visits, no memberships, no activations, empty cap cumulatives)
     * — so the announced amount is what a card created on the spot would have earned,
     * never an inflated promise. The burnable base stays zero: an anonymous customer
     * has nothing to burn.
     *
     * @param reading            The valued basket reading (§22.1); must not be null.
     * @param evaluationDateTime The fiscal evaluation instant at the program zone (§31.1).
     * @return The evaluation result, never null, flagged {@code ANONYMOUS}.
     */
    public EarnResult evaluateAnonymous(ValuationReading reading, java.time.LocalDateTime evaluationDateTime) {
        List<ValuedLine> lines = reading.lines;
        EarnResult result = new EarnResult(lines);
        result.projectionMode = EarnResponse.MODE_ANONYMOUS;
        result.warnings.addAll(reading.warnings);

        Set<String> excludedLineIds = new LinkedHashSet<>();
        List<RuleApplier> producers = splitRules(evaluationDateTime, lines, excludedLineIds);

        FidelityAccount ghost = new FidelityAccount();
        ghost.cardNumber = "ANONYMOUS";
        CardContext context = new CardContext(ghost, evaluationDateTime);
        applyProducers(result, producers, lines, excludedLineIds, context, true);
        return result;
    }

    /**
     * Splits the rules in force into producers and program exclusions, collecting the
     * excluded line ids of the latter (§15, §22.3).
     *
     * @param evaluationDateTime The fiscal evaluation instant.
     * @param lines              The valued basket lines.
     * @param excludedLineIds    The mutable sink of program-excluded line ids.
     * @return The producers, in force order, never null.
     */
    private List<RuleApplier> splitRules(java.time.LocalDateTime evaluationDateTime,
                                         List<ValuedLine> lines, Set<String> excludedLineIds) {
        List<RuleApplier> producers = new ArrayList<>();
        for (FidelityRule rule : FidelityRule.listInForceAt(evaluationDateTime)) {
            if (!registry.hasFactory(rule.type)) {
                continue;
            }
            EarnRuleApplier applier = registry.createApplier(rule);
            if (applier.isProducer()) {
                producers.add(new RuleApplier(rule, applier));
            } else {
                excludedLineIds.addAll(applier.excludedLineIds(lines));
            }
        }
        return producers;
    }

    /**
     * Runs the producers by descending priority, applying exclusivity and caps, and
     * accumulates the entries and the total on the result (§15, I2, I5).
     *
     * @param result          The result to fill.
     * @param producers       The producers, in force order.
     * @param lines           The valued basket lines.
     * @param excludedLineIds The program-excluded line ids.
     * @param context         The card context (a zero-history one in anonymous mode).
     * @param anonymousOnly   When true, only appliers declaring
     *                        {@link EarnRuleApplier#appliesAnonymously()} run (§20,
     *                        RFP BO-03-03-28).
     */
    private void applyProducers(EarnResult result, List<RuleApplier> producers, List<ValuedLine> lines,
                                Set<String> excludedLineIds, CardContext context, boolean anonymousOnly) {
        BigDecimal globalCap = FidelityProgramSetting.getDecimal(
                FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, DEFAULT_GLOBAL_CAP);

        // 4. Evaluate producers by descending priority, applying exclusivity and caps.
        Set<String> consumed = new LinkedHashSet<>(excludedLineIds);
        BigDecimal runningGlobal = context.globalMonthlyEarn;
        Map<String, BigDecimal> runningCommunity = new LinkedHashMap<>(context.communityMonthlyEarn);

        for (RuleApplier producer : producers) {
            if (anonymousOnly && !producer.applier.appliesAnonymously()) {
                continue;
            }
            List<ValuedLine> available = availableLines(lines, consumed);
            EarnEntry raw = producer.applier.apply(available, context);
            if (raw.isEmpty()) {
                continue;
            }
            // Exclusive rules consume their lines regardless of any cap truncation (I2).
            if (producer.rule.exclusive) {
                consumed.addAll(raw.lineIds);
            }
            BigDecimal granted = applyCaps(producer.rule, raw, context, runningGlobal, runningCommunity,
                    globalCap, result.capsApplied);
            if (granted.signum() > 0) {
                EarnResponse.Entry entry = new EarnResponse.Entry(producer.rule.code, producer.rule.label,
                        granted, raw.baseAmount, raw.lineIds);
                fillAdvantage(entry, producer.rule);
                result.entries.add(entry);
                runningGlobal = runningGlobal.add(granted);
                String communityCode = producer.rule.communityCodeFromSpec();
                if (communityCode != null) {
                    runningCommunity.merge(communityCode, granted, BigDecimal::add);
                }
                result.total = result.total.add(granted);
            }
        }
        result.total = result.total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Computes the burnable base: {@code totalPrice} minus the net (TTC) of the lines
     * covered by the program exclusions, clamped to zero (§22.3). An unknown-EAN line
     * is not covered, so it stays inside the base (§25.4).
     *
     * @param reading         The valued reading (for the total price).
     * @param lines           The valued lines.
     * @param excludedLineIds The program-excluded line ids.
     * @return The burnable base, euro at scale 2.
     */
    private BigDecimal burnableBase(ValuationReading reading, List<ValuedLine> lines, Set<String> excludedLineIds) {
        BigDecimal excludedNet = BigDecimal.ZERO;
        for (ValuedLine line : lines) {
            if (excludedLineIds.contains(line.lineId)) {
                excludedNet = excludedNet.add(line.netAmountIncludingTax);
            }
        }
        BigDecimal base = reading.totalPriceTtc.subtract(excludedNet);
        if (base.signum() < 0) {
            base = BigDecimal.ZERO;
        }
        return base.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Returns the basket lines still available to a rule: those not consumed by a prior
     * exclusive rule nor by a program exclusion (§15, I2).
     *
     * @param lines    The valued lines.
     * @param consumed The consumed / excluded line ids.
     * @return The available lines, never null.
     */
    private List<ValuedLine> availableLines(List<ValuedLine> lines, Set<String> consumed) {
        List<ValuedLine> available = new ArrayList<>(lines.size());
        for (ValuedLine line : lines) {
            if (!consumed.contains(line.lineId)) {
                available.add(line);
            }
        }
        return available;
    }

    /**
     * Truncates a rule's raw earn by the caps in order rule &rarr; community &rarr; global,
     * tracing each truncation under its scope (§15, I5, §27.2). The cap cumulatives are
     * the month's prior earn plus this basket's running totals.
     *
     * @param rule             The rule granting the earn.
     * @param raw              The raw earn entry before caps.
     * @param context          The card context (per-rule / per-community prior cumulatives).
     * @param runningGlobal    The global running total (prior month + this basket).
     * @param runningCommunity The per-community running totals (prior month + this basket).
     * @param globalCap        The global cap value.
     * @param capsApplied      The cap trace list to append truncations to.
     * @return The granted amount after caps, euro at scale 2.
     */
    /**
     * Fills an entry's ticket-grouping attributes from its rule and the advantage
     * referentials (RFP BO-03-03-25/-33): the POS groups on the codes and prints the
     * labels — it never translates a code itself (§18). A rule created before the
     * advantage referential existed may carry no type; the fields then stay null and
     * the POS falls back to its flat list.
     *
     * @param entry The response entry to fill.
     * @param rule  The crediting rule.
     */
    private void fillAdvantage(EarnResponse.Entry entry, FidelityRule rule) {
        if (rule.advantageType != null) {
            entry.advantageType = rule.advantageType;
            AdvantageType type = AdvantageType.findByCode(rule.advantageType);
            if (type != null) {
                entry.advantageTypeLabel = type.label;
                entry.advantageTypeOrder = type.displayOrder;
            }
        }
        if (rule.advantageCategory != null) {
            entry.advantageCategory = rule.advantageCategory;
            AdvantageCategory category = AdvantageCategory.findByCode(rule.advantageCategory);
            if (category != null) {
                entry.advantageCategoryLabel = category.label;
            }
        }
    }

    private BigDecimal applyCaps(FidelityRule rule, EarnEntry raw, CardContext context,
                                 BigDecimal runningGlobal, Map<String, BigDecimal> runningCommunity,
                                 BigDecimal globalCap, List<EarnResponse.CapApplied> capsApplied) {
        BigDecimal granted = raw.amount;

        // Rule cap.
        if (rule.monthlyCapPerCard != null) {
            BigDecimal cap = rule.monthlyCapPerCard.setScale(2, RoundingMode.HALF_UP);
            BigDecimal remaining = remaining(cap, context.ruleEarnSoFar(rule.code));
            granted = truncate(granted, remaining, "RULE:" + rule.code, cap, capsApplied);
        }

        // Community cap.
        String communityCode = rule.communityCodeFromSpec();
        if (communityCode != null) {
            FidelityCommunity community = FidelityCommunity.findByCode(communityCode);
            if (community != null && community.monthlyCap != null) {
                BigDecimal cap = community.monthlyCap.setScale(2, RoundingMode.HALF_UP);
                BigDecimal used = runningCommunity.getOrDefault(communityCode, context.communityEarnSoFar(communityCode));
                granted = truncate(granted, remaining(cap, used), "COMMUNITY:" + communityCode, cap, capsApplied);
            }
        }

        // Global cap.
        BigDecimal cap = globalCap.setScale(2, RoundingMode.HALF_UP);
        granted = truncate(granted, remaining(cap, runningGlobal), "GLOBAL", cap, capsApplied);
        return granted;
    }

    /**
     * Returns the non-negative remaining headroom of a cap given the used amount (§31.2).
     *
     * @param cap  The cap value.
     * @param used The amount already used against the cap.
     * @return The remaining headroom, never negative, euro at scale 2.
     */
    private static BigDecimal remaining(BigDecimal cap, BigDecimal used) {
        BigDecimal headroom = cap.subtract(used != null ? used : BigDecimal.ZERO);
        return headroom.signum() < 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : headroom.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Truncates an amount to a remaining headroom, tracing the truncation under its
     * scope when it actually cuts (§15, I5).
     *
     * @param amount      The amount to truncate.
     * @param remaining   The remaining headroom.
     * @param scope       The cap scope (RULE:/COMMUNITY:/GLOBAL).
     * @param capAmount   The cap value, for the trace.
     * @param capsApplied The trace list to append to.
     * @return The truncated amount, euro at scale 2.
     */
    private static BigDecimal truncate(BigDecimal amount, BigDecimal remaining, String scope,
                                       BigDecimal capAmount, List<EarnResponse.CapApplied> capsApplied) {
        if (amount.compareTo(remaining) > 0) {
            BigDecimal truncatedBy = amount.subtract(remaining).setScale(2, RoundingMode.HALF_UP);
            capsApplied.add(new EarnResponse.CapApplied(scope, capAmount, truncatedBy));
            return remaining;
        }
        return amount;
    }

    /**
     * A rule paired with its applier, kept in evaluation (priority) order.
     */
    private static final class RuleApplier {

        /**
         * The rule.
         */
        private final FidelityRule rule;

        /**
         * The applier bound to the rule.
         */
        private final EarnRuleApplier applier;

        /**
         * Pairs a rule with its applier.
         *
         * @param rule    The rule.
         * @param applier The applier.
         */
        private RuleApplier(FidelityRule rule, EarnRuleApplier applier) {
            this.rule = rule;
            this.applier = applier;
        }
    }
}
