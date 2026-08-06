package com.intermarche.fidelity.account;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.util.ProgramClock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The single writer of the loyalty ledger (§14, §16) — every account write goes
 * through here, under the per-card lock the caller holds (§30.1). It posts movements
 * idempotently by their natural key (ticketRef + type + ruleCode, I8), keeps the
 * denormalized balance the signed sum of the movements (§14) and advances
 * {@code lastUsedAt} on the fiscal ticket movements that drive the 24-month purge
 * (§14, §16).
 * <p>
 * It also exposes the available balance — balance minus the active reservations (I11,
 * §27.2) — the figure a reservation and the expiry batch decide on (§28.3).
 */
@ApplicationScoped
public class LedgerService {

    /**
     * The movement types that count as a fiscal use of the card and advance
     * {@code lastUsedAt} (ticket activity, §14); the ledger-management types
     * (ADJUSTMENT, EXPIRY, PURGE, ACTIVATION_VOID, TRANSFER) do not.
     */
    private static final Set<MovementType> FISCAL_USE = EnumSet.of(
            MovementType.EARN, MovementType.BURN, MovementType.RETURN_DEBIT, MovementType.REFUND_CREDIT);

    /**
     * The program clock resolving the fiscal use timestamp (§30.3).
     */
    @Inject
    ProgramClock clock;

    /**
     * Locks an account by card number for a write (§30.1). Must be called within a
     * transaction.
     *
     * @param cardNumber The card number.
     * @return The locked account, or null when unknown.
     */
    public FidelityAccount lock(String cardNumber) {
        return cardNumber == null ? null : FidelityAccount.lockByCardNumber(cardNumber.trim());
    }

    /**
     * Posts a movement to an account idempotently by its natural key (I8), recomputing
     * the balance and advancing {@code lastUsedAt} for a fiscal-use type (§14).
     * <p>
     * A movement whose natural key already exists is returned unchanged — the outbox
     * replay never double-counts (§16, §27.4). The account must already be locked by
     * the caller (§30.1).
     *
     * @param account   The locked account.
     * @param type      The movement type.
     * @param amount    The signed amount, euro at scale 2.
     * @param fiscalDate The fiscal date at the program zone (drives earnYear, §30.3).
     * @param ruleCode  The crediting rule code, or null (§29.4).
     * @param ticketRef The ticket reference, or null (batch movements).
     * @param lineRefs  The contributing line ids; null read as empty (§31.2).
     * @param reason    The mandatory reason of an ADJUSTMENT, or null (§32.1).
     * @return The posted (or pre-existing) movement, never null.
     */
    public FidelityMovement post(FidelityAccount account, MovementType type, BigDecimal amount,
                                 LocalDate fiscalDate, String ruleCode, String ticketRef,
                                 List<String> lineRefs, String reason) {
        if (ticketRef != null) {
            FidelityMovement existing = FidelityMovement.findByNaturalKey(ticketRef, type, ruleCode);
            if (existing != null) {
                return existing;
            }
        }
        FidelityMovement movement = new FidelityMovement();
        movement.account = account;
        movement.type = type;
        movement.amount = amount != null ? amount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        movement.movementDate = fiscalDate;
        movement.earnYear = FidelityMovement.earnYearOf(fiscalDate);
        movement.ruleCode = ruleCode;
        movement.ticketRef = ticketRef;
        if (lineRefs != null) {
            movement.lineRefs.addAll(lineRefs);
        }
        movement.reason = reason;
        movement.persist();
        refreshBalance(account);
        if (FISCAL_USE.contains(type) && fiscalDate != null) {
            advanceLastUsed(account, fiscalDate);
        }
        return movement;
    }

    /**
     * Recomputes and stores an account's denormalized balance as the signed sum of its
     * movements (§14).
     *
     * @param account The account to refresh.
     */
    public void refreshBalance(FidelityAccount account) {
        account.balance = FidelityMovement.computeBalance(account);
        account.persist();
    }

    /**
     * Advances {@code lastUsedAt} to the fiscal use, never rewinding it (a later replay
     * of an older ticket must not pull the purge clock back, §14, §16).
     *
     * @param account    The account.
     * @param fiscalDate The fiscal date of the use.
     */
    private void advanceLastUsed(FidelityAccount account, LocalDate fiscalDate) {
        java.time.LocalDateTime moment = fiscalDate.atStartOfDay();
        if (account.lastUsedAt == null || account.lastUsedAt.isBefore(moment)) {
            account.lastUsedAt = moment;
            account.persist();
        }
    }

    /**
     * Returns the available balance = balance − the sum of the account's active
     * reservations (I11, §27.2) — the figure a burn reservation and the expiry batch
     * decide on (§28.3).
     *
     * @param account The account.
     * @return The available balance, euro at scale 2, never null.
     */
    public BigDecimal availableBalance(FidelityAccount account) {
        BigDecimal held = activeReservationTotal(account);
        return account.balance.subtract(held).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Sums the amounts of an account's still-holding reservations (ACTIVE and not
     * expired at the current program time, I11).
     *
     * @param account The account.
     * @return The held total, euro at scale 2, never null.
     */
    public BigDecimal activeReservationTotal(FidelityAccount account) {
        java.time.LocalDateTime now = clock.now();
        BigDecimal held = BigDecimal.ZERO;
        FidelityReservation active = FidelityReservation.findActiveForAccount(account);
        if (active != null && active.isHolding(now)) {
            held = held.add(active.amount);
        }
        return held.setScale(2, RoundingMode.HALF_UP);
    }
}
