package com.intermarche.fidelity.domain;

import io.quarkus.panache.common.Page;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A single entry of the loyalty ledger (§14).
 * <p>
 * The balance is the signed sum of the movements; expiry consumes them FIFO by
 * {@link #earnYear}. Every movement carries a natural idempotency key
 * {@code ticketRef + type + ruleCode} (I8, §29.4): ingestion is an upsert, so
 * outbox replays never double-count. Amounts are euro at scale 2 (§30.5).
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "fidelity_movements",
        indexes = {
                @Index(name = "idx_movement_account", columnList = "account_id"),
                @Index(name = "idx_movement_ticket", columnList = "ticket_ref"),
                @Index(name = "idx_movement_earn_year", columnList = "account_id, earn_year"),
                @Index(name = "idx_movement_natural_key", columnList = "ticket_ref, type, rule_code")
        }
)
public class FidelityMovement extends BaseEntity {

    /**
     * The account the movement belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    public FidelityAccount account;

    /**
     * The movement type from the engraved nomenclature (§14).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @NotNull(message = "Movement type is mandatory")
    public MovementType type;

    /**
     * The signed amount in euro at scale 2: positive for credits (earn, refund,
     * incoming transfer), negative for debits (burn, return, expiry, purge).
     */
    @Column(nullable = false, precision = 19, scale = 2)
    @NotNull(message = "Movement amount is mandatory")
    public BigDecimal amount;

    /**
     * The fiscal date of the movement (ticket date, withdrawal date for Drive),
     * at the program zone; drives caps and expiry, never the ingestion date (§16,
     * §30.3).
     */
    @Column(name = "movement_date", nullable = false)
    @NotNull(message = "Movement date is mandatory")
    public LocalDate movementDate;

    /**
     * The civil year of acquisition, derived from the fiscal date at the program
     * zone; needed for the 1st-March FIFO expiry (§14, §30.3).
     */
    @Column(name = "earn_year", nullable = false)
    public int earnYear;

    /**
     * The code of the rule that produced the movement; null for movements not
     * borne by a rule (REFUND_CREDIT, ADJUSTMENT, EXPIRY, PURGE, TRANSFER) (§29.4).
     */
    @Column(name = "rule_code", length = 50)
    public String ruleCode;

    /**
     * The originating ticket reference (store + number); part of the idempotency
     * key. Null for movements with no ticket (e.g. batch EXPIRY/PURGE).
     */
    @Column(name = "ticket_ref", length = 80)
    public String ticketRef;

    /**
     * The valuation line ids this movement was computed from; kept so a partial
     * return debit stays computable (§24.2). Never null (§31.2).
     */
    @ElementCollection
    @CollectionTable(name = "fidelity_movement_line_refs",
            joinColumns = @JoinColumn(name = "movement_id"))
    @Column(name = "line_ref", length = 60)
    @OrderColumn(name = "list_index")
    public List<String> lineRefs = new ArrayList<>();

    /**
     * The mandatory reason of an ADJUSTMENT, kept on the movement and shown in the
     * history (§32.1); null for other types.
     */
    @Column(length = 255)
    public String reason;

    // --------------------------------------------------
    // Domain helpers
    // --------------------------------------------------

    /**
     * Derives the civil year of acquisition from a fiscal date (§30.3).
     *
     * @param fiscalDate The fiscal date, already resolved at the program zone.
     * @return The civil year, or 0 when the date is null.
     */
    public static int earnYearOf(LocalDate fiscalDate) {
        return fiscalDate != null ? fiscalDate.getYear() : 0;
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Computes the balance of an account as the signed sum of its movements.
     * <p>
     * Returns zero (scale 2) when the account has no movement, so callers never
     * face a null total (§31.2).
     *
     * @param account The account.
     * @return The balance, euro at scale 2.
     */
    public static BigDecimal computeBalance(FidelityAccount account) {
        BigDecimal sum = find("select coalesce(sum(m.amount), 0) from FidelityMovement m where m.account = ?1", account)
                .project(BigDecimal.class).firstResult();
        BigDecimal total = sum != null ? sum : BigDecimal.ZERO;
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Finds the movement matching a natural idempotency key (§29.4). A null
     * ruleCode matches movements with a null rule code.
     *
     * @param ticketRef The ticket reference.
     * @param type      The movement type.
     * @param ruleCode  The rule code, may be null.
     * @return The matching movement, or null.
     */
    public static FidelityMovement findByNaturalKey(String ticketRef, MovementType type, String ruleCode) {
        if (ruleCode == null) {
            return find("ticketRef = ?1 and type = ?2 and ruleCode is null", ticketRef, type).firstResult();
        }
        return find("ticketRef = ?1 and type = ?2 and ruleCode = ?3", ticketRef, type, ruleCode).firstResult();
    }

    /**
     * Returns a page of an account's movements, most recent first.
     *
     * @param account   The account.
     * @param pageIndex Zero-based page index.
     * @param pageSize  Page size.
     * @return The requested page of movements, never null.
     */
    public static List<FidelityMovement> pageForAccount(FidelityAccount account, int pageIndex, int pageSize) {
        return find("account = ?1 order by movementDate desc, id desc", account)
                .page(Page.of(pageIndex, pageSize)).list();
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum from the movement's business fields, using the account
     * business code. A movement is never updated after creation.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        String cardNumber = account != null ? account.cardNumber : null;
        return Objects.hash(cardNumber, type, amount, movementDate, earnYear, ruleCode, ticketRef, reason);
    }
}
