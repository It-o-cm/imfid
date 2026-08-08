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
 * Plain unit coverage for {@link CalendarFamilyEarnFactory} and its
 * {@link CalendarFamilyEarnApplier} — the weekend fruit &amp; vegetables mechanic
 * (10 %, §12): the active-weekday gate, the {@code activeDays} parsing (ISO numbers
 * and weekday name/prefix tokens, §31.2) and the single per-rule centime rounding (I4).
 * <p>
 * Pure logic: the applier's arithmetic is asserted directly on in-memory valued
 * baskets. The assiette scope uses {@code wholeStore} with EAN-less lines, so no
 * Panache product reference is read — no static finder needs mocking (§13, §25.4).
 * The card context carries a fixed fiscal instant with a mocked account, so the
 * weekend window borders are straddled by the two calendar days framing each border
 * (Friday/Saturday and Sunday/Monday) and never read from the real clock (§24.6,
 * §30.3). Every {@link BigDecimal} is asserted by {@code compareTo}.
 */
class CalendarFamilyEarnFactoryTest {

    /**
     * The stable rule code carried by every fixture rule.
     */
    private static final String CODE = "WEEKEND-FRUIT";

    /**
     * The printed rule label carried by every fixture rule.
     */
    private static final String LABEL = "Weekend fruit and vegetables";

    /**
     * A fixed Friday fiscal instant — the day before the weekend window, inactive
     * under the standard specification (2026-01-02, program zone).
     */
    private static final LocalDateTime FRIDAY = LocalDateTime.of(2026, 1, 2, 10, 0);

    /**
     * A fixed Saturday fiscal instant — the first active weekend day (2026-01-03).
     */
    private static final LocalDateTime SATURDAY = LocalDateTime.of(2026, 1, 3, 10, 0);

    /**
     * A fixed Sunday fiscal instant — the second active weekend day (2026-01-04).
     */
    private static final LocalDateTime SUNDAY = LocalDateTime.of(2026, 1, 4, 10, 0);

    /**
     * A fixed Monday fiscal instant — the day after the weekend window, inactive
     * under the standard specification (2026-01-05).
     */
    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 1, 5, 10, 0);

    /**
     * Builds a calendar-family rule carrying the given JSON specification.
     *
     * @param specification The rule specification JSON.
     * @return A fidelity rule ready for the applier.
     */
    private static FidelityRule ruleWith(String specification) {
        FidelityRule rule = new FidelityRule();
        rule.code = CODE;
        rule.label = LABEL;
        rule.type = FidelityRule.TYPE_CALENDAR_FAMILY_EARN;
        rule.specification = specification;
        return rule;
    }

    /**
     * Builds the standard specification: whole-store assiette, Saturday and Sunday
     * active by name, and a 10 % rate.
     *
     * @return The standard specification JSON.
     */
    private static String standardSpec() {
        return "{\"scope\":{\"wholeStore\":true},\"activeDays\":[\"SATURDAY\",\"SUNDAY\"],\"rate\":0.10}";
    }

    /**
     * Builds a specification with a whole-store assiette, a 10 % rate and the given
     * {@code activeDays} JSON literal.
     *
     * @param activeDaysLiteral The raw JSON value of the {@code activeDays} field.
     * @return The specification JSON.
     */
    private static String specWithActiveDays(String activeDaysLiteral) {
        return "{\"scope\":{\"wholeStore\":true},\"activeDays\":" + activeDaysLiteral + ",\"rate\":0.10}";
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
     * Builds a bare card context on the given fixed fiscal instant with a mocked
     * account (§15).
     *
     * @param instant The fixed fiscal evaluation instant.
     * @return The dated card context.
     */
    private static CardContext contextOn(LocalDateTime instant) {
        FidelityAccount account = Mockito.mock(FidelityAccount.class);
        return new CardContext(account, instant);
    }

    /**
     * The factory reports the calendar-family rule type it interprets.
     */
    @Test
    @DisplayName("getRuleType returns CALENDAR_FAMILY_EARN")
    void getRuleTypeReturnsCalendarFamily() {
        assertEquals(FidelityRule.TYPE_CALENDAR_FAMILY_EARN, new CalendarFamilyEarnFactory().getRuleType());
    }

    /**
     * The factory creates a calendar-family applier bound to the rule.
     */
    @Test
    @DisplayName("create returns a CalendarFamilyEarnApplier")
    void createReturnsApplier() {
        EarnRuleApplier applier = new CalendarFamilyEarnFactory().create(ruleWith(standardSpec()));
        assertNotNull(applier);
        assertTrue(applier instanceof CalendarFamilyEarnApplier);
    }

    /**
     * A null card context short-circuits to the empty entry (context == null, true leg
     * of the guard).
     */
    @Test
    @DisplayName("apply returns none when the context is null")
    void applyReturnsNoneWhenContextNull() {
        EarnRuleApplier applier = new CalendarFamilyEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), null);
        assertTrue(entry.isEmpty());
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(0, entry.amount.compareTo(BigDecimal.ZERO));
    }

    /**
     * On a Friday — the day framing the low side of the weekend border — the weekday
     * is inactive and the rule grants nothing (context != null false arm,
     * !activeDays.contains true leg).
     */
    @Test
    @DisplayName("apply returns none on an inactive weekday (Friday)")
    void applyReturnsNoneWhenWeekdayInactive() {
        EarnRuleApplier applier = new CalendarFamilyEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00"), line("L2", "20.00")), contextOn(FRIDAY));
        assertTrue(entry.isEmpty());
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * On a Monday — the day framing the high side of the weekend border — the weekday
     * is inactive and the rule grants nothing; straddles
     * {@link #applyGrantsOnActiveSunday()} across the Sunday/Monday border.
     */
    @Test
    @DisplayName("apply returns none on an inactive weekday (Monday)")
    void applyReturnsNoneWhenWeekdayInactiveMonday() {
        EarnRuleApplier applier = new CalendarFamilyEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), contextOn(MONDAY));
        assertTrue(entry.isEmpty());
    }

    /**
     * On an active Saturday with an empty basket, no line is eligible and the rule
     * grants nothing (both guard legs false, eligible.isEmpty true leg).
     */
    @Test
    @DisplayName("apply returns none on an active day with no eligible lines")
    void applyReturnsNoneWhenActiveButNoEligibleLines() {
        EarnRuleApplier applier = new CalendarFamilyEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(), contextOn(SATURDAY));
        assertTrue(entry.isEmpty());
        assertEquals(0, entry.baseAmount.compareTo(BigDecimal.ZERO));
        assertTrue(entry.lineIds.isEmpty());
    }

    /**
     * On an active Saturday where every line is consumed by a commercial offer, the
     * assiette is empty and the rule grants nothing (eligible.isEmpty true leg via I2
     * filtering).
     */
    @Test
    @DisplayName("apply returns none when every line is consumed by an offer")
    void applyReturnsNoneWhenAllLinesConsumed() {
        EarnRuleApplier applier = new CalendarFamilyEarnFactory().create(ruleWith(standardSpec()));
        List<ValuedLine> lines = new ArrayList<>();
        lines.add(new ValuedLine("L1", null, BigDecimal.ONE, new BigDecimal("10.00"), new BigDecimal("10.00"), true));
        lines.add(new ValuedLine("L2", null, BigDecimal.ONE, new BigDecimal("20.00"), new BigDecimal("20.00"), true));
        EarnEntry entry = applier.apply(lines, contextOn(SATURDAY));
        assertTrue(entry.isEmpty());
    }

    /**
     * On an active Saturday with eligible lines, the rate applies to the whole net
     * assiette (both guard legs false, eligible.isEmpty false leg → producer path).
     */
    @Test
    @DisplayName("apply grants 10 % on an active Saturday")
    void applyGrantsOnActiveSaturday() {
        EarnRuleApplier applier = new CalendarFamilyEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00"), line("L2", "20.00")), contextOn(SATURDAY));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("30.00")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("3.00")));
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(List.of("L1", "L2"), entry.lineIds);
    }

    /**
     * On an active Sunday the mechanic also grants; straddles the Friday/Saturday and
     * Sunday/Monday borders with the two inactive-day tests, covering both active
     * weekdays of the standard specification.
     */
    @Test
    @DisplayName("apply grants 10 % on an active Sunday")
    void applyGrantsOnActiveSunday() {
        EarnRuleApplier applier = new CalendarFamilyEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "15.00")), contextOn(SUNDAY));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("15.00")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("1.50")));
        assertEquals(List.of("L1"), entry.lineIds);
    }

    /**
     * The rate applies to the whole assiette and rounds once at the centime HALF_UP
     * (I4): 3.35 € at 10 % is 0.335 €, rounded to 0.34 €.
     */
    @Test
    @DisplayName("apply rounds the earn at the centime HALF_UP")
    void applyRoundsAtCentimeHalfUp() {
        EarnRuleApplier applier = new CalendarFamilyEarnFactory().create(ruleWith(standardSpec()));
        EarnEntry entry = applier.apply(List.of(line("L1", "3.35")), contextOn(SATURDAY));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("3.35")));
        assertEquals(0, entry.amount.compareTo(new BigDecimal("0.34")));
    }

    /**
     * A specification without an {@code activeDays} field resolves to no active day,
     * so even a Saturday grants nothing (parseActiveDays node == null true leg).
     */
    @Test
    @DisplayName("apply returns none when activeDays is absent")
    void applyReturnsNoneWhenActiveDaysAbsent() {
        String spec = "{\"scope\":{\"wholeStore\":true},\"rate\":0.10}";
        EarnRuleApplier applier = new CalendarFamilyEarnFactory().create(ruleWith(spec));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), contextOn(SATURDAY));
        assertTrue(entry.isEmpty());
    }

    /**
     * An {@code activeDays} that is not an array (a bare string) resolves to no active
     * day (parseActiveDays !node.isArray true leg).
     */
    @Test
    @DisplayName("apply returns none when activeDays is not an array")
    void applyReturnsNoneWhenActiveDaysNotArray() {
        EarnRuleApplier applier = new CalendarFamilyEarnFactory().create(ruleWith(specWithActiveDays("\"SATURDAY\"")));
        EarnEntry entry = applier.apply(List.of(line("L1", "10.00")), contextOn(SATURDAY));
        assertTrue(entry.isEmpty());
    }

    /**
     * An unknown textual token is ignored while a valid one is kept (toDayOfWeek both
     * name-match legs false → return null, and parseActiveDays day != null false then
     * true legs); the mechanic still grants on the retained Saturday.
     */
    @Test
    @DisplayName("apply ignores an unknown token but keeps a valid weekday")
    void applyIgnoresUnknownTokenButKeepsValid() {
        EarnRuleApplier applier =
                new CalendarFamilyEarnFactory().create(ruleWith(specWithActiveDays("[\"XYZ\",\"SATURDAY\"]")));
        EarnEntry saturday = applier.apply(List.of(line("L1", "10.00")), contextOn(SATURDAY));
        assertEquals(0, saturday.amount.compareTo(new BigDecimal("1.00")));
        EarnEntry friday = applier.apply(List.of(line("L1", "10.00")), contextOn(FRIDAY));
        assertTrue(friday.isEmpty());
    }

    /**
     * ISO weekday numbers select their weekdays (toDayOfWeek isNumber true arm,
     * ternary both bounds true): 6 = Saturday, 7 = Sunday.
     */
    @Test
    @DisplayName("apply accepts ISO weekday numbers")
    void applyAcceptsIsoWeekdayNumbers() {
        EarnRuleApplier applier =
                new CalendarFamilyEarnFactory().create(ruleWith(specWithActiveDays("[6,7]")));
        assertEquals(0, applier.apply(List.of(line("L1", "10.00")), contextOn(SATURDAY))
                .amount.compareTo(new BigDecimal("1.00")));
        assertEquals(0, applier.apply(List.of(line("L1", "10.00")), contextOn(SUNDAY))
                .amount.compareTo(new BigDecimal("1.00")));
    }

    /**
     * Out-of-range ISO numbers are ignored (toDayOfWeek ternary iso &gt;= 1 false leg
     * with 0, iso &lt;= 7 false leg with 8) while the valid 6 is kept; grants on
     * Saturday.
     */
    @Test
    @DisplayName("apply ignores out-of-range ISO numbers")
    void applyIgnoresOutOfRangeIsoNumbers() {
        EarnRuleApplier applier =
                new CalendarFamilyEarnFactory().create(ruleWith(specWithActiveDays("[0,8,6]")));
        assertEquals(0, applier.apply(List.of(line("L1", "10.00")), contextOn(SATURDAY))
                .amount.compareTo(new BigDecimal("1.00")));
        assertTrue(applier.apply(List.of(line("L1", "10.00")), contextOn(SUNDAY)).isEmpty());
    }

    /**
     * A JSON null element in the array is ignored (toDayOfWeek element.isNull true
     * leg) while the valid token is kept; grants on Saturday.
     */
    @Test
    @DisplayName("apply ignores a JSON null element")
    void applyIgnoresJsonNullElement() {
        EarnRuleApplier applier =
                new CalendarFamilyEarnFactory().create(ruleWith(specWithActiveDays("[null,\"SATURDAY\"]")));
        assertEquals(0, applier.apply(List.of(line("L1", "10.00")), contextOn(SATURDAY))
                .amount.compareTo(new BigDecimal("1.00")));
    }

    /**
     * A blank textual element is ignored (toDayOfWeek token.isBlank true leg) while
     * the valid token is kept; grants on Saturday.
     */
    @Test
    @DisplayName("apply ignores a blank textual element")
    void applyIgnoresBlankTextualElement() {
        EarnRuleApplier applier =
                new CalendarFamilyEarnFactory().create(ruleWith(specWithActiveDays("[\"   \",\"SATURDAY\"]")));
        assertEquals(0, applier.apply(List.of(line("L1", "10.00")), contextOn(SATURDAY))
                .amount.compareTo(new BigDecimal("1.00")));
    }

    /**
     * A weekday prefix matches by name start (toDayOfWeek name.startsWith(upper) true
     * leg): "SAT" resolves to Saturday.
     */
    @Test
    @DisplayName("apply matches a weekday name by prefix")
    void applyMatchesWeekdayNameByPrefix() {
        EarnRuleApplier applier =
                new CalendarFamilyEarnFactory().create(ruleWith(specWithActiveDays("[\"SAT\"]")));
        assertEquals(0, applier.apply(List.of(line("L1", "10.00")), contextOn(SATURDAY))
                .amount.compareTo(new BigDecimal("1.00")));
        assertTrue(applier.apply(List.of(line("L1", "10.00")), contextOn(SUNDAY)).isEmpty());
    }

    /**
     * A token that extends a weekday name matches on the second leg
     * (toDayOfWeek name.startsWith(upper) false, upper.startsWith(name) true):
     * "MONDAYISH" resolves to Monday.
     */
    @Test
    @DisplayName("apply matches when the token extends a weekday name")
    void applyMatchesWhenTokenExtendsName() {
        EarnRuleApplier applier =
                new CalendarFamilyEarnFactory().create(ruleWith(specWithActiveDays("[\"MONDAYISH\"]")));
        assertEquals(0, applier.apply(List.of(line("L1", "10.00")), contextOn(MONDAY))
                .amount.compareTo(new BigDecimal("1.00")));
        assertTrue(applier.apply(List.of(line("L1", "10.00")), contextOn(SATURDAY)).isEmpty());
    }

    /**
     * An element that is neither a number nor a string (a boolean) is ignored
     * (toDayOfWeek falls through both type guards to the trailing return null) while
     * the valid token is kept; grants on Saturday.
     */
    @Test
    @DisplayName("apply ignores a non-textual, non-numeric element")
    void applyIgnoresNonTextualNonNumericElement() {
        EarnRuleApplier applier =
                new CalendarFamilyEarnFactory().create(ruleWith(specWithActiveDays("[true,\"SATURDAY\"]")));
        assertEquals(0, applier.apply(List.of(line("L1", "10.00")), contextOn(SATURDAY))
                .amount.compareTo(new BigDecimal("1.00")));
    }
}
