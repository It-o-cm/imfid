package com.intermarche.fidelity.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.util.ProgramClock;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link AccountService}: the §27.2 read surface — the account
 * summary (balance, available balance, month visits, the GLOBAL / COMMUNITY / RULE cap
 * cumulatives, memberships) and the paginated movement history. Every branch is
 * exercised: the community-null {@code continue}, both arms of the {@code monthlyCap} and
 * {@code monthlyCapPerCard} guards, both arms of the {@code getOrDefault} rule-earn
 * lookup, every leg of the {@code rule != null && communityCode.equals(...)} guard of
 * {@code communityUsed} (unknown rule, mismatched community, matching community), and the
 * empty / non-empty movement page loop.
 * <p>
 * Fully isolated: the injected {@link ProgramClock} and {@link LedgerService} are mocked
 * and wired on the package-private fields, and every static finder is intercepted with
 * {@code mockStatic} in a try-with-resources. Time is fixed at the program zone through
 * the mocked clock (§24.6): the summary reads {@code today}, {@code monthStart} and
 * {@code monthEnd} from it, never a real clock, so the month bounds are the deterministic
 * March 2026 window rather than the day the campaign runs.
 */
class AccountServiceTest {

    /**
     * The fixed fiscal day returned by the mocked clock (§30.3).
     */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    /**
     * The first day of {@link #TODAY}'s month, the lower cap/visit bound (§29.1).
     */
    private static final LocalDate MONTH_START = LocalDate.of(2026, 3, 1);

    /**
     * The last day of {@link #TODAY}'s month, the upper cap/visit bound (§29.1).
     */
    private static final LocalDate MONTH_END = LocalDate.of(2026, 3, 31);

    /**
     * The GLOBAL cap the setting resolves for the test month (§25.1).
     */
    private static final BigDecimal GLOBAL_CAP = new BigDecimal("400.00");

    /**
     * The system under test, freshly built with mocked collaborators per test.
     */
    private AccountService service;

    /**
     * The mocked program clock fixing the month bounds at the program zone (§25.1).
     */
    private ProgramClock clock;

    /**
     * The mocked ledger exposing the available balance (I11).
     */
    private LedgerService ledger;

    /**
     * The account under read, carrying deterministic scalar fields (§27.2).
     */
    private FidelityAccount account;

    /**
     * Builds a fresh system under test, wires the mocked collaborators and fixes the
     * clock at the deterministic March 2026 window before each test.
     */
    @BeforeEach
    void setUp() {
        service = new AccountService();
        clock = Mockito.mock(ProgramClock.class);
        ledger = Mockito.mock(LedgerService.class);
        service.clock = clock;
        service.ledger = ledger;
        Mockito.when(clock.today()).thenReturn(TODAY);
        Mockito.when(clock.monthStart(TODAY)).thenReturn(MONTH_START);
        Mockito.when(clock.monthEnd(TODAY)).thenReturn(MONTH_END);
        account = new FidelityAccount();
        account.cardNumber = "CARD-1";
        account.status = AccountStatus.ACTIVE;
        account.balance = new BigDecimal("120.00");
        Mockito.when(ledger.availableBalance(account)).thenReturn(new BigDecimal("90.00"));
    }

    /**
     * Builds an in-force rule carrying a per-card cap and a specification-sourced
     * community code, the pure-logic input of {@code communityUsed} (§15, I5).
     *
     * @param code          The rule code.
     * @param communityCode The community code embedded in the specification, or null.
     * @param cap           The per-card monthly cap, or null.
     * @return The rule, never null.
     */
    private FidelityRule rule(String code, String communityCode, BigDecimal cap) {
        FidelityRule rule = new FidelityRule();
        rule.code = code;
        rule.monthlyCapPerCard = cap;
        rule.specification = communityCode == null ? "{}" : "{\"communityCode\":\"" + communityCode + "\"}";
        return rule;
    }

    /**
     * Builds an active membership binding the account to a community (§27.2).
     *
     * @param community The community, or null to model a dangling membership.
     * @param from      The membership start date.
     * @param to        The membership end date, or null while open.
     * @return The membership, never null.
     */
    private FidelityMembership membership(FidelityCommunity community, LocalDate from, LocalDate to) {
        FidelityMembership membership = new FidelityMembership();
        membership.community = community;
        membership.validFrom = from;
        membership.validTo = to;
        return membership;
    }

    /**
     * Builds a community with an optional monthly cap (§25.1).
     *
     * @param code The community code.
     * @param cap  The monthly cap, or null when the community carries none.
     * @return The community, never null.
     */
    private FidelityCommunity community(String code, BigDecimal cap) {
        FidelityCommunity community = new FidelityCommunity();
        community.code = code;
        community.monthlyCap = cap;
        return community;
    }

    /**
     * Finds the single cap view of the given scope, failing when absent (§27.2).
     *
     * @param summary The summary to search.
     * @param scope   The cap scope key.
     * @return The matching cap view, never null.
     */
    private AccountViews.CapView cap(AccountViews.Summary summary, String scope) {
        for (AccountViews.CapView view : summary.monthlyCaps) {
            if (scope.equals(view.scope)) {
                return view;
            }
        }
        throw new AssertionError("missing cap scope " + scope);
    }

    /**
     * A summary with no memberships and no capped rules carries the scalar fields
     * verbatim and the sole GLOBAL cap — both loops taking their empty arm.
     */
    @Test
    @DisplayName("summary: scalars and the sole GLOBAL cap, no memberships nor rules")
    void summaryScalarsAndGlobalCapOnly() {
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityMembership> memberships = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class)) {
            traces.when(() -> EarnTrace.countVisits("CARD-1", MONTH_START, MONTH_END)).thenReturn(4L);
            movements.when(() -> FidelityMovement.monthlyEarnByRule(account, MONTH_START, MONTH_END))
                    .thenReturn(Map.of());
            movements.when(() -> FidelityMovement.monthlyEarnTotal(account, MONTH_START, MONTH_END))
                    .thenReturn(new BigDecimal("12.00"));
            settings.when(() -> FidelityProgramSetting.getDecimal(
                    FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, new BigDecimal("400.00")))
                    .thenReturn(GLOBAL_CAP);
            memberships.when(() -> FidelityMembership.listActiveForAccount(account, TODAY)).thenReturn(List.of());
            rules.when(() -> FidelityRule.listInForceAt(TODAY.atStartOfDay())).thenReturn(List.of());
            AccountViews.Summary summary = service.summary(account);
            assertEquals("CARD-1", summary.cardNumber);
            assertEquals("ACTIVE", summary.status);
            assertEquals(0, summary.balance.compareTo(new BigDecimal("120.00")));
            assertEquals(0, summary.availableBalance.compareTo(new BigDecimal("90.00")));
            assertEquals(4, summary.monthVisits);
            assertTrue(summary.memberships.isEmpty());
            assertEquals(1, summary.monthlyCaps.size());
            AccountViews.CapView global = cap(summary, "GLOBAL");
            assertEquals(0, global.cap.compareTo(GLOBAL_CAP));
            assertEquals(0, global.used.compareTo(new BigDecimal("12.00")));
        }
    }

    /**
     * A membership whose community is null is skipped by the {@code continue} — neither a
     * membership view nor a community cap is emitted (the null arm of the guard).
     */
    @Test
    @DisplayName("summary: null-community membership skipped, no view nor cap")
    void summaryNullCommunityMembershipSkipped() {
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityMembership> memberships = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class)) {
            traces.when(() -> EarnTrace.countVisits("CARD-1", MONTH_START, MONTH_END)).thenReturn(0L);
            movements.when(() -> FidelityMovement.monthlyEarnByRule(account, MONTH_START, MONTH_END))
                    .thenReturn(Map.of());
            movements.when(() -> FidelityMovement.monthlyEarnTotal(account, MONTH_START, MONTH_END))
                    .thenReturn(BigDecimal.ZERO);
            settings.when(() -> FidelityProgramSetting.getDecimal(
                    FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, new BigDecimal("400.00")))
                    .thenReturn(GLOBAL_CAP);
            memberships.when(() -> FidelityMembership.listActiveForAccount(account, TODAY))
                    .thenReturn(List.of(membership(null, LocalDate.of(2026, 1, 1), null)));
            rules.when(() -> FidelityRule.listInForceAt(TODAY.atStartOfDay())).thenReturn(List.of());
            AccountViews.Summary summary = service.summary(account);
            assertTrue(summary.memberships.isEmpty());
            assertEquals(1, summary.monthlyCaps.size());
            assertEquals("GLOBAL", summary.monthlyCaps.get(0).scope);
        }
    }

    /**
     * A membership on a community without a cap adds the membership view but no COMMUNITY
     * cap (the {@code monthlyCap == null} arm).
     */
    @Test
    @DisplayName("summary: capless community adds the membership view only")
    void summaryCommunityWithoutCapAddsMembershipOnly() {
        FidelityCommunity com = community("CBIO", null);
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityMembership> memberships = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class)) {
            traces.when(() -> EarnTrace.countVisits("CARD-1", MONTH_START, MONTH_END)).thenReturn(1L);
            movements.when(() -> FidelityMovement.monthlyEarnByRule(account, MONTH_START, MONTH_END))
                    .thenReturn(Map.of());
            movements.when(() -> FidelityMovement.monthlyEarnTotal(account, MONTH_START, MONTH_END))
                    .thenReturn(BigDecimal.ZERO);
            settings.when(() -> FidelityProgramSetting.getDecimal(
                    FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, new BigDecimal("400.00")))
                    .thenReturn(GLOBAL_CAP);
            memberships.when(() -> FidelityMembership.listActiveForAccount(account, TODAY))
                    .thenReturn(List.of(membership(com, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 12, 31))));
            rules.when(() -> FidelityRule.listInForceAt(TODAY.atStartOfDay())).thenReturn(List.of());
            AccountViews.Summary summary = service.summary(account);
            assertEquals(1, summary.memberships.size());
            AccountViews.MembershipView view = summary.memberships.get(0);
            assertEquals("CBIO", view.community);
            assertEquals(LocalDate.of(2026, 2, 1), view.validFrom);
            assertEquals(LocalDate.of(2026, 12, 31), view.validTo);
            assertEquals(1, summary.monthlyCaps.size());
            assertEquals("GLOBAL", summary.monthlyCaps.get(0).scope);
        }
    }

    /**
     * A membership on a capped community adds the COMMUNITY cap whose used cumulative is
     * summed by {@code communityUsed} over the community's rules — the {@code monthlyCap
     * != null} arm and the matching-community leg of the guard.
     */
    @Test
    @DisplayName("summary: capped community adds a COMMUNITY cap summing its rules")
    void summaryCappedCommunityAddsCommunityCap() {
        FidelityCommunity com = community("CBIO", new BigDecimal("50.00"));
        Map<String, BigDecimal> earn = new LinkedHashMap<>();
        earn.put("R-BIO", new BigDecimal("7.00"));
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityMembership> memberships = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class)) {
            traces.when(() -> EarnTrace.countVisits("CARD-1", MONTH_START, MONTH_END)).thenReturn(2L);
            movements.when(() -> FidelityMovement.monthlyEarnByRule(account, MONTH_START, MONTH_END))
                    .thenReturn(earn);
            movements.when(() -> FidelityMovement.monthlyEarnTotal(account, MONTH_START, MONTH_END))
                    .thenReturn(new BigDecimal("7.00"));
            settings.when(() -> FidelityProgramSetting.getDecimal(
                    FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, new BigDecimal("400.00")))
                    .thenReturn(GLOBAL_CAP);
            memberships.when(() -> FidelityMembership.listActiveForAccount(account, TODAY))
                    .thenReturn(List.of(membership(com, LocalDate.of(2026, 1, 1), null)));
            rules.when(() -> FidelityRule.listInForceAt(TODAY.atStartOfDay())).thenReturn(List.of());
            rules.when(() -> FidelityRule.findByCode("R-BIO")).thenReturn(rule("R-BIO", "CBIO", null));
            AccountViews.Summary summary = service.summary(account);
            assertEquals(1, summary.memberships.size());
            AccountViews.CapView view = cap(summary, "COMMUNITY:CBIO");
            assertEquals(0, view.cap.compareTo(new BigDecimal("50.00")));
            assertEquals(0, view.used.compareTo(new BigDecimal("7.00")));
        }
    }

    /**
     * {@code communityUsed} sums only the rules mapped to the community: an unknown rule
     * (finder null leg), a rule of another community (mismatched leg) and two matching
     * rules (both-true leg) — the residual GLOBAL total is left untouched.
     */
    @Test
    @DisplayName("summary: communityUsed sums matches, skips unknown and mismatched rules")
    void summaryCommunityUsedSkipsUnknownAndMismatched() {
        FidelityCommunity com = community("CBIO", new BigDecimal("50.00"));
        Map<String, BigDecimal> earn = new LinkedHashMap<>();
        earn.put("R-GHOST", new BigDecimal("3.00"));
        earn.put("R-OTHER", new BigDecimal("5.00"));
        earn.put("R-BIO1", new BigDecimal("7.00"));
        earn.put("R-BIO2", new BigDecimal("2.50"));
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityMembership> memberships = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class)) {
            traces.when(() -> EarnTrace.countVisits("CARD-1", MONTH_START, MONTH_END)).thenReturn(3L);
            movements.when(() -> FidelityMovement.monthlyEarnByRule(account, MONTH_START, MONTH_END))
                    .thenReturn(earn);
            movements.when(() -> FidelityMovement.monthlyEarnTotal(account, MONTH_START, MONTH_END))
                    .thenReturn(new BigDecimal("17.50"));
            settings.when(() -> FidelityProgramSetting.getDecimal(
                    FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, new BigDecimal("400.00")))
                    .thenReturn(GLOBAL_CAP);
            memberships.when(() -> FidelityMembership.listActiveForAccount(account, TODAY))
                    .thenReturn(List.of(membership(com, LocalDate.of(2026, 1, 1), null)));
            rules.when(() -> FidelityRule.listInForceAt(TODAY.atStartOfDay())).thenReturn(List.of());
            rules.when(() -> FidelityRule.findByCode("R-GHOST")).thenReturn(null);
            rules.when(() -> FidelityRule.findByCode("R-OTHER")).thenReturn(rule("R-OTHER", "CFRAIS", null));
            rules.when(() -> FidelityRule.findByCode("R-BIO1")).thenReturn(rule("R-BIO1", "CBIO", null));
            rules.when(() -> FidelityRule.findByCode("R-BIO2")).thenReturn(rule("R-BIO2", "CBIO", null));
            AccountViews.Summary summary = service.summary(account);
            AccountViews.CapView view = cap(summary, "COMMUNITY:CBIO");
            assertEquals(0, view.used.compareTo(new BigDecimal("9.50")));
        }
    }

    /**
     * An in-force rule carrying a per-card cap and a matching earn entry emits a RULE cap
     * whose used is that entry — the {@code monthlyCapPerCard != null} arm and the
     * key-present arm of {@code getOrDefault}.
     */
    @Test
    @DisplayName("summary: capped rule with earn entry emits a RULE cap at that used")
    void summaryCappedRuleWithEarnEntry() {
        Map<String, BigDecimal> earn = new LinkedHashMap<>();
        earn.put("R-CAP", new BigDecimal("15.00"));
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityMembership> memberships = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class)) {
            traces.when(() -> EarnTrace.countVisits("CARD-1", MONTH_START, MONTH_END)).thenReturn(1L);
            movements.when(() -> FidelityMovement.monthlyEarnByRule(account, MONTH_START, MONTH_END))
                    .thenReturn(earn);
            movements.when(() -> FidelityMovement.monthlyEarnTotal(account, MONTH_START, MONTH_END))
                    .thenReturn(new BigDecimal("15.00"));
            settings.when(() -> FidelityProgramSetting.getDecimal(
                    FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, new BigDecimal("400.00")))
                    .thenReturn(GLOBAL_CAP);
            memberships.when(() -> FidelityMembership.listActiveForAccount(account, TODAY)).thenReturn(List.of());
            rules.when(() -> FidelityRule.listInForceAt(TODAY.atStartOfDay()))
                    .thenReturn(List.of(rule("R-CAP", null, new BigDecimal("30.00"))));
            AccountViews.Summary summary = service.summary(account);
            AccountViews.CapView view = cap(summary, "RULE:R-CAP");
            assertEquals(0, view.cap.compareTo(new BigDecimal("30.00")));
            assertEquals(0, view.used.compareTo(new BigDecimal("15.00")));
        }
    }

    /**
     * An in-force capped rule with no earn entry falls back to a zero used — the default
     * arm of {@code getOrDefault}.
     */
    @Test
    @DisplayName("summary: capped rule without earn entry uses a zero cumulative")
    void summaryCappedRuleWithoutEarnEntryUsesZero() {
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityMembership> memberships = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class)) {
            traces.when(() -> EarnTrace.countVisits("CARD-1", MONTH_START, MONTH_END)).thenReturn(0L);
            movements.when(() -> FidelityMovement.monthlyEarnByRule(account, MONTH_START, MONTH_END))
                    .thenReturn(Map.of());
            movements.when(() -> FidelityMovement.monthlyEarnTotal(account, MONTH_START, MONTH_END))
                    .thenReturn(BigDecimal.ZERO);
            settings.when(() -> FidelityProgramSetting.getDecimal(
                    FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, new BigDecimal("400.00")))
                    .thenReturn(GLOBAL_CAP);
            memberships.when(() -> FidelityMembership.listActiveForAccount(account, TODAY)).thenReturn(List.of());
            rules.when(() -> FidelityRule.listInForceAt(TODAY.atStartOfDay()))
                    .thenReturn(List.of(rule("R-CAP", null, new BigDecimal("30.00"))));
            AccountViews.Summary summary = service.summary(account);
            AccountViews.CapView view = cap(summary, "RULE:R-CAP");
            assertEquals(0, view.used.compareTo(BigDecimal.ZERO));
        }
    }

    /**
     * An in-force rule without a per-card cap emits no RULE cap — the {@code
     * monthlyCapPerCard == null} arm leaves only the GLOBAL cap.
     */
    @Test
    @DisplayName("summary: capless rule emits no RULE cap")
    void summaryCaplessRuleEmitsNoRuleCap() {
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityMembership> memberships = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class)) {
            traces.when(() -> EarnTrace.countVisits("CARD-1", MONTH_START, MONTH_END)).thenReturn(0L);
            movements.when(() -> FidelityMovement.monthlyEarnByRule(account, MONTH_START, MONTH_END))
                    .thenReturn(Map.of());
            movements.when(() -> FidelityMovement.monthlyEarnTotal(account, MONTH_START, MONTH_END))
                    .thenReturn(BigDecimal.ZERO);
            settings.when(() -> FidelityProgramSetting.getDecimal(
                    FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, new BigDecimal("400.00")))
                    .thenReturn(GLOBAL_CAP);
            memberships.when(() -> FidelityMembership.listActiveForAccount(account, TODAY)).thenReturn(List.of());
            rules.when(() -> FidelityRule.listInForceAt(TODAY.atStartOfDay()))
                    .thenReturn(List.of(rule("R-FLAT", null, null)));
            AccountViews.Summary summary = service.summary(account);
            assertEquals(1, summary.monthlyCaps.size());
            assertEquals("GLOBAL", summary.monthlyCaps.get(0).scope);
        }
    }

    /**
     * An empty movement page carries the total count and no items — the empty arm of the
     * page loop (§27.2).
     */
    @Test
    @DisplayName("movements: empty page carries the count and no items")
    void movementsEmptyPage() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class)) {
            panache.when(() -> PanacheEntityBase.count("account", account)).thenReturn(0L);
            movements.when(() -> FidelityMovement.pageForAccount(account, 2, 20)).thenReturn(List.of());
            AccountViews.MovementPage page = service.movements(account, 2, 20);
            assertEquals(0L, page.totalCount);
            assertTrue(page.items.isEmpty());
        }
    }

    /**
     * A non-empty movement page copies every field of each row, nullable ticket, rule and
     * reason included — the non-empty arm of the page loop (§27.2, §29.4, §32.1).
     */
    @Test
    @DisplayName("movements: non-empty page copies every field of each row")
    void movementsCopiesEveryField() {
        FidelityMovement earn = new FidelityMovement();
        earn.movementDate = LocalDate.of(2026, 3, 10);
        earn.type = MovementType.EARN;
        earn.amount = new BigDecimal("8.00");
        earn.ticketRef = "T-1";
        earn.ruleCode = "R-BIO";
        earn.reason = null;
        FidelityMovement adjustment = new FidelityMovement();
        adjustment.movementDate = LocalDate.of(2026, 3, 9);
        adjustment.type = MovementType.ADJUSTMENT;
        adjustment.amount = new BigDecimal("-3.00");
        adjustment.ticketRef = null;
        adjustment.ruleCode = null;
        adjustment.reason = "goodwill";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class)) {
            panache.when(() -> PanacheEntityBase.count("account", account)).thenReturn(2L);
            movements.when(() -> FidelityMovement.pageForAccount(account, 0, 50))
                    .thenReturn(List.of(earn, adjustment));
            AccountViews.MovementPage page = service.movements(account, 0, 50);
            assertEquals(2L, page.totalCount);
            assertEquals(2, page.items.size());
            AccountViews.MovementView first = page.items.get(0);
            assertEquals(LocalDate.of(2026, 3, 10), first.date);
            assertEquals("EARN", first.type);
            assertEquals(0, first.amount.compareTo(new BigDecimal("8.00")));
            assertEquals("T-1", first.ticketRef);
            assertEquals("R-BIO", first.ruleCode);
            assertNull(first.reason);
            AccountViews.MovementView second = page.items.get(1);
            assertEquals("ADJUSTMENT", second.type);
            assertEquals(0, second.amount.compareTo(new BigDecimal("-3.00")));
            assertNull(second.ticketRef);
            assertNull(second.ruleCode);
            assertEquals("goodwill", second.reason);
        }
    }

    /**
     * The available balance is read from the ledger, never derived — the summary carries
     * the ledger figure verbatim (I11).
     */
    @Test
    @DisplayName("summary: available balance is the ledger figure verbatim")
    void summaryAvailableBalanceFromLedger() {
        FidelityAccount other = new FidelityAccount();
        other.cardNumber = "CARD-2";
        other.status = AccountStatus.RESILIATED;
        other.balance = new BigDecimal("0.00");
        BigDecimal available = new BigDecimal("42.00");
        Mockito.when(ledger.availableBalance(other)).thenReturn(available);
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<FidelityMembership> memberships = Mockito.mockStatic(FidelityMembership.class);
             MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class)) {
            traces.when(() -> EarnTrace.countVisits("CARD-2", MONTH_START, MONTH_END)).thenReturn(0L);
            movements.when(() -> FidelityMovement.monthlyEarnByRule(other, MONTH_START, MONTH_END))
                    .thenReturn(Map.of());
            movements.when(() -> FidelityMovement.monthlyEarnTotal(other, MONTH_START, MONTH_END))
                    .thenReturn(BigDecimal.ZERO);
            settings.when(() -> FidelityProgramSetting.getDecimal(
                    FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, new BigDecimal("400.00")))
                    .thenReturn(GLOBAL_CAP);
            memberships.when(() -> FidelityMembership.listActiveForAccount(other, TODAY)).thenReturn(List.of());
            rules.when(() -> FidelityRule.listInForceAt(TODAY.atStartOfDay())).thenReturn(List.of());
            AccountViews.Summary summary = service.summary(other);
            assertEquals("CARD-2", summary.cardNumber);
            assertEquals("RESILIATED", summary.status);
            assertSame(available, summary.availableBalance);
        }
    }
}
