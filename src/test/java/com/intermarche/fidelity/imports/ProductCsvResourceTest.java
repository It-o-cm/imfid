package com.intermarche.fidelity.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.domain.ProductType;
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
 * Plain unit coverage for {@link ProductCsvResource}: the bulk CSV import of the imfid product
 * reference (§15, §18). The class holds no clock and no protected division, so it needs neither a
 * {@code DateTimeProvider} nor a zero-denominator fixture; its two {@code BigDecimal} fields — the
 * {@link Product#referenceWeight} and {@link Product#referenceVolume} — are asserted by
 * {@code compareTo} (§29.6). Its branches are the bulk pre-fetch compound guard, the create/update
 * dispatch and the checksum optimization compound guard.
 * <p>
 * Fully isolated: the resource is instantiated bare and the collaborators it reaches through the
 * inherited machinery are Mockito static mocks in try-with-resources — the Panache finders
 * ({@code PanacheEntityBase.list/findById}, {@code Product.findByEan}) and the session
 * ({@code Panache.getEntityManager}) — so no {@code persist()} ever hits an absent database. The
 * private helpers ({@code feedProduct}, {@code computeIncomingChecksum}) are exercised through
 * {@code processLineLogic} and asserted field by field (§29, §29.6).
 */
class ProductCsvResourceTest {

    /**
     * The system under test, a bare resource with no CDI wiring.
     */
    private final ProductCsvResource resource = new ProductCsvResource();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Header names of the fixture rows, in cell order.
     */
    private static final String[] TEST_HEADER = {"EAN", "NAME", "DESCRIPTION", "BRAND", "REFERENCE_WEIGHT", "REFERENCE_VOLUME", "PRODUCT_TYPE", "UNIT_NAME", "ACTIVE"};

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
     * Builds a nominal nine-column product row.
     *
     * @return The row {@code 3001|Milk|Whole milk|Lactel|1.5|1.0|UNIT|pcs|true}.
     */
    private LineData milkLine() {
        return line("3001", "Milk", "Whole milk", "Lactel", "1.5", "1.0", "UNIT", "pcs", "true");
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
            Method method = ProductCsvResource.class.getDeclaredMethod(name, types);
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
    // importProducts(): delegation to the staged importer
    // --------------------------------------------------

    /**
     * A header-only stream drives the delegation to {@code importCsvStream} without touching the
     * transaction machinery: the header row is skipped, no chunk is processed and the report
     * carries zero counts.
     */
    @Test
    @DisplayName("importProducts(): a header-only stream yields an empty report")
    void importProductsHeaderOnly() {
        InputStream in = new ByteArrayInputStream(
                "EAN|NAME|DESCRIPTION|BRAND|REFERENCE_WEIGHT|REFERENCE_VOLUME|PRODUCT_TYPE|UNIT_NAME|ACTIVE\n"
                        .getBytes(StandardCharsets.UTF_8));
        Response response = resource.importProducts(in);
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    // --------------------------------------------------
    // processChunkWithFallback(): the bulk pre-fetch (compound && guard, §29.6)
    // --------------------------------------------------

    /**
     * Both legs true: a non-empty chunk with a distinct key queries the persisted products and
     * keys them by EAN.
     */
    @Test
    @DisplayName("processChunkWithFallback(): a non-empty chunk keys existing products by EAN")
    void processChunkBothPresent() {
        Product existing = new Product();
        existing.ean = "3001";
        Set<String> targets = new HashSet<>(Set.of("3001"));
        List<PanacheEntityBase> found = new ArrayList<>();
        found.add(existing);
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("ean in ?1", targets)).thenReturn(found);
            Map<String, Object> map = resource.processChunkWithFallback(
                    List.of(milkLine()), targets, counters, errors);
            assertEquals(1, map.size());
            assertSame(existing, map.get("3001"));
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
                List.of(), new HashSet<>(Set.of("3001")), counters, new ArrayList<>());
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
                List.of(milkLine()), new HashSet<>(), counters, new ArrayList<>());
        assertTrue(map.isEmpty());
    }

    // --------------------------------------------------
    // findEntityForLine(): the 1-by-1 fallback lookup
    // --------------------------------------------------

    /**
     * A provided key is looked up by {@code findByEan}, returning the persisted product.
     */
    @Test
    @DisplayName("findEntityForLine(): an EAN is looked up by findByEan")
    void findEntityForLineFound() {
        Product existing = new Product();
        existing.ean = "3001";
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("3001")).thenReturn(existing);
            assertSame(existing, resource.findEntityForLine(milkLine()));
        }
    }

    /**
     * An absent EAN resolves to null so the row becomes a creation in the 1-by-1 fallback.
     */
    @Test
    @DisplayName("findEntityForLine(): an absent EAN resolves to null")
    void findEntityForLineAbsent() {
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("3001")).thenReturn(null);
            assertNull(resource.findEntityForLine(milkLine()));
        }
    }

    // --------------------------------------------------
    // processLineLogic(): create / update dispatch (compound || guard, §29.6)
    // --------------------------------------------------

    /**
     * The create arm: an EAN absent from the context map creates a product, feeds every business
     * field, bumps the created counter and persists it. Both {@code BigDecimal} references are
     * asserted by {@code compareTo} (§29.6).
     */
    @Test
    @DisplayName("processLineLogic(): an EAN absent from the map creates and persists a product")
    void processLineCreate() {
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(milkLine(), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            Mockito.verify(em).persist(captor.capture());
            Product created = (Product) captor.getValue();
            assertEquals("3001", created.ean);
            assertEquals("Milk", created.name);
            assertEquals("Whole milk", created.description);
            assertEquals("Lactel", created.brand);
            assertEquals(0, created.referenceWeight.compareTo(new BigDecimal("1.5")));
            assertEquals(0, created.referenceVolume.compareTo(new BigDecimal("1.0")));
            assertEquals(ProductType.UNIT, created.productType);
            assertEquals("pcs", created.unitName);
            assertTrue(created.active);
        }
    }

    /**
     * The update arm, first leg true: an existing product with a null checksum is re-read by id,
     * fed the incoming values and counted as updated; the created counter stays put and nothing is
     * persisted.
     */
    @Test
    @DisplayName("processLineLogic(): a null-checksum product is re-read and updated")
    void processLineUpdateNullChecksum() {
        Product existing = new Product();
        existing.ean = "3001";
        existing.id = 5L;
        existing.checksum = null;
        Product reread = new Product();
        reread.ean = "3001";
        reread.id = 5L;
        Map<String, Object> map = new HashMap<>();
        map.put("3001", existing);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(5L)).thenReturn(reread);
            resource.processLineLogic(milkLine(), map, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Milk", reread.name);
            assertEquals(0, reread.referenceWeight.compareTo(new BigDecimal("1.5")));
            assertEquals(0, reread.referenceVolume.compareTo(new BigDecimal("1.0")));
        }
    }

    /**
     * The update arm, first leg false and second leg true: an existing product with a non-null
     * checksum that differs from the incoming one is re-read by id and counted as updated.
     */
    @Test
    @DisplayName("processLineLogic(): a differing checksum re-reads and updates the product")
    void processLineUpdateDifferingChecksum() {
        LineData data = milkLine();
        Product existing = new Product();
        existing.ean = "3001";
        existing.id = 7L;
        existing.checksum = incomingChecksum(data) + 1;
        Product reread = new Product();
        reread.ean = "3001";
        reread.id = 7L;
        Map<String, Object> map = new HashMap<>();
        map.put("3001", existing);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(reread);
            resource.processLineLogic(data, map, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Milk", reread.name);
        }
    }

    /**
     * Both legs false: an existing product whose non-null checksum matches the incoming one is a
     * no-op — neither counter moves, nothing is re-read and nothing is persisted.
     */
    @Test
    @DisplayName("processLineLogic(): a matching checksum leaves the product untouched")
    void processLineUnchanged() {
        LineData data = milkLine();
        Product existing = new Product();
        existing.ean = "3001";
        existing.id = 9L;
        existing.checksum = incomingChecksum(data);
        Map<String, Object> map = new HashMap<>();
        map.put("3001", existing);
        int[] counters = {0, 0};
        resource.processLineLogic(data, map, counters);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }
}
