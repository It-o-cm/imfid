package com.intermarche.fidelity.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.imports.ImporterCsvResource.LineData;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link FidelityCommunityCsvResource}: the bulk CSV import of loyalty
 * communities (§13, §18). The class holds no clock and no protected division, so it needs neither
 * a {@code DateTimeProvider} nor a zero-denominator fixture; its only {@code BigDecimal} — the
 * per-card {@link FidelityCommunity#monthlyCap} — is asserted by {@code compareTo} (§29.6). Its
 * branches are the bulk pre-fetch compound guard, the create/update dispatch and the checksum
 * optimization compound guard.
 * <p>
 * Fully isolated: the resource is instantiated bare and the collaborators it reaches through the
 * inherited machinery are Mockito static mocks in try-with-resources — the Panache finders
 * ({@code PanacheEntityBase.list/findById}, {@code FidelityCommunity.findByCode}) and the session
 * ({@code Panache.getEntityManager}) — so no {@code persist()} ever hits an absent database. The
 * private helpers ({@code feedCommunity}, {@code computeIncomingChecksum}) are exercised through
 * {@code processLineLogic} and asserted field by field (§29, §29.6).
 */
class FidelityCommunityCsvResourceTest {

    /**
     * The system under test, a bare resource with no CDI wiring.
     */
    private final FidelityCommunityCsvResource resource = new FidelityCommunityCsvResource();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Header names of the fixture rows, in cell order.
     */
    private static final String[] TEST_HEADER = {"CODE", "LABEL", "MONTHLY_CAP", "ENROLLMENT_CAP", "RENEWAL_START_MONTH", "RENEWAL_END_MONTH", "ELIGIBILITY_CRITERIA"};

    /**
     * Builds a header-bound CSV row from positional fixture cells: the header
     * maps {@link #TEST_HEADER} onto the cell positions and the first name is
     * the key column.
     *
     * @param parts The row's raw cells.
     * @return The line carrying line number 2 and the header-resolved key.
     */
    private LineData line(String... parts) {
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int i = 0; i < TEST_HEADER.length; i++) {
            header.put(TEST_HEADER[i], i);
        }
        return new LineData(2, header, parts, TEST_HEADER[0]);
    }

    /**
     * Builds a nominal seven-column community row.
     *
     * @return The row {@code BABY|Babies|30.00|1000|10|2|Children under 3}.
     */
    private LineData babyLine() {
        return line("BABY", "Babies", "30.00", "1000", "10", "2", "Children under 3");
    }

    /**
     * Reflectively invokes a declared method of the resource, unwrapping reflective failures.
     *
     * @param name  The method name.
     * @param types The parameter types.
     * @param args  The arguments.
     * @return The method result.
     */
    private Object invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method method = FidelityCommunityCsvResource.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(resource, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
    }

    /**
     * Computes the incoming checksum of a row through the private helper.
     *
     * @param data The parsed row.
     * @return The incoming checksum the update guard compares against.
     */
    private int incomingChecksum(LineData data) {
        return (int) invoke("computeIncomingChecksum", new Class<?>[]{LineData.class}, data);
    }

    // --------------------------------------------------
    // importCommunities(): delegation to the staged importer
    // --------------------------------------------------

    /**
     * A header-only stream drives the delegation to {@code importCsvStream} without touching the
     * transaction machinery: the header row is skipped, no chunk is processed and the report
     * carries zero counts.
     */
    @Test
    @DisplayName("importCommunities(): a header-only stream yields an empty report")
    void importCommunitiesHeaderOnly() {
        InputStream in = new ByteArrayInputStream(
                "CODE|LABEL|MONTHLY_CAP|ENROLLMENT_CAP|RENEWAL_START_MONTH|RENEWAL_END_MONTH|ELIGIBILITY_CRITERIA\n"
                        .getBytes(StandardCharsets.UTF_8));
        Response response = resource.importCommunities(in);
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    // --------------------------------------------------
    // processChunkWithFallback(): the bulk pre-fetch (compound && guard, §29.6)
    // --------------------------------------------------

    /**
     * Both legs true: a non-empty chunk with a distinct key queries the persisted communities and
     * keys them by code.
     */
    @Test
    @DisplayName("processChunkWithFallback(): a non-empty chunk keys existing communities by code")
    void processChunkBothPresent() {
        FidelityCommunity existing = new FidelityCommunity();
        existing.code = "BABY";
        Set<String> targets = new HashSet<>(Set.of("BABY"));
        List<PanacheEntityBase> found = new ArrayList<>();
        found.add(existing);
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("code in ?1", targets)).thenReturn(found);
            Map<String, Object> map = resource.processChunkWithFallback(
                    List.of(babyLine()), targets, counters, errors);
            assertEquals(1, map.size());
            assertSame(existing, map.get("BABY"));
        }
    }

    /**
     * First leg false: an empty line list short-circuits before the second leg, returning an empty
     * context with no query.
     */
    @Test
    @DisplayName("processChunkWithFallback(): an empty line list returns an empty context")
    void processChunkEmptyLines() {
        int[] counters = {0, 0};
        Map<String, Object> map = resource.processChunkWithFallback(
                List.of(), new HashSet<>(Set.of("BABY")), counters, new ArrayList<>());
        assertTrue(map.isEmpty());
    }

    /**
     * Second leg false: a non-empty line list with no target key returns an empty context with no
     * query.
     */
    @Test
    @DisplayName("processChunkWithFallback(): no target key returns an empty context")
    void processChunkEmptyTargets() {
        int[] counters = {0, 0};
        Map<String, Object> map = resource.processChunkWithFallback(
                List.of(babyLine()), new HashSet<>(), counters, new ArrayList<>());
        assertTrue(map.isEmpty());
    }

    // --------------------------------------------------
    // findEntityForLine(): the 1-by-1 fallback lookup
    // --------------------------------------------------

    /**
     * A provided key is looked up by {@code findByCode}, returning the persisted community.
     */
    @Test
    @DisplayName("findEntityForLine(): a code is looked up by findByCode")
    void findEntityForLineFound() {
        FidelityCommunity existing = new FidelityCommunity();
        existing.code = "BABY";
        try (MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            communities.when(() -> FidelityCommunity.findByCode("BABY")).thenReturn(existing);
            assertSame(existing, resource.findEntityForLine(babyLine()));
        }
    }

    /**
     * An absent code resolves to null so the row becomes a creation in the 1-by-1 fallback.
     */
    @Test
    @DisplayName("findEntityForLine(): an absent code resolves to null")
    void findEntityForLineAbsent() {
        try (MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            communities.when(() -> FidelityCommunity.findByCode("BABY")).thenReturn(null);
            assertNull(resource.findEntityForLine(babyLine()));
        }
    }

    // --------------------------------------------------
    // processLineLogic(): create / update dispatch (compound || guard, §29.6)
    // --------------------------------------------------

    /**
     * The create arm: a code absent from the context map creates a community, feeds every business
     * field, bumps the created counter and persists it. The {@code monthlyCap} is asserted by
     * {@code compareTo} (§29.6).
     */
    @Test
    @DisplayName("processLineLogic(): a code absent from the map creates and persists a community")
    void processLineCreate() {
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(babyLine(), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            Mockito.verify(em).persist(captor.capture());
            FidelityCommunity created = (FidelityCommunity) captor.getValue();
            assertEquals("BABY", created.code);
            assertEquals("Babies", created.label);
            assertEquals(0, created.monthlyCap.compareTo(new BigDecimal("30.00")));
            assertEquals(1000, created.enrollmentCap);
            assertEquals(10, created.renewalStartMonth);
            assertEquals(2, created.renewalEndMonth);
            assertEquals("Children under 3", created.eligibilityCriteria);
        }
    }

    /**
     * The update arm, first leg true: an existing community with a null checksum is re-read by id,
     * fed the incoming values and counted as updated; the created counter stays put and nothing is
     * persisted.
     */
    @Test
    @DisplayName("processLineLogic(): a null-checksum community is re-read and updated")
    void processLineUpdateNullChecksum() {
        FidelityCommunity existing = new FidelityCommunity();
        existing.code = "BABY";
        existing.id = 5L;
        existing.checksum = null;
        FidelityCommunity reread = new FidelityCommunity();
        reread.code = "BABY";
        reread.id = 5L;
        Map<String, Object> map = new HashMap<>();
        map.put("BABY", existing);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(5L)).thenReturn(reread);
            resource.processLineLogic(babyLine(), map, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Babies", reread.label);
            assertEquals(0, reread.monthlyCap.compareTo(new BigDecimal("30.00")));
        }
    }

    /**
     * The update arm, first leg false and second leg true: an existing community with a non-null
     * checksum that differs from the incoming one is re-read by id and counted as updated.
     */
    @Test
    @DisplayName("processLineLogic(): a differing checksum re-reads and updates the community")
    void processLineUpdateDifferingChecksum() {
        LineData data = babyLine();
        FidelityCommunity existing = new FidelityCommunity();
        existing.code = "BABY";
        existing.id = 7L;
        existing.checksum = incomingChecksum(data) + 1;
        FidelityCommunity reread = new FidelityCommunity();
        reread.code = "BABY";
        reread.id = 7L;
        Map<String, Object> map = new HashMap<>();
        map.put("BABY", existing);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(reread);
            resource.processLineLogic(data, map, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Babies", reread.label);
        }
    }

    /**
     * Both legs false: an existing community whose non-null checksum matches the incoming one is a
     * no-op — neither counter moves, nothing is re-read and nothing is persisted.
     */
    @Test
    @DisplayName("processLineLogic(): a matching checksum leaves the community untouched")
    void processLineUnchanged() {
        LineData data = babyLine();
        FidelityCommunity existing = new FidelityCommunity();
        existing.code = "BABY";
        existing.id = 9L;
        existing.checksum = incomingChecksum(data);
        Map<String, Object> map = new HashMap<>();
        map.put("BABY", existing);
        int[] counters = {0, 0};
        resource.processLineLogic(data, map, counters);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }
}
