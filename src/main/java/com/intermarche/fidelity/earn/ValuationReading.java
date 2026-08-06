package com.intermarche.fidelity.earn;

import com.intermarche.fidelity.rule.ValuedLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of reading a {@code /valuation} response for the earn (§22): the valued
 * lines (one per {@code lineId}), the TTC total price that founds the burnable base
 * (§22.3) and the non-blocking warnings raised while reading (unknown EAN, §25.4).
 * <p>
 * A reading is a side-effect-free value: {@code /earn} is a read (§30.2). The
 * reconciliation invariants (Σ items = amount, coherent totals) are enforced by the
 * reader before a reading is produced, throwing a {@link ValuationReconciliationException}
 * on violation (§22.1, §25.2). Fields are public and final: the reading is an
 * immutable carrier.
 */
public final class ValuationReading {

    /**
     * The valued lines, one per {@code lineId}, in encounter order (§22.1).
     */
    public final List<ValuedLine> lines;

    /**
     * The TTC total price of the basket — the reference of the maximal burn and the
     * base of the burnable computation (§22.3), euro at scale 2.
     */
    public final BigDecimal totalPriceTtc;

    /**
     * The warnings raised while reading (unknown EAN, §25.4); never null (§31.2).
     */
    public final List<EarnResponse.Warning> warnings;

    /**
     * Builds a reading, taking defensive copies and normalizing the total price to
     * scale 2 (§30.5).
     *
     * @param lines         The valued lines; null read as empty.
     * @param totalPriceTtc The TTC total price; null read as zero.
     * @param warnings      The warnings; null read as empty.
     */
    public ValuationReading(List<ValuedLine> lines, BigDecimal totalPriceTtc, List<EarnResponse.Warning> warnings) {
        this.lines = lines == null ? List.of() : List.copyOf(lines);
        BigDecimal total = totalPriceTtc != null ? totalPriceTtc : BigDecimal.ZERO;
        this.totalPriceTtc = total.setScale(2, RoundingMode.HALF_UP);
        this.warnings = warnings == null ? new ArrayList<>() : new ArrayList<>(warnings);
    }
}
