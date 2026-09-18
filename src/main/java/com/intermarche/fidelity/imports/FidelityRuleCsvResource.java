package com.intermarche.fidelity.imports;

import com.intermarche.fidelity.domain.AdvantageCategory;
import com.intermarche.fidelity.domain.AdvantageType;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.rule.EarnRuleRegistry;
import io.quarkus.hibernate.orm.panache.Panache;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bulk CSV import of the administrable earn rules ({@code FIDELITY_RULES}, §12, §18).
 * <p>
 * Every row carries the rule backbone plus its JSON specification (§13). The import
 * enforces the extension contract (§12, I10): a row whose {@code type} has no
 * deployed factory is refused, and a specification that does not validate its
 * factory's JSON Schema is refused — a rule in base without an interpreter, or one
 * inconsistent with its schema, is a configuration error, not a silent case. The
 * {@link FidelityRule} lifecycle re-materializes its {@code FidelityRuleTier} rows
 * from the specification on every write, the JSON staying the source of truth (§13).
 * <p>
 * Consumed columns (resolved by header name; unknown columns of the shared
 * feed are ignored): CODE (key), TYPE, LABEL, VALID_FROM, VALID_TO, PRIORITY,
 * EXCLUSIVE, MONTHLY_CAP_PER_CARD, ACTIVE, SPECIFICATION — the SPECIFICATION
 * column declared last in the header so an embedded delimiter is tolerated
 * (the tail cells are re-joined).
 * The specification must be a single JSON object on the row. The {@code fid-admin}
 * role guard (§24.1) is attached in the security build step; the staged fallback
 * and checksum optimization come from {@link ImporterCsvResource}.
 */
@Path("/fidelity/rules/import")
@ApplicationScoped
@RunOnVirtualThread
public class FidelityRuleCsvResource extends ImporterCsvResource {

    /** Header name of the natural key: the rule code. */
    static final String COL_CODE = "CODE";
    /** Header name of the rule type (must have a deployed factory). */
    static final String COL_TYPE = "TYPE";
    /** Header name of the rule label. */
    static final String COL_LABEL = "LABEL";
    /** Header name of the validity window start. */
    static final String COL_VALID_FROM = "VALID_FROM";
    /** Header name of the validity window end. */
    static final String COL_VALID_TO = "VALID_TO";
    /** Header name of the rule priority. */
    static final String COL_PRIORITY = "PRIORITY";
    /** Header name of the exclusive flag. */
    static final String COL_EXCLUSIVE = "EXCLUSIVE";
    /** Header name of the per-card monthly cap. */
    static final String COL_MONTHLY_CAP_PER_CARD = "MONTHLY_CAP_PER_CARD";
    /** Header name of the active flag. */
    static final String COL_ACTIVE = "ACTIVE";

    /**
     * The advantage-type column (RFP BO-03-03-25) — mandatory on a crediting rule.
     */
    static final String COL_ADVANTAGE_TYPE = "ADVANTAGE_TYPE";

    /**
     * The optional advantage-category column (RFP BO-03-03-33).
     */
    static final String COL_ADVANTAGE_CATEGORY = "ADVANTAGE_CATEGORY";
    /** Header name of the JSON specification (last column, possibly delimiter-bearing). */
    static final String COL_SPECIFICATION = "SPECIFICATION";

    /** The columns this importer cannot work without. */
    private static final List<String> REQUIRED_COLUMNS = List.of(
            COL_TYPE, COL_LABEL, COL_VALID_FROM, COL_VALID_TO, COL_PRIORITY,
            COL_EXCLUSIVE, COL_MONTHLY_CAP_PER_CARD, COL_ACTIVE,
            COL_ADVANTAGE_TYPE, COL_ADVANTAGE_CATEGORY, COL_SPECIFICATION);

    /**
     * The registry validating the type and the specification of each rule (§12).
     */
    @Inject
    EarnRuleRegistry registry;

    /**
     * Imports or updates earn rules from a CSV stream (§12, §18).
     *
     * @param inputStream The rule CSV stream.
     * @return The JSON import report (created/updated counts and errors).
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    public Response importRules(InputStream inputStream) {
        return this.importCsvStream(inputStream, COL_CODE, REQUIRED_COLUMNS);
    }

    /**
     * Bulk-fetches the rules already present for the chunk, keyed by code.
     *
     * @param parsedLines The rows of the current chunk.
     * @param targetCodes The distinct rule codes present in the chunk.
     * @param counters    The global {@code [created, updated]} counters.
     * @param errors      The accumulating list of definitive row errors.
     * @return A map of code to existing {@link FidelityRule}, never null.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        Map<String, Object> contextMap = new HashMap<>();
        if (!parsedLines.isEmpty() && !targetCodes.isEmpty()) {
            for (FidelityRule rule : FidelityRule.<FidelityRule>list("code in ?1", targetCodes)) {
                contextMap.put(rule.code, rule);
            }
        }
        return contextMap;
    }

    /**
     * Creates a new rule or updates an existing one after validating its type and
     * specification, skipping unchanged rows through the checksum optimization (§12).
     * <p>
     * An unknown type or an invalid specification raises an exception, which the
     * staged fallback isolates to the offending row and reports (§12).
     *
     * @param data      The row to process.
     * @param entityMap A map of code to existing {@link FidelityRule}.
     * @param counters  The local {@code [created, updated]} counters to increment.
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        String type = safeGetNonBlank(data, COL_TYPE);
        String specification = specificationOf(data);
        validateRule(data.code, type, specification);

        FidelityRule rule = (FidelityRule) entityMap.get(data.code);
        if (rule == null) {
            rule = new FidelityRule();
            rule.code = data.code;
            feedRule(data, rule);
            counters[0]++;
            Panache.getEntityManager().persist(rule);
        } else if (rule.checksum == null || rule.checksum != computeIncomingChecksum(data)) {
            rule = FidelityRule.findById(rule.id);
            feedRule(data, rule);
            counters[1]++;
        }
    }

    /**
     * Looks up a rule by code for the 1-by-1 fallback.
     *
     * @param data The row to look up.
     * @return The {@link FidelityRule}, or null when absent.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return FidelityRule.findByCode(data.code);
    }

    /**
     * Validates that the rule type has a deployed factory and that the specification
     * validates its schema, raising a descriptive exception otherwise (§12, I10).
     *
     * @param code          The rule code, for the message.
     * @param type          The rule type.
     * @param specification The rule specification JSON.
     */
    private void validateRule(String code, String type, String specification) {
        if (type == null) {
            throw new IllegalArgumentException("missing rule type");
        }
        if (!registry.hasFactory(type)) {
            throw new IllegalArgumentException("unknown rule type '" + type + "' (no factory deployed)");
        }
        List<String> violations = registry.validate(type, specification);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("specification invalid for type '" + type + "': "
                    + String.join("; ", violations));
        }
    }

    /**
     * Populates a rule's backbone and specification from a parsed row; the tiers are
     * re-materialized by the entity lifecycle from the specification (§13).
     *
     * @param data The row carrying the values.
     * @param rule The rule to populate.
     */
    private void feedRule(LineData data, FidelityRule rule) {
        rule.type = safeGetNonBlank(data, COL_TYPE);
        rule.label = safeGet(data, COL_LABEL);
        LocalDateTime validFrom = safeParseDateTime(data, COL_VALID_FROM);
        if (validFrom == null) {
            throw new IllegalArgumentException("missing or malformed validFrom (ISO date-time)");
        }
        rule.validFrom = validFrom;
        rule.validTo = safeParseDateTime(data, COL_VALID_TO);
        Integer priority = safeParseInt(data, COL_PRIORITY);
        rule.priority = priority != null ? priority : 0;
        rule.exclusive = safeParseBoolean(data, COL_EXCLUSIVE);
        rule.monthlyCapPerCard = safeParseBigDecimal(data, COL_MONTHLY_CAP_PER_CARD);
        rule.active = parseActive(data);
        rule.specification = specificationOf(data);
        rule.advantageType = advantageTypeOf(data, rule.type);
        rule.advantageCategory = advantageCategoryOf(data);
    }

    /**
     * Reads and validates the mandatory advantage type of a crediting row against
     * the referential (RFP BO-03-03-25); a program exclusion carries none.
     *
     * @param data The parsed CSV line.
     * @param type The rule (mechanic) type.
     * @return The advantage-type code, or null for a program exclusion.
     * @throws IllegalArgumentException when absent on a crediting row or unknown.
     */
    private String advantageTypeOf(LineData data, String type) {
        String value = safeGetNonBlank(data, COL_ADVANTAGE_TYPE);
        if (FidelityRule.TYPE_PROGRAM_EXCLUSION.equals(type)) {
            return null;
        }
        if (value == null) {
            throw new IllegalArgumentException("missing ADVANTAGE_TYPE on a crediting rule (§18)");
        }
        if (AdvantageType.findByCode(value) == null) {
            throw new IllegalArgumentException("unknown advantage type '" + value + "' (§18)");
        }
        return value;
    }

    /**
     * Reads and validates the optional advantage category of a row against the
     * referential (RFP BO-03-03-33).
     *
     * @param data The parsed CSV line.
     * @return The category code, or null when the column is blank.
     * @throws IllegalArgumentException when the code is unknown.
     */
    private String advantageCategoryOf(LineData data) {
        String value = safeGetNonBlank(data, COL_ADVANTAGE_CATEGORY);
        if (value != null && AdvantageCategory.findByCode(value) == null) {
            throw new IllegalArgumentException("unknown advantage category '" + value + "' (§18)");
        }
        return value;
    }

    /**
     * Reconstructs the specification column, joining any tail cells beyond the
     * header-resolved {@code SPECIFICATION} index with the pipe delimiter so a
     * JSON specification bearing a {@code |} survives the split — which is why
     * the file contract declares {@code SPECIFICATION} as the LAST header column.
     *
     * @param data The parsed CSV line.
     * @return The specification JSON string, or null when absent.
     */
    private String specificationOf(LineData data) {
        Integer specIndex = data.header.get(COL_SPECIFICATION);
        if (specIndex == null || data.parts.length <= specIndex) {
            return null;
        }
        if (data.parts.length == specIndex + 1) {
            String single = data.parts[specIndex];
            return single == null ? null : single.trim();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = specIndex; i < data.parts.length; i++) {
            if (i > specIndex) {
                sb.append('|');
            }
            sb.append(data.parts[i] == null ? "" : data.parts[i]);
        }
        return sb.toString().trim();
    }

    /**
     * Parses the {@code ACTIVE} column, defaulting to {@code true} when blank so a
     * rule is active unless explicitly deactivated.
     *
     * @param data The parsed CSV line.
     * @return true unless the column explicitly reads {@code false}.
     */
    private boolean parseActive(LineData data) {
        String value = safeGetNonBlank(data, COL_ACTIVE);
        return value == null || Boolean.parseBoolean(value);
    }

    /**
     * Computes the checksum of a row's incoming values, mirroring
     * {@link FidelityRule#getChecksum()} so an unchanged row is skipped.
     *
     * @param data The row to hash.
     * @return The incoming checksum.
     */
    private int computeIncomingChecksum(LineData data) {
        // Field order mirrors FidelityRule#getChecksum() exactly, so an unchanged
        // row hashes identically and is skipped.
        return Objects.hash(
                data.code,
                safeGetNonBlank(data, COL_TYPE),
                safeGet(data, COL_LABEL),
                specificationOf(data),
                safeParseDateTime(data, COL_VALID_FROM),
                safeParseDateTime(data, COL_VALID_TO),
                priorityOf(data),
                safeParseBoolean(data, COL_EXCLUSIVE),
                safeParseBigDecimal(data, COL_MONTHLY_CAP_PER_CARD),
                parseActive(data),
                safeGetNonBlank(data, COL_ADVANTAGE_TYPE),
                safeGetNonBlank(data, COL_ADVANTAGE_CATEGORY)
        );
    }

    /**
     * Reads the priority column with the same default as {@link #feedRule}.
     *
     * @param data The parsed CSV line.
     * @return The priority, or 0 when absent.
     */
    private int priorityOf(LineData data) {
        Integer priority = safeParseInt(data, COL_PRIORITY);
        return priority != null ? priority : 0;
    }
}
