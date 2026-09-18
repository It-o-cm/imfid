package com.intermarche.fidelity.graphql;

import com.intermarche.fidelity.account.AccountService;
import com.intermarche.fidelity.account.AccountViews;
import com.intermarche.fidelity.admin.AdminService;
import com.intermarche.fidelity.batch.BatchResult;
import com.intermarche.fidelity.batch.BatchService;
import com.intermarche.fidelity.batch.BatchType;
import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.util.ProgramClock;
import com.intermarche.fidelity.admin.AdminException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The administration GraphQL surface, by business codes never by IDs (§26.5), at the
 * imvaluation pattern. Every operation requires the {@code fid-admin} role (§24.1); the
 * mutations delegate to the shared {@link AdminService}, so the GraphQL and UI channels
 * enforce identical integrity rules (§18, §28), and {@code triggerBatch} drives the same
 * batch engine as the cron and the UI (§32.3).
 */
@GraphQLApi
@RolesAllowed(AppUser.ROLE_FID_ADMIN)
public class FidelityGraphQLApi {

    /**
     * The mutation service (§18, §26.5, §28).
     */
    @Inject
    AdminService admin;

    /**
     * The account read service (§27.2).
     */
    @Inject
    AccountService accounts;

    /**
     * The batch engine (§16, §32.3).
     */
    @Inject
    BatchService batches;

    /**
     * The program clock resolving the current day (§25.1).
     */
    @Inject
    ProgramClock clock;

    // --------------------------------------------------
    // Queries (§26.5)
    // --------------------------------------------------

    /**
     * Returns a rule by its code (the latest instance), or null.
     *
     * @param code The rule code.
     * @return The rule projection, or null.
     */
    @Query
    @Description("A rule by its business code (latest instance).")
    public GraphQLTypes.RuleType rule(@Name("code") String code) {
        return GraphQLTypes.RuleType.of(
                FidelityRule.find("code = ?1 order by validFrom desc", code).firstResult());
    }

    /**
     * Lists rules, optionally filtered by type and to the active ones (§26.5).
     *
     * @param type       The rule type filter, or null for all.
     * @param activeOnly Whether to keep only active rules.
     * @return The rule projections, never null.
     */
    @Query
    @Description("Rules, optionally filtered by type and active flag.")
    public List<GraphQLTypes.RuleType> rules(@Name("type") String type, @Name("activeOnly") Boolean activeOnly) {
        List<FidelityRule> rules;
        if (type != null && !type.isBlank()) {
            rules = FidelityRule.list("type = ?1 order by code, validFrom", type);
        } else {
            rules = FidelityRule.list("order by code, validFrom");
        }
        List<GraphQLTypes.RuleType> result = new ArrayList<>();
        for (FidelityRule rule : rules) {
            if (activeOnly != null && activeOnly && !rule.active) {
                continue;
            }
            result.add(GraphQLTypes.RuleType.of(rule));
        }
        return result;
    }

    /**
     * Returns an account summary by card number, or null when unknown (§27.2).
     *
     * @param card The card number.
     * @return The account summary, or null.
     */
    @Query
    @Description("An account summary by card number.")
    public AccountViews.Summary account(@Name("card") String card) {
        FidelityAccount account = FidelityAccount.findByCardNumber(card);
        return account == null ? null : accounts.summary(account);
    }

    /**
     * Returns a page of an account's movements (§27.2).
     *
     * @param card The card number.
     * @param page The zero-based page index.
     * @param size The page size (clamped 1..200).
     * @return The movement page, or null when the card is unknown.
     */
    @Query
    @Description("A page of an account's movements.")
    public AccountViews.MovementPage movements(@Name("card") String card,
                                               @Name("page") Integer page, @Name("size") Integer size) {
        FidelityAccount account = FidelityAccount.findByCardNumber(card);
        if (account == null) {
            return null;
        }
        int pageIndex = page != null ? Math.max(0, page) : 0;
        int pageSize = size != null ? Math.min(Math.max(1, size), 200) : 50;
        return accounts.movements(account, pageIndex, pageSize);
    }

    /**
     * Lists the communities with their active member counts (§26.5).
     *
     * @return The community projections, never null.
     */
    @Query
    @Description("The loyalty communities.")
    public List<GraphQLTypes.CommunityType> communities() {
        List<GraphQLTypes.CommunityType> result = new ArrayList<>();
        LocalDate today = clock.today();
        for (FidelityCommunity community : FidelityCommunity.listAllByCode()) {
            long active = FidelityMembership.count(
                    "community = ?1 and (validTo is null or validTo >= ?2)", community, today);
            result.add(GraphQLTypes.CommunityType.of(community, active));
        }
        return result;
    }

    /**
     * Lists the program settings (§25.1).
     *
     * @return The setting projections, never null.
     */
    @Query
    @Description("The program settings.")
    public List<GraphQLTypes.ProgramSettingType> programSettings() {
        List<GraphQLTypes.ProgramSettingType> result = new ArrayList<>();
        for (FidelityProgramSetting setting : FidelityProgramSetting.<FidelityProgramSetting>list("order by key")) {
            result.add(GraphQLTypes.ProgramSettingType.of(setting));
        }
        return result;
    }

    // --------------------------------------------------
    // Mutations (§26.5)
    // --------------------------------------------------

    /**
     * Creates an earn rule (§18).
     *
     * @param code              The rule code.
     * @param type              The rule type.
     * @param label             The printed label.
     * @param validFrom         The window start.
     * @param validTo           The window end, or null.
     * @param priority          The priority.
     * @param exclusive         Whether exclusive.
     * @param monthlyCapPerCard The per-card cap, or null.
     * @param active            Whether active.
     * @param specification     The JSON specification.
     * @param advantageType     The advantage-type code (RFP BO-03-03-25); mandatory
     *                          on a crediting rule.
     * @param advantageCategory The optional advantage-category code, or null.
     * @return The created rule.
     */
    @Mutation
    public GraphQLTypes.RuleType createRule(@Name("code") String code, @Name("type") String type,
                                            @Name("label") String label, @Name("validFrom") LocalDateTime validFrom,
                                            @Name("validTo") LocalDateTime validTo, @Name("priority") int priority,
                                            @Name("exclusive") boolean exclusive,
                                            @Name("monthlyCapPerCard") BigDecimal monthlyCapPerCard,
                                            @Name("active") boolean active, @Name("specification") String specification,
                                            @Name("advantageType") String advantageType,
                                            @Name("advantageCategory") String advantageCategory) throws GraphQLException {
        return guard(() -> GraphQLTypes.RuleType.of(admin.createRule(code, type, label, validFrom, validTo,
                priority, exclusive, monthlyCapPerCard, active, specification,
                advantageType, advantageCategory)));
    }

    /**
     * Closes an open rule (§18).
     *
     * @param code    The rule code.
     * @param validTo The window end (not in the past).
     * @return The closed rule.
     */
    @Mutation
    public GraphQLTypes.RuleType closeRule(@Name("code") String code, @Name("validTo") LocalDateTime validTo) throws GraphQLException {
        return guard(() -> GraphQLTypes.RuleType.of(admin.closeRule(code, validTo)));
    }

    /**
     * Duplicates a rule under a new code (§23.3).
     *
     * @param sourceCode The source rule code.
     * @param newCode    The new rule code.
     * @return The duplicated rule.
     */
    @Mutation
    public GraphQLTypes.RuleType duplicateRule(@Name("sourceCode") String sourceCode, @Name("newCode") String newCode) throws GraphQLException {
        return guard(() -> GraphQLTypes.RuleType.of(admin.duplicateRule(sourceCode, newCode)));
    }

    /**
     * Creates a loyalty card (§33.1, §30.4).
     *
     * @param status The initial status.
     * @return The created card.
     */
    @Mutation
    public GraphQLTypes.CardType createCard(@Name("status") AccountStatus status) throws GraphQLException {
        return guard(() -> card(admin.createCard(status)));
    }

    /**
     * Transfers a card on loss/theft (§16, §28.1, §34.3).
     *
     * @param fromCard The card to transfer from.
     * @return The new card.
     */
    @Mutation
    public GraphQLTypes.CardType transferCard(@Name("fromCard") String fromCard) throws GraphQLException {
        return guard(() -> card(admin.transferCard(fromCard)));
    }

    /**
     * Resiliates a card (§26.5, §28.1).
     *
     * @param card The card number.
     * @return The resiliated card.
     */
    @Mutation
    public GraphQLTypes.CardType resiliateCard(@Name("card") String card) throws GraphQLException {
        return guard(() -> card(admin.resiliateCard(card)));
    }

    /**
     * Posts an ADJUSTMENT on a card (§32.1).
     *
     * @param card   The card number.
     * @param amount The signed amount.
     * @param reason The mandatory reason.
     * @return The adjusted card.
     */
    @Mutation
    public GraphQLTypes.CardType adjustCard(@Name("card") String card, @Name("amount") BigDecimal amount,
                                            @Name("reason") String reason) throws GraphQLException {
        return guard(() -> card(admin.adjustCard(card, amount, reason)));
    }

    /**
     * Upserts a community membership (§28.4).
     *
     * @param card      The card number.
     * @param community The community code.
     * @param validFrom The window start.
     * @param validTo   The window end, or null.
     * @return The upserted membership.
     */
    @Mutation
    public GraphQLTypes.MembershipType upsertMembership(@Name("card") String card, @Name("community") String community,
                                                        @Name("validFrom") LocalDate validFrom,
                                                        @Name("validTo") LocalDate validTo) throws GraphQLException {
        return guard(() -> GraphQLTypes.MembershipType.of(admin.upsertMembership(card, community, validFrom, validTo)));
    }

    /**
     * Upserts a card activation (§14, §24.3).
     *
     * @param card        The card number.
     * @param ruleCode    The rule enabled.
     * @param periodStart The period start.
     * @param periodEnd   The period end, or null.
     * @param missionDone Whether the mission is done.
     * @return The upserted activation.
     */
    @Mutation
    public GraphQLTypes.ActivationType setActivation(@Name("card") String card, @Name("ruleCode") String ruleCode,
                                                     @Name("periodStart") LocalDate periodStart,
                                                     @Name("periodEnd") LocalDate periodEnd,
                                                     @Name("missionDone") boolean missionDone) throws GraphQLException {
        return guard(() -> GraphQLTypes.ActivationType.of(admin.setActivation(card, ruleCode, periodStart, periodEnd, missionDone)));
    }

    /**
     * Upserts a program setting (§25.1).
     *
     * @param key   The setting key.
     * @param value The setting value.
     * @return The upserted setting.
     */
    @Mutation
    public GraphQLTypes.ProgramSettingType setProgramSetting(@Name("key") String key, @Name("value") String value) throws GraphQLException {
        return guard(() -> GraphQLTypes.ProgramSettingType.of(admin.setProgramSetting(key, value)));
    }

    /**
     * Triggers a batch, simulate or execute (§32.3).
     *
     * @param batch  The batch type.
     * @param dryRun Whether to simulate.
     * @return The batch result.
     */
    @Mutation
    public BatchResult triggerBatch(@Name("batch") BatchType batch, @Name("dryRun") boolean dryRun) throws GraphQLException {
        return guard(() -> batches.run(batch, dryRun));
    }

    /**
     * Maps an account to its card projection.
     *
     * @param account The account.
     * @return The card projection.
     */
    private GraphQLTypes.CardType card(FidelityAccount account) {
        return new GraphQLTypes.CardType(account.cardNumber, account.status.name(), account.balance);
    }

    /**
     * Runs a mutation action, translating a business-rule refusal into a GraphQL error
     * carrying the operator-facing message (§18, §28) — the GraphQL twin of the UI failure
     * notice.
     *
     * @param action The mutation action.
     * @param <T>    The result type.
     * @return The action result.
     * @throws GraphQLException when the action is refused by a business rule.
     */
    private <T> T guard(MutationAction<T> action) throws GraphQLException {
        try {
            return action.run();
        } catch (AdminException e) {
            throw new GraphQLException(e.getMessage());
        }
    }

    /**
     * A mutation action producing a result, able to raise an {@link AdminException}.
     *
     * @param <T> The result type.
     */
    @FunctionalInterface
    private interface MutationAction<T> {

        /**
         * Runs the action.
         *
         * @return The result.
         */
        T run();
    }
}
