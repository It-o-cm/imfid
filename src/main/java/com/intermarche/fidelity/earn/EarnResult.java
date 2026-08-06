package com.intermarche.fidelity.earn;

import com.intermarche.fidelity.rule.ValuedLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The internal outcome of an earn evaluation (§15) — shared by the {@code /earn}
 * projection (§27.1) and the ingestion recalc (§26.1). It carries the post-cap earn
 * entries, the cap truncation traces, the total, the burnable base and the warnings,
 * plus the valued lines it was computed from so the ingestion can persist the per-line
 * trace (§24.2) without re-reading the couple.
 * <p>
 * A mutable builder filled by {@link EarnEngine}; the resource maps it to an
 * {@link EarnResponse}. Amounts are euro at scale 2 (§30.5).
 */
public final class EarnResult {

    /**
     * The post-cap earn entries, in evaluation order (§15).
     */
    public final List<EarnResponse.Entry> entries = new ArrayList<>();

    /**
     * The cap truncation traces (§15, I5).
     */
    public final List<EarnResponse.CapApplied> capsApplied = new ArrayList<>();

    /**
     * The non-blocking warnings (§25.4, §27.1).
     */
    public final List<EarnResponse.Warning> warnings = new ArrayList<>();

    /**
     * The valued lines the evaluation read (§22.1), kept for the ingestion per-line
     * trace (§24.2).
     */
    public final List<ValuedLine> lines;

    /**
     * The earn total after caps, euro at scale 2.
     */
    public BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /**
     * The burnable base = {@code totalPrice} − net of the program-excluded lines,
     * clamped to zero, euro at scale 2 (§22.3).
     */
    public BigDecimal burnableBase = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /**
     * Builds a result carrying the valued lines it derives from.
     *
     * @param lines The valued lines; null read as empty.
     */
    public EarnResult(List<ValuedLine> lines) {
        this.lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * Maps this internal result to the {@code /earn} projection response (§27.1).
     *
     * @return The response DTO, never null.
     */
    public EarnResponse toResponse() {
        EarnResponse response = new EarnResponse();
        response.total = total;
        response.entries = new ArrayList<>(entries);
        response.capsApplied = new ArrayList<>(capsApplied);
        response.burnableBase = burnableBase;
        response.warnings = new ArrayList<>(warnings);
        return response;
    }
}
