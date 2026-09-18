package com.intermarche.fidelity.graphql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.account.AccountService;
import com.intermarche.fidelity.account.AccountViews;
import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.admin.AdminService;
import com.intermarche.fidelity.batch.BatchResult;
import com.intermarche.fidelity.batch.BatchType;
import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.util.ProgramClock;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link FidelityGraphQLApi}: the administration GraphQL surface
 * keyed by business codes (§26.5). Every branch of the read queries — the type/active
 * filters of {@code rules} (each leg of the two compound {@code &&} guards, nullities
 * included, §29.6), the null/non-null ternaries of {@code account}, {@code movements} and
 * the page/size clamps — is exercised, and every mutation is driven through {@code guard}
 * on both its success arm and its {@link AdminException} translation arm (§18, §28).
 * <p>
 * Fully isolated: {@link AdminService}, {@link AccountService}, the batch engine and
 * {@link ProgramClock} are Mockito mocks injected into the package-private fields; the
 * Panache inherited finders ({@code find}/{@code list}/{@code count}) are intercepted with
 * {@code mockStatic(PanacheEntityBase.class)} in try-with-resources, and the
 * entity-declared finders {@code FidelityAccount.findByCardNumber} and
 * {@code FidelityCommunity.listAllByCode} resolve against those stubs. The clock is fixed
 * through the mocked {@link ProgramClock} so {@code communities} reads {@link #TODAY},
 * never the campaign day (§24.6, §30.3, §25.1). Every {@code BigDecimal} is asserted by
 * {@code compareTo}, to the cent (§30.5).
 */
class FidelityGraphQLApiTest {

    /**
     * The fixed program fiscal day every clock read resolves to (§30.3).
     */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);

    /**
     * The system under test, freshly built per test with mocked collaborators.
     */
    private FidelityGraphQLApi api;

    /**
     * The mocked mutation service (§18, §26.5, §28).
     */
    private AdminService admin;

    /**
     * The mocked account read service (§27.2).
     */
    private AccountService accounts;

    /**
     * The mocked batch engine (§16, §32.3).
     */
    private com.intermarche.fidelity.batch.BatchService batches;

    /**
     * The mocked program clock resolving the current day (§25.1).
     */
    private ProgramClock clock;

    /**
     * Wires a fresh API with its mocked collaborators and fixes the clock before each test.
     */
    @BeforeEach
    void setUp() {
        api = new FidelityGraphQLApi();
        admin = Mockito.mock(AdminService.class);
        accounts = Mockito.mock(AccountService.class);
        batches = Mockito.mock(com.intermarche.fidelity.batch.BatchService.class);
        clock = Mockito.mock(ProgramClock.class);
        api.admin = admin;
        api.accounts = accounts;
        api.batches = batches;
        api.clock = clock;
        Mockito.when(clock.today()).thenReturn(TODAY);
    }

    // --------------------------------------------------
    // Fixtures
    // --------------------------------------------------

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
     * Builds a rule entity carrying a code, a type and its active flag.
     *
     * @param code   The rule code.
     * @param type   The rule type.
     * @param active Whether the rule is active.
     * @return The rule entity.
     */
    private FidelityRule rule(String code, String type, boolean active) {
        FidelityRule rule = new FidelityRule();
        rule.code = code;
        rule.type = type;
        rule.label = "L-" + code;
        rule.active = active;
        return rule;
    }

    /**
     * Builds an account entity carrying a card number, a status and a balance.
     *
     * @param card    The card number.
     * @param status  The account status.
     * @param balance The balance.
     * @return The account entity.
     */
    private FidelityAccount account(String card, AccountStatus status, BigDecimal balance) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = card;
        account.status = status;
        account.balance = balance;
        return account;
    }

    // --------------------------------------------------
    // rule (§26.5)
    // --------------------------------------------------

    /**
     * A found rule is projected by its code (the latest instance).
     */
    @Test
    @DisplayName("rule: projects the latest instance found by code")
    void ruleFound() {
        FidelityRule found = rule("R", "BRAND_TIERED_EARN", true);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code = ?1 order by validFrom desc", "R"))
                    .thenReturn(queryOf(found));
            GraphQLTypes.RuleType result = api.rule("R");
            assertEquals("R", result.code);
            assertEquals("BRAND_TIERED_EARN", result.type);
        }
    }

    /**
     * An unknown code yields a null projection.
     */
    @Test
    @DisplayName("rule: null when the code is unknown")
    void ruleUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code = ?1 order by validFrom desc", "X"))
                    .thenReturn(queryOf(null));
            assertNull(api.rule("X"));
        }
    }

    // --------------------------------------------------
    // rules (§26.5)
    // --------------------------------------------------

    /**
     * A non-blank type filters by type (both {@code &&} legs true); a null activeOnly keeps
     * every rule (first leg of the loop guard false).
     */
    @Test
    @DisplayName("rules: type non-blank filters by type, activeOnly null keeps all")
    void rulesTypeNonBlankActiveOnlyNull() {
        FidelityRule a = rule("A", "COMMUNITY_EARN", true);
        FidelityRule b = rule("B", "COMMUNITY_EARN", false);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("type = ?1 order by code, validFrom", "COMMUNITY_EARN"))
                    .thenReturn(List.of(a, b));
            List<GraphQLTypes.RuleType> result = api.rules("COMMUNITY_EARN", null);
            assertEquals(2, result.size());
            assertEquals("A", result.get(0).code);
            assertEquals("B", result.get(1).code);
        }
    }

    /**
     * A null type lists all rules (first leg of the type {@code &&} false).
     */
    @Test
    @DisplayName("rules: null type lists all")
    void rulesTypeNull() {
        FidelityRule a = rule("A", "ECOUPON_EARN", true);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by code, validFrom"))
                    .thenReturn(List.of(a));
            List<GraphQLTypes.RuleType> result = api.rules(null, null);
            assertEquals(1, result.size());
            assertEquals("A", result.get(0).code);
        }
    }

    /**
     * A blank type lists all rules (second leg of the type {@code &&} false).
     */
    @Test
    @DisplayName("rules: blank type lists all")
    void rulesTypeBlank() {
        FidelityRule a = rule("A", "ECOUPON_EARN", true);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by code, validFrom"))
                    .thenReturn(List.of(a));
            List<GraphQLTypes.RuleType> result = api.rules("   ", null);
            assertEquals(1, result.size());
        }
    }

    /**
     * A non-null but false activeOnly keeps every rule (second leg of the loop guard false).
     */
    @Test
    @DisplayName("rules: activeOnly false keeps inactive rules")
    void rulesActiveOnlyFalse() {
        FidelityRule inactive = rule("A", "ECOUPON_EARN", false);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by code, validFrom"))
                    .thenReturn(List.of(inactive));
            List<GraphQLTypes.RuleType> result = api.rules(null, false);
            assertEquals(1, result.size());
        }
    }

    /**
     * A true activeOnly keeps active rules (third leg {@code !active} false) and drops
     * inactive ones (third leg true) — both legs of the loop guard in one pass.
     */
    @Test
    @DisplayName("rules: activeOnly true drops inactive, keeps active")
    void rulesActiveOnlyTrueMixed() {
        FidelityRule active = rule("A", "ECOUPON_EARN", true);
        FidelityRule inactive = rule("B", "ECOUPON_EARN", false);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by code, validFrom"))
                    .thenReturn(List.of(active, inactive));
            List<GraphQLTypes.RuleType> result = api.rules(null, true);
            assertEquals(1, result.size());
            assertEquals("A", result.get(0).code);
        }
    }

    // --------------------------------------------------
    // account (§27.2)
    // --------------------------------------------------

    /**
     * A known card yields the service summary (non-null arm of the ternary).
     */
    @Test
    @DisplayName("account: known card returns the summary")
    void accountKnown() {
        FidelityAccount found = account("C1", AccountStatus.ACTIVE, new BigDecimal("12.00"));
        AccountViews.Summary summary = new AccountViews.Summary();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", "C1")).thenReturn(queryOf(found));
            Mockito.when(accounts.summary(found)).thenReturn(summary);
            assertSame(summary, api.account("C1"));
        }
    }

    /**
     * An unknown card yields null (null arm of the ternary), never touching the service.
     */
    @Test
    @DisplayName("account: unknown card returns null")
    void accountUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", "C0")).thenReturn(queryOf(null));
            assertNull(api.account("C0"));
            Mockito.verifyNoInteractions(accounts);
        }
    }

    // --------------------------------------------------
    // movements (§27.2)
    // --------------------------------------------------

    /**
     * An unknown card returns null before any paging (null arm of the account guard).
     */
    @Test
    @DisplayName("movements: unknown card returns null")
    void movementsUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", "C0")).thenReturn(queryOf(null));
            assertNull(api.movements("C0", 3, 25));
            Mockito.verifyNoInteractions(accounts);
        }
    }

    /**
     * Null page and size fall back to page 0 and size 50 (else arms of both ternaries).
     */
    @Test
    @DisplayName("movements: null page/size default to 0/50")
    void movementsDefaultPaging() {
        FidelityAccount found = account("C1", AccountStatus.ACTIVE, BigDecimal.ZERO);
        AccountViews.MovementPage page = new AccountViews.MovementPage();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", "C1")).thenReturn(queryOf(found));
            Mockito.when(accounts.movements(found, 0, 50)).thenReturn(page);
            assertSame(page, api.movements("C1", null, null));
        }
    }

    /**
     * Non-null page and size are used (non-null arms of both ternaries).
     */
    @Test
    @DisplayName("movements: non-null page/size are used")
    void movementsExplicitPaging() {
        FidelityAccount found = account("C1", AccountStatus.ACTIVE, BigDecimal.ZERO);
        AccountViews.MovementPage page = new AccountViews.MovementPage();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", "C1")).thenReturn(queryOf(found));
            Mockito.when(accounts.movements(found, 3, 25)).thenReturn(page);
            assertSame(page, api.movements("C1", 3, 25));
        }
    }

    // --------------------------------------------------
    // communities (§26.5)
    // --------------------------------------------------

    /**
     * Communities are projected with their active member count resolved at the fixed
     * fiscal day (§25.1) — never the campaign clock.
     */
    @Test
    @DisplayName("communities: projects each with its active member count at today")
    void communitiesProjected() {
        FidelityCommunity community = new FidelityCommunity();
        community.code = "BABIES";
        community.label = "Babies";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by code")).thenReturn(List.of(community));
            panache.when(() -> PanacheEntityBase.count(
                            "community = ?1 and (validTo is null or validTo >= ?2)", community, TODAY))
                    .thenReturn(7L);
            List<GraphQLTypes.CommunityType> result = api.communities();
            assertEquals(1, result.size());
            assertEquals("BABIES", result.get(0).code);
            assertEquals(7L, result.get(0).activeMembers);
        }
    }

    // --------------------------------------------------
    // programSettings (§25.1)
    // --------------------------------------------------

    /**
     * Program settings are projected in key order.
     */
    @Test
    @DisplayName("programSettings: projects each setting")
    void programSettingsProjected() {
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.key = "program.zone";
        setting.value = "Europe/Paris";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("order by key")).thenReturn(List.of(setting));
            List<GraphQLTypes.ProgramSettingType> result = api.programSettings();
            assertEquals(1, result.size());
            assertEquals("program.zone", result.get(0).key);
            assertEquals("Europe/Paris", result.get(0).value);
        }
    }

    // --------------------------------------------------
    // Mutations — success arm of guard (§18, §28)
    // --------------------------------------------------

    /**
     * createRule projects the rule the service creates.
     *
     * @throws GraphQLException never on the success arm.
     */
    @Test
    @DisplayName("createRule: projects the created rule")
    void createRuleSuccess() throws GraphQLException {
        FidelityRule created = rule("R", "ECOUPON_EARN", true);
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        BigDecimal cap = new BigDecimal("30.00");
        Mockito.when(admin.createRule("R", "ECOUPON_EARN", "L", from, null, 5, true, cap, true, "{}", null, null))
                .thenReturn(created);
        GraphQLTypes.RuleType result = api.createRule("R", "ECOUPON_EARN", "L", from, null, 5, true, cap, true, "{}", null, null);
        assertEquals("R", result.code);
    }

    /**
     * closeRule projects the closed rule.
     *
     * @throws GraphQLException never on the success arm.
     */
    @Test
    @DisplayName("closeRule: projects the closed rule")
    void closeRuleSuccess() throws GraphQLException {
        FidelityRule closed = rule("R", "ECOUPON_EARN", true);
        LocalDateTime to = LocalDateTime.of(2026, 12, 31, 23, 59);
        Mockito.when(admin.closeRule("R", to)).thenReturn(closed);
        assertEquals("R", api.closeRule("R", to).code);
    }

    /**
     * duplicateRule projects the duplicated rule.
     *
     * @throws GraphQLException never on the success arm.
     */
    @Test
    @DisplayName("duplicateRule: projects the duplicated rule")
    void duplicateRuleSuccess() throws GraphQLException {
        FidelityRule copy = rule("R2", "ECOUPON_EARN", true);
        Mockito.when(admin.duplicateRule("R1", "R2")).thenReturn(copy);
        assertEquals("R2", api.duplicateRule("R1", "R2").code);
    }

    /**
     * createCard projects the created card.
     *
     * @throws GraphQLException never on the success arm.
     */
    @Test
    @DisplayName("createCard: projects the created card")
    void createCardSuccess() throws GraphQLException {
        FidelityAccount created = account("C1", AccountStatus.PENDING_ACTIVATION, new BigDecimal("0.00"));
        Mockito.when(admin.createCard(AccountStatus.PENDING_ACTIVATION)).thenReturn(created);
        GraphQLTypes.CardType result = api.createCard(AccountStatus.PENDING_ACTIVATION);
        assertEquals("C1", result.cardNumber);
        assertEquals("PENDING_ACTIVATION", result.status);
        assertEquals(0, new BigDecimal("0.00").compareTo(result.balance));
    }

    /**
     * transferCard projects the new card.
     *
     * @throws GraphQLException never on the success arm.
     */
    @Test
    @DisplayName("transferCard: projects the new card")
    void transferCardSuccess() throws GraphQLException {
        FidelityAccount to = account("C2", AccountStatus.ACTIVE, new BigDecimal("41.50"));
        Mockito.when(admin.transferCard("C1")).thenReturn(to);
        GraphQLTypes.CardType result = api.transferCard("C1");
        assertEquals("C2", result.cardNumber);
        assertEquals(0, new BigDecimal("41.50").compareTo(result.balance));
    }

    /**
     * resiliateCard projects the resiliated card.
     *
     * @throws GraphQLException never on the success arm.
     */
    @Test
    @DisplayName("resiliateCard: projects the resiliated card")
    void resiliateCardSuccess() throws GraphQLException {
        FidelityAccount card = account("C1", AccountStatus.RESILIATED, new BigDecimal("0.00"));
        Mockito.when(admin.resiliateCard("C1")).thenReturn(card);
        GraphQLTypes.CardType result = api.resiliateCard("C1");
        assertEquals("RESILIATED", result.status);
    }

    /**
     * adjustCard projects the adjusted card.
     *
     * @throws GraphQLException never on the success arm.
     */
    @Test
    @DisplayName("adjustCard: projects the adjusted card")
    void adjustCardSuccess() throws GraphQLException {
        FidelityAccount card = account("C1", AccountStatus.ACTIVE, new BigDecimal("15.00"));
        BigDecimal amount = new BigDecimal("5.00");
        Mockito.when(admin.adjustCard("C1", amount, "fix")).thenReturn(card);
        GraphQLTypes.CardType result = api.adjustCard("C1", amount, "fix");
        assertEquals(0, new BigDecimal("15.00").compareTo(result.balance));
    }

    /**
     * upsertMembership projects the upserted membership.
     *
     * @throws GraphQLException never on the success arm.
     */
    @Test
    @DisplayName("upsertMembership: projects the upserted membership")
    void upsertMembershipSuccess() throws GraphQLException {
        FidelityMembership membership = new FidelityMembership();
        membership.validFrom = LocalDate.of(2026, 1, 1);
        FidelityAccount holder = account("C1", AccountStatus.ACTIVE, BigDecimal.ZERO);
        membership.account = holder;
        FidelityCommunity community = new FidelityCommunity();
        community.code = "BABIES";
        membership.community = community;
        LocalDate from = LocalDate.of(2026, 1, 1);
        Mockito.when(admin.upsertMembership("C1", "BABIES", from, null)).thenReturn(membership);
        GraphQLTypes.MembershipType result = api.upsertMembership("C1", "BABIES", from, null);
        assertEquals("C1", result.card);
        assertEquals("BABIES", result.community);
    }

    /**
     * setActivation projects the upserted activation.
     *
     * @throws GraphQLException never on the success arm.
     */
    @Test
    @DisplayName("setActivation: projects the upserted activation")
    void setActivationSuccess() throws GraphQLException {
        FidelityActivation activation = new FidelityActivation();
        activation.ruleCode = "R";
        activation.missionDone = true;
        LocalDate start = LocalDate.of(2026, 1, 1);
        Mockito.when(admin.setActivation("C1", "R", start, null, true)).thenReturn(activation);
        GraphQLTypes.ActivationType result = api.setActivation("C1", "R", start, null, true);
        assertEquals("R", result.ruleCode);
        assertTrue(result.missionDone);
    }

    /**
     * setProgramSetting projects the upserted setting.
     *
     * @throws GraphQLException never on the success arm.
     */
    @Test
    @DisplayName("setProgramSetting: projects the upserted setting")
    void setProgramSettingSuccess() throws GraphQLException {
        FidelityProgramSetting setting = new FidelityProgramSetting();
        setting.key = "program.zone";
        setting.value = "Europe/Paris";
        Mockito.when(admin.setProgramSetting("program.zone", "Europe/Paris")).thenReturn(setting);
        GraphQLTypes.ProgramSettingType result = api.setProgramSetting("program.zone", "Europe/Paris");
        assertEquals("Europe/Paris", result.value);
    }

    /**
     * triggerBatch returns the batch engine result directly (§32.3).
     *
     * @throws GraphQLException never on the success arm.
     */
    @Test
    @DisplayName("triggerBatch: returns the engine result")
    void triggerBatchSuccess() throws GraphQLException {
        BatchResult batchResult = new BatchResult("EXPIRY", true);
        Mockito.when(batches.run(BatchType.EXPIRY, true)).thenReturn(batchResult);
        assertSame(batchResult, api.triggerBatch(BatchType.EXPIRY, true));
    }

    // --------------------------------------------------
    // Mutations — refusal arm of guard (§18, §28)
    // --------------------------------------------------

    /**
     * A business-rule refusal is translated into a {@link GraphQLException} carrying the
     * operator-facing message (catch arm of {@code guard}).
     */
    @Test
    @DisplayName("guard: an AdminException becomes a GraphQLException with the same message")
    void guardTranslatesRefusal() {
        Mockito.when(admin.duplicateRule("R1", "R2")).thenThrow(new AdminException("code already used"));
        GraphQLException thrown = assertThrows(GraphQLException.class, () -> api.duplicateRule("R1", "R2"));
        assertEquals("code already used", thrown.getMessage());
    }
}
