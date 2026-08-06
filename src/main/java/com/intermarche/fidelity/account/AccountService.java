package com.intermarche.fidelity.account;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.util.ProgramClock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The read service of the account API (§27.2) — the account summary (balance,
 * available balance, month's visits, cap cumulatives, memberships) and the paginated
 * movement history. Reads take no lock (§30.1); the cap cumulatives are derived from
 * the movements, the single source of truth (§14).
 */
@ApplicationScoped
public class AccountService {

    /**
     * The default global monthly cap when the setting is absent (400 €, §25.1).
     */
    private static final BigDecimal DEFAULT_GLOBAL_CAP = new BigDecimal("400.00");

    /**
     * The program clock resolving the month bounds at the program zone (§25.1).
     */
    @Inject
    ProgramClock clock;

    /**
     * The ledger service exposing the available balance (I11).
     */
    @Inject
    LedgerService ledger;

    /**
     * Builds the summary of an account for the current month (§27.2).
     *
     * @param account The account.
     * @return The account summary, never null.
     */
    public AccountViews.Summary summary(FidelityAccount account) {
        LocalDate today = clock.today();
        LocalDate monthStart = clock.monthStart(today);
        LocalDate monthEnd = clock.monthEnd(today);

        AccountViews.Summary summary = new AccountViews.Summary();
        summary.cardNumber = account.cardNumber;
        summary.status = account.status.name();
        summary.balance = account.balance;
        summary.availableBalance = ledger.availableBalance(account);
        summary.monthVisits = (int) com.intermarche.fidelity.domain.EarnTrace.countVisits(
                account.cardNumber, monthStart, monthEnd);

        Map<String, BigDecimal> ruleEarn = FidelityMovement.monthlyEarnByRule(account, monthStart, monthEnd);
        BigDecimal globalUsed = FidelityMovement.monthlyEarnTotal(account, monthStart, monthEnd);

        // GLOBAL cap.
        BigDecimal globalCap = FidelityProgramSetting.getDecimal(
                FidelityProgramSetting.KEY_GLOBAL_MONTHLY_CAP, DEFAULT_GLOBAL_CAP);
        summary.monthlyCaps.add(new AccountViews.CapView("GLOBAL", globalCap, globalUsed));

        // COMMUNITY caps of the card's active memberships.
        List<FidelityMembership> memberships = FidelityMembership.listActiveForAccount(account, today);
        for (FidelityMembership membership : memberships) {
            FidelityCommunity community = membership.community;
            if (community == null) {
                continue;
            }
            summary.memberships.add(new AccountViews.MembershipView(
                    community.code, membership.validFrom, membership.validTo));
            if (community.monthlyCap != null) {
                BigDecimal used = communityUsed(community.code, ruleEarn);
                summary.monthlyCaps.add(new AccountViews.CapView("COMMUNITY:" + community.code,
                        community.monthlyCap, used));
            }
        }

        // RULE caps of the in-force rules that carry one.
        for (FidelityRule rule : FidelityRule.listInForceAt(today.atStartOfDay())) {
            if (rule.monthlyCapPerCard != null) {
                BigDecimal used = ruleEarn.getOrDefault(rule.code, BigDecimal.ZERO);
                summary.monthlyCaps.add(new AccountViews.CapView("RULE:" + rule.code,
                        rule.monthlyCapPerCard, used));
            }
        }
        return summary;
    }

    /**
     * Returns a page of an account's movements, most recent first (§27.2).
     *
     * @param account   The account.
     * @param pageIndex Zero-based page index.
     * @param pageSize  The page size (already clamped by the resource).
     * @return The movement page, never null.
     */
    public AccountViews.MovementPage movements(FidelityAccount account, int pageIndex, int pageSize) {
        AccountViews.MovementPage page = new AccountViews.MovementPage();
        page.totalCount = FidelityMovement.count("account", account);
        for (FidelityMovement movement : FidelityMovement.pageForAccount(account, pageIndex, pageSize)) {
            AccountViews.MovementView view = new AccountViews.MovementView();
            view.date = movement.movementDate;
            view.type = movement.type.name();
            view.amount = movement.amount;
            view.ticketRef = movement.ticketRef;
            view.ruleCode = movement.ruleCode;
            view.reason = movement.reason;
            page.items.add(view);
        }
        return page;
    }

    /**
     * Sums the month's earn of the rules belonging to a community — the community cap
     * cumulative (§15, I5).
     *
     * @param communityCode The community code.
     * @param ruleEarn      The per-rule monthly earn.
     * @return The community cumulative, euro at scale 2, never null.
     */
    private BigDecimal communityUsed(String communityCode, Map<String, BigDecimal> ruleEarn) {
        BigDecimal used = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : ruleEarn.entrySet()) {
            FidelityRule rule = FidelityRule.findByCode(entry.getKey());
            if (rule != null && communityCode.equals(rule.communityCodeFromSpec())) {
                used = used.add(entry.getValue());
            }
        }
        return used;
    }
}
