package com.intermarche.fidelity.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.imports.ImporterCsvResource.Executor;
import com.intermarche.fidelity.imports.ImporterCsvResource.LineData;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionManager;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link ImporterCsvResource}, the abstract staged CSV importer reprised
 * from imvaluation (§18, §32). The class holds no clock (no {@code DateTimeProvider} needed) and its
 * only division-free arithmetic is counter aggregation; the sole {@code BigDecimal} it produces —
 * {@link ImporterCsvResource#safeParseBigDecimal} — is asserted by {@code compareTo} (§29.6).
 * <p>
 * A private {@link TestImporter} supplies the three abstract hooks so the generic framework can be
 * driven in isolation: stream reading and header-driven column resolution, the staged fallback
 * (1000&rarr;100&rarr;10&rarr;1), the programmatic transaction with rollback handling, the JSON
 * report and every null-guarded parser. The transaction manager is a Mockito mock and the session
 * ({@code Panache.getEntityManager}) is a static mock opened per test in {@link #setUp()}, so no
 * {@code persist()} or {@code clear()} ever reaches an absent database. Every guard is covered on
 * both arms and each leg of the compound conditions (§29, §29.6).
 */
class ImporterCsvResourceTest {

    /**
     * The concrete system under test, wired with a mocked transaction manager.
     */
    private TestImporter resource;

    /**
     * The mocked JTA transaction manager backing {@link ImporterCsvResource#withTransaction}.
     */
    private TransactionManager tm;

    /**
     * The mocked persistence session returned by the {@code Panache} static mock.
     */
    private EntityManager em;

    /**
     * The static mock of {@code Panache} neutralizing {@code getEntityManager().clear()}.
     */
    private MockedStatic<Panache> panacheStatic;

    /**
     * Wires a fresh resource, a mocked transaction manager and the {@code Panache} static mock.
     */
    @BeforeEach
    void setUp() {
        resource = new TestImporter();
        tm = Mockito.mock(TransactionManager.class);
        resource.tm = tm;
        em = Mockito.mock(EntityManager.class);
        panacheStatic = Mockito.mockStatic(Panache.class);
        panacheStatic.when(Panache::getEntityManager).thenReturn(em);
    }

    /**
     * Closes the {@code Panache} static mock so it never leaks into the next test.
     */
    @AfterEach
    void tearDown() {
        panacheStatic.close();
    }

    // --------------------------------------------------
    // Test doubles
    // --------------------------------------------------

    /**
     * A concrete importer whose abstract hooks are configurable per test: the chunk pre-fetch, the
     * per-row create/update logic and the 1-by-1 lookup.
     */
    private static class TestImporter extends ImporterCsvResource {

        /**
         * The context map returned by {@link #processChunkWithFallback} when no failure is armed.
         */
        Map<String, Object> chunkResult = new HashMap<>();

        /**
         * When set, {@link #processChunkWithFallback} throws it to drive the {@code Throwable} branch.
         */
        RuntimeException chunkException;

        /**
         * Predicate selecting the codes for which {@link #processLineLogic} fails.
         */
        Predicate<String> logicFailFor = code -> false;

        /**
         * The fresh entities returned by {@link #findEntityForLine}, keyed by code.
         */
        Map<String, Object> entities = new HashMap<>();

        /**
         * Returns the armed context map, or throws the armed exception when present.
         *
         * @param parsedLines The rows of the current chunk.
         * @param targetCodes The distinct natural keys present in the chunk.
         * @param counters    The global {@code [created, updated]} counters.
         * @param errors      The accumulating list of definitive row errors.
         * @return The armed context map.
         */
        @Override
        protected Map<String, Object> processChunkWithFallback(
                List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
            if (chunkException != null) {
                throw chunkException;
            }
            return chunkResult;
        }

        /**
         * Counts the row as updated when its code is present in the context map, otherwise as created,
         * and throws for any code selected by {@link #logicFailFor}.
         *
         * @param data      The row to process.
         * @param entityMap The context map for the row.
         * @param counters  The local {@code [created, updated]} counters to increment.
         */
        @Override
        protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
            if (logicFailFor.test(data.code)) {
                throw new RuntimeException("bad row " + data.code);
            }
            if (entityMap.containsKey(data.code)) {
                counters[1]++;
            } else {
                counters[0]++;
            }
        }

        /**
         * Returns the armed fresh entity for the row's code, or null when none is registered.
         *
         * @param data The row to look up.
         * @return The registered entity, or null.
         */
        @Override
        protected Object findEntityForLine(LineData data) {
            return entities.get(data.code);
        }
    }

    /**
     * An input stream that always fails, driving the {@code IOException} branch of the reader.
     */
    private static final class ThrowingInputStream extends InputStream {

        /**
         * Fails on every single-byte read.
         *
         * @return Never returns normally.
         * @throws IOException Always.
         */
        @Override
        public int read() throws IOException {
            throw new IOException("stream boom");
        }

        /**
         * Fails on every bulk read.
         *
         * @param b   The destination buffer.
         * @param off The start offset.
         * @param len The maximum number of bytes to read.
         * @return Never returns normally.
         * @throws IOException Always.
         */
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            throw new IOException("stream boom");
        }
    }

    /**
     * A two-constant enum used to exercise {@link ImporterCsvResource#safeParseEnum}.
     */
    private enum Sample {

        /**
         * The first constant, matched case-insensitively by {@code "red"}.
         */
        RED,

        /**
         * The second constant, unused but proving a multi-constant lookup.
         */
        GREEN
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Header names of the test rows, in cell order.
     */
    private static final String[] TEST_HEADER = {"CODE", "VAL"};

    /**
     * Builds a header-bound row from positional fixture cells: the header maps
     * {@link #TEST_HEADER} onto the cell positions and CODE is the key column.
     *
     * @param lineNumber The 1-based source line number.
     * @param parts      The row's raw cells.
     * @return The header-bound parsed row.
     */
    private LineData line(int lineNumber, String... parts) {
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int i = 0; i < TEST_HEADER.length; i++) {
            header.put(TEST_HEADER[i], i);
        }
        return new LineData(lineNumber, header, parts, TEST_HEADER[0]);
    }

    /**
     * Wraps a UTF-8 string as a readable stream.
     *
     * @param csv The CSV payload.
     * @return The backing input stream.
     */
    private InputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reflectively invokes the private static {@code escapeJson} helper.
     *
     * @param value The raw message.
     * @return The escaped message.
     */
    private String escapeJson(String value) {
        try {
            Method method = ImporterCsvResource.class.getDeclaredMethod("escapeJson", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
    }

    // --------------------------------------------------
    // importCsvStream(): stream reading, header, chunking, report
    // --------------------------------------------------

    /**
     * A header-only stream skips the header, processes no chunk (final list empty) and reports zero
     * counts with no error array.
     */
    @Test
    @DisplayName("importCsvStream(): a header-only stream yields an empty report")
    void importHeaderOnly() {
        Response response = resource.importCsvStream(stream("CODE|VAL\n"), "CODE", List.of("VAL"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    /**
     * A small stream stays below {@link ImporterCsvResource#STAGE_1_SIZE} (mid-loop guard false),
     * flushes the final non-empty chunk (final guard true) and reports every row created.
     */
    @Test
    @DisplayName("importCsvStream(): a small clean stream reports each row created")
    void importSmallClean() {
        Response response = resource.importCsvStream(stream("CODE|VAL\nA|1\nB|2\nC|3\n"), "CODE", List.of("VAL"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":3, \"updatedCount\":0}", response.getEntity());
    }

    /**
     * A blank line is skipped (empty guard true) and a row with fewer cells than the header is
     * reported and dropped (truncated-row guard true), so the report carries an errors array
     * escaped through {@code escapeJson}.
     */
    @Test
    @DisplayName("importCsvStream(): a blank line is skipped and a truncated row is reported")
    void importBlankAndShortRow() {
        Response response = resource.importCsvStream(stream("CODE|VAL\n\nX\nA|1\n"), "CODE", List.of("VAL"));
        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("\"createdCount\":1"));
        assertTrue(body.contains("\"errors\":["));
        assertTrue(body.contains("Line 3 ignored"));
    }

    /**
     * A stream reaching exactly {@link ImporterCsvResource#STAGE_1_SIZE} rows flushes a chunk
     * mid-loop (mid-loop guard true) then finds the buffer empty at the end (final guard false).
     */
    @Test
    @DisplayName("importCsvStream(): a full-batch stream flushes a chunk mid-loop")
    void importFullBatch() {
        StringBuilder csv = new StringBuilder("CODE|VAL\n");
        for (int i = 0; i < ImporterCsvResource.STAGE_1_SIZE; i++) {
            csv.append("C").append(i).append("|v\n");
        }
        Response response = resource.importCsvStream(stream(csv.toString()), "CODE", List.of("VAL"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":1000, \"updatedCount\":0}", response.getEntity());
    }

    /**
     * A stream whose read fails is caught as an {@code IOException} and answered with a 500 report.
     */
    @Test
    @DisplayName("importCsvStream(): an unreadable stream yields a server error")
    void importIoError() {
        Response response = resource.importCsvStream(new ThrowingInputStream(), "CODE", List.of("VAL"));
        assertEquals(500, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("Error reading file"));
    }

    /**
     * An unexpected failure in the chunk pre-fetch is caught by the {@code Throwable} guard and
     * answered with a 500 report.
     */
    @Test
    @DisplayName("importCsvStream(): an unexpected error yields a server error")
    void importUnexpectedError() {
        resource.chunkException = new RuntimeException("boom");
        Response response = resource.importCsvStream(stream("CODE|VAL\nA|1\n"), "CODE", List.of("VAL"));
        assertEquals(500, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("Unexpected error"));
    }

    // --------------------------------------------------
    // processWithStages(): staged fallback
    // --------------------------------------------------

    /**
     * An empty chunk returns immediately (empty guard true), leaving counters and errors untouched.
     */
    @Test
    @DisplayName("processWithStages(): an empty chunk is a no-op")
    void stagesEmpty() {
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        resource.processWithStages(new ArrayList<>(), null, ImporterCsvResource.STAGE_1_SIZE, counters, errors);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
        assertTrue(errors.isEmpty());
    }

    /**
     * A clean chunk larger than one commits in a single transaction (empty guard false, size guard
     * false) and the success path aggregates the local counters.
     */
    @Test
    @DisplayName("processWithStages(): a clean chunk commits and aggregates counters")
    void stagesCleanChunk() {
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        List<LineData> lines = new ArrayList<>();
        lines.add(line(2, "A"));
        lines.add(line(3, "B"));
        resource.processWithStages(lines, new HashMap<>(), ImporterCsvResource.STAGE_1_SIZE, counters, errors);
        assertEquals(2, counters[0]);
        assertEquals(0, counters[1]);
        assertTrue(errors.isEmpty());
    }

    /**
     * A chunk with one poisoned row fails at every batch size (1000&rarr;100&rarr;10&rarr;1), then
     * the 1-by-1 fallback commits the good row (success arm, fresh entity present) and records the
     * bad row's error (failure arm, no fresh entity).
     */
    @Test
    @DisplayName("processWithStages(): a poisoned chunk isolates the bad row through the fallback")
    void stagesFallbackIsolatesBadRow() {
        resource.entities.put("OK", new Object());
        resource.logicFailFor = code -> "BAD".equals(code);
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        List<LineData> lines = new ArrayList<>();
        lines.add(line(2, "OK"));
        lines.add(line(3, "BAD"));
        resource.processWithStages(lines, new HashMap<>(), ImporterCsvResource.STAGE_1_SIZE, counters, errors);
        assertEquals(0, counters[0]);
        assertEquals(1, counters[1]);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("BAD"));
    }

    /**
     * A chunk handed directly at size one takes the base-case branch (size guard true), committing
     * the single row through the 1-by-1 fallback.
     */
    @Test
    @DisplayName("processWithStages(): a size-one chunk processes line by line")
    void stagesSizeOne() {
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        List<LineData> lines = new ArrayList<>();
        lines.add(line(2, "A"));
        resource.processWithStages(lines, new HashMap<>(), 1, counters, errors);
        assertEquals(1, counters[0]);
        assertEquals(0, counters[1]);
        assertTrue(errors.isEmpty());
    }

    // --------------------------------------------------
    // prepareContextForLine(): fresh single-row lookup
    // --------------------------------------------------

    /**
     * A found fresh entity is keyed by its code (non-null guard true).
     */
    @Test
    @DisplayName("prepareContextForLine(): a found entity is keyed by code")
    void prepareContextFound() {
        Object entity = new Object();
        resource.entities.put("BABY", entity);
        Map<String, Object> map = resource.prepareContextForLine(line(2, "BABY"));
        assertEquals(1, map.size());
        assertSame(entity, map.get("BABY"));
    }

    /**
     * An absent fresh entity yields an empty context (non-null guard false).
     */
    @Test
    @DisplayName("prepareContextForLine(): an absent entity yields an empty context")
    void prepareContextAbsent() {
        Map<String, Object> map = resource.prepareContextForLine(line(2, "NONE"));
        assertTrue(map.isEmpty());
    }

    // --------------------------------------------------
    // getNextSize(): staged step ladder
    // --------------------------------------------------

    /**
     * The step ladder descends 1000&rarr;100&rarr;10&rarr;1 across both compound guards, including a
     * mid-range size resolving to the middle step.
     */
    @Test
    @DisplayName("getNextSize(): the ladder descends through every step")
    void nextSizeLadder() {
        assertEquals(ImporterCsvResource.STAGE_2_SIZE, resource.getNextSize(ImporterCsvResource.STAGE_1_SIZE));
        assertEquals(ImporterCsvResource.STAGE_3_SIZE, resource.getNextSize(ImporterCsvResource.STAGE_2_SIZE));
        assertEquals(1, resource.getNextSize(ImporterCsvResource.STAGE_3_SIZE));
        assertEquals(ImporterCsvResource.STAGE_3_SIZE, resource.getNextSize(50));
        assertEquals(1, resource.getNextSize(5));
    }

    // --------------------------------------------------
    // updateCounters(): counter aggregation
    // --------------------------------------------------

    /**
     * Local counters are added element-wise into the global pair.
     */
    @Test
    @DisplayName("updateCounters(): local counts are added into the global pair")
    void updateCountersAdds() {
        int[] global = {1, 2};
        resource.updateCounters(global, new int[]{3, 4});
        assertEquals(4, global[0]);
        assertEquals(6, global[1]);
    }

    // --------------------------------------------------
    // withTransaction(): commit / rollback
    // --------------------------------------------------

    /**
     * A successful unit of work is committed, its result captured, and the session cleared.
     *
     * @throws Exception Never; the mocked transaction methods declare checked exceptions.
     */
    @Test
    @DisplayName("withTransaction(): a successful unit commits and clears the session")
    void withTransactionCommits() throws Exception {
        Executor<int[]> executor = resource.withTransaction(() -> new int[]{1, 0});
        assertNotNull(executor.result);
        assertNull(executor.ex);
        assertEquals(1, executor.result[0]);
        Mockito.verify(tm).begin();
        Mockito.verify(tm).commit();
        Mockito.verify(em).clear();
    }

    /**
     * A failing unit of work rolls back when a transaction is active (status guard true) and captures
     * the raised exception.
     *
     * @throws Exception Never; the mocked transaction methods declare checked exceptions.
     */
    @Test
    @DisplayName("withTransaction(): a failing unit rolls back the active transaction")
    void withTransactionRollsBack() throws Exception {
        Executor<int[]> executor = resource.withTransaction(() -> {
            throw new RuntimeException("bad");
        });
        assertNull(executor.result);
        assertNotNull(executor.ex);
        Mockito.verify(tm).rollback();
        Mockito.verify(em).clear();
    }

    /**
     * When no transaction is active (status guard false) the rollback is skipped, yet the failure is
     * still captured and the session cleared.
     *
     * @throws Exception Never; the mocked transaction methods declare checked exceptions.
     */
    @Test
    @DisplayName("withTransaction(): no active transaction skips the rollback")
    void withTransactionNoActiveTransaction() throws Exception {
        Mockito.when(tm.getStatus()).thenReturn(Status.STATUS_NO_TRANSACTION);
        Executor<int[]> executor = resource.withTransaction(() -> {
            throw new RuntimeException("bad");
        });
        assertNotNull(executor.ex);
        Mockito.verify(tm, Mockito.never()).rollback();
        Mockito.verify(em).clear();
    }

    /**
     * A rollback that itself fails is swallowed (inner catch), leaving the original failure captured.
     *
     * @throws Exception Never; the mocked transaction methods declare checked exceptions.
     */
    @Test
    @DisplayName("withTransaction(): a failing rollback is swallowed")
    void withTransactionRollbackFails() throws Exception {
        Mockito.doThrow(new RuntimeException("rollback boom")).when(tm).rollback();
        Executor<int[]> executor = resource.withTransaction(() -> {
            throw new RuntimeException("bad");
        });
        assertNotNull(executor.ex);
        assertEquals("bad", executor.ex.getMessage());
        Mockito.verify(em).clear();
    }

    // --------------------------------------------------
    // Executor: onSuccess / onFailure legs
    // --------------------------------------------------

    /**
     * {@code onSuccess} runs its action when a result is present (result guard true) and returns the
     * executor for chaining.
     */
    @Test
    @DisplayName("Executor.onSuccess(): runs the action when a result is present")
    void executorOnSuccessPresent() {
        Executor<int[]> executor = new Executor<>();
        executor.setResult(new int[]{1, 1});
        boolean[] ran = {false};
        Executor<int[]> returned = executor.onSuccess(r -> ran[0] = true);
        assertTrue(ran[0]);
        assertSame(executor, returned);
    }

    /**
     * {@code onSuccess} skips its action when no result is present (result guard false).
     */
    @Test
    @DisplayName("Executor.onSuccess(): skips the action when no result is present")
    void executorOnSuccessAbsent() {
        Executor<int[]> executor = new Executor<>();
        boolean[] ran = {false};
        executor.onSuccess(r -> ran[0] = true);
        assertFalse(ran[0]);
    }

    /**
     * {@code onFailure} runs its action when both the consumer and the exception are present (both
     * legs true).
     */
    @Test
    @DisplayName("Executor.onFailure(): runs the action when both consumer and exception are present")
    void executorOnFailureBoth() {
        Executor<int[]> executor = new Executor<>();
        executor.setException(new RuntimeException("x"));
        boolean[] ran = {false};
        Executor<int[]> returned = executor.onFailure(t -> ran[0] = true);
        assertTrue(ran[0]);
        assertSame(executor, returned);
    }

    /**
     * {@code onFailure} skips its action when no exception is present (second leg false).
     */
    @Test
    @DisplayName("Executor.onFailure(): skips the action when no exception is present")
    void executorOnFailureNoException() {
        Executor<int[]> executor = new Executor<>();
        boolean[] ran = {false};
        executor.onFailure(t -> ran[0] = true);
        assertFalse(ran[0]);
    }

    /**
     * {@code onFailure} short-circuits on a null consumer (first leg false) without dereferencing the
     * exception.
     */
    @Test
    @DisplayName("Executor.onFailure(): a null consumer short-circuits")
    void executorOnFailureNullConsumer() {
        Executor<int[]> executor = new Executor<>();
        executor.setException(new RuntimeException("x"));
        Executor<int[]> returned = executor.onFailure(null);
        assertSame(executor, returned);
    }

    // --------------------------------------------------
    // escapeJson(): report escaping
    // --------------------------------------------------

    /**
     * A null message escapes to an empty string (null guard true).
     */
    @Test
    @DisplayName("escapeJson(): a null message becomes empty")
    void escapeJsonNull() {
        assertEquals("", escapeJson(null));
    }

    /**
     * Every escapable character is replaced: backslash, quote, newline, carriage return and tab
     * (null guard false).
     */
    @Test
    @DisplayName("escapeJson(): every escapable character is replaced")
    void escapeJsonSpecials() {
        assertEquals("a\\\\b\\\"c   ", escapeJson("a\\b\"c\n\r\t"));
    }

    // --------------------------------------------------
    // safeGet(): bounds and null cell
    // --------------------------------------------------

    /**
     * The unknown-column, missing-cell, null-cell and trimmed-value legs of {@code safeGet} are
     * covered by four probes.
     */
    @Test
    @DisplayName("safeGet(): unknown column, missing cell, null cell and trimmed value")
    void safeGetBranches() {
        assertNull(resource.safeGet(line(1, "x", "y"), "NOPE"));
        assertNull(resource.safeGet(line(1, "x"), "VAL"));
        assertNull(resource.safeGet(line(1, "x", null), "VAL"));
        assertEquals("y", resource.safeGet(line(1, "x", "  y  "), "VAL"));
    }

    /**
     * The null, empty and non-empty legs of {@code safeGetNonBlank} are covered.
     */
    @Test
    @DisplayName("safeGetNonBlank(): null, blank and value")
    void safeGetNonBlankBranches() {
        LineData data = line(1, "a", "");
        assertNull(resource.safeGetNonBlank(data, "NOPE"));
        assertNull(resource.safeGetNonBlank(data, "VAL"));
        assertEquals("a", resource.safeGetNonBlank(data, "CODE"));
    }

    /**
     * The unknown-column, empty, true and false legs of {@code safeParseBoolean} are covered.
     */
    @Test
    @DisplayName("safeParseBoolean(): unknown column, empty, true and false")
    void safeParseBooleanBranches() {
        LineData data = line(1, "true", "");
        assertFalse(resource.safeParseBoolean(data, "NOPE"));
        assertFalse(resource.safeParseBoolean(data, "VAL"));
        assertTrue(resource.safeParseBoolean(data, "CODE"));
        assertFalse(resource.safeParseBoolean(line(1, "notabool"), "CODE"));
    }

    /**
     * The unknown-column, empty, malformed and valid legs of {@code safeParseBigDecimal} are
     * covered; the parsed value is asserted by {@code compareTo} (§29.6).
     */
    @Test
    @DisplayName("safeParseBigDecimal(): unknown column, empty, malformed and value")
    void safeParseBigDecimalBranches() {
        LineData data = line(1, "12.34", "");
        assertNull(resource.safeParseBigDecimal(data, "NOPE"));
        assertNull(resource.safeParseBigDecimal(data, "VAL"));
        assertNull(resource.safeParseBigDecimal(line(1, "abc"), "CODE"));
        assertEquals(0, resource.safeParseBigDecimal(data, "CODE").compareTo(new BigDecimal("12.34")));
    }

    /**
     * The unknown-column, empty, malformed and valid legs of {@code safeParseInt} are covered.
     */
    @Test
    @DisplayName("safeParseInt(): unknown column, empty, malformed and value")
    void safeParseIntBranches() {
        LineData data = line(1, "42", "");
        assertNull(resource.safeParseInt(data, "NOPE"));
        assertNull(resource.safeParseInt(data, "VAL"));
        assertNull(resource.safeParseInt(line(1, "xx"), "CODE"));
        assertEquals(Integer.valueOf(42), resource.safeParseInt(data, "CODE"));
    }

    /**
     * The unknown-column, empty, malformed and valid legs of {@code safeParseDateTime} are covered.
     */
    @Test
    @DisplayName("safeParseDateTime(): unknown column, empty, malformed and value")
    void safeParseDateTimeBranches() {
        LineData data = line(1, "2026-01-31T23:59:59", "");
        assertNull(resource.safeParseDateTime(data, "NOPE"));
        assertNull(resource.safeParseDateTime(data, "VAL"));
        assertNull(resource.safeParseDateTime(line(1, "nope"), "CODE"));
        assertEquals(LocalDateTime.of(2026, 1, 31, 23, 59, 59), resource.safeParseDateTime(data, "CODE"));
    }

    /**
     * The unknown-column, empty, malformed and valid legs of {@code safeParseLocalDate} are covered.
     */
    @Test
    @DisplayName("safeParseLocalDate(): unknown column, empty, malformed and value")
    void safeParseLocalDateBranches() {
        LineData data = line(1, "2026-01-01", "");
        assertNull(resource.safeParseLocalDate(data, "NOPE"));
        assertNull(resource.safeParseLocalDate(data, "VAL"));
        assertNull(resource.safeParseLocalDate(line(1, "nope"), "CODE"));
        assertEquals(LocalDate.of(2026, 1, 1), resource.safeParseLocalDate(data, "CODE"));
    }

    /**
     * The unknown-column, empty, unknown-constant and valid (case-insensitive) legs of
     * {@code safeParseEnum} are covered.
     */
    @Test
    @DisplayName("safeParseEnum(): unknown column, empty, unknown constant and value")
    void safeParseEnumBranches() {
        LineData data = line(1, "red", "");
        assertNull(resource.safeParseEnum(Sample.class, data, "NOPE"));
        assertNull(resource.safeParseEnum(Sample.class, data, "VAL"));
        assertNull(resource.safeParseEnum(Sample.class, line(1, "purple"), "CODE"));
        assertEquals(Sample.RED, resource.safeParseEnum(Sample.class, data, "CODE"));
    }

    // --------------------------------------------------
    // importCsvStream(): header validation and key guard
    // --------------------------------------------------

    /**
     * A header missing the key column or a required column rejects the file with a 400 naming the
     * missing names, before any chunk is processed.
     */
    @Test
    @DisplayName("importCsvStream(): missing required columns reject the file with a 400")
    void importMissingRequiredColumns() {
        Response response = resource.importCsvStream(stream("OTHER|VAL\nA|foo\n"), "CODE", List.of("VAL", "EXTRA"));
        assertEquals(400, response.getStatus());
        assertEquals("{\"error\":\"Missing required columns: CODE, EXTRA\"}", response.getEntity());
    }

    /**
     * A duplicate header name keeps its FIRST index (first-wins arm of {@code parseHeader}): the
     * key is read from the first occurrence, so the row matches the armed context and counts as an
     * update.
     */
    @Test
    @DisplayName("importCsvStream(): a duplicate header column keeps its first index")
    void importDuplicateHeaderFirstWins() {
        resource.chunkResult.put("A", new Object());
        Response response = resource.importCsvStream(stream("CODE|CODE\nA|B\n"), "CODE", List.of());
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":1}", response.getEntity());
    }

    /**
     * A data row whose key cell is empty is reported and skipped (empty-key guard true with the
     * default {@code acceptsEmptyKey()} false) without stopping the import.
     */
    @Test
    @DisplayName("importCsvStream(): an empty key row is reported and skipped")
    void importEmptyKeyReported() {
        Response response = resource.importCsvStream(stream("CODE|VAL\n|foo\nB|bar\n"), "CODE", List.of("VAL"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":1, \"updatedCount\":0, \"errors\":[\"Line 2 ignored (empty key 'CODE')\"]}",
                response.getEntity());
    }

    /**
     * The {@code acceptsEmptyKey()} true arm: an importer that generates its own key (the account
     * import, §33.1) lets an empty-key row through to the processing logic instead of reporting it.
     */
    @Test
    @DisplayName("importCsvStream(): an empty-key-accepting importer processes the blank row")
    void importEmptyKeyAccepted() {
        TestImporter generating = new TestImporter() {
            /**
             * Accepts empty-key rows, as a key-generating importer would (§33.1).
             *
             * @return Always true.
             */
            @Override
            protected boolean acceptsEmptyKey() {
                return true;
            }
        };
        generating.tm = tm;
        Response response = generating.importCsvStream(stream("CODE|VAL\n|foo\n"), "CODE", List.of("VAL"));
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":1, \"updatedCount\":0}", response.getEntity());
    }

    /**
     * The null, blank and populated legs of {@code parseCodes} are covered, including empty-token
     * filtering and sorting.
     */
    @Test
    @DisplayName("parseCodes(): null, blank and a sorted deduped split")
    void parseCodesBranches() {
        assertTrue(resource.parseCodes(null).isEmpty());
        assertTrue(resource.parseCodes("   ").isEmpty());
        assertEquals(List.of("A", "B", "C"), resource.parseCodes("B, A, ,C"));
    }
}
