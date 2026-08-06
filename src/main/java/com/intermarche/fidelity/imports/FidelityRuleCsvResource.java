package com.intermarche.fidelity.imports;

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
 * File format (10 pipe-delimited columns, the specification last so an embedded
 * delimiter is tolerated):
 * {@code code|type|label|validFrom|validTo|priority|exclusive|monthlyCapPerCard|active|specification}.
 * The specification must be a single JSON object on the row. The {@code fid-admin}
 * role guard (§24.1) is attached in the security build step; the staged fallback
 * and checksum optimization come from {@link ImporterCsvResource}.
 */
@Path("/fidelity/rules/import")
@ApplicationScoped
@RunOnVirtualThread
public class FidelityRuleCsvResource extends ImporterCsvResource {

    /**
     * Number of columns expected in the rule CSV.
     */
    private static final int COLUMNS = 10;

    /**
     * Zero-based index of the specification column (the last, possibly delimiter-bearing).
     */
    private static final int SPEC_INDEX = 9;

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
        return this.importCsvStream(inputStream, COLUMNS);
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
        String[] parts = data.parts;
        String type = safeGetNonBlank(parts, 1);
        String specification = specificationOf(parts);
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
        String[] parts = data.parts;
        rule.type = safeGetNonBlank(parts, 1);
        rule.label = safeGet(parts, 2);
        LocalDateTime validFrom = safeParseDateTime(parts, 3);
        if (validFrom == null) {
            throw new IllegalArgumentException("missing or malformed validFrom (ISO date-time)");
        }
        rule.validFrom = validFrom;
        rule.validTo = safeParseDateTime(parts, 4);
        Integer priority = safeParseInt(parts, 5);
        rule.priority = priority != null ? priority : 0;
        rule.exclusive = safeParseBoolean(parts, 6);
        rule.monthlyCapPerCard = safeParseBigDecimal(parts, 7);
        rule.active = parseActive(parts, 8);
        rule.specification = specificationOf(parts);
    }

    /**
     * Reconstructs the specification column, joining any tail columns with the pipe
     * delimiter so a JSON specification bearing a {@code |} survives the split.
     *
     * @param parts The row columns.
     * @return The specification JSON string, or null when absent.
     */
    private String specificationOf(String[] parts) {
        if (parts.length <= SPEC_INDEX) {
            return null;
        }
        if (parts.length == SPEC_INDEX + 1) {
            String single = parts[SPEC_INDEX];
            return single == null ? null : single.trim();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = SPEC_INDEX; i < parts.length; i++) {
            if (i > SPEC_INDEX) {
                sb.append('|');
            }
            sb.append(parts[i] == null ? "" : parts[i]);
        }
        return sb.toString().trim();
    }

    /**
     * Parses the {@code active} column, defaulting to {@code true} when blank so a
     * rule is active unless explicitly deactivated.
     *
     * @param parts The row columns.
     * @param index The column index.
     * @return true unless the column explicitly reads {@code false}.
     */
    private boolean parseActive(String[] parts, int index) {
        String value = safeGetNonBlank(parts, index);
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
        String[] parts = data.parts;
        // Field order mirrors FidelityRule#getChecksum() exactly, so an unchanged
        // row hashes identically and is skipped.
        return Objects.hash(
                data.code,
                safeGetNonBlank(parts, 1),
                safeGet(parts, 2),
                specificationOf(parts),
                safeParseDateTime(parts, 3),
                safeParseDateTime(parts, 4),
                priorityOf(parts),
                safeParseBoolean(parts, 6),
                safeParseBigDecimal(parts, 7),
                parseActive(parts, 8)
        );
    }

    /**
     * Reads the priority column with the same default as {@link #feedRule}.
     *
     * @param parts The row columns.
     * @return The priority, or 0 when absent.
     */
    private int priorityOf(String[] parts) {
        Integer priority = safeParseInt(parts, 5);
        return priority != null ? priority : 0;
    }
}
