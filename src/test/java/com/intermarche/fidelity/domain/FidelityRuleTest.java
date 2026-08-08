package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.util.DateTimeProvider;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link FidelityRule}: the administrable earn rule that carries the
 * backbone in columns and the mechanic parametrics in a JSON specification (§13). Two behaviours
 * hold temporal or JSON logic: {@link FidelityRule#isInForceAt(LocalDateTime)} reads the clock only
 * through {@link DateTimeProvider} (§24.6), so its window is straddled by the two instants framing
 * each border rather than sampled from the host clock; and the specification-driven tier
 * materialization plus {@link FidelityRule#communityCodeFromSpec()} exercise every guard leg and
 * both arms of every ternary, nullities and malformed JSON included (§29, §29.6, §31.2). The three
 * Panache active-record finders are mocked through {@link PanacheEntityBase} in try-with-resources
 * per the imfid unit bench, and {@link FidelityRule#getChecksum()} is asserted as a pure function of
 * the backbone. No Quarkus boot and no H2 are involved; every {@link BigDecimal} is compared by
 * {@code compareTo}.
 */
class FidelityRuleTest {

    /**
     * A deterministic instant used to freeze the clock so the lifecycle callbacks' audit stamps and
     * the {@code isInForceAt(null)} branch are reproducible.
     */
    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 6, 15, 12, 0, 0);

    /**
     * Freezes the program clock before each test so nothing reads the host clock (§24.6).
     */
    @BeforeEach
    void freezeClock() {
        DateTimeProvider.setFixedDateTime(FIXED);
    }

    /**
     * Clears the fixed instant so a later test never inherits this one's frozen clock.
     */
    @AfterEach
    void clearClock() {
        DateTimeProvider.clear();
    }

    // --------------------------------------------------
    // onCreate() / onUpdate() — tier materialization
    // --------------------------------------------------

    /**
     * The persist callback materializes every well-formed tier and stamps the audit fields from the
     * fixed instant; a non-object array element is skipped, numeric fields are read, a JSON-null and
     * a non-numeric field yield null, and the three {@code missionRequired} states (true, explicit
     * false, absent) are all resolved.
     */
    @Test
    @DisplayName("onCreate(): builds well-formed tiers, skips non-objects, stamps audit")
    void onCreateBuildsTiers() {
        FidelityRule rule = new FidelityRule();
        rule.code = "R1";
        rule.specification = "{\"tiers\":[" +
                "{\"threshold\":3,\"rate\":0.10,\"reward\":5.00,\"missionRequired\":true}," +
                "{\"rate\":null,\"reward\":\"abc\",\"missionRequired\":false}," +
                "{}," +
                "42]}";
        rule.onCreate();
        assertEquals(3, rule.tiers.size());
        FidelityRuleTier first = rule.tiers.get(0);
        assertEquals(0, first.rank);
        assertSame(rule, first.rule);
        assertEquals(0, new BigDecimal("3").compareTo(first.threshold));
        assertEquals(0, new BigDecimal("0.10").compareTo(first.rate));
        assertEquals(0, new BigDecimal("5.00").compareTo(first.rewardAmount));
        assertTrue(first.missionRequired);
        FidelityRuleTier second = rule.tiers.get(1);
        assertEquals(1, second.rank);
        assertNull(second.threshold);
        assertNull(second.rate);
        assertNull(second.rewardAmount);
        assertFalse(second.missionRequired);
        FidelityRuleTier third = rule.tiers.get(2);
        assertEquals(2, third.rank);
        assertNull(third.threshold);
        assertFalse(third.missionRequired);
        assertEquals(FIXED, rule.createdAt);
        assertEquals(FIXED, rule.updatedAt);
        assertEquals(rule.getChecksum(), rule.checksum);
    }

    /**
     * The update callback clears any previously materialized tiers and rebuilds them from the
     * current specification, refreshing the update stamp.
     */
    @Test
    @DisplayName("onUpdate(): clears then rebuilds tiers, refreshes updatedAt")
    void onUpdateRebuildsTiers() {
        FidelityRule rule = new FidelityRule();
        rule.code = "R2";
        rule.tiers.add(new FidelityRuleTier());
        rule.specification = "{\"tiers\":[{\"threshold\":10,\"rate\":0.05}]}";
        rule.onUpdate();
        assertEquals(1, rule.tiers.size());
        assertEquals(0, new BigDecimal("10").compareTo(rule.tiers.get(0).threshold));
        assertEquals(FIXED, rule.updatedAt);
    }

    /**
     * A null specification leaves no tiers: the first leg of the null-or-blank guard short-circuits
     * and any pre-existing tiers are cleared.
     */
    @Test
    @DisplayName("onUpdate(): a null specification clears the tiers")
    void nullSpecificationClearsTiers() {
        FidelityRule rule = new FidelityRule();
        rule.tiers.add(new FidelityRuleTier());
        rule.specification = null;
        rule.onUpdate();
        assertTrue(rule.tiers.isEmpty());
    }

    /**
     * A blank specification leaves no tiers: the null leg is false and the blank leg is true.
     */
    @Test
    @DisplayName("onUpdate(): a blank specification clears the tiers")
    void blankSpecificationClearsTiers() {
        FidelityRule rule = new FidelityRule();
        rule.tiers.add(new FidelityRuleTier());
        rule.specification = "   ";
        rule.onUpdate();
        assertTrue(rule.tiers.isEmpty());
    }

    /**
     * A specification with no {@code tiers} field yields no tiers: the tiers node is null.
     */
    @Test
    @DisplayName("onUpdate(): a specification without a tiers field yields no tiers")
    void missingTiersFieldYieldsNoTiers() {
        FidelityRule rule = new FidelityRule();
        rule.specification = "{\"communityCode\":\"BABIES\"}";
        rule.onUpdate();
        assertTrue(rule.tiers.isEmpty());
    }

    /**
     * A {@code tiers} field that is not an array yields no tiers: the node is present but the
     * is-array leg is false.
     */
    @Test
    @DisplayName("onUpdate(): a non-array tiers field yields no tiers")
    void nonArrayTiersFieldYieldsNoTiers() {
        FidelityRule rule = new FidelityRule();
        rule.specification = "{\"tiers\":5}";
        rule.onUpdate();
        assertTrue(rule.tiers.isEmpty());
    }

    /**
     * Malformed JSON in the specification aborts the rebuild with an {@link IllegalStateException}
     * carrying the rule code, so a parse never leaves the entity half-built (§31.2).
     */
    @Test
    @DisplayName("onUpdate(): malformed JSON raises IllegalStateException with the code")
    void malformedSpecificationRaises() {
        FidelityRule rule = new FidelityRule();
        rule.code = "BAD";
        rule.specification = "{not valid json";
        IllegalStateException ex = assertThrows(IllegalStateException.class, rule::onUpdate);
        assertTrue(ex.getMessage().contains("BAD"));
    }

    // --------------------------------------------------
    // communityCodeFromSpec()
    // --------------------------------------------------

    /**
     * A null specification carries no community code.
     */
    @Test
    @DisplayName("communityCodeFromSpec(): null specification returns null")
    void communityCodeNullSpec() {
        FidelityRule rule = new FidelityRule();
        rule.specification = null;
        assertNull(rule.communityCodeFromSpec());
    }

    /**
     * A blank specification carries no community code: the null leg is false and the blank leg true.
     */
    @Test
    @DisplayName("communityCodeFromSpec(): blank specification returns null")
    void communityCodeBlankSpec() {
        FidelityRule rule = new FidelityRule();
        rule.specification = "  ";
        assertNull(rule.communityCodeFromSpec());
    }

    /**
     * A textual, non-blank community code is returned trimmed of surrounding whitespace.
     */
    @Test
    @DisplayName("communityCodeFromSpec(): textual code is returned trimmed")
    void communityCodeTextualTrimmed() {
        FidelityRule rule = new FidelityRule();
        rule.specification = "{\"communityCode\":\"  BABIES  \"}";
        assertEquals("BABIES", rule.communityCodeFromSpec());
    }

    /**
     * A textual but blank community code returns null: the node is textual yet its text is blank.
     */
    @Test
    @DisplayName("communityCodeFromSpec(): blank textual code returns null")
    void communityCodeTextualBlank() {
        FidelityRule rule = new FidelityRule();
        rule.specification = "{\"communityCode\":\"   \"}";
        assertNull(rule.communityCodeFromSpec());
    }

    /**
     * A non-textual community code node returns null: the node is present but the is-textual leg is
     * false.
     */
    @Test
    @DisplayName("communityCodeFromSpec(): non-textual code node returns null")
    void communityCodeNonTextual() {
        FidelityRule rule = new FidelityRule();
        rule.specification = "{\"communityCode\":42}";
        assertNull(rule.communityCodeFromSpec());
    }

    /**
     * An absent community code node returns null: the node itself is null.
     */
    @Test
    @DisplayName("communityCodeFromSpec(): absent code field returns null")
    void communityCodeAbsentField() {
        FidelityRule rule = new FidelityRule();
        rule.specification = "{\"tiers\":[]}";
        assertNull(rule.communityCodeFromSpec());
    }

    /**
     * Malformed JSON is swallowed and yields null rather than propagating (§31.2).
     */
    @Test
    @DisplayName("communityCodeFromSpec(): malformed JSON returns null")
    void communityCodeMalformed() {
        FidelityRule rule = new FidelityRule();
        rule.specification = "{not json";
        assertNull(rule.communityCodeFromSpec());
    }

    // --------------------------------------------------
    // isInForceAt()
    // --------------------------------------------------

    /**
     * An explicit instant inside an active window is in force: validTo is present and the instant
     * precedes it.
     */
    @Test
    @DisplayName("isInForceAt(): explicit instant inside a closed window is in force")
    void inForceExplicitInsideWindow() {
        FidelityRule rule = windowed(true);
        assertTrue(rule.isInForceAt(LocalDateTime.of(2026, 6, 15, 0, 0)));
    }

    /**
     * A null instant falls back to the program clock; the fixed instant lies inside the window, so
     * the rule is in force.
     */
    @Test
    @DisplayName("isInForceAt(): null instant uses the program clock")
    void inForceNullUsesProviderClock() {
        FidelityRule rule = windowed(true);
        assertTrue(rule.isInForceAt(null));
    }

    /**
     * An inactive rule is never in force: the first leg of the exclusion guard is true.
     */
    @Test
    @DisplayName("isInForceAt(): an inactive rule is never in force")
    void notInForceWhenInactive() {
        FidelityRule rule = windowed(false);
        assertFalse(rule.isInForceAt(LocalDateTime.of(2026, 6, 15, 0, 0)));
    }

    /**
     * A rule with no validFrom is never in force: the active leg is false and the null-from leg true.
     */
    @Test
    @DisplayName("isInForceAt(): a null validFrom is never in force")
    void notInForceWhenNullValidFrom() {
        FidelityRule rule = windowed(true);
        rule.validFrom = null;
        assertFalse(rule.isInForceAt(LocalDateTime.of(2026, 6, 15, 0, 0)));
    }

    /**
     * The instant on the eve of validFrom is out: the two earlier legs are false and the
     * before-window leg is true.
     */
    @Test
    @DisplayName("isInForceAt(): the instant just before validFrom is out")
    void notInForceJustBeforeValidFrom() {
        FidelityRule rule = windowed(true);
        assertFalse(rule.isInForceAt(LocalDateTime.of(2026, 5, 31, 23, 59, 59, 999_999_999)));
    }

    /**
     * The instant exactly at validFrom is in force: the inclusive lower border passes every leg.
     */
    @Test
    @DisplayName("isInForceAt(): the instant exactly at validFrom is in force")
    void inForceExactlyAtValidFrom() {
        FidelityRule rule = windowed(true);
        assertTrue(rule.isInForceAt(LocalDateTime.of(2026, 6, 1, 0, 0)));
    }

    /**
     * The instant just before validTo is in force: validTo is present and the instant precedes it.
     */
    @Test
    @DisplayName("isInForceAt(): the instant just before validTo is in force")
    void inForceJustBeforeValidTo() {
        FidelityRule rule = windowed(true);
        assertTrue(rule.isInForceAt(LocalDateTime.of(2026, 6, 30, 23, 59, 59, 999_999_999)));
    }

    /**
     * The instant exactly at validTo is out: the upper border is exclusive, so the before-validTo
     * leg is false.
     */
    @Test
    @DisplayName("isInForceAt(): the instant exactly at validTo is out")
    void notInForceExactlyAtValidTo() {
        FidelityRule rule = windowed(true);
        assertFalse(rule.isInForceAt(LocalDateTime.of(2026, 7, 1, 0, 0)));
    }

    /**
     * An open rule (null validTo) is in force at any instant on or after validFrom: the null-validTo
     * leg is true.
     */
    @Test
    @DisplayName("isInForceAt(): an open rule (null validTo) is in force after validFrom")
    void inForceOpenRule() {
        FidelityRule rule = windowed(true);
        rule.validTo = null;
        assertTrue(rule.isInForceAt(LocalDateTime.of(2030, 1, 1, 0, 0)));
    }

    // --------------------------------------------------
    // findByCode() / listByType() / listInForceAt()
    // --------------------------------------------------

    /**
     * The code finder delegates to the Panache query and returns its first result.
     */
    @Test
    @DisplayName("findByCode(): returns the matching rule")
    void findByCodeFound() {
        FidelityRule found = new FidelityRule();
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityRule> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "R1")).thenReturn(query);
            assertSame(found, FidelityRule.findByCode("R1"));
        }
    }

    /**
     * The code finder returns null when the query yields nothing.
     */
    @Test
    @DisplayName("findByCode(): returns null when absent")
    void findByCodeAbsent() {
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityRule> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "GHOST")).thenReturn(query);
            assertNull(FidelityRule.findByCode("GHOST"));
        }
    }

    /**
     * The type listing delegates to the Panache list and returns it verbatim.
     */
    @Test
    @DisplayName("listByType(): delegates to the Panache list")
    void listByTypeDelegates() {
        List<FidelityRule> rules = List.of(new FidelityRule(), new FidelityRule());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("type", FidelityRule.TYPE_COMMUNITY_EARN))
                    .thenReturn(rules);
            assertSame(rules, FidelityRule.listByType(FidelityRule.TYPE_COMMUNITY_EARN));
        }
    }

    /**
     * The in-force listing delegates to the priority-ordered Panache query with the instant bound.
     */
    @Test
    @DisplayName("listInForceAt(): delegates to the ordered Panache query")
    void listInForceAtDelegates() {
        LocalDateTime at = LocalDateTime.of(2026, 6, 15, 0, 0);
        List<FidelityRule> rules = List.of(new FidelityRule());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(
                            "active = true and validFrom <= ?1 and (validTo is null or validTo > ?1) order by priority desc",
                            at))
                    .thenReturn(rules);
            assertSame(rules, FidelityRule.listInForceAt(at));
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The checksum is a pure function of the backbone: two rules with identical fields share it.
     */
    @Test
    @DisplayName("getChecksum(): identical backbones share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * A different code changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a code change alters the checksum")
    void checksumChangesWithCode() {
        FidelityRule other = sample();
        other.code = "OTHER";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different type changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a type change alters the checksum")
    void checksumChangesWithType() {
        FidelityRule other = sample();
        other.type = FidelityRule.TYPE_MONTHLY_DATE_EARN;
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different label changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a label change alters the checksum")
    void checksumChangesWithLabel() {
        FidelityRule other = sample();
        other.label = "Renamed";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different specification changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a specification change alters the checksum")
    void checksumChangesWithSpecification() {
        FidelityRule other = sample();
        other.specification = "{\"communityCode\":\"STUDENTS\"}";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different validFrom changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a validFrom change alters the checksum")
    void checksumChangesWithValidFrom() {
        FidelityRule other = sample();
        other.validFrom = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different validTo changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a validTo change alters the checksum")
    void checksumChangesWithValidTo() {
        FidelityRule other = sample();
        other.validTo = LocalDateTime.of(2027, 1, 1, 0, 0);
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different priority changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): a priority change alters the checksum")
    void checksumChangesWithPriority() {
        FidelityRule other = sample();
        other.priority = 99;
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Toggling the exclusive flag changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): an exclusive-flag change alters the checksum")
    void checksumChangesWithExclusive() {
        FidelityRule other = sample();
        other.exclusive = false;
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * A different monthly cap changes the checksum; the {@link BigDecimal} field participates in the
     * hash like any other backbone attribute.
     */
    @Test
    @DisplayName("getChecksum(): a monthly-cap change alters the checksum")
    void checksumChangesWithMonthlyCap() {
        FidelityRule other = sample();
        other.monthlyCapPerCard = new BigDecimal("20.00");
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    /**
     * Toggling the active flag changes the checksum.
     */
    @Test
    @DisplayName("getChecksum(): an active-flag change alters the checksum")
    void checksumChangesWithActive() {
        FidelityRule other = sample();
        other.active = false;
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a rule with a closed June-2026 window and the given active flag for the in-force tests.
     *
     * @param active Whether the rule is active.
     * @return A windowed rule.
     */
    private FidelityRule windowed(boolean active) {
        FidelityRule rule = new FidelityRule();
        rule.active = active;
        rule.validFrom = LocalDateTime.of(2026, 6, 1, 0, 0);
        rule.validTo = LocalDateTime.of(2026, 7, 1, 0, 0);
        return rule;
    }

    /**
     * Builds a fully-populated rule with fixed backbone fields for checksum assertions.
     *
     * @return A sample rule with deterministic attributes.
     */
    private FidelityRule sample() {
        FidelityRule rule = new FidelityRule();
        rule.code = "EARN-BABIES";
        rule.type = FidelityRule.TYPE_COMMUNITY_EARN;
        rule.label = "Babies earn";
        rule.specification = "{\"communityCode\":\"BABIES\"}";
        rule.validFrom = LocalDateTime.of(2026, 6, 1, 0, 0);
        rule.validTo = LocalDateTime.of(2026, 7, 1, 0, 0);
        rule.priority = 10;
        rule.exclusive = true;
        rule.monthlyCapPerCard = new BigDecimal("15.00");
        rule.active = true;
        return rule;
    }
}
