package com.intermarche.fidelity.domain;

import io.quarkus.panache.common.Page;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The ticket header of the earn trace (§24.2, §29.1) — the imfid counterpart of
 * the POS {@code ticket_line_valuations}, kept per ingested ticket.
 * <p>
 * Ingestion always writes a header, even without any earn entry: visits derive
 * from these headers (a distinct civil day at the program zone bearing a
 * card-carrying ticket counts as a visit, whether it earned or not, §29.1). The
 * per-line earn detail hangs off it, so a partial return debit stays computable
 * and the connector is debuggable line by line (§24.2). Only ingestion feeds
 * this trace — {@code POST /earn} is a side-effect-free read (§30.2).
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "earn_traces",
        indexes = {
                @Index(name = "idx_trace_ticket", columnList = "ticket_ref"),
                @Index(name = "idx_trace_card", columnList = "card_number"),
                @Index(name = "idx_trace_visit", columnList = "card_number, fiscal_date")
        }
)
public class EarnTrace extends BaseEntity {

    /**
     * Ingestion that recalculated the earn to completion.
     */
    public static final String STATUS_SUCCESS = "SUCCESS";

    /**
     * Ingestion that traced the header but created no movement — resiliated account
     * or reconciliation issue (§34.2).
     */
    public static final String STATUS_NO_MOVEMENT = "NO_MOVEMENT";

    /**
     * Ingestion interrupted by an error.
     */
    public static final String STATUS_FAILED = "FAILED";

    // --------------------------------------------------
    // Context
    // --------------------------------------------------

    /**
     * The ticket reference (store + number) this trace records.
     */
    @Column(name = "ticket_ref", nullable = false, length = 80)
    public String ticketRef;

    /**
     * The card number the ticket carried; copied out for filtering and visit
     * derivation.
     */
    @Column(name = "card_number", length = 40)
    public String cardNumber;

    /**
     * The store the ticket was closed at, copied out for filtering.
     */
    @Column(name = "store_code", length = 20)
    public String storeCode;

    /**
     * The fiscal date of the ticket at the program zone (withdrawal date for Drive);
     * the civil day that counts the visit (I3, §29.1).
     */
    @Column(name = "fiscal_date", nullable = false)
    public LocalDate fiscalDate;

    // --------------------------------------------------
    // Outcome
    // --------------------------------------------------

    /**
     * The ingestion outcome (one of the {@code STATUS_*} constants).
     */
    @Column(nullable = false, length = 20)
    public String status;

    /**
     * The earn total displayed at the register and carried by the event for trace;
     * never authoritative (§26.1). Euro at scale 2.
     */
    @Column(name = "displayed_earn", precision = 19, scale = 2)
    public BigDecimal displayedEarn;

    /**
     * The authoritative earn total recomputed at ingestion — the recalc prevails
     * (§26.1). Euro at scale 2.
     */
    @Column(name = "recalculated_earn", precision = 19, scale = 2)
    public BigDecimal recalculatedEarn;

    /**
     * Warnings raised at ingestion: unknown EAN (§25.4), displayed/recalc drift
     * (§26.1), expired-lease confirmation (§29.2), resiliated-account event
     * (§34.2). Never null (§31.2).
     */
    @ElementCollection
    @CollectionTable(name = "earn_trace_warnings",
            joinColumns = @JoinColumn(name = "trace_id"))
    @Column(name = "warning", length = 500)
    @OrderColumn(name = "list_index")
    public List<String> warnings = new ArrayList<>();

    // --------------------------------------------------
    // Payloads
    // --------------------------------------------------

    /**
     * The {@code /valuation} request of the fiscal event, stored verbatim (§26.1).
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "request_payload")
    public String requestPayload;

    /**
     * The {@code /valuation} response of the fiscal event, stored verbatim (§26.1).
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "response_payload")
    public String responsePayload;

    // --------------------------------------------------
    // Lines
    // --------------------------------------------------

    /**
     * The per-line earn detail; empty for a ticket that earned nothing (the header
     * is still written for the visit, §29.1).
     */
    @OneToMany(mappedBy = "trace", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderColumn(name = "list_index")
    public List<EarnTraceLine> lines = new ArrayList<>();

    // --------------------------------------------------
    // Domain helpers
    // --------------------------------------------------

    /**
     * Adds an earn detail line and wires its back-reference to this trace.
     *
     * @param line The line to attach; ignored when null.
     */
    public void addLine(EarnTraceLine line) {
        if (line == null) {
            return;
        }
        line.trace = this;
        this.lines.add(line);
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds the trace of a ticket by its reference.
     *
     * @param ticketRef The ticket reference.
     * @return The trace, or null if none matches.
     */
    public static EarnTrace findByTicketRef(String ticketRef) {
        return find("ticketRef", ticketRef).firstResult();
    }

    /**
     * Counts the distinct fiscal civil days a card was seen on, within a date range
     * — the visit count of the period (§29.1). The range bounds are inclusive.
     *
     * @param cardNumber The card number.
     * @param from       First fiscal date of the range (inclusive).
     * @param to         Last fiscal date of the range (inclusive).
     * @return The number of distinct visit days.
     */
    public static long countVisits(String cardNumber, LocalDate from, LocalDate to) {
        return find("select count(distinct t.fiscalDate) from EarnTrace t " +
                "where t.cardNumber = ?1 and t.fiscalDate >= ?2 and t.fiscalDate <= ?3",
                cardNumber, from, to).project(Long.class).firstResult();
    }

    /**
     * Returns the most recent traces, newest first.
     *
     * @param limit The maximum number of traces to return.
     * @return The latest traces, never null.
     */
    public static List<EarnTrace> findLatest(int limit) {
        return find("order by createdAt desc").page(Page.ofSize(limit)).list();
    }

    /**
     * Deletes every trace created before the given instant; retention is aligned on
     * that of the movements (§25.5).
     *
     * @param threshold The cut-off instant; traces older than this are removed.
     * @return The number of deleted traces.
     */
    public static long deleteOlderThan(LocalDateTime threshold) {
        return delete("createdAt < ?1", threshold);
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum from the traced exchange; the lines are excluded as they
     * derive from the recomputed earn. A trace is never updated after creation.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(ticketRef, cardNumber, storeCode, fiscalDate, status,
                displayedEarn, recalculatedEarn);
    }
}
