package com.intermarche.fidelity.admin;

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
import com.intermarche.fidelity.domain.util.CardNumberGenerator;
import com.intermarche.fidelity.domain.util.ProgramClock;
import com.intermarche.fidelity.rule.EarnRuleRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The administration write service (§18, §26.5, §28, §32.1) — the single home of the
 * mutation logic, shared by the GraphQL surface (§26.5) and the admin UI POST handlers
 * (§23), so the two channels enforce the very same integrity rules: no overlapping rule
 * window and a validated specification (§18), an immutable closed rule (§13, §18), the
 * transfer/resiliation guard on an active lease (§28.1), the enrollment cap (§28.4), the
 * mandatory adjustment reason (§32.1), and the earnYear-preserving transfer (§34.3).
 * <p>
 * Every account write takes the per-card lock (§30.1). A refused mutation throws an
 * {@link AdminException}.
 */
@ApplicationScoped
public class AdminService {

    /**
     * The registry validating a rule type and specification (§12).
     */
    @Inject
    EarnRuleRegistry registry;

    /**
     * The ledger writer holding the lock and posting the movements (§30.1).
     */
    @Inject
    LedgerService ledger;

    /**
     * The program clock resolving the fiscal day and rule windows (§25.1, §30.3).
     */
    @Inject
    ProgramClock clock;

    /**
     * The card number generator (§33.1).
     */
    @Inject
    CardNumberGenerator cardNumbers;

    // --------------------------------------------------
    // Rules (§18)
    // --------------------------------------------------

    /**
     * Creates an earn rule, validating its type and specification and refusing an
     * overlapping window for the same code (§12, §18).
     *
     * @param code              The stable rule code.
     * @param type              The rule type (must have a deployed factory).
     * @param label             The printed label.
     * @param validFrom         The window start.
     * @param validTo           The window end, or null while open.
     * @param priority          The evaluation priority.
     * @param exclusive         Whether the rule consumes its lines.
     * @param monthlyCapPerCard The per-card monthly cap, or null.
     * @param active            Whether the rule is active.
     * @param specification     The JSON specification.
     * @return The created rule.
     */
    @Transactional
    public FidelityRule createRule(String code, String type, String label, LocalDateTime validFrom,
                                   LocalDateTime validTo, int priority, boolean exclusive,
                                   BigDecimal monthlyCapPerCard, boolean active, String specification) {
        requireText(code, "code");
        requireText(type, "type");
        if (!registry.hasFactory(type)) {
            throw new AdminException("Unknown rule type '" + type + "' (no factory deployed)");
        }
        List<String> violations = registry.validate(type, specification);
        if (!violations.isEmpty()) {
            throw new AdminException("Invalid specification for type '" + type + "': " + String.join("; ", violations));
        }
        if (validFrom == null) {
            throw new AdminException("validFrom is mandatory");
        }
        for (FidelityRule existing : FidelityRule.<FidelityRule>list("code", code)) {
            if (windowsOverlap(validFrom, validTo, existing.validFrom, existing.validTo)) {
                throw new AdminException("Rule window overlaps an existing instance of code '" + code + "'");
            }
        }
        FidelityRule rule = new FidelityRule();
        rule.code = code;
        rule.type = type;
        rule.label = label;
        rule.validFrom = validFrom;
        rule.validTo = validTo;
        rule.priority = priority;
        rule.exclusive = exclusive;
        rule.monthlyCapPerCard = monthlyCapPerCard;
        rule.active = active;
        rule.specification = specification;
        rule.persist();
        return rule;
    }

    /**
     * Updates in place a rule that has not yet entered into force (§18, §23.3) — the one
     * case where editing is sound: an upcoming rule has never been evaluated, so no
     * ledger movement or replay depends on it. Everything but the code is editable; the
     * updated window must not start in the past (the edit stays replay-neutral), and the
     * type and specification are validated like at creation (§12).
     *
     * @param code              The code of the rule to update (frozen identity).
     * @param type              The rule type (must have a deployed factory).
     * @param label             The printed label.
     * @param validFrom         The window start (must not be in the past).
     * @param validTo           The window end, or null while open.
     * @param priority          The evaluation priority.
     * @param exclusive         Whether the rule consumes its lines.
     * @param monthlyCapPerCard The per-card monthly cap, or null.
     * @param active            Whether the rule is active.
     * @param specification     The JSON specification.
     * @return The updated rule.
     */
    @Transactional
    public FidelityRule updateRule(String code, String type, String label, LocalDateTime validFrom,
                                   LocalDateTime validTo, int priority, boolean exclusive,
                                   BigDecimal monthlyCapPerCard, boolean active, String specification) {
        FidelityRule rule = FidelityRule.findByCode(code);
        if (rule == null) {
            throw new AdminException("No rule with code '" + code + "'");
        }
        if (!clock.now().isBefore(rule.validFrom)) {
            throw new AdminException("Only a rule not yet in force can be edited; version it instead (§18)");
        }
        requireText(type, "type");
        if (!registry.hasFactory(type)) {
            throw new AdminException("Unknown rule type '" + type + "' (no factory deployed)");
        }
        List<String> violations = registry.validate(type, specification);
        if (!violations.isEmpty()) {
            throw new AdminException("Invalid specification for type '" + type + "': " + String.join("; ", violations));
        }
        if (validFrom == null) {
            throw new AdminException("validFrom is mandatory");
        }
        if (validFrom.toLocalDate().isBefore(clock.today())) {
            throw new AdminException("An edited rule cannot start in the past (§18)");
        }
        for (FidelityRule existing : FidelityRule.<FidelityRule>list("code", code)) {
            if (!existing.id.equals(rule.id) && windowsOverlap(validFrom, validTo, existing.validFrom, existing.validTo)) {
                throw new AdminException("Rule window overlaps an existing instance of code '" + code + "'");
            }
        }
        rule.type = type;
        rule.label = label;
        rule.validFrom = validFrom;
        rule.validTo = validTo;
        rule.priority = priority;
        rule.exclusive = exclusive;
        rule.monthlyCapPerCard = monthlyCapPerCard;
        rule.active = active;
        rule.specification = specification;
        rule.persist();
        return rule;
    }

    /**
     * Updates the end of application of a rule whose window has not ended yet (§18):
     * the end may be moved earlier or later, or cleared (null = open-ended again). The
     * one invariant is replay-neutrality — the current end has not passed, and the new
     * end may not be in the past — so no already-emitted ticket ever changes meaning at
     * replay. A rule whose window has ended is frozen forever; extending the end is
     * refused when it would overlap another instance of the same code (its successor
     * version).
     *
     * @param code    The rule code.
     * @param validTo The new window end (at or after today), or null for open-ended.
     * @return The updated rule.
     */
    @Transactional
    public FidelityRule updateEndDate(String code, LocalDateTime validTo) {
        FidelityRule rule = openRuleByCode(code);
        if (rule == null) {
            throw new AdminException("No rule with code '" + code + "' whose window is still open; "
                    + "an ended rule is frozen — version it instead (§18)");
        }
        if (validTo != null && validTo.toLocalDate().isBefore(clock.today())) {
            throw new AdminException("The end of application can never be set in the past (§18)");
        }
        for (FidelityRule existing : FidelityRule.<FidelityRule>list("code", code)) {
            if (!existing.id.equals(rule.id)
                    && windowsOverlap(rule.validFrom, validTo, existing.validFrom, existing.validTo)) {
                throw new AdminException("The new window would overlap another instance of code '" + code + "'");
            }
        }
        rule.validTo = validTo;
        rule.persist();
        return rule;
    }

    /**
     * Closes an open rule by posting a {@code validTo} not in the past (§18); the
     * specification then becomes immutable (§13). Kept as the GraphQL administration
     * gesture (§26); the UI edits the end of application through
     * {@link #updateEndDate(String, LocalDateTime)}.
     *
     * @param code    The rule code.
     * @param validTo The window end (must be at or after today).
     * @return The closed rule.
     */
    @Transactional
    public FidelityRule closeRule(String code, LocalDateTime validTo) {
        FidelityRule rule = openRuleByCode(code);
        if (rule == null) {
            throw new AdminException("No open rule with code '" + code + "'");
        }
        LocalDateTime end = validTo != null ? validTo : clock.today().atStartOfDay();
        if (end.toLocalDate().isBefore(clock.today())) {
            throw new AdminException("A rule is closed with a validTo not in the past (§18)");
        }
        rule.validTo = end;
        rule.persist();
        return rule;
    }

    /**
     * Duplicates a rule under a new code, copying its backbone and specification (§23.3) —
     * the "duplicate then adjust then close the old one" gesture. The new instance is left
     * for the operator to adjust.
     *
     * @param sourceCode The source rule code.
     * @param newCode    The new rule code (must not exist).
     * @return The duplicated rule.
     */
    @Transactional
    public FidelityRule duplicateRule(String sourceCode, String newCode) {
        FidelityRule source = latestRuleByCode(sourceCode);
        if (source == null) {
            throw new AdminException("No rule with code '" + sourceCode + "'");
        }
        requireText(newCode, "newCode");
        if (FidelityRule.findByCode(newCode) != null) {
            throw new AdminException("A rule with code '" + newCode + "' already exists");
        }
        FidelityRule copy = new FidelityRule();
        copy.code = newCode;
        copy.type = source.type;
        copy.label = source.label;
        copy.validFrom = source.validFrom;
        copy.validTo = source.validTo;
        copy.priority = source.priority;
        copy.exclusive = source.exclusive;
        copy.monthlyCapPerCard = source.monthlyCapPerCard;
        copy.active = source.active;
        copy.specification = source.specification;
        copy.persist();
        return copy;
    }

    // --------------------------------------------------
    // Cards (§23.1, §28, §32, §33.1)
    // --------------------------------------------------

    /**
     * Creates a loyalty card with a generated number (§33.1), the given status (§30.4).
     *
     * @param status The initial status (ACTIVE default, PENDING_ACTIVATION selectable).
     * @return The created account.
     */
    @Transactional
    public FidelityAccount createCard(AccountStatus status) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = cardNumbers.generate();
        account.status = status != null ? status : AccountStatus.ACTIVE;
        if (account.status == AccountStatus.ACTIVE) {
            account.activatedAt = clock.now();
        }
        account.persist();
        return account;
    }

    /**
     * Posts an ADJUSTMENT on a card — a signed gesture with a mandatory reason, out of
     * every cap, expirable like earn (§32.1).
     *
     * @param card   The card number.
     * @param amount The signed amount.
     * @param reason The mandatory reason.
     * @return The adjusted account.
     */
    @Transactional
    public FidelityAccount adjustCard(String card, BigDecimal amount, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminException("An adjustment reason is mandatory (§32.1)");
        }
        if (amount == null || amount.signum() == 0) {
            throw new AdminException("An adjustment amount is mandatory");
        }
        FidelityAccount account = lockOrThrow(card);
        ledger.post(account, MovementType.ADJUSTMENT, amount, clock.today(), null, null, List.of(), reason.trim());
        return account;
    }

    /**
     * Resiliates a card administratively (§26.5); refused on an active lease (§28.1).
     *
     * @param card The card number.
     * @return The resiliated account.
     */
    @Transactional
    public FidelityAccount resiliateCard(String card) {
        FidelityAccount account = lockOrThrow(card);
        refuseOnActiveLease(account);
        account.status = AccountStatus.RESILIATED;
        account.persist();
        return account;
    }

    /**
     * Transfers a card on loss/theft to a fresh card (§16, §28.1, §34.3): the balance is
     * decomposed by earnYear onto the new account, the memberships, activations and visit
     * headers follow it, and the old account is deactivated. Refused on an active lease
     * (§28.1).
     *
     * @param fromCard The card to transfer from.
     * @return The new account.
     */
    @Transactional
    public FidelityAccount transferCard(String fromCard) {
        FidelityAccount source = lockOrThrow(fromCard);
        refuseOnActiveLease(source);
        if (source.status == AccountStatus.RESILIATED) {
            throw new AdminException("A resiliated card cannot be transferred");
        }
        FidelityAccount target = new FidelityAccount();
        target.cardNumber = cardNumbers.generate();
        target.status = AccountStatus.ACTIVE;
        target.activatedAt = clock.now();
        target.persist();

        LocalDate today = clock.today();
        BigDecimal balance = source.balance;
        if (balance.signum() != 0) {
            // Capture the earnYear decomposition BEFORE the outgoing debit, so the FIFO
            // residual does not see its own transfer-out (§34.3).
            Map<Integer, BigDecimal> residual = residualByYear(source);
            // Outgoing on the source, incoming decomposed by earnYear on the target (§34.3).
            ledger.post(source, MovementType.TRANSFER, balance.negate(), today, null,
                    transferRef(source, target), List.of(), "Transfer to " + target.cardNumber);
            for (Map.Entry<Integer, BigDecimal> entry : residual.entrySet()) {
                if (entry.getValue().signum() > 0) {
                    postTransferIn(target, entry.getValue(), entry.getKey(), today, source, target);
                }
            }
        }
        // Carry the month's visits and the return-debit continuity (§28.1, §32.2).
        EarnTrace.update("cardNumber = ?1 where cardNumber = ?2", target.cardNumber, source.cardNumber);
        // Carry memberships and activations (§28.1).
        for (FidelityMembership membership : FidelityMembership.listForAccount(source)) {
            membership.account = target;
            membership.persist();
        }
        for (FidelityActivation activation : FidelityActivation.listForAccount(source)) {
            activation.account = target;
            activation.persist();
        }
        source.transferredToCard = target.cardNumber;
        source.status = AccountStatus.RESILIATED;
        ledger.refreshBalance(source);
        ledger.refreshBalance(target);
        return target;
    }

    // --------------------------------------------------
    // Memberships, activations, settings (§26.5, §28.4)
    // --------------------------------------------------

    // --------------------------------------------------
    // Community catalog (§23.2)
    // --------------------------------------------------

    /**
     * Creates a community from the administration form (§23.2) — the unit gesture; the
     * CSV import remains the central bulk feed, both upserting by the stable code.
     *
     * @param code                The unique business code.
     * @param label               The human label.
     * @param monthlyCap          The per-card monthly cap in euro, or null.
     * @param enrollmentCap       The maximum number of memberships, or null.
     * @param renewalStartMonth   First month (1-12) of the renewal window, or null.
     * @param renewalEndMonth     Last month (1-12) of the renewal window, or null.
     * @param eligibilityCriteria The descriptive eligibility criterion, or null.
     * @return The created community.
     */
    @Transactional
    public FidelityCommunity createCommunity(String code, String label, BigDecimal monthlyCap,
                                             Integer enrollmentCap, Integer renewalStartMonth,
                                             Integer renewalEndMonth, String eligibilityCriteria) {
        requireText(code, "code");
        requireText(label, "label");
        if (FidelityCommunity.findByCode(code.trim()) != null) {
            throw new AdminException("A community with code '" + code.trim() + "' already exists");
        }
        validateCommunityParams(monthlyCap, enrollmentCap, renewalStartMonth, renewalEndMonth);
        FidelityCommunity community = new FidelityCommunity();
        community.code = code.trim();
        applyCommunityParams(community, label, monthlyCap, enrollmentCap,
                renewalStartMonth, renewalEndMonth, eligibilityCriteria);
        community.active = true;
        community.persist();
        return community;
    }

    /**
     * Updates the parameters of a community (§23.2); the code, referenced by rule
     * specifications and by the ledger's per-community caps, is frozen.
     *
     * @param code                The code of the community to update.
     * @param label               The human label.
     * @param monthlyCap          The per-card monthly cap in euro, or null.
     * @param enrollmentCap       The maximum number of memberships, or null.
     * @param renewalStartMonth   First month (1-12) of the renewal window, or null.
     * @param renewalEndMonth     Last month (1-12) of the renewal window, or null.
     * @param eligibilityCriteria The descriptive eligibility criterion, or null.
     * @return The updated community.
     */
    @Transactional
    public FidelityCommunity updateCommunity(String code, String label, BigDecimal monthlyCap,
                                             Integer enrollmentCap, Integer renewalStartMonth,
                                             Integer renewalEndMonth, String eligibilityCriteria) {
        FidelityCommunity community = FidelityCommunity.findByCode(code);
        if (community == null) {
            throw new AdminException("Unknown community '" + code + "'");
        }
        requireText(label, "label");
        validateCommunityParams(monthlyCap, enrollmentCap, renewalStartMonth, renewalEndMonth);
        applyCommunityParams(community, label, monthlyCap, enrollmentCap,
                renewalStartMonth, renewalEndMonth, eligibilityCriteria);
        community.persist();
        return community;
    }

    /**
     * Opens or closes a community to new enrollments (§23.2). Deactivation blocks any
     * new membership only: existing members keep earning as long as their memberships
     * and the referencing rules run — extinction is driven by the rules' end of
     * application.
     *
     * @param code   The community code.
     * @param active Whether the community accepts new enrollments.
     * @return The updated community.
     */
    @Transactional
    public FidelityCommunity setCommunityActive(String code, boolean active) {
        FidelityCommunity community = FidelityCommunity.findByCode(code);
        if (community == null) {
            throw new AdminException("Unknown community '" + code + "'");
        }
        community.active = active;
        community.persist();
        return community;
    }

    /**
     * Copies the editable community parameters onto the entity.
     *
     * @param community           The target community.
     * @param label               The human label.
     * @param monthlyCap          The per-card monthly cap, or null.
     * @param enrollmentCap       The enrollment cap, or null.
     * @param renewalStartMonth   The renewal window start month, or null.
     * @param renewalEndMonth     The renewal window end month, or null.
     * @param eligibilityCriteria The eligibility criterion, or null.
     */
    private void applyCommunityParams(FidelityCommunity community, String label, BigDecimal monthlyCap,
                                      Integer enrollmentCap, Integer renewalStartMonth,
                                      Integer renewalEndMonth, String eligibilityCriteria) {
        community.label = label.trim();
        community.monthlyCap = monthlyCap;
        community.enrollmentCap = enrollmentCap;
        community.renewalStartMonth = renewalStartMonth;
        community.renewalEndMonth = renewalEndMonth;
        community.eligibilityCriteria = eligibilityCriteria == null || eligibilityCriteria.isBlank()
                ? null : eligibilityCriteria.trim();
    }

    /**
     * Validates the community parameters: non-negative caps, months within 1-12, and a
     * renewal window either absent or complete.
     *
     * @param monthlyCap        The per-card monthly cap, or null.
     * @param enrollmentCap     The enrollment cap, or null.
     * @param renewalStartMonth The renewal window start month, or null.
     * @param renewalEndMonth   The renewal window end month, or null.
     */
    private void validateCommunityParams(BigDecimal monthlyCap, Integer enrollmentCap,
                                         Integer renewalStartMonth, Integer renewalEndMonth) {
        if (monthlyCap != null && monthlyCap.signum() < 0) {
            throw new AdminException("The monthly cap cannot be negative");
        }
        if (enrollmentCap != null && enrollmentCap < 0) {
            throw new AdminException("The enrollment cap cannot be negative");
        }
        if ((renewalStartMonth == null) != (renewalEndMonth == null)) {
            throw new AdminException("A renewal window needs both its start and end months");
        }
        for (Integer month : new Integer[]{renewalStartMonth, renewalEndMonth}) {
            if (month != null && (month < 1 || month > 12)) {
                throw new AdminException("A renewal month must be between 1 and 12");
            }
        }
    }

    /**
     * Upserts a community membership over a window; enforces the enrollment cap on a new
     * membership (§28.4).
     *
     * @param card          The card number.
     * @param communityCode The community code.
     * @param validFrom     The window start.
     * @param validTo       The window end, or null while open.
     * @return The upserted membership.
     */
    @Transactional
    public FidelityMembership upsertMembership(String card, String communityCode, LocalDate validFrom, LocalDate validTo) {
        FidelityAccount account = lockOrThrow(card);
        FidelityCommunity community = FidelityCommunity.findByCode(communityCode);
        if (community == null) {
            throw new AdminException("Unknown community '" + communityCode + "'");
        }
        if (validFrom == null) {
            throw new AdminException("Membership validFrom is mandatory");
        }
        FidelityMembership existing = FidelityMembership.find(
                "account = ?1 and community = ?2 and validFrom = ?3", account, community, validFrom).firstResult();
        if (existing == null && !community.active) {
            throw new AdminException("Community '" + communityCode
                    + "' is closed to new enrollments (§23.2)");
        }
        if (existing == null && community.enrollmentCap != null) {
            long active = FidelityMembership.count(
                    "community = ?1 and (validTo is null or validTo >= ?2)", community, clock.today());
            if (active >= community.enrollmentCap) {
                throw new AdminException("Enrollment cap reached for community '" + communityCode
                        + "' (" + community.enrollmentCap + ", §28.4)");
            }
        }
        FidelityMembership membership = existing != null ? existing : new FidelityMembership();
        membership.account = account;
        membership.community = community;
        membership.validFrom = validFrom;
        membership.validTo = validTo;
        membership.persist();
        return membership;
    }

    /**
     * Replaces the whole membership set of a community from the workbench submission
     * (§23.2) — the screen is the complete source of truth, so a member absent from the
     * submission is removed (deletion by omission, §21.3). Enforces the enrollment cap on
     * the submitted size (§28.4), refuses new members when the community is closed to
     * enrollments (§23.2), and ignores an entry whose card is unknown.
     *
     * @param communityCode The community code.
     * @param entries       The complete membership set.
     * @return The number of memberships persisted.
     */
    @Transactional
    public int replaceMemberships(String communityCode, List<MembershipInput> entries) {
        FidelityCommunity community = FidelityCommunity.findByCode(communityCode);
        if (community == null) {
            throw new AdminException("Unknown community '" + communityCode + "'");
        }
        if (!community.active) {
            java.util.Set<String> existingCards = new java.util.HashSet<>();
            for (FidelityMembership membership : FidelityMembership.<FidelityMembership>list("community", community)) {
                if (membership.account != null) {
                    existingCards.add(membership.account.cardNumber);
                }
            }
            for (MembershipInput entry : entries) {
                if (entry != null && entry.card != null && !existingCards.contains(entry.card.trim())) {
                    throw new AdminException("Community '" + communityCode
                            + "' is closed to new enrollments: card " + entry.card.trim()
                            + " cannot be added (§23.2)");
                }
            }
        }
        List<MembershipInput> valid = new java.util.ArrayList<>();
        for (MembershipInput entry : entries) {
            if (entry != null && entry.card != null && !entry.card.isBlank() && entry.validFrom != null
                    && FidelityAccount.findByCardNumber(entry.card.trim()) != null) {
                valid.add(entry);
            }
        }
        if (community.enrollmentCap != null && valid.size() > community.enrollmentCap) {
            throw new AdminException("Enrollment cap reached for community '" + communityCode
                    + "' (" + community.enrollmentCap + ", §28.4)");
        }
        FidelityMembership.delete("community", community);
        int count = 0;
        for (MembershipInput entry : valid) {
            FidelityAccount account = FidelityAccount.findByCardNumber(entry.card.trim());
            FidelityMembership membership = new FidelityMembership();
            membership.account = account;
            membership.community = community;
            membership.validFrom = entry.validFrom;
            membership.validTo = entry.validTo;
            membership.persist();
            count++;
        }
        return count;
    }

    /**
     * Upserts a card activation over a period (§14, §24.3): e-coupon activation, engaged
     * challenge, completed mission.
     *
     * @param card        The card number.
     * @param ruleCode    The rule the activation enables.
     * @param periodStart The period start.
     * @param periodEnd   The period end, or null while open.
     * @param missionDone Whether the mission is completed.
     * @return The upserted activation.
     */
    @Transactional
    public FidelityActivation setActivation(String card, String ruleCode, LocalDate periodStart,
                                            LocalDate periodEnd, boolean missionDone) {
        FidelityAccount account = lockOrThrow(card);
        requireText(ruleCode, "ruleCode");
        if (periodStart == null) {
            throw new AdminException("Activation periodStart is mandatory");
        }
        FidelityActivation activation = FidelityActivation.find(
                "account = ?1 and ruleCode = ?2 and periodStart = ?3", account, ruleCode, periodStart).firstResult();
        if (activation == null) {
            activation = new FidelityActivation();
            activation.account = account;
            activation.ruleCode = ruleCode;
            activation.periodStart = periodStart;
        }
        activation.periodEnd = periodEnd;
        activation.missionDone = missionDone;
        activation.persist();
        return activation;
    }

    /**
     * Upserts a program setting (§25.1).
     *
     * @param key   The setting key.
     * @param value The setting value.
     * @return The upserted setting.
     */
    @Transactional
    public FidelityProgramSetting setProgramSetting(String key, String value) {
        requireText(key, "key");
        FidelityProgramSetting setting = FidelityProgramSetting.findByKey(key);
        if (setting == null) {
            setting = new FidelityProgramSetting();
            setting.key = key;
        }
        setting.value = value;
        setting.persist();
        return setting;
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Locks an account by card or throws when unknown (§30.1).
     *
     * @param card The card number.
     * @return The locked account.
     */
    private FidelityAccount lockOrThrow(String card) {
        FidelityAccount account = ledger.lock(card);
        if (account == null) {
            throw new AdminException("Unknown card '" + card + "'");
        }
        return account;
    }

    /**
     * Refuses a fin-de-vie gesture on an account holding an active lease (§28.1).
     *
     * @param account The account.
     */
    private void refuseOnActiveLease(FidelityAccount account) {
        FidelityReservation active = FidelityReservation.findActiveForAccount(account);
        if (active != null && active.isHolding(clock.now())) {
            throw new AdminException("Refused: the card holds an active burn reservation (§28.1)");
        }
    }

    /**
     * Posts an incoming TRANSFER on the target for one earnYear (§34.3).
     *
     * @param target   The target account.
     * @param amount   The residual amount of the year.
     * @param earnYear The earnYear to preserve.
     * @param date     The fiscal date of the movement.
     * @param source   The source account (for the ref).
     * @param targetForRef The target account (for the ref).
     */
    private void postTransferIn(FidelityAccount target, BigDecimal amount, int earnYear, LocalDate date,
                                FidelityAccount source, FidelityAccount targetForRef) {
        FidelityMovement movement = new FidelityMovement();
        movement.account = target;
        movement.type = MovementType.TRANSFER;
        movement.amount = amount;
        movement.movementDate = date;
        movement.earnYear = earnYear;
        movement.ticketRef = transferRef(source, targetForRef) + ":" + earnYear;
        movement.reason = "Transfer from " + source.cardNumber + " (earnYear " + earnYear + ")";
        movement.persist();
    }

    /**
     * Builds a stable transfer reference from the two card numbers.
     *
     * @param source The source account.
     * @param target The target account.
     * @return The transfer reference.
     */
    private String transferRef(FidelityAccount source, FidelityAccount target) {
        return "TRANSFER:" + source.cardNumber + ">" + target.cardNumber;
    }

    /**
     * Computes the residual balance of an account per earnYear after a FIFO consumption of
     * the debits from the oldest year first (§34.3).
     *
     * @param account The account.
     * @return A map of earnYear to residual euro, ascending, never null.
     */
    private Map<Integer, BigDecimal> residualByYear(FidelityAccount account) {
        Map<Integer, BigDecimal> credits = FidelityMovement.creditsByEarnYear(account);
        BigDecimal debits = FidelityMovement.totalDebits(account);
        java.util.Map<Integer, BigDecimal> residual = new java.util.LinkedHashMap<>();
        for (Map.Entry<Integer, BigDecimal> entry : credits.entrySet()) {
            BigDecimal credit = entry.getValue();
            BigDecimal consumed = debits.min(credit);
            residual.put(entry.getKey(), credit.subtract(consumed));
            debits = debits.subtract(consumed);
        }
        return residual;
    }

    /**
     * Returns the open rule of a code (validTo null or in the future), or null.
     *
     * @param code The rule code.
     * @return The open rule, or null.
     */
    private FidelityRule openRuleByCode(String code) {
        return FidelityRule.find("code = ?1 and (validTo is null or validTo > ?2) order by validFrom desc",
                code, clock.today().atStartOfDay()).firstResult();
    }

    /**
     * Returns the most recent instance of a rule code, or null.
     *
     * @param code The rule code.
     * @return The latest rule, or null.
     */
    private FidelityRule latestRuleByCode(String code) {
        return FidelityRule.find("code = ?1 order by validFrom desc", code).firstResult();
    }

    /**
     * Indicates whether two half-open windows [from, to) overlap (a null {@code to} means
     * open-ended).
     *
     * @param aFrom First window start.
     * @param aTo   First window end, or null.
     * @param bFrom Second window start.
     * @param bTo   Second window end, or null.
     * @return true when the windows overlap.
     */
    private boolean windowsOverlap(LocalDateTime aFrom, LocalDateTime aTo, LocalDateTime bFrom, LocalDateTime bTo) {
        boolean aBeforeB = aTo != null && !aTo.isAfter(bFrom);
        boolean bBeforeA = bTo != null && !bTo.isAfter(aFrom);
        return !(aBeforeB || bBeforeA);
    }

    /**
     * Requires a non-blank text argument.
     *
     * @param value The value.
     * @param name  The argument name for the message.
     */
    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AdminException(name + " is mandatory");
        }
    }
}
