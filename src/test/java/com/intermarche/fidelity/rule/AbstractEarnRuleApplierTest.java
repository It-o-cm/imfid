package com.intermarche.fidelity.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.domain.ProductFamily;
import com.intermarche.fidelity.domain.ProductType;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link AbstractEarnRuleApplier} — the shared earn machinery
 * (§12, §13): specification parsing, INCLUDE/EXCLUDE assiette scope resolution against
 * the imfid product reference (§15, §25.4), the eligible-item count (§22.2), the single
 * per-rule centime rounding (I4) and the null-guarded specification readers (§31.2).
 * <p>
 * The class is abstract and carries no clock and no card context: it is exercised through
 * a minimal concrete {@link TestApplier} whose {@code apply} is a stub, and its protected
 * members are reached directly from this same-package test. The static product finders
 * ({@link Product#findByEan(String)}, {@link ProductFamily#findAllFamiliesForProduct}) are
 * mocked with {@code Mockito.mockStatic} in try-with-resources whenever an EAN is resolved;
 * pure arithmetic is asserted in memory to the centime. Every {@link BigDecimal} is asserted
 * by {@code compareTo}. The two genuinely unreachable private legs ({@code readStringSet}
 * with a null parent, {@code intersects} with a null operand) are reached by reflection.
 */
class AbstractEarnRuleApplierTest {

    /**
     * The stable rule code carried by every fixture rule (§13).
     */
    private static final String CODE = "R1";

    /**
     * The printed rule label carried by every fixture rule (§18).
     */
    private static final String LABEL = "Rule 1";

    /**
     * A minimal concrete applier: all the tested behaviour lives in the base class, so its
     * {@code apply} simply returns the empty entry.
     */
    private static final class TestApplier extends AbstractEarnRuleApplier {

        /**
         * Builds the test applier over the given rule.
         *
         * @param rule The rule to interpret.
         */
        TestApplier(FidelityRule rule) {
            super(rule);
        }

        /**
         * Returns the empty entry; the base class carries every branch under test.
         *
         * @param lines   The valued basket lines.
         * @param context The dated card context.
         * @return The empty entry of the rule.
         */
        @Override
        public EarnEntry apply(List<ValuedLine> lines, CardContext context) {
            return none();
        }
    }

    /**
     * Builds a rule carrying the given JSON specification.
     *
     * @param specification The rule specification JSON; may be null or blank.
     * @return A fidelity rule ready for the applier.
     */
    private static FidelityRule ruleWith(String specification) {
        FidelityRule rule = new FidelityRule();
        rule.code = CODE;
        rule.label = LABEL;
        rule.type = FidelityRule.TYPE_BRAND_TIERED_EARN;
        rule.specification = specification;
        return rule;
    }

    /**
     * Builds a test applier over a rule carrying the given specification.
     *
     * @param specification The rule specification JSON.
     * @return The test applier.
     */
    private static TestApplier applierWith(String specification) {
        return new TestApplier(ruleWith(specification));
    }

    /**
     * Builds a valued line with an explicit EAN, quantity and TTC net.
     *
     * @param lineId   The line id.
     * @param ean      The line EAN; may be null.
     * @param quantity The line quantity.
     * @param netTtc   The net TTC amount that founds the assiette.
     * @param consumed Whether a commercial offer consumed the line.
     * @return The valued line.
     */
    private static ValuedLine line(String lineId, String ean, String quantity, String netTtc, boolean consumed) {
        return new ValuedLine(lineId, ean, new BigDecimal(quantity),
                new BigDecimal(netTtc), new BigDecimal(netTtc), consumed);
    }

    /**
     * Builds a product of the reference with the given EAN, brand and type.
     *
     * @param ean         The product EAN.
     * @param brand       The product brand; may be null.
     * @param productType The product type; may be null.
     * @return The product.
     */
    private static Product product(String ean, String brand, ProductType productType) {
        Product product = new Product();
        product.ean = ean;
        product.brand = brand;
        product.productType = productType;
        return product;
    }

    /**
     * Builds a product family with the given business code.
     *
     * @param code The family code; may be null.
     * @return The family.
     */
    private static ProductFamily family(String code) {
        ProductFamily family = new ProductFamily();
        family.code = code;
        return family;
    }

    /**
     * Invokes a private static method of the base class by reflection.
     *
     * @param name  The method name.
     * @param types The parameter types.
     * @param args  The arguments.
     * @return The method result.
     * @throws Exception when the reflective invocation fails.
     */
    private static Object invokePrivateStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = AbstractEarnRuleApplier.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    // --------------------------------------------------
    // Constructor and specification parsing (§31.2)
    // --------------------------------------------------

    /**
     * A null rule is rejected (rule == null, true arm).
     */
    @Test
    @DisplayName("constructor rejects a null rule")
    void constructorRejectsNullRule() {
        assertThrows(IllegalArgumentException.class, () -> new TestApplier(null));
    }

    /**
     * A null specification parses to an empty object: no wholeStore, no includes, so an
     * EAN-less line is out of scope (specification == null true leg).
     */
    @Test
    @DisplayName("null specification yields an empty scope")
    void nullSpecificationYieldsEmptyScope() {
        TestApplier applier = applierWith(null);
        assertNotNull(applier);
        assertFalse(applier.isInScope(line("L1", null, "1", "10.00", false)));
    }

    /**
     * A blank specification parses to an empty object (specification.isBlank() true leg).
     */
    @Test
    @DisplayName("blank specification yields an empty scope")
    void blankSpecificationYieldsEmptyScope() {
        TestApplier applier = applierWith("   ");
        assertFalse(applier.isInScope(line("L1", null, "1", "10.00", false)));
    }

    /**
     * A malformed specification raises an IllegalStateException (parse catch branch).
     */
    @Test
    @DisplayName("malformed specification raises IllegalStateException")
    void malformedSpecificationThrows() {
        assertThrows(IllegalStateException.class, () -> applierWith("{not-json"));
    }

    /**
     * A scope with an array carrying a blank string, a non-textual element and a valid
     * brand keeps only the trimmed, non-blank textual value (readStringSet legs).
     */
    @Test
    @DisplayName("readStringSet keeps only non-blank textual values")
    void readStringSetFiltersBlankAndNonTextual() {
        TestApplier applier = applierWith("{\"scope\":{\"include\":{\"brands\":[\"  \",7,\" ACME \"]}}}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            Product product = product("E1", "ACME", ProductType.UNIT);
            products.when(() -> Product.findByEan("E1")).thenReturn(product);
            assertTrue(applier.isInScope(line("L1", "E1", "1", "10.00", false)));
        }
    }

    /**
     * A non-array scope field resolves to an empty set, so it never retains a line
     * (readStringSet node.isArray() false leg).
     */
    @Test
    @DisplayName("readStringSet treats a non-array field as empty")
    void readStringSetTreatsNonArrayAsEmpty() {
        TestApplier applier = applierWith("{\"scope\":{\"include\":{\"brands\":\"ACME\",\"eans\":[\"E1\"]}}}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            Product product = product("E2", "ACME", ProductType.UNIT);
            products.when(() -> Product.findByEan("E2")).thenReturn(product);
            assertFalse(applier.isInScope(line("L1", "E2", "1", "10.00", false)));
        }
    }

    /**
     * The private {@code readStringSet} returns an empty set for a null parent — a leg the
     * constructor never reaches because scope children come from {@code JsonNode.path()}.
     *
     * @throws Exception when the reflective invocation fails.
     */
    @Test
    @DisplayName("readStringSet returns empty for a null parent")
    void readStringSetNullParent() throws Exception {
        Object result = invokePrivateStatic("readStringSet",
                new Class<?>[]{JsonNode.class, String.class}, null, "brands");
        assertTrue(result instanceof Set);
        assertTrue(((Set<?>) result).isEmpty());
    }

    /**
     * The private {@code readStringSet} skips a null array element — the §31.2 null guard on
     * the parser, reached with a hand-built array node whose single element is null
     * (element != null false leg).
     *
     * @throws Exception when the reflective invocation fails.
     */
    @Test
    @DisplayName("readStringSet skips a null array element")
    void readStringSetSkipsNullElement() throws Exception {
        JsonNode parent = Mockito.mock(JsonNode.class);
        JsonNode array = Mockito.mock(JsonNode.class);
        Mockito.when(parent.get("brands")).thenReturn(array);
        Mockito.when(array.isArray()).thenReturn(true);
        Mockito.when(array.iterator()).thenReturn(Collections.singletonList((JsonNode) null).iterator());
        Object result = invokePrivateStatic("readStringSet",
                new Class<?>[]{JsonNode.class, String.class}, parent, "brands");
        assertTrue(((Set<?>) result).isEmpty());
    }

    /**
     * The private {@code readStringSet} skips a textual element whose text reads null — the
     * §31.2 null guard on the parsed value (value != null false leg), reached with a
     * hand-built element returning a null text.
     *
     * @throws Exception when the reflective invocation fails.
     */
    @Test
    @DisplayName("readStringSet skips an element with null text")
    void readStringSetSkipsNullText() throws Exception {
        JsonNode parent = Mockito.mock(JsonNode.class);
        JsonNode array = Mockito.mock(JsonNode.class);
        JsonNode element = Mockito.mock(JsonNode.class);
        Mockito.when(parent.get("brands")).thenReturn(array);
        Mockito.when(array.isArray()).thenReturn(true);
        Mockito.when(array.iterator()).thenReturn(Collections.singletonList(element).iterator());
        Mockito.when(element.isTextual()).thenReturn(true);
        Mockito.when(element.asText()).thenReturn(null);
        Object result = invokePrivateStatic("readStringSet",
                new Class<?>[]{JsonNode.class, String.class}, parent, "brands");
        assertTrue(((Set<?>) result).isEmpty());
    }

    // --------------------------------------------------
    // isInScope (§13, §15, §25.4)
    // --------------------------------------------------

    /**
     * A null line is out of scope (line == null, true arm).
     */
    @Test
    @DisplayName("isInScope rejects a null line")
    void isInScopeNullLine() {
        assertFalse(applierWith("{\"scope\":{\"wholeStore\":true}}").isInScope(null));
    }

    /**
     * A whole-store scope retains an EAN-less line: wholeStore leg true, ean null so no
     * finder is read, no exclude matches (§25.4).
     */
    @Test
    @DisplayName("isInScope retains an EAN-less line under whole-store")
    void isInScopeWholeStoreEanLess() {
        assertTrue(applierWith("{\"scope\":{\"wholeStore\":true}}")
                .isInScope(line("L1", null, "1", "10.00", false)));
    }

    /**
     * The included EAN leg retains a line whose product is unknown (findByEan null, brand
     * null, families empty), matched solely by its EAN.
     */
    @Test
    @DisplayName("isInScope retains a line by its included EAN")
    void isInScopeIncludedByEan() {
        TestApplier applier = applierWith("{\"scope\":{\"include\":{\"eans\":[\"E1\"]}}}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("E1")).thenReturn(null);
            assertTrue(applier.isInScope(line("L1", "E1", "1", "10.00", false)));
        }
    }

    /**
     * The included brand leg retains a line whose product carries the brand (EAN leg false,
     * brand leg true).
     */
    @Test
    @DisplayName("isInScope retains a line by its included brand")
    void isInScopeIncludedByBrand() {
        TestApplier applier = applierWith("{\"scope\":{\"include\":{\"brands\":[\"ACME\"]}}}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("E2")).thenReturn(product("E2", "ACME", ProductType.UNIT));
            assertTrue(applier.isInScope(line("L1", "E2", "1", "10.00", false)));
        }
    }

    /**
     * The included family leg retains a line whose product hierarchy intersects the included
     * families; a family carrying a null code is ignored (familyCodesOf code != null legs).
     */
    @Test
    @DisplayName("isInScope retains a line by an included family")
    void isInScopeIncludedByFamily() {
        TestApplier applier = applierWith("{\"scope\":{\"include\":{\"families\":[\"FAM1\"]}}}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class);
             MockedStatic<ProductFamily> families = Mockito.mockStatic(ProductFamily.class)) {
            Product product = product("E3", null, ProductType.UNIT);
            products.when(() -> Product.findByEan("E3")).thenReturn(product);
            Set<ProductFamily> hierarchy = new HashSet<>();
            hierarchy.add(family(null));
            hierarchy.add(family("FAM1"));
            families.when(() -> ProductFamily.findAllFamiliesForProduct(product)).thenReturn(hierarchy);
            assertTrue(applier.isInScope(line("L1", "E3", "1", "10.00", false)));
        }
    }

    /**
     * A line matched by no include (and no whole-store) is out of scope (included false).
     */
    @Test
    @DisplayName("isInScope rejects a line matched by no include")
    void isInScopeNotIncluded() {
        TestApplier applier = applierWith("{\"scope\":{\"include\":{\"brands\":[\"ACME\"]}}}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("E4")).thenReturn(product("E4", "OTHER", ProductType.UNIT));
            assertFalse(applier.isInScope(line("L1", "E4", "1", "10.00", false)));
        }
    }

    /**
     * The excluded EAN leg removes an otherwise whole-store line (excluded eans leg true).
     */
    @Test
    @DisplayName("isInScope removes a line by its excluded EAN")
    void isInScopeExcludedByEan() {
        TestApplier applier = applierWith("{\"scope\":{\"wholeStore\":true,\"exclude\":{\"eans\":[\"E5\"]}}}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("E5")).thenReturn(null);
            assertFalse(applier.isInScope(line("L1", "E5", "1", "10.00", false)));
        }
    }

    /**
     * The excluded brand leg removes an otherwise whole-store line (excluded eans false,
     * brand leg true).
     */
    @Test
    @DisplayName("isInScope removes a line by its excluded brand")
    void isInScopeExcludedByBrand() {
        TestApplier applier = applierWith("{\"scope\":{\"wholeStore\":true,\"exclude\":{\"brands\":[\"BADBRAND\"]}}}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("E6")).thenReturn(product("E6", "BADBRAND", ProductType.UNIT));
            assertFalse(applier.isInScope(line("L1", "E6", "1", "10.00", false)));
        }
    }

    /**
     * The excluded family leg removes an otherwise whole-store line (excluded families leg
     * true).
     */
    @Test
    @DisplayName("isInScope removes a line by an excluded family")
    void isInScopeExcludedByFamily() {
        TestApplier applier = applierWith("{\"scope\":{\"wholeStore\":true,\"exclude\":{\"families\":[\"FEX\"]}}}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class);
             MockedStatic<ProductFamily> families = Mockito.mockStatic(ProductFamily.class)) {
            Product product = product("E7", null, ProductType.UNIT);
            products.when(() -> Product.findByEan("E7")).thenReturn(product);
            families.when(() -> ProductFamily.findAllFamiliesForProduct(product))
                    .thenReturn(Set.of(family("FEX")));
            assertFalse(applier.isInScope(line("L1", "E7", "1", "10.00", false)));
        }
    }

    /**
     * A whole-store line that matches no exclude is retained (excluded false, return true).
     */
    @Test
    @DisplayName("isInScope retains a whole-store line with no matching exclude")
    void isInScopeIncludedNotExcluded() {
        TestApplier applier = applierWith("{\"scope\":{\"wholeStore\":true,\"exclude\":{\"eans\":[\"OTHER\"]}}}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("E8")).thenReturn(product("E8", "ACME", ProductType.UNIT));
            assertTrue(applier.isInScope(line("L1", "E8", "1", "10.00", false)));
        }
    }

    // --------------------------------------------------
    // intersects (private helper, §13)
    // --------------------------------------------------

    /**
     * The private {@code intersects} covers each guard leg and the smaller/larger selection.
     *
     * @throws Exception when the reflective invocation fails.
     */
    @Test
    @DisplayName("intersects covers null, empty and size legs")
    void intersectsAllLegs() throws Exception {
        Class<?>[] types = {Set.class, Set.class};
        assertFalse((Boolean) invokePrivateStatic("intersects", types, null, Set.of("X")));
        assertFalse((Boolean) invokePrivateStatic("intersects", types, Set.of("X"), null));
        assertFalse((Boolean) invokePrivateStatic("intersects", types, Set.of(), Set.of("X")));
        assertFalse((Boolean) invokePrivateStatic("intersects", types, Set.of("X"), Set.of()));
        assertTrue((Boolean) invokePrivateStatic("intersects", types, Set.of("X"), Set.of("X", "Y")));
        assertFalse((Boolean) invokePrivateStatic("intersects", types, Set.of("X", "Y"), Set.of("Z")));
    }

    // --------------------------------------------------
    // isEligibleLine, eligibleLines (I2, §15)
    // --------------------------------------------------

    /**
     * A null line is never eligible (line != null, false arm).
     */
    @Test
    @DisplayName("isEligibleLine rejects a null line")
    void isEligibleLineNull() {
        assertFalse(applierWith("{\"scope\":{\"wholeStore\":true}}").isEligibleLine(null));
    }

    /**
     * A line consumed by a commercial offer is not an earn candidate (isEarnCandidate false
     * leg, I2).
     */
    @Test
    @DisplayName("isEligibleLine rejects a consumed line")
    void isEligibleLineConsumed() {
        assertFalse(applierWith("{\"scope\":{\"wholeStore\":true}}")
                .isEligibleLine(line("L1", null, "1", "10.00", true)));
    }

    /**
     * A candidate line out of scope is not eligible (isInScope false leg).
     */
    @Test
    @DisplayName("isEligibleLine rejects an out-of-scope candidate")
    void isEligibleLineOutOfScope() {
        assertFalse(applierWith("{}").isEligibleLine(line("L1", null, "1", "10.00", false)));
    }

    /**
     * A candidate, in-scope line is eligible (all three legs true).
     */
    @Test
    @DisplayName("isEligibleLine accepts a candidate in scope")
    void isEligibleLineEligible() {
        assertTrue(applierWith("{\"scope\":{\"wholeStore\":true}}")
                .isEligibleLine(line("L1", null, "1", "10.00", false)));
    }

    /**
     * A null basket yields an empty eligible list (lines == null true leg).
     */
    @Test
    @DisplayName("eligibleLines returns empty for a null basket")
    void eligibleLinesNull() {
        assertTrue(applierWith("{\"scope\":{\"wholeStore\":true}}").eligibleLines(null).isEmpty());
    }

    /**
     * A mixed basket keeps only the eligible line (lines != null leg, retain filter).
     */
    @Test
    @DisplayName("eligibleLines keeps only eligible lines")
    void eligibleLinesMixed() {
        TestApplier applier = applierWith("{\"scope\":{\"wholeStore\":true}}");
        List<ValuedLine> lines = List.of(
                line("KEEP", null, "1", "10.00", false),
                line("DROP", null, "1", "10.00", true));
        List<ValuedLine> retained = applier.eligibleLines(lines);
        assertEquals(1, retained.size());
        assertEquals("KEEP", retained.get(0).lineId);
    }

    // --------------------------------------------------
    // assietteOf, lineIdsOf (§15, §22.1)
    // --------------------------------------------------

    /**
     * A null list sums to zero (lines != null false arm).
     */
    @Test
    @DisplayName("assietteOf sums a null list to zero")
    void assietteOfNull() {
        assertEquals(0, applierWith("{}").assietteOf(null).compareTo(new BigDecimal("0.00")));
    }

    /**
     * A list sums the non-negative TTC nets; a negative net is clamped to zero (§22.1).
     */
    @Test
    @DisplayName("assietteOf sums the non-negative nets")
    void assietteOfSum() {
        TestApplier applier = applierWith("{}");
        List<ValuedLine> lines = List.of(
                line("L1", null, "1", "10.00", false),
                line("L2", null, "1", "5.55", false),
                line("L3", null, "1", "-3.00", false));
        assertEquals(0, applier.assietteOf(lines).compareTo(new BigDecimal("15.55")));
    }

    /**
     * A null list yields no ids (lines != null false arm).
     */
    @Test
    @DisplayName("lineIdsOf returns empty for a null list")
    void lineIdsOfNull() {
        assertTrue(applierWith("{}").lineIdsOf(null).isEmpty());
    }

    /**
     * A list yields its ids in encounter order (lines != null true arm).
     */
    @Test
    @DisplayName("lineIdsOf collects the ids in order")
    void lineIdsOfCollects() {
        TestApplier applier = applierWith("{}");
        List<String> ids = applier.lineIdsOf(List.of(
                line("A", null, "1", "1.00", false),
                line("B", null, "1", "1.00", false)));
        assertEquals(List.of("A", "B"), ids);
    }

    // --------------------------------------------------
    // countEligibleItems (§22.2)
    // --------------------------------------------------

    /**
     * A null list counts zero items (lines == null true leg).
     */
    @Test
    @DisplayName("countEligibleItems counts a null list as zero")
    void countEligibleItemsNull() {
        assertEquals(0, applierWith("{}").countEligibleItems(null));
    }

    /**
     * A UNIT product contributes its floored quantity (product UNIT leg, positive max).
     */
    @Test
    @DisplayName("countEligibleItems floors the quantity of a UNIT product")
    void countEligibleItemsUnitFloors() {
        TestApplier applier = applierWith("{}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("U1")).thenReturn(product("U1", null, ProductType.UNIT));
            assertEquals(2, applier.countEligibleItems(List.of(line("L1", "U1", "2.7", "10.00", false))));
        }
    }

    /**
     * A UNIT product with a negative quantity is clamped to zero (Math.max negative leg).
     */
    @Test
    @DisplayName("countEligibleItems clamps a negative UNIT quantity to zero")
    void countEligibleItemsUnitNegative() {
        TestApplier applier = applierWith("{}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("U2")).thenReturn(product("U2", null, ProductType.UNIT));
            assertEquals(0, applier.countEligibleItems(List.of(line("L1", "U2", "-1", "10.00", false))));
        }
    }

    /**
     * A WEIGHT product counts as one item (productType != UNIT leg, §22.2).
     */
    @Test
    @DisplayName("countEligibleItems counts a WEIGHT product as one item")
    void countEligibleItemsWeight() {
        TestApplier applier = applierWith("{}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("W1")).thenReturn(product("W1", null, ProductType.WEIGHT));
            assertEquals(1, applier.countEligibleItems(List.of(line("L1", "W1", "5.5", "10.00", false))));
        }
    }

    /**
     * A product with a null type counts as one item (productType != null false leg).
     */
    @Test
    @DisplayName("countEligibleItems counts a null-type product as one item")
    void countEligibleItemsNullType() {
        TestApplier applier = applierWith("{}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("N1")).thenReturn(product("N1", null, null));
            assertEquals(1, applier.countEligibleItems(List.of(line("L1", "N1", "3", "10.00", false))));
        }
    }

    /**
     * An unresolved product counts as one item whether the EAN is null (ean != null false
     * leg) or unknown to the reference (product != null false leg) — §22.2, §25.4.
     */
    @Test
    @DisplayName("countEligibleItems counts an unresolved product as one item")
    void countEligibleItemsUnresolved() {
        TestApplier applier = applierWith("{}");
        try (MockedStatic<Product> products = Mockito.mockStatic(Product.class)) {
            products.when(() -> Product.findByEan("X1")).thenReturn(null);
            List<ValuedLine> lines = List.of(
                    line("L1", null, "4", "10.00", false),
                    line("L2", "X1", "4", "10.00", false));
            assertEquals(2, applier.countEligibleItems(lines));
        }
    }

    // --------------------------------------------------
    // round, applyRate, entry, none (I4, §30.5)
    // --------------------------------------------------

    /**
     * A null amount rounds to zero (value != null false arm).
     */
    @Test
    @DisplayName("round reads a null amount as zero")
    void roundNull() {
        assertEquals(0, applierWith("{}").round(null).compareTo(new BigDecimal("0.00")));
    }

    /**
     * A non-null amount rounds HALF_UP at the centime (value != null true arm).
     */
    @Test
    @DisplayName("round rounds HALF_UP at the centime")
    void roundHalfUp() {
        TestApplier applier = applierWith("{}");
        assertEquals(0, applier.round(new BigDecimal("2.345")).compareTo(new BigDecimal("2.35")));
        assertEquals(0, applier.round(new BigDecimal("2.344")).compareTo(new BigDecimal("2.34")));
    }

    /**
     * A null assiette yields a zero earn (assiette == null true leg).
     */
    @Test
    @DisplayName("applyRate reads a null assiette as zero")
    void applyRateNullAssiette() {
        assertEquals(0, applierWith("{}").applyRate(null, new BigDecimal("0.05"))
                .compareTo(new BigDecimal("0.00")));
    }

    /**
     * A null rate yields a zero earn (rate == null true leg).
     */
    @Test
    @DisplayName("applyRate reads a null rate as zero")
    void applyRateNullRate() {
        assertEquals(0, applierWith("{}").applyRate(new BigDecimal("100.00"), null)
                .compareTo(new BigDecimal("0.00")));
    }

    /**
     * A rate on an assiette rounds once at the centime (both non-null arm, I4).
     */
    @Test
    @DisplayName("applyRate multiplies and rounds once")
    void applyRateComputes() {
        TestApplier applier = applierWith("{}");
        assertEquals(0, applier.applyRate(new BigDecimal("100.00"), new BigDecimal("0.05"))
                .compareTo(new BigDecimal("5.00")));
        assertEquals(0, applier.applyRate(new BigDecimal("33.33"), new BigDecimal("0.05"))
                .compareTo(new BigDecimal("1.67")));
    }

    /**
     * The entry carries the rule code, label, amount, assiette and line ids (§15).
     */
    @Test
    @DisplayName("entry carries the rule code, label, amount and lines")
    void entryBuilds() {
        TestApplier applier = applierWith("{}");
        List<ValuedLine> lines = List.of(line("A", null, "1", "1.00", false));
        EarnEntry entry = applier.entry(new BigDecimal("5.00"), new BigDecimal("100.00"), lines);
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(0, entry.amount.compareTo(new BigDecimal("5.00")));
        assertEquals(0, entry.baseAmount.compareTo(new BigDecimal("100.00")));
        assertEquals(List.of("A"), entry.lineIds);
    }

    /**
     * The empty entry grants nothing but keeps the rule identity (§15).
     */
    @Test
    @DisplayName("none returns the empty entry of the rule")
    void noneBuilds() {
        EarnEntry entry = applierWith("{}").none();
        assertTrue(entry.isEmpty());
        assertEquals(CODE, entry.ruleCode);
        assertEquals(LABEL, entry.label);
        assertEquals(0, entry.amount.compareTo(new BigDecimal("0.00")));
    }

    // --------------------------------------------------
    // decimal, integer, text, tiersNode (§31.2)
    // --------------------------------------------------

    /**
     * An absent decimal field returns the fallback (node == null true leg).
     */
    @Test
    @DisplayName("decimal returns the fallback when the field is absent")
    void decimalAbsent() {
        assertEquals(0, applierWith("{}").decimal("x", new BigDecimal("9.99"))
                .compareTo(new BigDecimal("9.99")));
    }

    /**
     * An explicit-null decimal field returns the fallback (node.isNull() true leg).
     */
    @Test
    @DisplayName("decimal returns the fallback for an explicit null")
    void decimalNull() {
        assertEquals(0, applierWith("{\"x\":null}").decimal("x", new BigDecimal("9.99"))
                .compareTo(new BigDecimal("9.99")));
    }

    /**
     * A non-numeric decimal field returns the fallback (!isNumber true leg).
     */
    @Test
    @DisplayName("decimal returns the fallback for a non-numeric field")
    void decimalNonNumber() {
        assertEquals(0, applierWith("{\"x\":\"str\"}").decimal("x", new BigDecimal("9.99"))
                .compareTo(new BigDecimal("9.99")));
    }

    /**
     * A numeric decimal field returns its value (all guard legs false).
     */
    @Test
    @DisplayName("decimal returns the numeric value")
    void decimalNumber() {
        assertEquals(0, applierWith("{\"x\":1.5}").decimal("x", new BigDecimal("9.99"))
                .compareTo(new BigDecimal("1.5")));
    }

    /**
     * An absent integer field returns the fallback (node == null true leg).
     */
    @Test
    @DisplayName("integer returns the fallback when the field is absent")
    void integerAbsent() {
        assertEquals(42, applierWith("{}").integer("n", 42));
    }

    /**
     * An explicit-null integer field returns the fallback (node.isNull() true leg).
     */
    @Test
    @DisplayName("integer returns the fallback for an explicit null")
    void integerNull() {
        assertEquals(42, applierWith("{\"n\":null}").integer("n", 42));
    }

    /**
     * A non-numeric integer field returns the fallback (!isNumber true leg).
     */
    @Test
    @DisplayName("integer returns the fallback for a non-numeric field")
    void integerNonNumber() {
        assertEquals(42, applierWith("{\"n\":\"str\"}").integer("n", 42));
    }

    /**
     * A numeric integer field returns its value (all guard legs false).
     */
    @Test
    @DisplayName("integer returns the numeric value")
    void integerNumber() {
        assertEquals(7, applierWith("{\"n\":7}").integer("n", 42));
    }

    /**
     * An absent text field returns null (node == null true leg).
     */
    @Test
    @DisplayName("text returns null when the field is absent")
    void textAbsent() {
        assertEquals(null, applierWith("{}").text("t"));
    }

    /**
     * An explicit-null text field returns null (node.isNull() true leg).
     */
    @Test
    @DisplayName("text returns null for an explicit null")
    void textNull() {
        assertEquals(null, applierWith("{\"t\":null}").text("t"));
    }

    /**
     * A non-textual field returns null (!isTextual true leg).
     */
    @Test
    @DisplayName("text returns null for a non-textual field")
    void textNonTextual() {
        assertEquals(null, applierWith("{\"t\":5}").text("t"));
    }

    /**
     * A blank textual field returns null (value.isBlank() true leg).
     */
    @Test
    @DisplayName("text returns null for a blank field")
    void textBlank() {
        assertEquals(null, applierWith("{\"t\":\"   \"}").text("t"));
    }

    /**
     * A non-blank textual field returns its trimmed value (value.isBlank() false leg).
     */
    @Test
    @DisplayName("text returns the trimmed value")
    void textTrims() {
        assertEquals("hi", applierWith("{\"t\":\"  hi \"}").text("t"));
    }

    /**
     * An absent tiers field yields an empty array (node == null leg).
     */
    @Test
    @DisplayName("tiersNode yields an empty array when absent")
    void tiersNodeAbsent() {
        JsonNode tiers = applierWith("{}").tiersNode();
        assertTrue(tiers.isArray());
        assertEquals(0, tiers.size());
    }

    /**
     * A non-array tiers field yields an empty array (node.isArray() false leg).
     */
    @Test
    @DisplayName("tiersNode yields an empty array for a non-array field")
    void tiersNodeNonArray() {
        JsonNode tiers = applierWith("{\"tiers\":5}").tiersNode();
        assertTrue(tiers.isArray());
        assertEquals(0, tiers.size());
    }

    /**
     * An array tiers field is returned as is (node != null and isArray legs).
     */
    @Test
    @DisplayName("tiersNode returns the array node")
    void tiersNodeArray() {
        JsonNode tiers = applierWith("{\"tiers\":[{},{}]}").tiersNode();
        assertTrue(tiers.isArray());
        assertEquals(2, tiers.size());
    }
}
