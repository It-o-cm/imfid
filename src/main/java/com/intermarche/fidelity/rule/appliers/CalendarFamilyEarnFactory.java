package com.intermarche.fidelity.rule.appliers;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.rule.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Factory for {@code CALENDAR_FAMILY_EARN} — the weekend fruit &amp; vegetables
 * (10 %, §12): a percentage on a family set, on given weekdays.
 * <p>
 * Administrable parameters: the included families and the exclusions (dried,
 * processed, frozen…) as ordinary scopes (§13), the active weekdays
 * ({@code activeDays}, e.g. Saturday/Sunday) and the rate ({@code rate}).
 */
@ApplicationScoped
public class CalendarFamilyEarnFactory extends AbstractEarnRuleApplierFactory {

    /**
     * Returns the rule type this factory interprets.
     *
     * @return {@link FidelityRule#TYPE_CALENDAR_FAMILY_EARN}.
     */
    @Override
    public String getRuleType() {
        return FidelityRule.TYPE_CALENDAR_FAMILY_EARN;
    }

    /**
     * Creates the applier bound to the given rule.
     *
     * @param rule The rule to interpret.
     * @return A new {@link CalendarFamilyEarnApplier}.
     */
    @Override
    public EarnRuleApplier create(FidelityRule rule) {
        return new CalendarFamilyEarnApplier(rule);
    }
}

/**
 * Applier for {@code CALENDAR_FAMILY_EARN} (§12, §15).
 * <p>
 * Grants nothing when the evaluation weekday is not among the active days;
 * otherwise sums the net assiette of the eligible family lines and applies the
 * rate, rounded once at the centime (I4).
 */
class CalendarFamilyEarnApplier extends AbstractEarnRuleApplier {

    /**
     * The rate as a stored fraction (0.10 = 10 %).
     */
    private final BigDecimal rate;

    /**
     * The weekdays the rule is active on, resolved from the specification (§12).
     */
    private final Set<DayOfWeek> activeDays;

    /**
     * Builds the applier, reading the rate and the active weekdays.
     *
     * @param rule The rule to interpret.
     */
    CalendarFamilyEarnApplier(FidelityRule rule) {
        super(rule);
        this.rate = decimal("rate", BigDecimal.ZERO);
        this.activeDays = parseActiveDays();
    }

    /**
     * Evaluates the calendar rule against the basket and the card context (§15).
     *
     * @param lines   The valued basket lines.
     * @param context The dated card context.
     * @return The earn produced, or the empty entry when the weekday is inactive or
     *         no line is eligible.
     */
    @Override
    public EarnEntry apply(List<ValuedLine> lines, CardContext context) {
        if (context == null || !activeDays.contains(context.evaluationDate.getDayOfWeek())) {
            return none();
        }
        List<ValuedLine> eligible = eligibleLines(lines);
        if (eligible.isEmpty()) {
            return none();
        }
        BigDecimal assiette = assietteOf(eligible);
        BigDecimal amount = applyRate(assiette, rate);
        return entry(amount, assiette, eligible);
    }

    /**
     * Parses the {@code activeDays} array of the specification, accepting an ISO
     * weekday number (1 = Monday … 7 = Sunday) or a weekday name/prefix (e.g. "SAT",
     * "SATURDAY"), case-insensitively; unknown tokens are ignored (§31.2).
     *
     * @return The active weekdays, never null.
     */
    private Set<DayOfWeek> parseActiveDays() {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        JsonNode node = spec.get("activeDays");
        if (node == null || !node.isArray()) {
            return days;
        }
        for (JsonNode element : node) {
            DayOfWeek day = toDayOfWeek(element);
            if (day != null) {
                days.add(day);
            }
        }
        return days;
    }

    /**
     * Resolves a single {@code activeDays} element to a weekday, guarding JSON nulls
     * and malformed tokens (§31.2). Java nulls need no guard: iterating a Jackson
     * array never yields one (a JSON {@code null} element is a {@code NullNode}),
     * and a textual node's {@code asText()} is never null.
     *
     * @param element The JSON element (a number or a string).
     * @return The weekday, or null when unresolved.
     */
    private static DayOfWeek toDayOfWeek(JsonNode element) {
        if (element.isNull()) {
            return null;
        }
        if (element.isNumber()) {
            int iso = element.asInt();
            return iso >= 1 && iso <= 7 ? DayOfWeek.of(iso) : null;
        }
        if (element.isTextual()) {
            String token = element.asText();
            // A blank token would otherwise match every day: name().startsWith("") is true.
            if (token.isBlank()) {
                return null;
            }
            String upper = token.trim().toUpperCase();
            for (DayOfWeek day : DayOfWeek.values()) {
                if (day.name().startsWith(upper) || upper.startsWith(day.name())) {
                    return day;
                }
            }
        }
        return null;
    }
}
