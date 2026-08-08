package com.intermarche.fidelity.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link CardContext}: the two constructors, the defensive
 * unmodifiable copies and null-as-zero readings (§31.2), and every accessor guard —
 * both arms (null and non-null) and each leg of every compound guard (§29, §29.6).
 * <p>
 * The context is pure logic joined to a fixed fiscal instant (§15, §31.1): no Panache
 * finder, no clock read. The evaluation instant is a fixed constant at the program zone,
 * so the derived evaluation date and the activation window predicates are deterministic
 * and never read from the real clock (§24.6, §30.3). BigDecimal cumulatives are asserted
 * by {@code compareTo} (never {@code equals}) to the euro cent.
 */
class CardContextTest {

    /**
     * The fixed fiscal evaluation instant at the program zone (mid-month).
     */
    private static final LocalDateTime EVAL_INSTANT = LocalDateTime.of(2026, 3, 15, 10, 0);

    /**
     * The evaluation date derived from {@link #EVAL_INSTANT}.
     */
    private static final LocalDate EVAL_DATE = LocalDate.of(2026, 3, 15);

    /**
     * The resolved account shared across the fixtures.
     */
    private FidelityAccount account;

    /**
     * Builds a fresh resolved account before each test.
     */
    @BeforeEach
    void setUp() {
        account = new FidelityAccount();
        account.cardNumber = "CARD-1";
    }

    /**
     * Builds a card activation for a rule over a period at the program zone.
     *
     * @param ruleCode    The rule code carried by the activation.
     * @param periodStart The inclusive activation start.
     * @param periodEnd   The inclusive activation end, or null while open.
     * @return The built activation.
     */
    private FidelityActivation activation(String ruleCode, LocalDate periodStart, LocalDate periodEnd) {
        FidelityActivation activation = new FidelityActivation();
        activation.ruleCode = ruleCode;
        activation.periodStart = periodStart;
        activation.periodEnd = periodEnd;
        return activation;
    }

    /**
     * The full constructor keeps a positive visit count and copies every cumulative
     * verbatim, deriving the evaluation date from the instant.
     */
    @Test
    @DisplayName("full constructor keeps positive visits and copies cumulatives")
    void fullConstructorPopulatesFields() {
        Map<String, BigDecimal> ruleEarn = Map.of("R1", new BigDecimal("12.00"));
        Map<String, BigDecimal> communityEarn = Map.of("C1", new BigDecimal("5.00"));
        Map<String, BigDecimal> challengeBase = Map.of("CH1", new BigDecimal("40.00"));
        Set<String> memberships = Set.of("C1");
        List<FidelityActivation> activations = List.of(activation("R1", EVAL_DATE, null));
        CardContext context = new CardContext(account, 3, ruleEarn, communityEarn,
                new BigDecimal("17.00"), challengeBase, memberships, activations, EVAL_INSTANT);
        assertSame(account, context.account);
        assertEquals(3, context.monthlyVisits);
        assertEquals(0, new BigDecimal("12.00").compareTo(context.ruleMonthlyEarn.get("R1")));
        assertEquals(0, new BigDecimal("5.00").compareTo(context.communityMonthlyEarn.get("C1")));
        assertEquals(0, new BigDecimal("17.00").compareTo(context.globalMonthlyEarn));
        assertEquals(0, new BigDecimal("40.00").compareTo(context.challengePeriodBase.get("CH1")));
        assertTrue(context.membershipCommunityCodes.contains("C1"));
        assertEquals(1, context.activations.size());
        assertEquals(EVAL_DATE, context.evaluationDate);
        assertSame(EVAL_INSTANT, context.evaluationDateTime);
    }

    /**
     * A negative visit count is clamped to zero (the {@code Math.max} guard, negative arm).
     */
    @Test
    @DisplayName("negative visits clamp to zero")
    void negativeVisitsClampToZero() {
        CardContext context = new CardContext(account, -4, null, null, null, null, null, null, EVAL_INSTANT);
        assertEquals(0, context.monthlyVisits);
    }

    /**
     * A zero visit count is kept as zero (the {@code Math.max} guard, non-negative arm).
     */
    @Test
    @DisplayName("zero visits stay zero")
    void zeroVisitsStayZero() {
        CardContext context = new CardContext(account, 0, null, null, null, null, null, null, EVAL_INSTANT);
        assertEquals(0, context.monthlyVisits);
    }

    /**
     * Null collections and a null global cumulative are read as empty and zero — the null
     * arm of every ternary (§31.2).
     */
    @Test
    @DisplayName("null cumulatives read as empty and zero")
    void nullCumulativesReadAsEmpty() {
        CardContext context = new CardContext(account, 2, null, null, null, null, null, null, EVAL_INSTANT);
        assertTrue(context.ruleMonthlyEarn.isEmpty());
        assertTrue(context.communityMonthlyEarn.isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(context.globalMonthlyEarn));
        assertTrue(context.challengePeriodBase.isEmpty());
        assertTrue(context.membershipCommunityCodes.isEmpty());
        assertTrue(context.activations.isEmpty());
    }

    /**
     * The collection copies are defensive: mutating the source maps, set and list after
     * construction does not change the context.
     */
    @Test
    @DisplayName("collections are defensive copies")
    void collectionsAreDefensiveCopies() {
        Map<String, BigDecimal> ruleEarn = new HashMap<>();
        ruleEarn.put("R1", new BigDecimal("1.00"));
        Map<String, BigDecimal> communityEarn = new HashMap<>();
        communityEarn.put("C1", new BigDecimal("2.00"));
        Map<String, BigDecimal> challengeBase = new HashMap<>();
        challengeBase.put("CH1", new BigDecimal("3.00"));
        Set<String> memberships = new HashSet<>();
        memberships.add("C1");
        List<FidelityActivation> activations = new ArrayList<>();
        activations.add(activation("R1", EVAL_DATE, null));
        CardContext context = new CardContext(account, 1, ruleEarn, communityEarn,
                new BigDecimal("9.00"), challengeBase, memberships, activations, EVAL_INSTANT);
        ruleEarn.put("R2", new BigDecimal("99.00"));
        communityEarn.put("C2", new BigDecimal("99.00"));
        challengeBase.put("CH2", new BigDecimal("99.00"));
        memberships.add("C2");
        activations.add(activation("R2", EVAL_DATE, null));
        assertEquals(1, context.ruleMonthlyEarn.size());
        assertEquals(1, context.communityMonthlyEarn.size());
        assertEquals(1, context.challengePeriodBase.size());
        assertEquals(1, context.membershipCommunityCodes.size());
        assertEquals(1, context.activations.size());
    }

    /**
     * The convenience constructor delegates to a bare context with no cumulatives,
     * memberships or activations, keeping the same instant.
     */
    @Test
    @DisplayName("convenience constructor builds a bare context")
    void convenienceConstructorBuildsBareContext() {
        CardContext context = new CardContext(account, EVAL_INSTANT);
        assertSame(account, context.account);
        assertEquals(0, context.monthlyVisits);
        assertTrue(context.ruleMonthlyEarn.isEmpty());
        assertTrue(context.communityMonthlyEarn.isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(context.globalMonthlyEarn));
        assertTrue(context.challengePeriodBase.isEmpty());
        assertTrue(context.membershipCommunityCodes.isEmpty());
        assertTrue(context.activations.isEmpty());
        assertEquals(EVAL_DATE, context.evaluationDate);
        assertSame(EVAL_INSTANT, context.evaluationDateTime);
    }

    /**
     * A null account is rejected by the constructor guard (true arm).
     */
    @Test
    @DisplayName("null account is rejected")
    void nullAccountRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CardContext(null, EVAL_INSTANT));
        assertEquals("account is mandatory", error.getMessage());
    }

    /**
     * A null evaluation instant is rejected by the constructor guard (true arm), the
     * account being present so the first guard passes (its false arm).
     */
    @Test
    @DisplayName("null evaluation instant is rejected")
    void nullInstantRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CardContext(account, null));
        assertEquals("evaluationDateTime is mandatory", error.getMessage());
    }

    /**
     * isMemberOf returns false for a null code (first leg false).
     */
    @Test
    @DisplayName("isMemberOf is false for a null code")
    void isMemberOfNullCode() {
        CardContext context = new CardContext(account, 0, null, null, null, null, Set.of("C1"), null, EVAL_INSTANT);
        assertFalse(context.isMemberOf(null));
    }

    /**
     * isMemberOf returns false for a non-member code (second leg false).
     */
    @Test
    @DisplayName("isMemberOf is false for a non-member code")
    void isMemberOfNonMember() {
        CardContext context = new CardContext(account, 0, null, null, null, null, Set.of("C1"), null, EVAL_INSTANT);
        assertFalse(context.isMemberOf("C2"));
    }

    /**
     * isMemberOf returns true when the card belongs to the community (both legs true).
     */
    @Test
    @DisplayName("isMemberOf is true for a member code")
    void isMemberOfMember() {
        CardContext context = new CardContext(account, 0, null, null, null, null, Set.of("C1"), null, EVAL_INSTANT);
        assertTrue(context.isMemberOf("C1"));
    }

    /**
     * activationFor returns null for a null rule code (early-return guard, true arm).
     */
    @Test
    @DisplayName("activationFor returns null for a null code")
    void activationForNullCode() {
        CardContext context = new CardContext(account, 0, null, null, null, null, null,
                List.of(activation("R1", EVAL_DATE, null)), EVAL_INSTANT);
        assertNull(context.activationFor(null));
    }

    /**
     * activationFor skips an activation whose rule code does not match (loop guard, first
     * leg false) and falls through to null.
     */
    @Test
    @DisplayName("activationFor returns null when no rule code matches")
    void activationForRuleMismatch() {
        CardContext context = new CardContext(account, 0, null, null, null, null, null,
                List.of(activation("OTHER", EVAL_DATE, null)), EVAL_INSTANT);
        assertNull(context.activationFor("R1"));
    }

    /**
     * activationFor skips a matching activation that is inactive on the evaluation date
     * (loop guard, first leg true, second leg false): the window ends the day before.
     */
    @Test
    @DisplayName("activationFor returns null when the match is inactive")
    void activationForInactiveMatch() {
        FidelityActivation ended = activation("R1", EVAL_DATE.minusDays(5), EVAL_DATE.minusDays(1));
        CardContext context = new CardContext(account, 0, null, null, null, null, null,
                List.of(ended), EVAL_INSTANT);
        assertNull(context.activationFor("R1"));
    }

    /**
     * activationFor returns the matching activation active on the evaluation date (both
     * legs true): the window opens on the very evaluation day.
     */
    @Test
    @DisplayName("activationFor returns the active matching activation")
    void activationForActiveMatch() {
        FidelityActivation active = activation("R1", EVAL_DATE, EVAL_DATE.plusDays(1));
        CardContext context = new CardContext(account, 0, null, null, null, null, null,
                List.of(activation("OTHER", EVAL_DATE, null), active), EVAL_INSTANT);
        assertSame(active, context.activationFor("R1"));
    }

    /**
     * hasEarnedThisPeriod is false for a null rule code (early-return guard, true arm).
     */
    @Test
    @DisplayName("hasEarnedThisPeriod is false for a null code")
    void hasEarnedNullCode() {
        CardContext context = new CardContext(account, 0, Map.of("R1", new BigDecimal("1.00")),
                null, null, null, null, null, EVAL_INSTANT);
        assertFalse(context.hasEarnedThisPeriod(null));
    }

    /**
     * hasEarnedThisPeriod is false when the rule has no cumulative (first leg false).
     */
    @Test
    @DisplayName("hasEarnedThisPeriod is false when absent")
    void hasEarnedAbsent() {
        CardContext context = new CardContext(account, 0, Map.of("R1", new BigDecimal("1.00")),
                null, null, null, null, null, EVAL_INSTANT);
        assertFalse(context.hasEarnedThisPeriod("R2"));
    }

    /**
     * hasEarnedThisPeriod is false when the cumulative is present but zero (second leg
     * false: {@code signum() > 0} fails at exactly zero).
     */
    @Test
    @DisplayName("hasEarnedThisPeriod is false when zero")
    void hasEarnedZero() {
        CardContext context = new CardContext(account, 0, Map.of("R1", new BigDecimal("0.00")),
                null, null, null, null, null, EVAL_INSTANT);
        assertFalse(context.hasEarnedThisPeriod("R1"));
    }

    /**
     * hasEarnedThisPeriod is true when a positive cumulative exists (both legs true).
     */
    @Test
    @DisplayName("hasEarnedThisPeriod is true when positive")
    void hasEarnedPositive() {
        CardContext context = new CardContext(account, 0, Map.of("R1", new BigDecimal("0.01")),
                null, null, null, null, null, EVAL_INSTANT);
        assertTrue(context.hasEarnedThisPeriod("R1"));
    }

    /**
     * ruleEarnSoFar returns zero for a null rule code (ternary, null arm).
     */
    @Test
    @DisplayName("ruleEarnSoFar returns zero for a null code")
    void ruleEarnNullCode() {
        CardContext context = new CardContext(account, 0, Map.of("R1", new BigDecimal("7.00")),
                null, null, null, null, null, EVAL_INSTANT);
        assertEquals(0, BigDecimal.ZERO.compareTo(context.ruleEarnSoFar(null)));
    }

    /**
     * ruleEarnSoFar returns zero when the rule is absent (second ternary, null arm).
     */
    @Test
    @DisplayName("ruleEarnSoFar returns zero when absent")
    void ruleEarnAbsent() {
        CardContext context = new CardContext(account, 0, Map.of("R1", new BigDecimal("7.00")),
                null, null, null, null, null, EVAL_INSTANT);
        assertEquals(0, BigDecimal.ZERO.compareTo(context.ruleEarnSoFar("R2")));
    }

    /**
     * ruleEarnSoFar returns the stored cumulative when present (both ternaries, non-null
     * arms).
     */
    @Test
    @DisplayName("ruleEarnSoFar returns the stored cumulative")
    void ruleEarnPresent() {
        CardContext context = new CardContext(account, 0, Map.of("R1", new BigDecimal("7.50")),
                null, null, null, null, null, EVAL_INSTANT);
        assertEquals(0, new BigDecimal("7.50").compareTo(context.ruleEarnSoFar("R1")));
    }

    /**
     * communityEarnSoFar returns zero for a null community code (ternary, null arm).
     */
    @Test
    @DisplayName("communityEarnSoFar returns zero for a null code")
    void communityEarnNullCode() {
        CardContext context = new CardContext(account, 0, null,
                Map.of("C1", new BigDecimal("4.00")), null, null, null, null, EVAL_INSTANT);
        assertEquals(0, BigDecimal.ZERO.compareTo(context.communityEarnSoFar(null)));
    }

    /**
     * communityEarnSoFar returns zero when the community is absent (second ternary, null
     * arm).
     */
    @Test
    @DisplayName("communityEarnSoFar returns zero when absent")
    void communityEarnAbsent() {
        CardContext context = new CardContext(account, 0, null,
                Map.of("C1", new BigDecimal("4.00")), null, null, null, null, EVAL_INSTANT);
        assertEquals(0, BigDecimal.ZERO.compareTo(context.communityEarnSoFar("C2")));
    }

    /**
     * communityEarnSoFar returns the stored cumulative when present (both ternaries,
     * non-null arms).
     */
    @Test
    @DisplayName("communityEarnSoFar returns the stored cumulative")
    void communityEarnPresent() {
        CardContext context = new CardContext(account, 0, null,
                Map.of("C1", new BigDecimal("4.25")), null, null, null, null, EVAL_INSTANT);
        assertEquals(0, new BigDecimal("4.25").compareTo(context.communityEarnSoFar("C1")));
    }

    /**
     * challengeBaseSoFar returns zero for a null challenge code (ternary, null arm).
     */
    @Test
    @DisplayName("challengeBaseSoFar returns zero for a null code")
    void challengeBaseNullCode() {
        CardContext context = new CardContext(account, 0, null, null, null,
                Map.of("CH1", new BigDecimal("30.00")), null, null, EVAL_INSTANT);
        assertEquals(0, BigDecimal.ZERO.compareTo(context.challengeBaseSoFar(null)));
    }

    /**
     * challengeBaseSoFar returns zero when the challenge is absent (second ternary, null
     * arm).
     */
    @Test
    @DisplayName("challengeBaseSoFar returns zero when absent")
    void challengeBaseAbsent() {
        CardContext context = new CardContext(account, 0, null, null, null,
                Map.of("CH1", new BigDecimal("30.00")), null, null, EVAL_INSTANT);
        assertEquals(0, BigDecimal.ZERO.compareTo(context.challengeBaseSoFar("CH2")));
    }

    /**
     * challengeBaseSoFar returns the running base when present (both ternaries, non-null
     * arms).
     */
    @Test
    @DisplayName("challengeBaseSoFar returns the running base")
    void challengeBasePresent() {
        CardContext context = new CardContext(account, 0, null, null, null,
                Map.of("CH1", new BigDecimal("30.75")), null, null, EVAL_INSTANT);
        assertEquals(0, new BigDecimal("30.75").compareTo(context.challengeBaseSoFar("CH1")));
    }
}
