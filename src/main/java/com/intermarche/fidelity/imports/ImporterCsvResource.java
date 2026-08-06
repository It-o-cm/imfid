package com.intermarche.fidelity.imports;

import io.quarkus.hibernate.orm.panache.Panache;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Abstract base class for REST endpoints performing bulk imports from CSV
 * streams, reprising the imvaluation import machinery bit for bit (§18, §32).
 * <p>
 * It provides the generic framework shared by every import domain:
 * <ul>
 *   <li>Stream reading, buffering and header skipping (pipe-delimited lines);</li>
 *   <li>Chunking to balance memory usage and throughput;</li>
 *   <li>Programmatic transaction management with rollback handling;</li>
 *   <li>A standardized JSON execution report (the {@code compte-rendu});</li>
 *   <li>Null-guarded parsers for every column type (§31.2);</li>
 *   <li><b>Staged fallback algorithm:</b> a failed chunk is retried at a smaller
 *       batch size (1000 &rarr; 100 &rarr; 10 &rarr; 1) so one bad row never sinks
 *       a whole batch;</li>
 *   <li><b>Checksum optimization:</b> a row whose incoming checksum matches the
 *       persisted one is skipped, avoiding a needless UPDATE.</li>
 * </ul>
 * <p>
 * Subclasses supply the domain-specific bulk fetch, the per-line create/update
 * logic and the single-row lookup through the abstract methods below. The class
 * runs on virtual threads via {@link RunOnVirtualThread}, as in imvaluation.
 */
@RunOnVirtualThread
public abstract class ImporterCsvResource {

    private static final Logger LOGGER = Logger.getLogger(ImporterCsvResource.class);

    /**
     * First (largest) staged batch size attempted.
     */
    protected static final int STAGE_1_SIZE = 1000;

    /**
     * Second staged batch size, tried when a batch of {@link #STAGE_1_SIZE} fails.
     */
    protected static final int STAGE_2_SIZE = 100;

    /**
     * Third staged batch size, tried when a batch of {@link #STAGE_2_SIZE} fails.
     */
    protected static final int STAGE_3_SIZE = 10;

    /**
     * ISO date-time parser used by {@link #safeParseDateTime(String[], int)}.
     */
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * ISO date parser used by {@link #safeParseLocalDate(String[], int)}.
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * The JTA transaction manager driving the programmatic transactions.
     */
    @Inject
    TransactionManager tm;

    /**
     * Main entry point importing a pipe-delimited CSV stream.
     * <p>
     * The stream is read line by line, the header (first non-empty line) is
     * skipped, and rows are accumulated in chunks of {@link #STAGE_1_SIZE} before
     * being handed to {@link #processChunkWithFallback} then {@link #processWithStages}.
     * A row with fewer than {@code colNumber} columns is reported and dropped.
     *
     * @param inputStream The CSV stream to read.
     * @param colNumber   The minimum number of columns a data row must carry.
     * @return A JSON {@link Response} summarizing created/updated counts and errors.
     */
    public Response importCsvStream(InputStream inputStream, int colNumber) {
        LOGGER.info("Starting bulk import from InputStream");
        int[] counters = new int[]{0, 0};
        List<String> errors = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            List<LineData> parsedLines = new ArrayList<>(STAGE_1_SIZE);
            Set<String> targetCodes = new HashSet<>(STAGE_1_SIZE);
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty()) continue;
                if (lineNumber == 1) continue; // Skip header
                String[] parts = line.split("\\|", -1);
                // Validate column count
                if (parts.length < colNumber) {
                    errors.add("Line " + lineNumber + " ignored (not enough columns): " + line);
                    continue;
                }
                String code = parts[0].trim();
                parsedLines.add(new LineData(lineNumber, code, parts));
                targetCodes.add(code);
                if (parsedLines.size() >= STAGE_1_SIZE) {
                    Map<String, Object> contextMap = processChunkWithFallback(parsedLines, targetCodes, counters, errors);
                    processWithStages(parsedLines, contextMap, STAGE_1_SIZE, counters, errors);
                    parsedLines.clear();
                    targetCodes.clear();
                }
            }
            if (!parsedLines.isEmpty()) {
                Map<String, Object> contextMap = processChunkWithFallback(parsedLines, targetCodes, counters, errors);
                processWithStages(parsedLines, contextMap, STAGE_1_SIZE, counters, errors);
            }
        } catch (IOException e) {
            LOGGER.error("Error reading input stream", e);
            return Response.serverError().entity("Error reading file: " + e.getMessage()).build();
        } catch (Throwable e) {
            LOGGER.error("Unexpected error", e);
            return Response.serverError().entity("Unexpected error: " + e.getMessage()).build();
        }
        LOGGER.info("Import finished. Created: " + counters[0] + ", Updated: " + counters[1]);
        StringBuilder sb = buildAnswer(counters, errors);
        return Response.ok(sb.toString()).build();
    }

    /**
     * Generic staged processing algorithm (1000 &rarr; 100 &rarr; 10 &rarr; 1).
     * <p>
     * The chunk is processed in a single transaction; on failure it is split into
     * smaller sub-chunks and each is retried at the next size. At size 1 each row
     * is processed in its own transaction with a fresh lookup, isolating the
     * offending row without blocking the others.
     *
     * @param lines         The rows of the current chunk.
     * @param preFetchedMap The entities pre-fetched for the chunk; may be null.
     * @param chunkSize     The batch size to attempt.
     * @param counters      The global {@code [created, updated]} counters.
     * @param errors        The accumulating list of definitive row errors.
     */
    protected void processWithStages(List<LineData> lines, Map<String, Object> preFetchedMap, int chunkSize, int[] counters, List<String> errors) {
        if (lines.isEmpty()) return;
        // Base case: atomic processing, one row per transaction.
        if (chunkSize == 1) {
            processLineByLine(lines, counters, errors);
            return;
        }
        // Recursive step: try the whole chunk at the current size.
        withTransaction(() -> {
            int[] lCounters = {0, 0};
            for (LineData data : lines) {
                processLineLogic(data, preFetchedMap, lCounters);
            }
            return lCounters;
        }).onSuccess(lCounters -> {
            updateCounters(counters, lCounters);
        }).onFailure(ex -> {
            int nextSize = getNextSize(chunkSize);
            LOGGER.warn("Failed to process chunk of size " + lines.size() + " with step " + chunkSize
                    + ". Retrying with step " + nextSize + ". Error: " + ex.getMessage());
            for (int i = 0; i < lines.size(); i += nextSize) {
                int end = Math.min(i + nextSize, lines.size());
                List<LineData> subList = lines.subList(i, end);
                processWithStages(subList, preFetchedMap, nextSize, counters, errors);
            }
        });
    }

    /**
     * Fallback processing one row per transaction, with a fresh per-row lookup.
     * <p>
     * A single failing row records its error and never blocks the others.
     *
     * @param lines    The rows to process atomically.
     * @param counters The global {@code [created, updated]} counters.
     * @param errors   The accumulating list of definitive row errors.
     */
    private void processLineByLine(List<LineData> lines, int[] counters, List<String> errors) {
        for (LineData data : lines) {
            withTransaction(() -> {
                Map<String, Object> singleLineMap = prepareContextForLine(data);
                int[] lCounters = {0, 0};
                processLineLogic(data, singleLineMap, lCounters);
                return lCounters;
            }).onSuccess(lCounters -> {
                updateCounters(counters, lCounters);
            }).onFailure(rowEx -> {
                errors.add("Line " + data.lineNumber + " (" + data.code + "): " + rowEx.getMessage());
            });
        }
    }

    /**
     * Builds a single-row context by freshly looking up the row's entity.
     * <p>
     * Used by the 1-by-1 fallback so each atomic row sees committed state.
     *
     * @param data The row to prepare a context for.
     * @return A map holding the row's code to its fresh entity, empty when none.
     */
    protected Map<String, Object> prepareContextForLine(LineData data) {
        Object freshEntity = findEntityForLine(data);
        Map<String, Object> singleLineMap = new HashMap<>();
        if (freshEntity != null) {
            singleLineMap.put(data.code, freshEntity);
        }
        return singleLineMap;
    }

    /**
     * Determines the next smaller batch size after a failure.
     *
     * @param currentSize The size that just failed.
     * @return The next smaller size (1000 &rarr; 100 &rarr; 10 &rarr; 1).
     */
    protected int getNextSize(int currentSize) {
        if (currentSize > STAGE_2_SIZE) return STAGE_2_SIZE;
        if (currentSize > STAGE_3_SIZE) return STAGE_3_SIZE;
        return 1;
    }

    /**
     * Builds the JSON execution report (the {@code compte-rendu}) of the import.
     *
     * @param counters The final {@code [created, updated]} counters.
     * @param errors   The definitive row errors, if any.
     * @return The JSON report as a {@link StringBuilder}.
     */
    private static StringBuilder buildAnswer(int[] counters, List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"createdCount\":").append(counters[0]);
        sb.append(", \"updatedCount\":").append(counters[1]);
        if (!errors.isEmpty()) {
            sb.append(", \"errors\":[\"");
            sb.append(errors.stream().map(ImporterCsvResource::escapeJson).collect(Collectors.joining("\",\"")));
            sb.append("\"]");
        }
        sb.append("}");
        return sb;
    }

    /**
     * Escapes the characters that would break a JSON string literal in the report.
     *
     * @param value The raw error message; may be null.
     * @return The escaped message, or an empty string when null.
     */
    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    // --------------------------------------------------
    // Abstract methods (implemented by subclasses)
    // --------------------------------------------------

    /**
     * Bulk-fetches the entities a chunk needs, keyed for {@link #processLineLogic}.
     *
     * @param parsedLines The rows of the current chunk.
     * @param targetCodes The distinct natural keys present in the chunk.
     * @param counters    The global {@code [created, updated]} counters.
     * @param errors      The accumulating list of definitive row errors.
     * @return The context map consumed by {@link #processLineLogic}, never null.
     */
    protected abstract Map<String, Object> processChunkWithFallback(
            List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors);

    /**
     * Creates or updates the entity of a single row, within the caller's transaction.
     *
     * @param data      The row to process.
     * @param entityMap The context map (pre-fetched, or a fresh single-row lookup).
     * @param counters  The local {@code [created, updated]} counters to increment.
     */
    protected abstract void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters);

    /**
     * Looks up the persisted entity of a row, used by the 1-by-1 fallback.
     *
     * @param data The row to look up.
     * @return The found entity, or null when the row is a creation.
     */
    protected abstract Object findEntityForLine(LineData data);

    // --------------------------------------------------
    // Transaction management
    // --------------------------------------------------

    /**
     * A small container carrying either the result or the failure of a processing
     * step, so callers can chain {@link #onSuccess(Consumer)} and
     * {@link #onFailure(Consumer)} without exposing the transaction plumbing.
     *
     * @param <R> The type of the processing result.
     */
    static class Executor<R> {

        /**
         * The successful result, or null when the step failed.
         */
        R result;

        /**
         * The exception thrown by the step, or null when it succeeded.
         */
        Exception ex;

        /**
         * Runs the given action when the step succeeded (result present).
         *
         * @param success The action to run on success.
         * @return This executor, for chaining.
         */
        public Executor<R> onSuccess(Consumer<R> success) {
            if (result != null) success.accept(result);
            return this;
        }

        /**
         * Runs the given action when the step failed (exception present).
         *
         * @param failure The action to run on failure.
         * @return This executor, for chaining.
         */
        public Executor<R> onFailure(Consumer<Throwable> failure) {
            if (failure != null && ex != null) failure.accept(ex);
            return this;
        }

        /**
         * Records the successful result.
         *
         * @param result The result value.
         */
        public void setResult(R result) {
            this.result = result;
        }

        /**
         * Records the failure.
         *
         * @param ex The exception thrown by the step.
         */
        public void setException(Exception ex) {
            this.ex = ex;
        }
    }

    /**
     * Runs a supplier inside a fresh JTA transaction, committing on success and
     * rolling back on failure, then clearing the persistence context.
     *
     * @param processing The unit of work to run transactionally.
     * @param <R>        The type of the work's result.
     * @return An {@link Executor} holding the result or the raised exception.
     */
    public <R> Executor<R> withTransaction(Supplier<R> processing) {
        Executor<R> executor = new Executor<>();
        R result;
        try {
            tm.begin();
            result = processing.get();
            tm.commit();
            executor.setResult(result);
        } catch (Exception rowEx) {
            try {
                if (tm.getStatus() != jakarta.transaction.Status.STATUS_NO_TRANSACTION) {
                    tm.rollback();
                }
            } catch (Exception rbRowEx) {
                LOGGER.error("Error during rollback", rbRowEx);
            }
            executor.setException(rowEx);
        } finally {
            Panache.getEntityManager().clear();
        }
        return executor;
    }

    /**
     * Merges a local counter pair into the global one.
     *
     * @param counters  The global {@code [created, updated]} counters.
     * @param lCounters The local counters to add in.
     */
    void updateCounters(int[] counters, int[] lCounters) {
        counters[0] += lCounters[0];
        counters[1] += lCounters[1];
    }

    // --------------------------------------------------
    // Null-guarded parsing helpers (§31.2)
    // --------------------------------------------------

    /**
     * Safely reads a trimmed string from an array by index.
     *
     * @param parts The row columns.
     * @param index The column index.
     * @return The trimmed value, or null when out of bounds or null.
     */
    String safeGet(String[] parts, int index) {
        return index >= 0 && index < parts.length ? (parts[index] == null ? null : parts[index].trim()) : null;
    }

    /**
     * Safely reads a trimmed, non-blank string from an array by index.
     *
     * @param parts The row columns.
     * @param index The column index.
     * @return The trimmed value, or null when out of bounds, null or blank.
     */
    String safeGetNonBlank(String[] parts, int index) {
        String value = safeGet(parts, index);
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Safely parses a boolean from an array by index, defaulting to false.
     *
     * @param parts The row columns.
     * @param index The column index.
     * @return The parsed boolean, or false on any error.
     */
    boolean safeParseBoolean(String[] parts, int index) {
        if (index >= parts.length) return false;
        String val = parts[index].trim();
        if (val.isEmpty()) return false;
        return Boolean.parseBoolean(val);
    }

    /**
     * Safely parses a {@link BigDecimal} from an array by index.
     *
     * @param parts The row columns.
     * @param index The column index.
     * @return The parsed value, or null when out of bounds, empty or malformed.
     */
    BigDecimal safeParseBigDecimal(String[] parts, int index) {
        if (index >= parts.length) return null;
        String val = parts[index].trim();
        if (val.isEmpty()) return null;
        try {
            return new BigDecimal(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Safely parses an {@link Integer} from an array by index.
     *
     * @param parts The row columns.
     * @param index The column index.
     * @return The parsed value, or null when out of bounds, empty or malformed.
     */
    Integer safeParseInt(String[] parts, int index) {
        if (index >= parts.length) return null;
        String val = parts[index].trim();
        if (val.isEmpty()) return null;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Safely parses an ISO {@link LocalDateTime} from an array by index.
     *
     * @param parts The row columns.
     * @param index The column index.
     * @return The parsed value, or null when out of bounds, empty or malformed.
     */
    LocalDateTime safeParseDateTime(String[] parts, int index) {
        if (index >= parts.length) return null;
        String val = parts[index].trim();
        if (val.isEmpty()) return null;
        try {
            return LocalDateTime.parse(val, DATE_TIME_FORMATTER);
        } catch (Exception e) {
            LOGGER.warn("Invalid date-time format at index " + index + ": " + val);
            return null;
        }
    }

    /**
     * Safely parses an ISO {@link LocalDate} from an array by index.
     *
     * @param parts The row columns.
     * @param index The column index.
     * @return The parsed value, or null when out of bounds, empty or malformed.
     */
    LocalDate safeParseLocalDate(String[] parts, int index) {
        if (index >= parts.length) return null;
        String val = parts[index].trim();
        if (val.isEmpty()) return null;
        try {
            return LocalDate.parse(val, DATE_FORMATTER);
        } catch (Exception e) {
            LOGGER.warn("Invalid date format at index " + index + ": " + val);
            return null;
        }
    }

    /**
     * Safely parses an enum constant from an array by index, case-insensitively.
     *
     * @param type  The enum class.
     * @param parts The row columns.
     * @param index The column index.
     * @param <E>   The enum type.
     * @return The parsed constant, or null when out of bounds, empty or unknown.
     */
    <E extends Enum<E>> E safeParseEnum(Class<E> type, String[] parts, int index) {
        if (index >= parts.length) return null;
        String val = parts[index].trim();
        if (val.isEmpty()) return null;
        try {
            return Enum.valueOf(type, val.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown " + type.getSimpleName() + " value: " + val + " at index " + index);
            return null;
        }
    }

    /**
     * Splits a comma-separated cell into a sorted list of trimmed, non-empty codes.
     *
     * @param raw The raw cell value (e.g. {@code "A, B, C"}); may be null.
     * @return A sorted list of codes, never null.
     */
    List<String> parseCodes(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Internal carrier of one parsed CSV row: its line number, natural key and columns.
     */
    public static class LineData {

        /**
         * The 1-based line number of the row in the source stream.
         */
        final int lineNumber;

        /**
         * The row's natural key (first column), used for bulk fetch and reporting.
         */
        final String code;

        /**
         * The row's raw, pipe-split columns.
         */
        final String[] parts;

        /**
         * Builds a parsed row.
         *
         * @param lineNumber The 1-based source line number.
         * @param code       The row's natural key (first column).
         * @param parts      The row's raw columns.
         */
        public LineData(int lineNumber, String code, String[] parts) {
            this.lineNumber = lineNumber;
            this.code = code;
            this.parts = parts;
        }
    }
}
