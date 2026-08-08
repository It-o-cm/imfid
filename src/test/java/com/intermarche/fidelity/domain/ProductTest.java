package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link Product}: the imfid product reference (§15). The class carries no
 * clock read of its own — every field is a stored attribute, not a {@code DateTimeProvider} call —
 * so no time is injected here. The {@code standardQuantity} conversion is pure arithmetic, exercised
 * leg by leg on its protected division (§31.2) and asserted to the sixth decimal by {@code compareTo}
 * (§29.6). The Panache active-record finders are mocked through {@link PanacheEntityBase} in a
 * try-with-resources per the imfid unit bench, both arms of every guard covered, and the checksum is
 * asserted as the pure function of the business fields it is.
 */
class ProductTest {

    // --------------------------------------------------
    // standardQuantity()
    // --------------------------------------------------

    /**
     * A UNIT product returns the raw quantity: the first guard {@code productType == UNIT} is true.
     */
    @Test
    @DisplayName("standardQuantity(): UNIT returns the raw quantity")
    void standardQuantityUnit() {
        Product product = new Product();
        product.productType = ProductType.UNIT;
        assertTrue(BigDecimal.valueOf(3).compareTo(product.standardQuantity(3)) == 0);
    }

    /**
     * A WEIGHT product divides by its reference weight: the UNIT guard is false and the ternary
     * selects {@code referenceWeight}, the reference being non-null and non-zero.
     */
    @Test
    @DisplayName("standardQuantity(): WEIGHT divides by the reference weight")
    void standardQuantityWeight() {
        Product product = new Product();
        product.productType = ProductType.WEIGHT;
        product.referenceWeight = new BigDecimal("0.250");
        assertTrue(new BigDecimal("4.000000").compareTo(product.standardQuantity(1.0)) == 0);
    }

    /**
     * A VOLUME product divides by its reference volume: the ternary {@code productType == VOLUME} is
     * true so the reference is {@code referenceVolume}, non-null and non-zero.
     */
    @Test
    @DisplayName("standardQuantity(): VOLUME divides by the reference volume")
    void standardQuantityVolume() {
        Product product = new Product();
        product.productType = ProductType.VOLUME;
        product.referenceVolume = new BigDecimal("2.000");
        assertTrue(new BigDecimal("0.500000").compareTo(product.standardQuantity(1.0)) == 0);
    }

    /**
     * A weight product with a null reference weight yields zero: the first leg
     * {@code reference == null} of the protected division guard is true (§31.2).
     */
    @Test
    @DisplayName("standardQuantity(): null reference returns zero")
    void standardQuantityNullReference() {
        Product product = new Product();
        product.productType = ProductType.WEIGHT;
        product.referenceWeight = null;
        assertTrue(BigDecimal.ZERO.compareTo(product.standardQuantity(5.0)) == 0);
    }

    /**
     * A volume product with a zero reference volume yields zero: the first leg is false, the second
     * {@code compareTo(ZERO) == 0} is true, guarding the division by zero (§31.2).
     */
    @Test
    @DisplayName("standardQuantity(): zero reference returns zero")
    void standardQuantityZeroReference() {
        Product product = new Product();
        product.productType = ProductType.VOLUME;
        product.referenceVolume = BigDecimal.ZERO;
        assertTrue(BigDecimal.ZERO.compareTo(product.standardQuantity(5.0)) == 0);
    }

    // --------------------------------------------------
    // findByEan()
    // --------------------------------------------------

    /**
     * The EAN finder delegates to the {@code ean} query and returns its first result when a product
     * matches.
     */
    @Test
    @DisplayName("findByEan(): returns the matching product")
    void findByEanFound() {
        Product found = new Product();
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("ean", "3245070000001")).thenReturn(query);
            assertSame(found, Product.findByEan("3245070000001"));
        }
    }

    /**
     * The EAN finder returns null when the EAN is unknown, feeding the warn-not-fail contract (§25.4).
     */
    @Test
    @DisplayName("findByEan(): returns null when unknown")
    void findByEanAbsent() {
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("ean", "0000000000000")).thenReturn(query);
            assertNull(Product.findByEan("0000000000000"));
        }
    }

    // --------------------------------------------------
    // findActiveByEan()
    // --------------------------------------------------

    /**
     * The active-EAN finder restricts the query to active products and returns its first result.
     */
    @Test
    @DisplayName("findActiveByEan(): returns the active product")
    void findActiveByEanFound() {
        Product found = new Product();
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("ean = ?1 and active = true", "3245070000001"))
                    .thenReturn(query);
            assertSame(found, Product.findActiveByEan("3245070000001"));
        }
    }

    // --------------------------------------------------
    // findByEans()
    // --------------------------------------------------

    /**
     * A null EAN collection short-circuits to an empty list: the first leg {@code eans == null} of the
     * guard is true, never touching the database (§29.6).
     */
    @Test
    @DisplayName("findByEans(): null collection returns an empty list")
    void findByEansNull() {
        assertEquals(List.of(), Product.findByEans(null));
    }

    /**
     * An empty EAN collection short-circuits to an empty list: the first leg is false, the second
     * {@code eans.isEmpty()} is true.
     */
    @Test
    @DisplayName("findByEans(): empty collection returns an empty list")
    void findByEansEmpty() {
        assertEquals(List.of(), Product.findByEans(List.of()));
    }

    /**
     * A populated EAN collection delegates to the {@code ean in ?1} query and returns its list: both
     * legs of the guard are false.
     */
    @Test
    @DisplayName("findByEans(): populated collection returns the matches")
    void findByEansPopulated() {
        List<String> eans = List.of("3245070000001", "3245070000002");
        List<Product> products = List.of(new Product(), new Product());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("ean in ?1", eans)).thenReturn(products);
            assertEquals(products, Product.findByEans(eans));
        }
    }

    // --------------------------------------------------
    // search()
    // --------------------------------------------------

    /**
     * A null query pages the EAN-ordered listing: the ternary {@code query == null} is true so the
     * term is empty and the {@code isEmpty()} branch is taken.
     */
    @Test
    @DisplayName("search(): null query pages the EAN-ordered listing")
    void searchNullQuery() {
        List<Product> products = List.of(new Product());
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.page(ArgumentMatchers.any(Page.class))).thenReturn(query);
        Mockito.when(query.list()).thenReturn(products);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("order by ean")).thenReturn(query);
            assertEquals(products, Product.search(null, 20));
        }
    }

    /**
     * A blank query pages the EAN-ordered listing: the ternary {@code query == null} is false so the
     * term is trimmed to empty, and the {@code isEmpty()} branch is taken.
     */
    @Test
    @DisplayName("search(): blank query pages the EAN-ordered listing")
    void searchBlankQuery() {
        List<Product> products = List.of(new Product());
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.page(ArgumentMatchers.any(Page.class))).thenReturn(query);
        Mockito.when(query.list()).thenReturn(products);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("order by ean")).thenReturn(query);
            assertEquals(products, Product.search("   ", 20));
        }
    }

    /**
     * A numeric query searches by EAN prefix: the term is non-empty and {@code allMatch(isDigit)} is
     * true, taking the {@code ean like} branch.
     */
    @Test
    @DisplayName("search(): numeric query searches by EAN prefix")
    void searchNumericQuery() {
        List<Product> products = List.of(new Product());
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.page(ArgumentMatchers.any(Page.class))).thenReturn(query);
        Mockito.when(query.list()).thenReturn(products);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("ean like ?1 order by ean", "324%"))
                    .thenReturn(query);
            assertEquals(products, Product.search("324", 20));
        }
    }

    /**
     * A textual query searches by name, brand or EAN fragment: the term is non-empty and
     * {@code allMatch(isDigit)} is false, taking the name/brand branch.
     */
    @Test
    @DisplayName("search(): textual query searches by name and brand")
    void searchTextualQuery() {
        List<Product> products = List.of(new Product());
        @SuppressWarnings("unchecked")
        PanacheQuery<Product> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.page(ArgumentMatchers.any(Page.class))).thenReturn(query);
        Mockito.when(query.list()).thenReturn(products);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "lower(name) like ?1 or lower(brand) like ?1 or ean like ?2 order by name",
                    "%milk%", "milk%")).thenReturn(query);
            assertEquals(products, Product.search("  MILK ", 20));
        }
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The checksum is a pure function of the business fields: two products with identical attributes
     * share it.
     */
    @Test
    @DisplayName("getChecksum(): identical business fields share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * Changing any business field — here the brand — changes the checksum, so any divergence between
     * the two references is detected (§25.4).
     */
    @Test
    @DisplayName("getChecksum(): a different brand alters the checksum")
    void checksumChangesWithBrand() {
        Product other = sample();
        other.brand = "Netto";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    // --------------------------------------------------
    // Field initializers
    // --------------------------------------------------

    /**
     * A freshly constructed product defaults to active.
     */
    @Test
    @DisplayName("new: active defaults to true")
    void activeDefaultsToTrue() {
        assertTrue(new Product().active);
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a fully-populated product with fixed business fields for checksum assertions.
     *
     * @return A sample product with deterministic attributes.
     */
    private Product sample() {
        Product product = new Product();
        product.ean = "3245070000001";
        product.name = "Whole milk 1L";
        product.description = "UHT whole milk";
        product.brand = "Paturages";
        product.referenceWeight = new BigDecimal("1.030");
        product.referenceVolume = new BigDecimal("1.000");
        product.productType = ProductType.VOLUME;
        product.unitName = "L";
        product.active = true;
        return product;
    }
}
