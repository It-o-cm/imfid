package com.intermarche.fidelity.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.domain.ProductFamily;
import com.intermarche.fidelity.imports.ImporterCsvResource.LineData;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
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
 * Plain unit coverage for {@link ProductFamilyCsvResource}: the bulk CSV import of the imfid
 * {@link ProductFamily} reference (§13, §15, §18). The class holds no clock and no protected
 * division, so it needs neither a {@code DateTimeProvider} nor a zero-denominator fixture; it
 * carries no {@code BigDecimal} field. Its branches are the empty-chunk short-circuit and the two
 * non-empty-set guards of the bulk pre-fetch, the create/update dispatch, the checksum-change
 * compound guard, the context-vs-fallback retrieval guards, and the three link-reconciliation
 * throws (unknown product, self-reference, unknown sub-family).
 * <p>
 * Fully isolated: the resource is instantiated bare and the collaborators it reaches through the
 * inherited machinery are Mockito static mocks in try-with-resources — the Panache finders
 * ({@code PanacheEntityBase.list/findById}, {@code ProductFamily.findByCode}) and the session
 * ({@code Panache.getEntityManager}) — so no {@code persist()} ever hits an absent database. The
 * private helpers ({@code feedFamily}, {@code linkProducts}, {@code linkSubFamilies},
 * {@code computeIncomingChecksum}) are exercised through the protected callbacks and asserted field
 * by field (§29, §29.6).
 */
class ProductFamilyCsvResourceTest {

    /**
     * Context key holding the pre-fetched products map, mirroring the resource constant.
     */
    private static final String CTX_PRODUCTS = "__CTX_PRODUCTS__";

    /**
     * Context key holding the pre-fetched sub-families map, mirroring the resource constant.
     */
    private static final String CTX_SUB_FAMILIES = "__CTX_SUB_FAMILIES__";

    /**
     * The system under test, a bare resource with no CDI wiring.
     */
    private final ProductFamilyCsvResource resource = new ProductFamilyCsvResource();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Header names of the fixture rows, in cell order.
     */
    private static final String[] TEST_HEADER = {"CODE", "DESCRIPTION", "FLAGS", "PRODUCT_EANS", "SUBFAMILY_CODES"};

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
     * Builds a nominal five-column family row referencing one product and one sub-family.
     *
     * @return The row {@code F1|Dairy|ORGANIC|3001|F2}.
     */
    private LineData dairyLine() {
        return line("F1", "Dairy", "ORGANIC", "3001", "F2");
    }

    /**
     * Builds a family with the given code, used as a context or fetch result.
     *
     * @param code The family business code.
     * @return The family carrying that code.
     */
    private ProductFamily family(String code) {
        ProductFamily family = new ProductFamily();
        family.code = code;
        return family;
    }

    /**
     * Builds a product with the given EAN, used as a context or fetch result.
     *
     * @param ean The product EAN.
     * @return The product carrying that EAN.
     */
    private Product product(String ean) {
        Product product = new Product();
        product.ean = ean;
        return product;
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
            Method method = ProductFamilyCsvResource.class.getDeclaredMethod(name, types);
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
    // importProductFamilies(): delegation to the staged importer
    // --------------------------------------------------

    /**
     * A header-only stream drives the delegation to {@code importCsvStream} without touching the
     * transaction machinery: the header row is skipped, no chunk is processed and the report carries
     * zero counts.
     */
    @Test
    @DisplayName("importProductFamilies(): a header-only stream yields an empty report")
    void importProductFamiliesHeaderOnly() {
        InputStream in = new ByteArrayInputStream(
                "CODE|DESCRIPTION|FLAGS|PRODUCT_EANS|SUBFAMILY_CODES\n".getBytes(StandardCharsets.UTF_8));
        Response response = resource.importProductFamilies(in);
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    // --------------------------------------------------
    // processChunkWithFallback(): empty short-circuit and the two non-empty-set guards (§29.6)
    // --------------------------------------------------

    /**
     * The {@code isEmpty()} guard true: an empty line list short-circuits before any query,
     * returning an empty context.
     */
    @Test
    @DisplayName("processChunkWithFallback(): an empty line list returns an empty context")
    void processChunkEmptyLines() {
        int[] counters = {0, 0};
        Map<String, Object> map = resource.processChunkWithFallback(
                List.of(), new HashSet<>(Set.of("F1")), counters, new ArrayList<>());
        assertTrue(map.isEmpty());
    }

    /**
     * The {@code isEmpty()} guard false with both fetch guards true: a non-empty chunk carrying a
     * product EAN and a sub-family code queries families, products and sub-families and keys each by
     * its natural key.
     */
    @Test
    @DisplayName("processChunkWithFallback(): a referencing chunk keys families, products and subs")
    void processChunkAllPresent() {
        ProductFamily existing = family("F1");
        Product referenced = product("3001");
        ProductFamily sub = family("F2");
        Set<String> targets = new HashSet<>();
        targets.add("F1");
        List<PanacheEntityBase> families = new ArrayList<>();
        families.add(existing);
        List<PanacheEntityBase> products = new ArrayList<>();
        products.add(referenced);
        List<PanacheEntityBase> subs = new ArrayList<>();
        subs.add(sub);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("code in ?1", targets)).thenReturn(families);
            panache.when(() -> PanacheEntityBase.list("ean in ?1", Set.of("3001"))).thenReturn(products);
            panache.when(() -> PanacheEntityBase.list("code in ?1", Set.of("F2"))).thenReturn(subs);
            Map<String, Object> map = resource.processChunkWithFallback(
                    List.of(dairyLine()), targets, counters, new ArrayList<>());
            assertEquals(3, map.size());
            assertSame(existing, map.get("F1"));
            @SuppressWarnings("unchecked")
            Map<String, Product> productMap = (Map<String, Product>) map.get(CTX_PRODUCTS);
            assertSame(referenced, productMap.get("3001"));
            @SuppressWarnings("unchecked")
            Map<String, ProductFamily> subMap = (Map<String, ProductFamily>) map.get(CTX_SUB_FAMILIES);
            assertSame(sub, subMap.get("F2"));
        }
    }

    /**
     * The {@code isEmpty()} guard false with both fetch guards false: a non-empty chunk whose EAN
     * and sub-family columns are blank skips both list queries, so the context holds only the two
     * empty pre-fetch maps and no family.
     */
    @Test
    @DisplayName("processChunkWithFallback(): a chunk with no references skips the product/sub queries")
    void processChunkNoReferences() {
        Set<String> targets = new HashSet<>();
        targets.add("F1");
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("code in ?1", targets))
                    .thenReturn(new ArrayList<PanacheEntityBase>());
            Map<String, Object> map = resource.processChunkWithFallback(
                    List.of(line("F1", "Dairy", "ORGANIC", "", "")), targets, counters, new ArrayList<>());
            assertEquals(2, map.size());
            assertTrue(((Map<?, ?>) map.get(CTX_PRODUCTS)).isEmpty());
            assertTrue(((Map<?, ?>) map.get(CTX_SUB_FAMILIES)).isEmpty());
        }
    }

    // --------------------------------------------------
    // findEntityForLine(): the 1-by-1 fallback lookup
    // --------------------------------------------------

    /**
     * A provided key is looked up by {@code findByCode}, returning the persisted family.
     */
    @Test
    @DisplayName("findEntityForLine(): a code is looked up by findByCode")
    void findEntityForLineFound() {
        ProductFamily existing = family("F1");
        try (MockedStatic<ProductFamily> families = Mockito.mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findByCode("F1")).thenReturn(existing);
            assertSame(existing, resource.findEntityForLine(dairyLine()));
        }
    }

    /**
     * An absent code resolves to null so the row becomes a creation in the 1-by-1 fallback.
     */
    @Test
    @DisplayName("findEntityForLine(): an absent code resolves to null")
    void findEntityForLineAbsent() {
        try (MockedStatic<ProductFamily> families = Mockito.mockStatic(ProductFamily.class)) {
            families.when(() -> ProductFamily.findByCode("F1")).thenReturn(null);
            assertNull(resource.findEntityForLine(dairyLine()));
        }
    }

    // --------------------------------------------------
    // processLineLogic(): create arm, context retrieval and link reconciliation
    // --------------------------------------------------

    /**
     * The create arm with a populated context (both retrieval guards non-null, both link loops
     * adding): a code absent from the map creates a family, feeds its fields, links the referenced
     * product and sub-family, bumps the created counter and persists it.
     */
    @Test
    @DisplayName("processLineLogic(): an absent code creates, links and persists a family")
    void processLineCreateWithLinks() {
        Product referenced = product("3001");
        ProductFamily sub = family("F2");
        Map<String, Object> map = new HashMap<>();
        map.put(CTX_PRODUCTS, new HashMap<>(Map.of("3001", referenced)));
        map.put(CTX_SUB_FAMILIES, new HashMap<>(Map.of("F2", sub)));
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(dairyLine(), map, counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            Mockito.verify(em).persist(captor.capture());
            ProductFamily created = (ProductFamily) captor.getValue();
            assertEquals("F1", created.code);
            assertEquals("Dairy", created.description);
            assertEquals("ORGANIC", created.flags);
            assertEquals(1, created.products.size());
            assertTrue(created.products.contains(referenced));
            assertEquals(1, created.productFamilies.size());
            assertTrue(created.productFamilies.contains(sub));
        }
    }

    /**
     * The create arm with an absent context (both retrieval guards null → fallback) and no
     * references: the fallback fetch skips its queries on empty sets, both link loops stay empty and
     * the family is created and persisted with no members.
     */
    @Test
    @DisplayName("processLineLogic(): a reference-free create falls back and persists an empty family")
    void processLineCreateFallbackNoReferences() {
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(line("F1", "Dairy", "ORGANIC", "", ""), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            Mockito.verify(em).persist(captor.capture());
            ProductFamily created = (ProductFamily) captor.getValue();
            assertEquals("F1", created.code);
            assertTrue(created.products.isEmpty());
            assertTrue(created.productFamilies.isEmpty());
        }
    }

    // --------------------------------------------------
    // processLineLogic(): update arm and the checksum-change compound guard (§29.6)
    // --------------------------------------------------

    /**
     * The update arm, first leg true: an existing family with a null checksum is re-read by id, fed
     * the incoming values and counted as updated; the created counter stays put and nothing is
     * persisted.
     */
    @Test
    @DisplayName("processLineLogic(): a null-checksum family is re-read and updated")
    void processLineUpdateNullChecksum() {
        ProductFamily existing = family("F1");
        existing.id = 5L;
        existing.checksum = null;
        ProductFamily reread = family("F1");
        reread.id = 5L;
        Map<String, Object> map = new HashMap<>();
        map.put("F1", existing);
        map.put(CTX_PRODUCTS, new HashMap<>());
        map.put(CTX_SUB_FAMILIES, new HashMap<>());
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(5L)).thenReturn(reread);
            resource.processLineLogic(line("F1", "Dairy", "ORGANIC", "", ""), map, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Dairy", reread.description);
            assertEquals("ORGANIC", reread.flags);
        }
    }

    /**
     * The update arm, first leg false and second leg true: an existing family with a non-null
     * checksum that differs from the incoming one is re-read by id and counted as updated.
     */
    @Test
    @DisplayName("processLineLogic(): a differing checksum re-reads and updates the family")
    void processLineUpdateDifferingChecksum() {
        LineData data = line("F1", "Dairy", "ORGANIC", "", "");
        ProductFamily existing = family("F1");
        existing.id = 7L;
        existing.checksum = incomingChecksum(data) + 1;
        ProductFamily reread = family("F1");
        reread.id = 7L;
        Map<String, Object> map = new HashMap<>();
        map.put("F1", existing);
        map.put(CTX_PRODUCTS, new HashMap<>());
        map.put(CTX_SUB_FAMILIES, new HashMap<>());
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(reread);
            resource.processLineLogic(data, map, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Dairy", reread.description);
        }
    }

    /**
     * The update arm, both legs false: an existing family whose non-null checksum matches the
     * incoming one is still re-read and fed (the family always reconciles its links) but neither
     * counter moves — the checksum optimization skips the updated count only.
     */
    @Test
    @DisplayName("processLineLogic(): a matching checksum re-reads but counts no update")
    void processLineUpdateMatchingChecksum() {
        LineData data = line("F1", "Dairy", "ORGANIC", "", "");
        ProductFamily existing = family("F1");
        existing.id = 9L;
        existing.checksum = incomingChecksum(data);
        ProductFamily reread = family("F1");
        reread.id = 9L;
        Map<String, Object> map = new HashMap<>();
        map.put("F1", existing);
        map.put(CTX_PRODUCTS, new HashMap<>());
        map.put(CTX_SUB_FAMILIES, new HashMap<>());
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(9L)).thenReturn(reread);
            resource.processLineLogic(data, map, counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            assertEquals("Dairy", reread.description);
        }
    }

    // --------------------------------------------------
    // linkProducts() / linkSubFamilies(): the three reconciliation throws
    // --------------------------------------------------

    /**
     * {@code linkProducts} throws on the {@code product == null} arm: a create row referencing an
     * EAN absent from the (present, empty) product context isolates the row.
     */
    @Test
    @DisplayName("processLineLogic(): an unknown product EAN rolls the row back")
    void processLineUnknownProduct() {
        Map<String, Object> map = new HashMap<>();
        map.put(CTX_PRODUCTS, new HashMap<>());
        map.put(CTX_SUB_FAMILIES, new HashMap<>());
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(line("F1", "Dairy", "ORGANIC", "3001", ""), map, counters));
        assertEquals("Product EAN '3001' not found.", ex.getMessage());
    }

    /**
     * {@code linkSubFamilies} throws on the self-reference arm: a create row listing its own code as
     * a sub-family is rejected before any lookup.
     */
    @Test
    @DisplayName("processLineLogic(): a self-referencing family rolls the row back")
    void processLineSelfReference() {
        Map<String, Object> map = new HashMap<>();
        map.put(CTX_PRODUCTS, new HashMap<>());
        map.put(CTX_SUB_FAMILIES, new HashMap<>());
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(line("F1", "Dairy", "ORGANIC", "", "F1"), map, counters));
        assertEquals("Family 'F1' cannot contain itself.", ex.getMessage());
    }

    /**
     * {@code linkSubFamilies} throws on the {@code sub == null} arm: a create row referencing a
     * sub-family code absent from the (present, empty) sub-family context isolates the row.
     */
    @Test
    @DisplayName("processLineLogic(): an unknown sub-family code rolls the row back")
    void processLineUnknownSubFamily() {
        Map<String, Object> map = new HashMap<>();
        map.put(CTX_PRODUCTS, new HashMap<>());
        map.put(CTX_SUB_FAMILIES, new HashMap<>());
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(line("F1", "Dairy", "ORGANIC", "", "F2"), map, counters));
        assertEquals("SubFamily code 'F2' not found.", ex.getMessage());
    }
}
