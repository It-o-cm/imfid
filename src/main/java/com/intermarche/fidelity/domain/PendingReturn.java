package com.intermarche.fidelity.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Objects;

/**
 * A return event held until its origin ticket arrives (§29.3).
 * <p>
 * A {@code ticket-return} can be replayed before the {@code ticket-closed} of its
 * origin; ingestion never rejects a fiscal event for ordering — it stores the return
 * here and replays it automatically when the origin is ingested. Idempotent by
 * {@code returnTicketRef}: the same return held twice is one row.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "pending_returns",
        indexes = {
                @Index(name = "idx_pending_return_ticket", columnList = "return_ticket_ref"),
                @Index(name = "idx_pending_return_origin", columnList = "origin_ticket_ref")
        }
)
public class PendingReturn extends BaseEntity {

    /**
     * The reference of the return ticket; the idempotency key of the held return (§29.4).
     */
    @Column(name = "return_ticket_ref", unique = true, nullable = false, length = 80)
    @NotBlank(message = "Return ticket reference is mandatory")
    public String returnTicketRef;

    /**
     * The reference of the origin ticket awaited (§29.3).
     */
    @Column(name = "origin_ticket_ref", nullable = false, length = 80)
    @NotBlank(message = "Origin ticket reference is mandatory")
    public String originTicketRef;

    /**
     * The full return event payload, stored verbatim so it can be replayed as is when
     * the origin arrives (§29.3).
     */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "payload", nullable = false)
    public String payload;

    /**
     * Finds a held return by its return ticket reference.
     *
     * @param returnTicketRef The return ticket reference.
     * @return The held return, or null when none matches.
     */
    public static PendingReturn findByReturnTicketRef(String returnTicketRef) {
        return find("returnTicketRef", returnTicketRef).firstResult();
    }

    /**
     * Lists the returns held awaiting a given origin ticket (§29.3).
     *
     * @param originTicketRef The origin ticket reference.
     * @return The held returns, never null.
     */
    public static List<PendingReturn> listAwaitingOrigin(String originTicketRef) {
        return list("originTicketRef", originTicketRef);
    }

    /**
     * Calculates a checksum from the held return's references.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(returnTicketRef, originTicketRef);
    }
}
