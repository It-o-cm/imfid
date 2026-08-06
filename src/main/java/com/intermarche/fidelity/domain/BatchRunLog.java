package com.intermarche.fidelity.domain;

import io.quarkus.panache.common.Page;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * The record of a batch run, for the "Programme" supervision screen (§23.4): the last
 * run of each batch with its date, the accounts it touched and the amounts it moved.
 * <p>
 * Only an actual execution is logged, never a dry-run simulation (§23.4, §32.3).
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "batch_run_logs",
        indexes = @Index(name = "idx_batch_run_type", columnList = "batch_type, run_at")
)
public class BatchRunLog extends BaseEntity {

    /**
     * The batch type ({@code EXPIRY} | {@code PURGE} | {@code ACTIVATION_VOID}).
     */
    @Column(name = "batch_type", nullable = false, length = 30)
    public String batchType;

    /**
     * When the batch ran, at the program zone.
     */
    @Column(name = "run_at", nullable = false)
    public LocalDateTime runAt;

    /**
     * The number of accounts the batch touched.
     */
    @Column(name = "accounts_affected", nullable = false)
    public int accountsAffected;

    /**
     * The total amount the batch moved (positive magnitude), euro at scale 2.
     */
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    public BigDecimal totalAmount = BigDecimal.ZERO;

    /**
     * Returns the most recent run of a batch type, or null when it never ran (§23.4).
     *
     * @param batchType The batch type.
     * @return The last run, or null.
     */
    public static BatchRunLog lastRun(String batchType) {
        return find("batchType = ?1 order by runAt desc", batchType).firstResult();
    }

    /**
     * Returns the most recent runs across all batch types, newest first.
     *
     * @param limit The maximum number of runs to return.
     * @return The latest runs, never null.
     */
    public static List<BatchRunLog> latest(int limit) {
        return find("order by runAt desc").page(Page.ofSize(limit)).list();
    }

    /**
     * Calculates a checksum from the run's business fields.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(batchType, runAt, accountsAffected, totalAmount);
    }
}
