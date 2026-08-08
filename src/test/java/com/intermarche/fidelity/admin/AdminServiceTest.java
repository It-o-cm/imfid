package com.intermarche.fidelity.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.account.LedgerService;
import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.ReservationState;
import com.intermarche.fidelity.domain.util.CardNumberGenerator;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import com.intermarche.fidelity.domain.util.ProgramClock;
import com.intermarche.fidelity.rule.EarnRuleRegistry;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link AdminService}: the administration write service (§18,
 * §26.5, §28, §32.1). Every leg of every guard of the rule mutations (unknown type,
 * invalid specification, mandatory {@code validFrom}, the window-overlap matrix, the
 * not-yet-in-force edit gate, the frozen-past bounds), of the card gestures (mandatory
 * adjustment reason and amount, the active-lease refusal, the earnYear-preserving
 * transfer, §34.3), and of the community/membership/activation/setting upserts (the
 * enrollment cap §28.4, deletion by omission §21.3, the closed-to-enrollment guard) is
 * exercised.
 * <p>
 * Fully isolated: {@link LedgerService}, {@link ProgramClock}, {@link CardNumberGenerator}
 * and {@link EarnRuleRegistry} are Mockito mocks injected into the package-private fields.
 * The Panache inherited finders ({@code find}/{@code list}/{@code count}/{@code delete}/
 * {@code update}) are intercepted with {@code mockStatic(PanacheEntityBase.class)} in
 * try-with-resources; the entity-declared finders that pass through them
 * ({@code findByCode}, {@code findByCardNumber}, {@code findByKey},
 * {@code findActiveForAccount}, {@code listForAccount}) resolve against those stubs, while
 * the entity-manager aggregations {@link FidelityMovement#creditsByEarnYear} and
 * {@link FidelityMovement#totalDebits} are stubbed with {@code mockStatic(FidelityMovement)}.
 * Every {@code new Entity()} the write paths perform is neutralized with
 * {@code mockConstruction}. The clock is fixed through the mocked {@link ProgramClock} —
 * every time-driven decision reads {@link #FIXED}/{@link #TODAY}, never the campaign day
 * (§24.6, §30.3, §25.1). Every {@code BigDecimal} is asserted by {@code compareTo}, to the
 * cent (§30.5).
 */
class AdminServiceTest {

    /**
     * The fixed program wall time every clock read resolves to (§24.6).
     */
    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 3, 15, 10, 0);

    /**
     * The fixed program fiscal day matching {@link #FIXED} (§30.3).
     */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    /**
     * The system under test, freshly built per test with mocked collaborators.
     */
    private AdminService service;

    /**
     * The mocked rule registry validating the type and specification (§12).
     */
    private EarnRuleRegistry registry;

    /**
     * The mocked ledger holding the lock and posting the movements (§30.1).
     */
    private LedgerService ledger;

    /**
     * The mocked program clock resolving the fiscal day and rule windows.
     */
    private ProgramClock clock;

    /**
     * The mocked card number generator (§33.1).
     */
    private CardNumberGenerator cardNumbers;

    /**
     * Wires a fresh service with its mocked collaborators and fixes the clock before each test.
     */
    @BeforeEach
    void setUp() {
        service = new AdminService();
        registry = Mockito.mock(EarnRuleRegistry.class);
        ledger = Mockito.mock(LedgerService.class);
        clock = Mockito.mock(ProgramClock.class);
        cardNumbers = Mockito.mock(CardNumberGenerator.class);
        service.registry = registry;
        service.ledger = ledger;
        service.clock = clock;
        service.cardNumbers = cardNumbers;
        Mockito.when(clock.now()).thenReturn(FIXED);
        Mockito.when(clock.today()).thenReturn(TODAY);
        DateTimeProvider.setFixedDateTime(FIXED);
    }

    /**
     * Clears the fixed clock after each test so no fixation leaks across the campaign (§24.6).
     */
    @AfterEach
    void tearDown() {
        DateTimeProvider.clear();
    }

    // --------------------------------------------------
    // Fixtures
    // --------------------------------------------------

    /**
     * Builds a fidelity account mock carrying a card number, a status and a balance.
     *
     * @param card    The card number.
     * @param status  The account status.
     * @param balance The materialized balance, euro at scale 2.
     * @return The account mock (its {@code persist()} is a no-op).
     */
    private FidelityAccount account(String card, AccountStatus status, String balance) {
        FidelityAccount account = Mockito.mock(FidelityAccount.class);
        account.cardNumber = card;
        account.status = status;
        account.balance = new BigDecimal(balance);
        return account;
    }

    /**
     * Builds a rule mock with an id, code and window, so its {@code persist()} is a no-op.
     *
     * @param id   The identity used by the same-instance guards.
     * @param code The rule code.
     * @param from The window start.
     * @param to   The window end, or null.
     * @return The rule mock.
     */
    private FidelityRule rule(Long id, String code, LocalDateTime from, LocalDateTime to) {
        FidelityRule rule = Mockito.mock(FidelityRule.class);
        rule.id = id;
        rule.code = code;
        rule.validFrom = from;
        rule.validTo = to;
        return rule;
    }

    /**
     * Builds a community mock carrying its editable parameters.
     *
     * @param code   The community code.
     * @param active Whether the community accepts new enrollments.
     * @param cap    The enrollment cap, or null.
     * @return The community mock.
     */
    private FidelityCommunity community(String code, boolean active, Integer cap) {
        FidelityCommunity community = Mockito.mock(FidelityCommunity.class);
        community.code = code;
        community.active = active;
        community.enrollmentCap = cap;
        return community;
    }

    /**
     * Wraps a value in a {@link PanacheQuery} mock whose {@code firstResult()} returns it.
     *
     * @param value The value the query resolves to, or null.
     * @param <T>   The entity type.
     * @return The query mock.
     */
    private <T> PanacheQuery<T> queryOf(T value) {
        @SuppressWarnings("unchecked")
        PanacheQuery<T> query = (PanacheQuery<T>) Mockito.mock(PanacheQuery.class,
                invocation -> "firstResult".equals(invocation.getMethod().getName())
                        ? value : Mockito.RETURNS_DEFAULTS.answer(invocation));
        return query;
    }

    /**
     * Builds a membership entry for the workbench submission.
     *
     * @param card The card number, or null.
     * @param from The window start, or null.
     * @param to   The window end, or null.
     * @return The membership input.
     */
    private MembershipInput input(String card, LocalDate from, LocalDate to) {
        MembershipInput entry = new MembershipInput();
        entry.card = card;
        entry.validFrom = from;
        entry.validTo = to;
        return entry;
    }

    // --------------------------------------------------
    // createRule (§18)
    // --------------------------------------------------

    /**
     * A null code fails the {@code value == null} leg of {@code requireText} (§18).
     */
    @Test
    @DisplayName("createRule(): a null code is refused")
    void createRuleNullCode() {
        assertThrows(AdminException.class, () -> service.createRule(
                null, "T", "L", FIXED, null, 0, false, null, true, "{}"));
    }

    /**
     * A blank type fails the {@code value.isBlank()} leg of {@code requireText} (§18).
     */
    @Test
    @DisplayName("createRule(): a blank type is refused")
    void createRuleBlankType() {
        assertThrows(AdminException.class, () -> service.createRule(
                "R", " ", "L", FIXED, null, 0, false, null, true, "{}"));
    }

    /**
     * A type with no deployed factory fails {@code !registry.hasFactory} (§12).
     */
    @Test
    @DisplayName("createRule(): an unknown type is refused")
    void createRuleUnknownType() {
        Mockito.when(registry.hasFactory("T")).thenReturn(false);
        assertThrows(AdminException.class, () -> service.createRule(
                "R", "T", "L", FIXED, null, 0, false, null, true, "{}"));
    }

    /**
     * A specification with violations fails {@code !violations.isEmpty()} (§12).
     */
    @Test
    @DisplayName("createRule(): an invalid specification is refused")
    void createRuleInvalidSpec() {
        Mockito.when(registry.hasFactory("T")).thenReturn(true);
        Mockito.when(registry.validate("T", "{}")).thenReturn(List.of("boom"));
        assertThrows(AdminException.class, () -> service.createRule(
                "R", "T", "L", FIXED, null, 0, false, null, true, "{}"));
    }

    /**
     * A null {@code validFrom} is refused after a valid type and specification (§18).
     */
    @Test
    @DisplayName("createRule(): a null validFrom is refused")
    void createRuleNullValidFrom() {
        Mockito.when(registry.hasFactory("T")).thenReturn(true);
        Mockito.when(registry.validate("T", "{}")).thenReturn(List.of());
        assertThrows(AdminException.class, () -> service.createRule(
                "R", "T", "L", null, null, 0, false, null, true, "{}"));
    }

    /**
     * A first instance of a code, with no existing rule, is created and persisted (§18).
     */
    @Test
    @DisplayName("createRule(): a first instance is created")
    void createRuleFirstInstance() {
        Mockito.when(registry.hasFactory("T")).thenReturn(true);
        Mockito.when(registry.validate("T", "{}")).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityRule> cons = Mockito.mockConstruction(FidelityRule.class)) {
            panache.when(() -> PanacheEntityBase.list("code", "R")).thenReturn(List.of());
            FidelityRule created = service.createRule(
                    "R", "T", "L", FIXED, FIXED.plusDays(1), 5, true, new BigDecimal("2.00"), true, "{}");
            assertSame(cons.constructed().get(0), created);
            assertEquals("R", created.code);
            assertEquals("T", created.type);
            assertEquals("L", created.label);
            assertEquals(FIXED, created.validFrom);
            assertEquals(FIXED.plusDays(1), created.validTo);
            assertEquals(5, created.priority);
            assertTrue(created.exclusive);
            assertEquals(0, created.monthlyCapPerCard.compareTo(new BigDecimal("2.00")));
            assertTrue(created.active);
        }
    }

    /**
     * Two open windows (both {@code validTo} null) overlap — the {@code aTo == null} and
     * {@code bTo == null} legs both false-arm, so the {@code ||} returns true and it is
     * refused (§18).
     */
    @Test
    @DisplayName("createRule(): two open windows overlap and are refused")
    void createRuleOverlapBothOpen() {
        Mockito.when(registry.hasFactory("T")).thenReturn(true);
        Mockito.when(registry.validate("T", "{}")).thenReturn(List.of());
        FidelityRule existing = rule(1L, "R", FIXED.minusDays(5), null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("code", "R")).thenReturn(List.of(existing));
            assertThrows(AdminException.class, () -> service.createRule(
                    "R", "T", "L", FIXED, null, 0, false, null, true, "{}"));
        }
    }

    /**
     * A new window ending at or before the existing start does not overlap — the
     * {@code aTo != null} true and {@code !aTo.isAfter(bFrom)} true legs (aBeforeB), so
     * creation succeeds (§18).
     */
    @Test
    @DisplayName("createRule(): a window ending before the existing start is accepted")
    void createRuleNewEndsBeforeExistingStart() {
        Mockito.when(registry.hasFactory("T")).thenReturn(true);
        Mockito.when(registry.validate("T", "{}")).thenReturn(List.of());
        FidelityRule existing = rule(1L, "R", FIXED, null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityRule> cons = Mockito.mockConstruction(FidelityRule.class)) {
            panache.when(() -> PanacheEntityBase.list("code", "R")).thenReturn(List.of(existing));
            FidelityRule created = service.createRule(
                    "R", "T", "L", FIXED.minusDays(5), FIXED, 0, false, null, true, "{}");
            assertSame(cons.constructed().get(0), created);
        }
    }

    /**
     * An existing window ending at or before the new start does not overlap — the
     * {@code !aTo.isAfter(bFrom)} false leg then the {@code bTo != null} true and
     * {@code !bTo.isAfter(aFrom)} true legs (bBeforeA), so creation succeeds (§18).
     */
    @Test
    @DisplayName("createRule(): an existing window ending before the new start is accepted")
    void createRuleExistingEndsBeforeNewStart() {
        Mockito.when(registry.hasFactory("T")).thenReturn(true);
        Mockito.when(registry.validate("T", "{}")).thenReturn(List.of());
        FidelityRule existing = rule(1L, "R", FIXED.minusDays(10), FIXED.minusDays(5));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityRule> cons = Mockito.mockConstruction(FidelityRule.class)) {
            panache.when(() -> PanacheEntityBase.list("code", "R")).thenReturn(List.of(existing));
            FidelityRule created = service.createRule(
                    "R", "T", "L", FIXED, FIXED.plusDays(5), 0, false, null, true, "{}");
            assertSame(cons.constructed().get(0), created);
        }
    }

    /**
     * Two strictly interleaved closed windows overlap — the {@code !bTo.isAfter(aFrom)}
     * false leg with aBeforeB and bBeforeA both false, so the {@code ||} returns true and
     * it is refused (§18).
     */
    @Test
    @DisplayName("createRule(): two interleaved closed windows overlap and are refused")
    void createRuleOverlapClosedWindows() {
        Mockito.when(registry.hasFactory("T")).thenReturn(true);
        Mockito.when(registry.validate("T", "{}")).thenReturn(List.of());
        FidelityRule existing = rule(1L, "R", FIXED.plusDays(10), FIXED.plusDays(30));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("code", "R")).thenReturn(List.of(existing));
            assertThrows(AdminException.class, () -> service.createRule(
                    "R", "T", "L", FIXED, FIXED.plusDays(20), 0, false, null, true, "{}"));
        }
    }

    // --------------------------------------------------
    // updateRule (§18, §23.3)
    // --------------------------------------------------

    /**
     * An unknown code is refused — the {@code rule == null} arm (§18).
     */
    @Test
    @DisplayName("updateRule(): an unknown code is refused")
    void updateRuleUnknownCode() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "R")).thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.updateRule(
                    "R", "T", "L", FIXED.plusDays(5), null, 0, false, null, true, "{}"));
        }
    }

    /**
     * A rule already in force at the boundary (now equals {@code validFrom}) is refused —
     * the {@code !now.isBefore(validFrom)} true arm (§18, §24.6).
     */
    @Test
    @DisplayName("updateRule(): a rule in force at the boundary is refused")
    void updateRuleInForceBoundary() {
        FidelityRule rule = rule(1L, "R", FIXED, null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "R")).thenReturn(queryOf(rule));
            assertThrows(AdminException.class, () -> service.updateRule(
                    "R", "T", "L", FIXED.plusDays(5), null, 0, false, null, true, "{}"));
        }
    }

    /**
     * An upcoming rule with an unknown type is refused — the {@code now.isBefore} true
     * arm then {@code !registry.hasFactory} (§12).
     */
    @Test
    @DisplayName("updateRule(): an upcoming rule with an unknown type is refused")
    void updateRuleUnknownType() {
        FidelityRule rule = rule(1L, "R", FIXED.plusSeconds(1), null);
        Mockito.when(registry.hasFactory("T")).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "R")).thenReturn(queryOf(rule));
            assertThrows(AdminException.class, () -> service.updateRule(
                    "R", "T", "L", FIXED.plusDays(5), null, 0, false, null, true, "{}"));
        }
    }

    /**
     * An upcoming rule with an invalid specification is refused (§12).
     */
    @Test
    @DisplayName("updateRule(): an invalid specification is refused")
    void updateRuleInvalidSpec() {
        FidelityRule rule = rule(1L, "R", FIXED.plusSeconds(1), null);
        Mockito.when(registry.hasFactory("T")).thenReturn(true);
        Mockito.when(registry.validate("T", "{}")).thenReturn(List.of("boom"));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "R")).thenReturn(queryOf(rule));
            assertThrows(AdminException.class, () -> service.updateRule(
                    "R", "T", "L", FIXED.plusDays(5), null, 0, false, null, true, "{}"));
        }
    }

    /**
     * A null {@code validFrom} is refused after the valid type and specification (§18).
     */
    @Test
    @DisplayName("updateRule(): a null validFrom is refused")
    void updateRuleNullValidFrom() {
        FidelityRule rule = rule(1L, "R", FIXED.plusSeconds(1), null);
        Mockito.when(registry.hasFactory("T")).thenReturn(true);
        Mockito.when(registry.validate("T", "{}")).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "R")).thenReturn(queryOf(rule));
            assertThrows(AdminException.class, () -> service.updateRule(
                    "R", "T", "L", null, null, 0, false, null, true, "{}"));
        }
    }

    /**
     * A new {@code validFrom} the day before today is refused — the
     * {@code validFrom.toLocalDate().isBefore(today)} true arm (§18, §24.6).
     */
    @Test
    @DisplayName("updateRule(): a validFrom in the past is refused")
    void updateRuleValidFromInPast() {
        FidelityRule rule = rule(1L, "R", FIXED.plusDays(5), null);
        Mockito.when(registry.hasFactory("T")).thenReturn(true);
        Mockito.when(registry.validate("T", "{}")).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "R")).thenReturn(queryOf(rule));
            assertThrows(AdminException.class, () -> service.updateRule(
                    "R", "T", "L", TODAY.minusDays(1).atStartOfDay(), null, 0, false, null, true, "{}"));
        }
    }

    /**
     * A different overlapping instance is refused — the {@code !existing.id.equals(rule.id)}
     * true leg and {@code windowsOverlap} true leg of the {@code &&} (§18).
     */
    @Test
    @DisplayName("updateRule(): a different overlapping instance is refused")
    void updateRuleOverlapOtherInstance() {
        FidelityRule rule = rule(1L, "R", FIXED.plusDays(5), null);
        FidelityRule other = rule(2L, "R", FIXED.plusDays(1), null);
        Mockito.when(registry.hasFactory("T")).thenReturn(true);
        Mockito.when(registry.validate("T", "{}")).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "R")).thenReturn(queryOf(rule));
            panache.when(() -> PanacheEntityBase.list("code", "R")).thenReturn(List.of(other));
            assertThrows(AdminException.class, () -> service.updateRule(
                    "R", "T", "L", TODAY.atStartOfDay(), null, 0, false, null, true, "{}"));
        }
    }

    /**
     * The rule's own instance is skipped by the {@code !existing.id.equals(rule.id)} false
     * leg, so the update through the same identity succeeds (§18).
     */
    @Test
    @DisplayName("updateRule(): the rule's own instance is skipped and the update succeeds")
    void updateRuleSameInstanceSkipped() {
        FidelityRule rule = rule(1L, "R", FIXED.plusDays(5), null);
        Mockito.when(registry.hasFactory("T")).thenReturn(true);
        Mockito.when(registry.validate("T", "{}")).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "R")).thenReturn(queryOf(rule));
            panache.when(() -> PanacheEntityBase.list("code", "R")).thenReturn(List.of(rule));
            FidelityRule updated = service.updateRule(
                    "R", "T", "L2", TODAY.atStartOfDay(), FIXED.plusDays(20), 9, true,
                    new BigDecimal("3.00"), false, "{}");
            assertSame(rule, updated);
            assertEquals("T", updated.type);
            assertEquals("L2", updated.label);
            assertEquals(TODAY.atStartOfDay(), updated.validFrom);
            assertEquals(9, updated.priority);
            assertTrue(updated.exclusive);
            assertEquals(0, updated.monthlyCapPerCard.compareTo(new BigDecimal("3.00")));
        }
    }

    /**
     * A different non-overlapping instance passes — the {@code !existing.id.equals} true
     * leg with {@code windowsOverlap} false leg, so the update succeeds (§18).
     */
    @Test
    @DisplayName("updateRule(): a different non-overlapping instance is accepted")
    void updateRuleDifferentNonOverlapping() {
        FidelityRule rule = rule(1L, "R", FIXED.plusDays(5), null);
        FidelityRule other = rule(2L, "R", FIXED.minusDays(10), FIXED.minusDays(5));
        Mockito.when(registry.hasFactory("T")).thenReturn(true);
        Mockito.when(registry.validate("T", "{}")).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "R")).thenReturn(queryOf(rule));
            panache.when(() -> PanacheEntityBase.list("code", "R")).thenReturn(List.of(other));
            FidelityRule updated = service.updateRule(
                    "R", "T", "L", TODAY.atStartOfDay(), FIXED.plusDays(20), 0, false, null, true, "{}");
            assertSame(rule, updated);
        }
    }

    // --------------------------------------------------
    // updateEndDate (§18)
    // --------------------------------------------------

    /**
     * No open rule of the code is refused — the {@code rule == null} arm (§18).
     */
    @Test
    @DisplayName("updateEndDate(): no open rule is refused")
    void updateEndDateNoOpenRule() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "code = ?1 and (validTo is null or validTo > ?2) order by validFrom desc",
                    "R", TODAY.atStartOfDay())).thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.updateEndDate("R", FIXED.plusDays(5)));
        }
    }

    /**
     * A non-null end the day before today is refused — the {@code validTo != null} true and
     * {@code isBefore(today)} true legs (§18, §24.6).
     */
    @Test
    @DisplayName("updateEndDate(): an end in the past is refused")
    void updateEndDateInPast() {
        FidelityRule rule = rule(1L, "R", FIXED.minusDays(5), null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "code = ?1 and (validTo is null or validTo > ?2) order by validFrom desc",
                    "R", TODAY.atStartOfDay())).thenReturn(queryOf(rule));
            assertThrows(AdminException.class, () -> service.updateEndDate(
                    "R", TODAY.minusDays(1).atStartOfDay()));
        }
    }

    /**
     * A null end clears the window and skips the past-check — the {@code validTo != null}
     * false leg — and the rule's own instance is skipped, so it succeeds (§18).
     */
    @Test
    @DisplayName("updateEndDate(): a null end reopens the window")
    void updateEndDateNullEnd() {
        FidelityRule rule = rule(1L, "R", FIXED.minusDays(5), FIXED.plusDays(5));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "code = ?1 and (validTo is null or validTo > ?2) order by validFrom desc",
                    "R", TODAY.atStartOfDay())).thenReturn(queryOf(rule));
            panache.when(() -> PanacheEntityBase.list("code", "R")).thenReturn(List.of(rule));
            FidelityRule updated = service.updateEndDate("R", null);
            assertSame(rule, updated);
            assertNull(updated.validTo);
        }
    }

    /**
     * A new end overlapping another instance is refused — the {@code !existing.id.equals}
     * true leg with {@code windowsOverlap} true leg (§18).
     */
    @Test
    @DisplayName("updateEndDate(): an end overlapping another instance is refused")
    void updateEndDateOverlapOther() {
        FidelityRule rule = rule(1L, "R", FIXED.minusDays(5), FIXED.plusDays(2));
        FidelityRule other = rule(2L, "R", FIXED.plusDays(10), null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "code = ?1 and (validTo is null or validTo > ?2) order by validFrom desc",
                    "R", TODAY.atStartOfDay())).thenReturn(queryOf(rule));
            panache.when(() -> PanacheEntityBase.list("code", "R")).thenReturn(List.of(other));
            assertThrows(AdminException.class, () -> service.updateEndDate("R", FIXED.plusDays(20)));
        }
    }

    /**
     * A new end at today (the boundary, not in the past) not overlapping a different
     * instance succeeds — the {@code isBefore(today)} false leg and the {@code windowsOverlap}
     * false leg (§18).
     */
    @Test
    @DisplayName("updateEndDate(): an end at today not overlapping is accepted")
    void updateEndDateAcceptedBoundary() {
        FidelityRule rule = rule(1L, "R", FIXED.minusDays(10), FIXED.minusDays(2));
        FidelityRule other = rule(2L, "R", FIXED.plusDays(10), null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "code = ?1 and (validTo is null or validTo > ?2) order by validFrom desc",
                    "R", TODAY.atStartOfDay())).thenReturn(queryOf(rule));
            panache.when(() -> PanacheEntityBase.list("code", "R")).thenReturn(List.of(other));
            FidelityRule updated = service.updateEndDate("R", TODAY.atStartOfDay());
            assertSame(rule, updated);
            assertEquals(TODAY.atStartOfDay(), updated.validTo);
        }
    }

    // --------------------------------------------------
    // closeRule (§18)
    // --------------------------------------------------

    /**
     * No open rule of the code is refused — the {@code rule == null} arm (§18).
     */
    @Test
    @DisplayName("closeRule(): no open rule is refused")
    void closeRuleNoOpenRule() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "code = ?1 and (validTo is null or validTo > ?2) order by validFrom desc",
                    "R", TODAY.atStartOfDay())).thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.closeRule("R", FIXED.plusDays(1)));
        }
    }

    /**
     * A null end defaults to today at start of day — the ternary false arm — which is not
     * in the past, so the rule is closed (§18).
     */
    @Test
    @DisplayName("closeRule(): a null end defaults to today")
    void closeRuleNullEndDefaultsToday() {
        FidelityRule rule = rule(1L, "R", FIXED.minusDays(5), null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "code = ?1 and (validTo is null or validTo > ?2) order by validFrom desc",
                    "R", TODAY.atStartOfDay())).thenReturn(queryOf(rule));
            FidelityRule closed = service.closeRule("R", null);
            assertSame(rule, closed);
            assertEquals(TODAY.atStartOfDay(), closed.validTo);
        }
    }

    /**
     * A provided future end is used — the ternary true arm — and the rule is closed (§18).
     */
    @Test
    @DisplayName("closeRule(): a provided future end is used")
    void closeRuleProvidedEnd() {
        FidelityRule rule = rule(1L, "R", FIXED.minusDays(5), null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "code = ?1 and (validTo is null or validTo > ?2) order by validFrom desc",
                    "R", TODAY.atStartOfDay())).thenReturn(queryOf(rule));
            FidelityRule closed = service.closeRule("R", FIXED.plusDays(3));
            assertSame(rule, closed);
            assertEquals(FIXED.plusDays(3), closed.validTo);
        }
    }

    /**
     * A provided end in the past is refused — the {@code end.isBefore(today)} true arm (§18).
     */
    @Test
    @DisplayName("closeRule(): an end in the past is refused")
    void closeRuleEndInPast() {
        FidelityRule rule = rule(1L, "R", FIXED.minusDays(5), null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "code = ?1 and (validTo is null or validTo > ?2) order by validFrom desc",
                    "R", TODAY.atStartOfDay())).thenReturn(queryOf(rule));
            assertThrows(AdminException.class, () -> service.closeRule(
                    "R", TODAY.minusDays(1).atStartOfDay()));
        }
    }

    // --------------------------------------------------
    // duplicateRule (§23.3)
    // --------------------------------------------------

    /**
     * An unknown source is refused — the {@code source == null} arm (§23.3).
     */
    @Test
    @DisplayName("duplicateRule(): an unknown source is refused")
    void duplicateRuleUnknownSource() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code = ?1 order by validFrom desc", "S"))
                    .thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.duplicateRule("S", "N"));
        }
    }

    /**
     * A blank new code is refused — the {@code requireText} blank leg (§23.3).
     */
    @Test
    @DisplayName("duplicateRule(): a blank new code is refused")
    void duplicateRuleBlankNewCode() {
        FidelityRule source = rule(1L, "S", FIXED, null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code = ?1 order by validFrom desc", "S"))
                    .thenReturn(queryOf(source));
            assertThrows(AdminException.class, () -> service.duplicateRule("S", " "));
        }
    }

    /**
     * An already existing new code is refused — the {@code findByCode(newCode) != null}
     * arm (§23.3).
     */
    @Test
    @DisplayName("duplicateRule(): an existing new code is refused")
    void duplicateRuleNewCodeExists() {
        FidelityRule source = rule(1L, "S", FIXED, null);
        FidelityRule clash = rule(2L, "N", FIXED, null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code = ?1 order by validFrom desc", "S"))
                    .thenReturn(queryOf(source));
            panache.when(() -> PanacheEntityBase.find("code", "N")).thenReturn(queryOf(clash));
            assertThrows(AdminException.class, () -> service.duplicateRule("S", "N"));
        }
    }

    /**
     * A free new code duplicates the source's backbone and specification (§23.3).
     */
    @Test
    @DisplayName("duplicateRule(): a free new code duplicates the source")
    void duplicateRuleSuccess() {
        FidelityRule source = rule(1L, "S", FIXED, FIXED.plusDays(1));
        source.type = "T";
        source.label = "L";
        source.priority = 7;
        source.exclusive = true;
        source.monthlyCapPerCard = new BigDecimal("2.00");
        source.active = true;
        source.specification = "{\"a\":1}";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityRule> cons = Mockito.mockConstruction(FidelityRule.class)) {
            panache.when(() -> PanacheEntityBase.find("code = ?1 order by validFrom desc", "S"))
                    .thenReturn(queryOf(source));
            panache.when(() -> PanacheEntityBase.find("code", "N")).thenReturn(queryOf(null));
            FidelityRule copy = service.duplicateRule("S", "N");
            assertSame(cons.constructed().get(0), copy);
            assertEquals("N", copy.code);
            assertEquals("T", copy.type);
            assertEquals("L", copy.label);
            assertEquals(FIXED, copy.validFrom);
            assertEquals(FIXED.plusDays(1), copy.validTo);
            assertEquals(7, copy.priority);
            assertTrue(copy.exclusive);
            assertEquals(0, copy.monthlyCapPerCard.compareTo(new BigDecimal("2.00")));
            assertTrue(copy.active);
            assertEquals("{\"a\":1}", copy.specification);
        }
    }

    // --------------------------------------------------
    // createCard (§33.1, §30.4)
    // --------------------------------------------------

    /**
     * A null status defaults to ACTIVE and stamps {@code activatedAt} — the ternary null
     * arm then the {@code status == ACTIVE} true arm (§30.4).
     */
    @Test
    @DisplayName("createCard(): a null status defaults to ACTIVE and stamps activation")
    void createCardNullStatusActive() {
        Mockito.when(cardNumbers.generate()).thenReturn("2990000000019");
        try (MockedConstruction<FidelityAccount> cons = Mockito.mockConstruction(FidelityAccount.class)) {
            FidelityAccount created = service.createCard(null);
            assertSame(cons.constructed().get(0), created);
            assertEquals("2990000000019", created.cardNumber);
            assertEquals(AccountStatus.ACTIVE, created.status);
            assertEquals(FIXED, created.activatedAt);
        }
    }

    /**
     * A PENDING_ACTIVATION status is kept and leaves {@code activatedAt} null — the ternary
     * non-null arm then the {@code status == ACTIVE} false arm (§30.4).
     */
    @Test
    @DisplayName("createCard(): a PENDING_ACTIVATION status leaves activation null")
    void createCardPendingStatus() {
        Mockito.when(cardNumbers.generate()).thenReturn("2990000000019");
        try (MockedConstruction<FidelityAccount> cons = Mockito.mockConstruction(FidelityAccount.class)) {
            FidelityAccount created = service.createCard(AccountStatus.PENDING_ACTIVATION);
            assertSame(cons.constructed().get(0), created);
            assertEquals(AccountStatus.PENDING_ACTIVATION, created.status);
            assertNull(created.activatedAt);
        }
    }

    // --------------------------------------------------
    // adjustCard (§32.1)
    // --------------------------------------------------

    /**
     * A null reason is refused — the {@code reason == null} leg (§32.1).
     */
    @Test
    @DisplayName("adjustCard(): a null reason is refused")
    void adjustCardNullReason() {
        assertThrows(AdminException.class, () -> service.adjustCard("C", new BigDecimal("5.00"), null));
    }

    /**
     * A blank reason is refused — the {@code reason.isBlank()} leg (§32.1).
     */
    @Test
    @DisplayName("adjustCard(): a blank reason is refused")
    void adjustCardBlankReason() {
        assertThrows(AdminException.class, () -> service.adjustCard("C", new BigDecimal("5.00"), " "));
    }

    /**
     * A null amount is refused — the {@code amount == null} leg (§32.1).
     */
    @Test
    @DisplayName("adjustCard(): a null amount is refused")
    void adjustCardNullAmount() {
        assertThrows(AdminException.class, () -> service.adjustCard("C", null, "reason"));
    }

    /**
     * A zero amount is refused — the {@code amount.signum() == 0} leg (§32.1).
     */
    @Test
    @DisplayName("adjustCard(): a zero amount is refused")
    void adjustCardZeroAmount() {
        assertThrows(AdminException.class, () -> service.adjustCard("C", new BigDecimal("0.00"), "reason"));
    }

    /**
     * An unknown card is refused — the {@code lockOrThrow} null arm (§30.1).
     */
    @Test
    @DisplayName("adjustCard(): an unknown card is refused")
    void adjustCardUnknownCard() {
        Mockito.when(ledger.lock("C")).thenReturn(null);
        assertThrows(AdminException.class, () -> service.adjustCard("C", new BigDecimal("5.00"), "reason"));
    }

    /**
     * A valid adjustment posts an ADJUSTMENT movement with the trimmed reason at the fiscal
     * day (§32.1).
     */
    @Test
    @DisplayName("adjustCard(): a valid adjustment posts the movement")
    void adjustCardSuccess() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "10.00");
        Mockito.when(ledger.lock("C")).thenReturn(account);
        FidelityAccount result = service.adjustCard("C", new BigDecimal("5.00"), "  gesture  ");
        assertSame(account, result);
        Mockito.verify(ledger).post(account, MovementType.ADJUSTMENT, new BigDecimal("5.00"),
                TODAY, null, null, List.of(), "gesture");
    }

    // --------------------------------------------------
    // resiliateCard (§26.5, §28.1)
    // --------------------------------------------------

    /**
     * An active holding lease refuses the resiliation — the {@code active != null} true and
     * {@code isHolding} true legs (§28.1).
     */
    @Test
    @DisplayName("resiliateCard(): an active lease refuses the resiliation")
    void resiliateCardActiveLease() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "10.00");
        Mockito.when(ledger.lock("C")).thenReturn(account);
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        Mockito.when(reservation.isHolding(FIXED)).thenReturn(true);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and state = ?2", account, ReservationState.ACTIVE))
                    .thenReturn(queryOf(reservation));
            assertThrows(AdminException.class, () -> service.resiliateCard("C"));
        }
    }

    /**
     * A lease that is no longer holding does not block — the {@code isHolding} false leg —
     * so the card is resiliated (§28.1).
     */
    @Test
    @DisplayName("resiliateCard(): a non-holding lease does not block")
    void resiliateCardLeaseNotHolding() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "10.00");
        Mockito.when(ledger.lock("C")).thenReturn(account);
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        Mockito.when(reservation.isHolding(FIXED)).thenReturn(false);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and state = ?2", account, ReservationState.ACTIVE))
                    .thenReturn(queryOf(reservation));
            FidelityAccount result = service.resiliateCard("C");
            assertSame(account, result);
            assertEquals(AccountStatus.RESILIATED, result.status);
        }
    }

    /**
     * No lease at all does not block — the {@code active != null} false leg — so the card is
     * resiliated (§28.1).
     */
    @Test
    @DisplayName("resiliateCard(): no lease resiliates the card")
    void resiliateCardNoLease() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "10.00");
        Mockito.when(ledger.lock("C")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and state = ?2", account, ReservationState.ACTIVE))
                    .thenReturn(queryOf(null));
            FidelityAccount result = service.resiliateCard("C");
            assertSame(account, result);
            assertEquals(AccountStatus.RESILIATED, result.status);
        }
    }

    // --------------------------------------------------
    // transferCard (§16, §28.1, §34.3)
    // --------------------------------------------------

    /**
     * A resiliated card cannot be transferred — the {@code status == RESILIATED} arm (§28.1).
     */
    @Test
    @DisplayName("transferCard(): a resiliated card is refused")
    void transferCardResiliated() {
        FidelityAccount source = account("OLD", AccountStatus.RESILIATED, "0.00");
        Mockito.when(ledger.lock("OLD")).thenReturn(source);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and state = ?2", source, ReservationState.ACTIVE))
                    .thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.transferCard("OLD"));
        }
    }

    /**
     * A zero-balance transfer skips the ledger movements — the {@code balance.signum() != 0}
     * false arm — and still carries the headers and deactivates the source (§34.3).
     */
    @Test
    @DisplayName("transferCard(): a zero-balance transfer carries the headers only")
    void transferCardZeroBalance() {
        FidelityAccount source = account("OLD", AccountStatus.ACTIVE, "0.00");
        Mockito.when(ledger.lock("OLD")).thenReturn(source);
        Mockito.when(cardNumbers.generate()).thenReturn("NEW");
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityAccount> cons = Mockito.mockConstruction(FidelityAccount.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and state = ?2", source, ReservationState.ACTIVE))
                    .thenReturn(queryOf(null));
            panache.when(() -> PanacheEntityBase.list("account", source)).thenReturn(List.of());
            FidelityAccount target = service.transferCard("OLD");
            assertSame(cons.constructed().get(0), target);
            assertEquals("NEW", target.cardNumber);
            assertEquals(AccountStatus.ACTIVE, target.status);
            assertEquals(FIXED, target.activatedAt);
            assertEquals("NEW", source.transferredToCard);
            assertEquals(AccountStatus.RESILIATED, source.status);
            Mockito.verify(ledger, Mockito.never()).post(Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
            Mockito.verify(ledger).refreshBalance(source);
            Mockito.verify(ledger).refreshBalance(target);
        }
    }

    /**
     * A non-zero transfer posts the outgoing debit and decomposes the residual by earnYear —
     * the {@code balance.signum() != 0} true arm, the FIFO {@code residualByYear}, and the
     * {@code entry.getValue().signum() > 0} both arms (only the residual year is credited),
     * while the memberships and activations follow the target (§34.3, §28.1).
     */
    @Test
    @DisplayName("transferCard(): a non-zero transfer decomposes the residual by earnYear")
    void transferCardNonZeroBalance() {
        FidelityAccount source = account("OLD", AccountStatus.ACTIVE, "3.00");
        Mockito.when(ledger.lock("OLD")).thenReturn(source);
        Mockito.when(cardNumbers.generate()).thenReturn("NEW");
        Map<Integer, BigDecimal> credits = new LinkedHashMap<>();
        credits.put(2024, new BigDecimal("10.00"));
        credits.put(2025, new BigDecimal("5.00"));
        FidelityMembership membership = Mockito.mock(FidelityMembership.class);
        FidelityActivation activation = Mockito.mock(FidelityActivation.class);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedConstruction<FidelityAccount> accounts = Mockito.mockConstruction(FidelityAccount.class);
             MockedConstruction<FidelityMovement> posted = Mockito.mockConstruction(FidelityMovement.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and state = ?2", source, ReservationState.ACTIVE))
                    .thenReturn(queryOf(null));
            movements.when(() -> FidelityMovement.creditsByEarnYear(source)).thenReturn(credits);
            movements.when(() -> FidelityMovement.totalDebits(source)).thenReturn(new BigDecimal("12.00"));
            panache.when(() -> PanacheEntityBase.list("account", source))
                    .thenReturn(List.of(membership), List.of(activation));
            FidelityAccount target = service.transferCard("OLD");
            assertSame(accounts.constructed().get(0), target);
            Mockito.verify(ledger).post(source, MovementType.TRANSFER, new BigDecimal("-3.00"),
                    TODAY, null, "TRANSFER:OLD>NEW", List.of(), "Transfer to NEW");
            assertEquals(1, posted.constructed().size());
            FidelityMovement credit = posted.constructed().get(0);
            assertSame(target, credit.account);
            assertEquals(MovementType.TRANSFER, credit.type);
            assertEquals(0, credit.amount.compareTo(new BigDecimal("3.00")));
            assertEquals(2025, credit.earnYear);
            assertEquals("TRANSFER:OLD>NEW:2025", credit.ticketRef);
            assertSame(target, membership.account);
            assertSame(target, activation.account);
            assertEquals("NEW", source.transferredToCard);
            assertEquals(AccountStatus.RESILIATED, source.status);
        }
    }

    // --------------------------------------------------
    // createCommunity (§23.2)
    // --------------------------------------------------

    /**
     * A blank code is refused — the {@code requireText(code)} blank leg (§23.2).
     */
    @Test
    @DisplayName("createCommunity(): a blank code is refused")
    void createCommunityBlankCode() {
        assertThrows(AdminException.class, () -> service.createCommunity(
                " ", "L", null, null, null, null, null));
    }

    /**
     * A blank label is refused — the {@code requireText(label)} blank leg (§23.2).
     */
    @Test
    @DisplayName("createCommunity(): a blank label is refused")
    void createCommunityBlankLabel() {
        assertThrows(AdminException.class, () -> service.createCommunity(
                "C", " ", null, null, null, null, null));
    }

    /**
     * An existing code is refused — the {@code findByCode != null} arm (§23.2).
     */
    @Test
    @DisplayName("createCommunity(): an existing code is refused")
    void createCommunityCodeExists() {
        FidelityCommunity existing = community("C", true, null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(existing));
            assertThrows(AdminException.class, () -> service.createCommunity(
                    "C", "L", null, null, null, null, null));
        }
    }

    /**
     * A negative monthly cap is refused — the {@code monthlyCap.signum() < 0} true leg (§23.2).
     */
    @Test
    @DisplayName("createCommunity(): a negative monthly cap is refused")
    void createCommunityNegativeMonthlyCap() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.createCommunity(
                    "C", "L", new BigDecimal("-0.01"), null, null, null, null));
        }
    }

    /**
     * A negative enrollment cap is refused — the {@code enrollmentCap < 0} true leg (§28.4).
     */
    @Test
    @DisplayName("createCommunity(): a negative enrollment cap is refused")
    void createCommunityNegativeEnrollmentCap() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.createCommunity(
                    "C", "L", new BigDecimal("0.00"), -1, null, null, null));
        }
    }

    /**
     * A renewal start without an end is refused — the XOR {@code start != end nullity} true
     * (start non-null, end null) (§23.2).
     */
    @Test
    @DisplayName("createCommunity(): a renewal start without an end is refused")
    void createCommunityRenewalStartWithoutEnd() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.createCommunity(
                    "C", "L", null, null, 3, null, null));
        }
    }

    /**
     * A renewal end without a start is refused — the XOR the other way (start null, end
     * non-null) (§23.2).
     */
    @Test
    @DisplayName("createCommunity(): a renewal end without a start is refused")
    void createCommunityRenewalEndWithoutStart() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.createCommunity(
                    "C", "L", null, null, null, 3, null));
        }
    }

    /**
     * A renewal month below 1 is refused — the {@code month < 1} true leg (§23.2).
     */
    @Test
    @DisplayName("createCommunity(): a renewal month below 1 is refused")
    void createCommunityMonthTooLow() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.createCommunity(
                    "C", "L", null, null, 0, 5, null));
        }
    }

    /**
     * A renewal month above 12 is refused — a valid start then the {@code month > 12} true
     * leg on the end (§23.2).
     */
    @Test
    @DisplayName("createCommunity(): a renewal month above 12 is refused")
    void createCommunityMonthTooHigh() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.createCommunity(
                    "C", "L", null, null, 5, 13, null));
        }
    }

    /**
     * A full valid community is created — every continue arm, the XOR both-non-null false,
     * valid months, and the eligibility non-blank trim arm (§23.2).
     */
    @Test
    @DisplayName("createCommunity(): a full valid community is created")
    void createCommunitySuccess() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityCommunity> cons = Mockito.mockConstruction(FidelityCommunity.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(null));
            FidelityCommunity created = service.createCommunity(
                    " C ", " Label ", new BigDecimal("10.00"), 5, 1, 12, "  crit  ");
            assertSame(cons.constructed().get(0), created);
            assertEquals("C", created.code);
            assertEquals("Label", created.label);
            assertEquals(0, created.monthlyCap.compareTo(new BigDecimal("10.00")));
            assertEquals(5, created.enrollmentCap);
            assertEquals(1, created.renewalStartMonth);
            assertEquals(12, created.renewalEndMonth);
            assertEquals("crit", created.eligibilityCriteria);
            assertTrue(created.active);
        }
    }

    /**
     * A minimal community with every optional parameter null is created — the null-skip legs
     * (monthly cap, enrollment cap), the XOR both-null false, and the eligibility null arm (§23.2).
     */
    @Test
    @DisplayName("createCommunity(): a minimal community with null options is created")
    void createCommunityMinimalNulls() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityCommunity> cons = Mockito.mockConstruction(FidelityCommunity.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(null));
            FidelityCommunity created = service.createCommunity(
                    "C", "Label", null, null, null, null, null);
            assertSame(cons.constructed().get(0), created);
            assertNull(created.monthlyCap);
            assertNull(created.enrollmentCap);
            assertNull(created.renewalStartMonth);
            assertNull(created.eligibilityCriteria);
        }
    }

    /**
     * A blank eligibility collapses to null — the {@code eligibilityCriteria.isBlank()} true
     * leg of the ternary (§23.2).
     */
    @Test
    @DisplayName("createCommunity(): a blank eligibility collapses to null")
    void createCommunityBlankEligibility() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityCommunity> cons = Mockito.mockConstruction(FidelityCommunity.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(null));
            FidelityCommunity created = service.createCommunity(
                    "C", "Label", null, null, null, null, " ");
            assertSame(cons.constructed().get(0), created);
            assertNull(created.eligibilityCriteria);
        }
    }

    // --------------------------------------------------
    // updateCommunity / setCommunityActive (§23.2)
    // --------------------------------------------------

    /**
     * An unknown community is refused — the {@code community == null} arm (§23.2).
     */
    @Test
    @DisplayName("updateCommunity(): an unknown community is refused")
    void updateCommunityUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.updateCommunity(
                    "C", "L", null, null, null, null, null));
        }
    }

    /**
     * A blank label is refused — the {@code requireText(label)} blank leg (§23.2).
     */
    @Test
    @DisplayName("updateCommunity(): a blank label is refused")
    void updateCommunityBlankLabel() {
        FidelityCommunity community = community("C", true, null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(community));
            assertThrows(AdminException.class, () -> service.updateCommunity(
                    "C", " ", null, null, null, null, null));
        }
    }

    /**
     * A known community has its editable parameters applied (§23.2).
     */
    @Test
    @DisplayName("updateCommunity(): a known community is updated")
    void updateCommunitySuccess() {
        FidelityCommunity community = community("C", true, null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(community));
            FidelityCommunity updated = service.updateCommunity(
                    "C", " New ", new BigDecimal("4.00"), 3, null, null, "crit");
            assertSame(community, updated);
            assertEquals("New", updated.label);
            assertEquals(0, updated.monthlyCap.compareTo(new BigDecimal("4.00")));
            assertEquals(3, updated.enrollmentCap);
            assertEquals("crit", updated.eligibilityCriteria);
        }
    }

    /**
     * An unknown community is refused — the {@code community == null} arm (§23.2).
     */
    @Test
    @DisplayName("setCommunityActive(): an unknown community is refused")
    void setCommunityActiveUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.setCommunityActive("C", false));
        }
    }

    /**
     * A known community's activation flag is toggled (§23.2).
     */
    @Test
    @DisplayName("setCommunityActive(): a known community is toggled")
    void setCommunityActiveSuccess() {
        FidelityCommunity community = community("C", true, null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "C")).thenReturn(queryOf(community));
            FidelityCommunity updated = service.setCommunityActive("C", false);
            assertSame(community, updated);
            assertEquals(false, updated.active);
        }
    }

    // --------------------------------------------------
    // upsertMembership (§28.4)
    // --------------------------------------------------

    /**
     * An unknown card is refused — the {@code lockOrThrow} null arm (§30.1).
     */
    @Test
    @DisplayName("upsertMembership(): an unknown card is refused")
    void upsertMembershipUnknownCard() {
        Mockito.when(ledger.lock("C")).thenReturn(null);
        assertThrows(AdminException.class, () -> service.upsertMembership("C", "COM", TODAY, null));
    }

    /**
     * An unknown community is refused — the {@code community == null} arm (§23.2).
     */
    @Test
    @DisplayName("upsertMembership(): an unknown community is refused")
    void upsertMembershipUnknownCommunity() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "0.00");
        Mockito.when(ledger.lock("C")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "COM")).thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.upsertMembership("C", "COM", TODAY, null));
        }
    }

    /**
     * A null {@code validFrom} is refused — the {@code validFrom == null} arm (§28.4).
     */
    @Test
    @DisplayName("upsertMembership(): a null validFrom is refused")
    void upsertMembershipNullValidFrom() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "0.00");
        FidelityCommunity community = community("COM", true, null);
        Mockito.when(ledger.lock("C")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "COM")).thenReturn(queryOf(community));
            assertThrows(AdminException.class, () -> service.upsertMembership("C", "COM", null, null));
        }
    }

    /**
     * A new membership on a closed community is refused — the {@code existing == null} true
     * and {@code !community.active} true legs (§23.2).
     */
    @Test
    @DisplayName("upsertMembership(): a new membership on a closed community is refused")
    void upsertMembershipClosedCommunity() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "0.00");
        FidelityCommunity community = community("COM", false, null);
        Mockito.when(ledger.lock("C")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "COM")).thenReturn(queryOf(community));
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and community = ?2 and validFrom = ?3", account, community, TODAY))
                    .thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.upsertMembership("C", "COM", TODAY, null));
        }
    }

    /**
     * A new membership at the enrollment cap is refused — the {@code enrollmentCap != null}
     * true and {@code active >= cap} true legs at the boundary (§28.4).
     */
    @Test
    @DisplayName("upsertMembership(): a new membership at the cap is refused")
    void upsertMembershipCapReached() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "0.00");
        FidelityCommunity community = community("COM", true, 2);
        Mockito.when(ledger.lock("C")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "COM")).thenReturn(queryOf(community));
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and community = ?2 and validFrom = ?3", account, community, TODAY))
                    .thenReturn(queryOf(null));
            panache.when(() -> PanacheEntityBase.count(
                    "community = ?1 and (validTo is null or validTo >= ?2)", community, TODAY))
                    .thenReturn(2L);
            assertThrows(AdminException.class, () -> service.upsertMembership("C", "COM", TODAY, null));
        }
    }

    /**
     * A new membership under the cap is created — the {@code active >= cap} false leg at the
     * boundary (§28.4).
     */
    @Test
    @DisplayName("upsertMembership(): a new membership under the cap is created")
    void upsertMembershipUnderCap() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "0.00");
        FidelityCommunity community = community("COM", true, 2);
        Mockito.when(ledger.lock("C")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityMembership> cons = Mockito.mockConstruction(FidelityMembership.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "COM")).thenReturn(queryOf(community));
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and community = ?2 and validFrom = ?3", account, community, TODAY))
                    .thenReturn(queryOf(null));
            panache.when(() -> PanacheEntityBase.count(
                    "community = ?1 and (validTo is null or validTo >= ?2)", community, TODAY))
                    .thenReturn(1L);
            FidelityMembership membership = service.upsertMembership("C", "COM", TODAY, TODAY.plusDays(30));
            assertSame(cons.constructed().get(0), membership);
            assertSame(account, membership.account);
            assertSame(community, membership.community);
            assertEquals(TODAY, membership.validFrom);
            assertEquals(TODAY.plusDays(30), membership.validTo);
        }
    }

    /**
     * A new membership on an open community with no cap is created — the {@code enrollmentCap
     * != null} false leg skips the count (§28.4).
     */
    @Test
    @DisplayName("upsertMembership(): a new membership with no cap is created")
    void upsertMembershipNoCap() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "0.00");
        FidelityCommunity community = community("COM", true, null);
        Mockito.when(ledger.lock("C")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityMembership> cons = Mockito.mockConstruction(FidelityMembership.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "COM")).thenReturn(queryOf(community));
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and community = ?2 and validFrom = ?3", account, community, TODAY))
                    .thenReturn(queryOf(null));
            FidelityMembership membership = service.upsertMembership("C", "COM", TODAY, null);
            assertSame(cons.constructed().get(0), membership);
        }
    }

    /**
     * An existing membership is reused and re-fed — the {@code existing == null} false leg of
     * both guards and the ternary {@code existing != null} arm (§28.4).
     */
    @Test
    @DisplayName("upsertMembership(): an existing membership is reused")
    void upsertMembershipExisting() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "0.00");
        FidelityCommunity community = community("COM", false, 1);
        FidelityMembership existing = Mockito.mock(FidelityMembership.class);
        Mockito.when(ledger.lock("C")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "COM")).thenReturn(queryOf(community));
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and community = ?2 and validFrom = ?3", account, community, TODAY))
                    .thenReturn(queryOf(existing));
            FidelityMembership membership = service.upsertMembership("C", "COM", TODAY, TODAY.plusDays(5));
            assertSame(existing, membership);
            assertSame(account, membership.account);
            assertEquals(TODAY.plusDays(5), membership.validTo);
        }
    }

    // --------------------------------------------------
    // replaceMemberships (§21.3, §23.2, §28.4)
    // --------------------------------------------------

    /**
     * An unknown community is refused — the {@code community == null} arm (§23.2).
     */
    @Test
    @DisplayName("replaceMemberships(): an unknown community is refused")
    void replaceMembershipsUnknownCommunity() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "COM")).thenReturn(queryOf(null));
            assertThrows(AdminException.class, () -> service.replaceMemberships("COM", List.of()));
        }
    }

    /**
     * A closed community rejects a card not already a member — the {@code !community.active}
     * true leg, the {@code membership.account != null} true and false legs (building the set),
     * and the entry legs up to {@code !existingCards.contains} true (§23.2).
     */
    @Test
    @DisplayName("replaceMemberships(): a closed community rejects a new card")
    void replaceMembershipsClosedRejectsNewCard() {
        FidelityCommunity community = community("COM", false, null);
        FidelityAccount member = account("CARD1", AccountStatus.ACTIVE, "0.00");
        FidelityMembership existingMember = Mockito.mock(FidelityMembership.class);
        existingMember.account = member;
        FidelityMembership orphan = Mockito.mock(FidelityMembership.class);
        orphan.account = null;
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "COM")).thenReturn(queryOf(community));
            panache.when(() -> PanacheEntityBase.list("community", community))
                    .thenReturn(List.of(existingMember, orphan));
            List<MembershipInput> entries = List.of(input("CARD9", TODAY, null));
            assertThrows(AdminException.class, () -> service.replaceMemberships("COM", entries));
        }
    }

    /**
     * A closed community accepts an already-member card and skips null entries — the entry
     * {@code entry != null} false, {@code entry.card != null} false, and {@code !contains}
     * false legs — then replaces the set (§21.3, §23.2).
     */
    @Test
    @DisplayName("replaceMemberships(): a closed community accepts an existing card")
    void replaceMembershipsClosedAcceptsExistingCard() {
        FidelityCommunity community = community("COM", false, null);
        FidelityAccount member = account("CARD1", AccountStatus.ACTIVE, "0.00");
        FidelityMembership existingMember = Mockito.mock(FidelityMembership.class);
        existingMember.account = member;
        List<MembershipInput> entries = new ArrayList<>();
        entries.add(null);
        entries.add(input(null, TODAY, null));
        entries.add(input("CARD1", TODAY, TODAY.plusDays(10)));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityMembership> cons = Mockito.mockConstruction(FidelityMembership.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "COM")).thenReturn(queryOf(community));
            panache.when(() -> PanacheEntityBase.list("community", community))
                    .thenReturn(List.of(existingMember));
            panache.when(() -> PanacheEntityBase.find("cardNumber", "CARD1")).thenReturn(queryOf(member));
            int count = service.replaceMemberships("COM", entries);
            assertEquals(1, count);
            assertEquals(1, cons.constructed().size());
            assertSame(member, cons.constructed().get(0).account);
        }
    }

    /**
     * An open community over its cap is refused — the {@code !community.active} false leg, the
     * {@code enrollmentCap != null} true and {@code valid.size() > cap} true legs (§28.4).
     */
    @Test
    @DisplayName("replaceMemberships(): an open community over the cap is refused")
    void replaceMembershipsCapExceeded() {
        FidelityCommunity community = community("COM", true, 1);
        FidelityAccount a1 = account("CARD1", AccountStatus.ACTIVE, "0.00");
        FidelityAccount a2 = account("CARD2", AccountStatus.ACTIVE, "0.00");
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "COM")).thenReturn(queryOf(community));
            panache.when(() -> PanacheEntityBase.find("cardNumber", "CARD1")).thenReturn(queryOf(a1));
            panache.when(() -> PanacheEntityBase.find("cardNumber", "CARD2")).thenReturn(queryOf(a2));
            List<MembershipInput> entries = List.of(
                    input("CARD1", TODAY, null), input("CARD2", TODAY, null));
            assertThrows(AdminException.class, () -> service.replaceMemberships("COM", entries));
        }
    }

    /**
     * An open community filters every invalid entry and persists the valid ones — the valid
     * loop false legs (null entry, null card, blank card, null validFrom, unknown card) and
     * the all-true add — with a null cap skipping the cap check (§21.3, §28.4).
     */
    @Test
    @DisplayName("replaceMemberships(): the invalid entries are filtered and the valid ones persisted")
    void replaceMembershipsFiltersAndPersists() {
        FidelityCommunity community = community("COM", true, null);
        FidelityAccount known = account("C3", AccountStatus.ACTIVE, "0.00");
        List<MembershipInput> entries = new ArrayList<>();
        entries.add(null);
        entries.add(input(null, TODAY, null));
        entries.add(input(" ", TODAY, null));
        entries.add(input("C1", null, null));
        entries.add(input("C2", TODAY, null));
        entries.add(input("C3", TODAY, TODAY.plusDays(7)));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityMembership> cons = Mockito.mockConstruction(FidelityMembership.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "COM")).thenReturn(queryOf(community));
            panache.when(() -> PanacheEntityBase.find("cardNumber", "C2")).thenReturn(queryOf(null));
            panache.when(() -> PanacheEntityBase.find("cardNumber", "C3")).thenReturn(queryOf(known));
            int count = service.replaceMemberships("COM", entries);
            assertEquals(1, count);
            assertEquals(1, cons.constructed().size());
            FidelityMembership created = cons.constructed().get(0);
            assertSame(known, created.account);
            assertSame(community, created.community);
            assertEquals(TODAY, created.validFrom);
            assertEquals(TODAY.plusDays(7), created.validTo);
        }
    }

    /**
     * An open community within its cap persists the valid set — the {@code valid.size() > cap}
     * false leg (§28.4).
     */
    @Test
    @DisplayName("replaceMemberships(): an open community within the cap persists")
    void replaceMembershipsUnderCap() {
        FidelityCommunity community = community("COM", true, 5);
        FidelityAccount known = account("C1", AccountStatus.ACTIVE, "0.00");
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityMembership> cons = Mockito.mockConstruction(FidelityMembership.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "COM")).thenReturn(queryOf(community));
            panache.when(() -> PanacheEntityBase.find("cardNumber", "C1")).thenReturn(queryOf(known));
            int count = service.replaceMemberships("COM", List.of(input("C1", TODAY, null)));
            assertEquals(1, count);
            assertEquals(1, cons.constructed().size());
        }
    }

    // --------------------------------------------------
    // setActivation (§14, §24.3)
    // --------------------------------------------------

    /**
     * An unknown card is refused — the {@code lockOrThrow} null arm (§30.1).
     */
    @Test
    @DisplayName("setActivation(): an unknown card is refused")
    void setActivationUnknownCard() {
        Mockito.when(ledger.lock("C")).thenReturn(null);
        assertThrows(AdminException.class, () -> service.setActivation("C", "R", TODAY, null, false));
    }

    /**
     * A blank rule code is refused — the {@code requireText(ruleCode)} blank leg (§24.3).
     */
    @Test
    @DisplayName("setActivation(): a blank rule code is refused")
    void setActivationBlankRuleCode() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "0.00");
        Mockito.when(ledger.lock("C")).thenReturn(account);
        assertThrows(AdminException.class, () -> service.setActivation("C", " ", TODAY, null, false));
    }

    /**
     * A null period start is refused — the {@code periodStart == null} arm (§24.3).
     */
    @Test
    @DisplayName("setActivation(): a null period start is refused")
    void setActivationNullPeriodStart() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "0.00");
        Mockito.when(ledger.lock("C")).thenReturn(account);
        assertThrows(AdminException.class, () -> service.setActivation("C", "R", null, null, false));
    }

    /**
     * A fresh activation is created — the {@code activation == null} true arm (§24.3).
     */
    @Test
    @DisplayName("setActivation(): a fresh activation is created")
    void setActivationNew() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "0.00");
        Mockito.when(ledger.lock("C")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityActivation> cons = Mockito.mockConstruction(FidelityActivation.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and ruleCode = ?2 and periodStart = ?3", account, "R", TODAY))
                    .thenReturn(queryOf(null));
            FidelityActivation activation = service.setActivation("C", "R", TODAY, TODAY.plusDays(3), true);
            assertSame(cons.constructed().get(0), activation);
            assertSame(account, activation.account);
            assertEquals("R", activation.ruleCode);
            assertEquals(TODAY, activation.periodStart);
            assertEquals(TODAY.plusDays(3), activation.periodEnd);
            assertTrue(activation.missionDone);
        }
    }

    /**
     * An existing activation is reused and re-fed — the {@code activation == null} false arm
     * (§24.3).
     */
    @Test
    @DisplayName("setActivation(): an existing activation is reused")
    void setActivationExisting() {
        FidelityAccount account = account("C", AccountStatus.ACTIVE, "0.00");
        FidelityActivation existing = Mockito.mock(FidelityActivation.class);
        Mockito.when(ledger.lock("C")).thenReturn(account);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and ruleCode = ?2 and periodStart = ?3", account, "R", TODAY))
                    .thenReturn(queryOf(existing));
            FidelityActivation activation = service.setActivation("C", "R", TODAY, null, false);
            assertSame(existing, activation);
            assertNull(activation.periodEnd);
            assertEquals(false, activation.missionDone);
        }
    }

    // --------------------------------------------------
    // setProgramSetting (§25.1)
    // --------------------------------------------------

    /**
     * A blank key is refused — the {@code requireText(key)} blank leg (§25.1).
     */
    @Test
    @DisplayName("setProgramSetting(): a blank key is refused")
    void setProgramSettingBlankKey() {
        assertThrows(AdminException.class, () -> service.setProgramSetting(" ", "v"));
    }

    /**
     * A fresh key creates a setting — the {@code setting == null} true arm (§25.1).
     */
    @Test
    @DisplayName("setProgramSetting(): a fresh key creates a setting")
    void setProgramSettingNew() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedConstruction<FidelityProgramSetting> cons =
                     Mockito.mockConstruction(FidelityProgramSetting.class)) {
            panache.when(() -> PanacheEntityBase.find("key", "k")).thenReturn(queryOf(null));
            FidelityProgramSetting setting = service.setProgramSetting("k", "v");
            assertSame(cons.constructed().get(0), setting);
            assertEquals("k", setting.key);
            assertEquals("v", setting.value);
        }
    }

    /**
     * An existing key is reused and re-fed — the {@code setting == null} false arm (§25.1).
     */
    @Test
    @DisplayName("setProgramSetting(): an existing key is reused")
    void setProgramSettingExisting() {
        FidelityProgramSetting existing = Mockito.mock(FidelityProgramSetting.class);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("key", "k")).thenReturn(queryOf(existing));
            FidelityProgramSetting setting = service.setProgramSetting("k", "v2");
            assertSame(existing, setting);
            assertEquals("v2", setting.value);
        }
    }
}
