package com.intermarche.fidelity.rule.appliers;

import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.rule.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Factory for {@code PROGRAM_EXCLUSION} — the whole-programme assiette filter (§12,
 * §15): it removes its scopes from every earn assiette and founds the burnable base
 * (the "neither accrue nor use" clause of the CGU — gift cards, books, press, gas,
 * fuel).
 * <p>
 * Administrable parameters: the include/exclude scopes (brands, families, EANs) and
 * the validity window — nothing hard-coded (§13). This is a filter, not a producer:
 * it never grants an earn entry; the orchestration evaluates it upstream of all
 * other rules (§15).
 */
@ApplicationScoped
public class ProgramExclusionFactory extends AbstractEarnRuleApplierFactory {

    /**
     * Returns the rule type this factory interprets.
     *
     * @return {@link FidelityRule#TYPE_PROGRAM_EXCLUSION}.
     */
    @Override
    public String getRuleType() {
        return FidelityRule.TYPE_PROGRAM_EXCLUSION;
    }

    /**
     * Creates the applier bound to the given rule.
     *
     * @param rule The rule to interpret.
     * @return A new {@link ProgramExclusionApplier}.
     */
    @Override
    public EarnRuleApplier create(FidelityRule rule) {
        return new ProgramExclusionApplier(rule);
    }
}

/**
 * Applier for {@code PROGRAM_EXCLUSION} (§12, §15).
 * <p>
 * A pure filter: {@link #isProducer()} is false and {@link #apply(List, CardContext)}
 * never grants an entry. It exposes the lines its scopes cover
 * ({@link #coveredLines(List)} / {@link #coveredLineIds(List)}) so the orchestration
 * can remove them from every earn assiette and subtract their net from
 * {@code totalPrice} to found the burnable base (§22.3) — including lines already
 * consumed by a commercial offer, since the "neither use" clause bounds the burn
 * regardless of the earn (§22.3).
 */
class ProgramExclusionApplier extends AbstractEarnRuleApplier {

    /**
     * Builds the applier.
     *
     * @param rule The rule to interpret.
     */
    ProgramExclusionApplier(FidelityRule rule) {
        super(rule);
    }

    /**
     * Exposes the covered line ids to the orchestration through the SPI (§15, §22.3).
     *
     * @param lines The valued basket lines; a null list yields an empty set.
     * @return The covered line ids, never null.
     */
    @Override
    public Set<String> excludedLineIds(List<ValuedLine> lines) {
        return coveredLineIds(lines);
    }

    /**
     * A program exclusion is a filter, not a producer (§12, §15).
     *
     * @return false.
     */
    @Override
    public boolean isProducer() {
        return false;
    }

    /**
     * Never grants an earn: a program exclusion produces nothing (§12, §15).
     *
     * @param lines   The valued basket lines (unused).
     * @param context The dated card context (unused).
     * @return The empty entry.
     */
    @Override
    public EarnEntry apply(List<ValuedLine> lines, CardContext context) {
        return none();
    }

    /**
     * Returns the basket lines this exclusion's scopes cover, regardless of
     * commercial consumption — the lines the orchestration removes from every earn
     * assiette and subtracts from {@code totalPrice} for the burnable base (§22.3).
     *
     * @param lines The valued basket lines; a null list yields an empty result.
     * @return The covered lines, in encounter order, never null.
     */
    List<ValuedLine> coveredLines(List<ValuedLine> lines) {
        List<ValuedLine> covered = new ArrayList<>();
        if (lines == null) {
            return covered;
        }
        for (ValuedLine line : lines) {
            if (isInScope(line)) {
                covered.add(line);
            }
        }
        return covered;
    }

    /**
     * Returns the ids of the lines this exclusion covers (§22.3).
     *
     * @param lines The valued basket lines; a null list yields an empty set.
     * @return The covered line ids, in encounter order, never null.
     */
    Set<String> coveredLineIds(List<ValuedLine> lines) {
        Set<String> ids = new LinkedHashSet<>();
        for (ValuedLine line : coveredLines(lines)) {
            ids.add(line.lineId);
        }
        return ids;
    }
}
