package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link ProductFamily}: the imfid family reference (§13, §15). The class
 * carries no clock read — every field is a stored attribute and no {@code DateTimeProvider} call is
 * made — so no time is injected here. The graph traversals and flag arithmetic are pure logic
 * exercised leg by leg on each compound guard (§29.6); the Panache active-record finders are mocked
 * through {@link PanacheEntityBase} in a try-with-resources per the imfid unit bench, and the
 * checksum is asserted as the pure function of the business fields it is.
 */
class ProductFamilyTest {

    /**
     * The JPQL selecting the families a product directly belongs to.
     */
    private static final String DIRECT_PARENTS_QUERY =
            "select pf from ProductFamily pf join pf.products p where p.id = ?1";

    /**
     * The JPQL selecting the parents of a given family.
     */
    private static final String ANCESTORS_QUERY =
            "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1";

    // --------------------------------------------------
    // findByCode()
    // --------------------------------------------------

    /**
     * The code finder delegates to the {@code code} query and returns its first result.
     */
    @Test
    @DisplayName("findByCode(): returns the matching family")
    void findByCodeFound() {
        ProductFamily found = new ProductFamily();
        @SuppressWarnings("unchecked")
        PanacheQuery<ProductFamily> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(found);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "FRUITS")).thenReturn(query);
            assertSame(found, ProductFamily.findByCode("FRUITS"));
        }
    }

    /**
     * The code finder returns null when no family matches.
     */
    @Test
    @DisplayName("findByCode(): returns null when unknown")
    void findByCodeAbsent() {
        @SuppressWarnings("unchecked")
        PanacheQuery<ProductFamily> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("code", "GHOST")).thenReturn(query);
            assertNull(ProductFamily.findByCode("GHOST"));
        }
    }

    // --------------------------------------------------
    // findAllFamiliesForProduct()
    // --------------------------------------------------

    /**
     * A null product yields an empty hierarchy: the first leg {@code product == null} of the guard is
     * true, no query is issued (§29.6).
     */
    @Test
    @DisplayName("findAllFamiliesForProduct(): null product returns empty")
    void findAllFamiliesNullProduct() {
        assertTrue(ProductFamily.findAllFamiliesForProduct(null).isEmpty());
    }

    /**
     * A transient product with a null id yields an empty hierarchy: the first leg is false, the second
     * {@code product.id == null} is true.
     */
    @Test
    @DisplayName("findAllFamiliesForProduct(): null id returns empty")
    void findAllFamiliesNullId() {
        Product product = new Product();
        product.id = null;
        assertTrue(ProductFamily.findAllFamiliesForProduct(product).isEmpty());
    }

    /**
     * A product with a persisted id walks the graph upwards: both guard legs are false, the direct
     * parent is collected and its own parent reached, exercising the recursive loop body.
     */
    @Test
    @DisplayName("findAllFamiliesForProduct(): collects direct parents and ancestors")
    void findAllFamiliesTwoLevels() {
        Product product = new Product();
        product.id = 10L;
        ProductFamily familyA = new ProductFamily();
        familyA.id = 1L;
        ProductFamily familyB = new ProductFamily();
        familyB.id = 2L;
        @SuppressWarnings("unchecked")
        PanacheQuery<ProductFamily> directQuery = Mockito.mock(PanacheQuery.class);
        Mockito.when(directQuery.list()).thenReturn(List.of(familyA));
        @SuppressWarnings("unchecked")
        PanacheQuery<ProductFamily> parentsOfA = Mockito.mock(PanacheQuery.class);
        Mockito.when(parentsOfA.list()).thenReturn(List.of(familyB));
        @SuppressWarnings("unchecked")
        PanacheQuery<ProductFamily> parentsOfB = Mockito.mock(PanacheQuery.class);
        Mockito.when(parentsOfB.list()).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(DIRECT_PARENTS_QUERY, 10L)).thenReturn(directQuery);
            panache.when(() -> PanacheEntityBase.find(ANCESTORS_QUERY, 1L)).thenReturn(parentsOfA);
            panache.when(() -> PanacheEntityBase.find(ANCESTORS_QUERY, 2L)).thenReturn(parentsOfB);
            Set<ProductFamily> hierarchy = ProductFamily.findAllFamiliesForProduct(product);
            assertEquals(2, hierarchy.size());
            assertTrue(hierarchy.contains(familyA));
            assertTrue(hierarchy.contains(familyB));
        }
    }

    // --------------------------------------------------
    // findAncestorsRecursive()
    // --------------------------------------------------

    /**
     * A family already accumulated short-circuits the recursion: the cycle guard
     * {@code ancestors.contains(family)} is true, so no query runs and the set is untouched.
     */
    @Test
    @DisplayName("findAncestorsRecursive(): already-seen family is skipped")
    void findAncestorsAlreadySeen() {
        ProductFamily family = new ProductFamily();
        family.id = 5L;
        Set<ProductFamily> ancestors = new HashSet<>();
        ancestors.add(family);
        ProductFamily.findAncestorsRecursive(family, ancestors);
        assertEquals(1, ancestors.size());
    }

    /**
     * A fresh family is added and its parents queried: the cycle guard is false, the family is
     * accumulated and the empty parent list ends the recursion.
     */
    @Test
    @DisplayName("findAncestorsRecursive(): fresh family is added and its parents queried")
    void findAncestorsFresh() {
        ProductFamily family = new ProductFamily();
        family.id = 7L;
        @SuppressWarnings("unchecked")
        PanacheQuery<ProductFamily> parents = Mockito.mock(PanacheQuery.class);
        Mockito.when(parents.list()).thenReturn(List.of());
        Set<ProductFamily> ancestors = new HashSet<>();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(ANCESTORS_QUERY, 7L)).thenReturn(parents);
            ProductFamily.findAncestorsRecursive(family, ancestors);
            assertEquals(1, ancestors.size());
            assertTrue(ancestors.contains(family));
        }
    }

    // --------------------------------------------------
    // productHasFlag()
    // --------------------------------------------------

    /**
     * A null product carries no flag: the first leg {@code product == null} of the guard is true.
     */
    @Test
    @DisplayName("productHasFlag(): null product returns false")
    void productHasFlagNullProduct() {
        assertFalse(ProductFamily.productHasFlag(null, "ORGANIC"));
    }

    /**
     * A null flag is never present: the first leg is false, the second {@code flag == null} is true.
     */
    @Test
    @DisplayName("productHasFlag(): null flag returns false")
    void productHasFlagNullFlag() {
        Product product = new Product();
        product.id = 10L;
        assertFalse(ProductFamily.productHasFlag(product, null));
    }

    /**
     * A blank flag is never present: the first two legs are false, the third {@code flag.isBlank()} is
     * true.
     */
    @Test
    @DisplayName("productHasFlag(): blank flag returns false")
    void productHasFlagBlankFlag() {
        Product product = new Product();
        product.id = 10L;
        assertFalse(ProductFamily.productHasFlag(product, "   "));
    }

    /**
     * The flag is found in the product's hierarchy: all guard legs are false and a family in the
     * hierarchy carries the token, so the loop returns true.
     */
    @Test
    @DisplayName("productHasFlag(): flag present in hierarchy returns true")
    void productHasFlagFound() {
        Product product = new Product();
        product.id = 10L;
        ProductFamily family = new ProductFamily();
        family.id = 1L;
        family.flags = "ORGANIC,SEASONAL";
        assertTrue(withHierarchy(product, family, () -> ProductFamily.productHasFlag(product, "ORGANIC")));
    }

    /**
     * The flag is absent from the product's hierarchy: the family carries other tokens, so the loop
     * exhausts and returns false.
     */
    @Test
    @DisplayName("productHasFlag(): flag absent from hierarchy returns false")
    void productHasFlagNotFound() {
        Product product = new Product();
        product.id = 10L;
        ProductFamily family = new ProductFamily();
        family.id = 1L;
        family.flags = "SEASONAL";
        assertFalse(withHierarchy(product, family, () -> ProductFamily.productHasFlag(product, "ORGANIC")));
    }

    // --------------------------------------------------
    // searchFlags()
    // --------------------------------------------------

    /**
     * A null query returns every distinct token sorted: the ternary {@code query == null} is true so
     * the term is empty and every non-empty token is kept; the empty segment of {@code "FRUIT,,VEG"} is
     * dropped.
     */
    @Test
    @DisplayName("searchFlags(): null query returns all distinct tokens sorted")
    void searchFlagsNullQuery() {
        ProductFamily first = new ProductFamily();
        first.flags = "ORGANIC,SEASONAL";
        ProductFamily second = new ProductFamily();
        second.flags = "FRUIT,,VEG";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("flags is not null")).thenReturn(List.of(first, second));
            assertEquals(List.of("FRUIT", "ORGANIC", "SEASONAL", "VEG"), ProductFamily.searchFlags(null, 10));
        }
    }

    /**
     * A non-null query filters by fragment: the ternary is false so the term is trimmed and lowered;
     * {@code "ru"} matches {@code FRUIT} (contains true) and rejects the others (contains false).
     */
    @Test
    @DisplayName("searchFlags(): fragment query filters by containment")
    void searchFlagsFragmentQuery() {
        ProductFamily family = new ProductFamily();
        family.flags = "FRUIT,ORGANIC,SEASONAL";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("flags is not null")).thenReturn(List.of(family));
            assertEquals(List.of("FRUIT"), ProductFamily.searchFlags(" RU ", 10));
        }
    }

    /**
     * The limit truncates the sorted result: four distinct tokens are capped to the first two.
     */
    @Test
    @DisplayName("searchFlags(): limit truncates the sorted result")
    void searchFlagsLimit() {
        ProductFamily family = new ProductFamily();
        family.flags = "DELTA,ALPHA,CHARLIE,BRAVO";
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("flags is not null")).thenReturn(List.of(family));
            assertEquals(List.of("ALPHA", "BRAVO"), ProductFamily.searchFlags(null, 2));
        }
    }

    // --------------------------------------------------
    // addFlag()
    // --------------------------------------------------

    /**
     * A null token is ignored: the first leg {@code token == null} of the guard is true and the flags
     * stay untouched.
     */
    @Test
    @DisplayName("addFlag(): null token is ignored")
    void addFlagNull() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";
        family.addFlag(null);
        assertEquals("ORGANIC", family.flags);
    }

    /**
     * A blank token is ignored: the first leg is false, the second {@code token.isBlank()} is true.
     */
    @Test
    @DisplayName("addFlag(): blank token is ignored")
    void addFlagBlank() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";
        family.addFlag("   ");
        assertEquals("ORGANIC", family.flags);
    }

    /**
     * A new token on an empty family is trimmed and stored: both guard legs are false, the flags field
     * is null so {@code getFlagsSet()} takes its null branch, and {@code add} returns true.
     */
    @Test
    @DisplayName("addFlag(): new token on empty family is stored")
    void addFlagNewOnEmpty() {
        ProductFamily family = new ProductFamily();
        family.flags = null;
        family.addFlag("  ORGANIC  ");
        assertEquals("ORGANIC", family.flags);
    }

    /**
     * An already-present token leaves the flags unchanged: {@code add} returns false so no
     * re-serialization occurs.
     */
    @Test
    @DisplayName("addFlag(): existing token leaves flags unchanged")
    void addFlagExisting() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";
        family.addFlag("ORGANIC");
        assertEquals("ORGANIC", family.flags);
    }

    // --------------------------------------------------
    // removeFlag()
    // --------------------------------------------------

    /**
     * A null token removes nothing: the guard {@code token == null} is true and false is returned.
     */
    @Test
    @DisplayName("removeFlag(): null token returns false")
    void removeFlagNull() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";
        assertFalse(family.removeFlag(null));
        assertEquals("ORGANIC", family.flags);
    }

    /**
     * Removing a present token among several re-serializes the remainder: {@code remove} is true and
     * the set is non-empty, so the else branch updates the flags.
     */
    @Test
    @DisplayName("removeFlag(): present token among several updates the flags")
    void removeFlagPresentNotLast() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC,SEASONAL";
        assertTrue(family.removeFlag("ORGANIC"));
        assertEquals("SEASONAL", family.flags);
    }

    /**
     * Removing the last token clears the field: {@code remove} is true and the set becomes empty, so
     * the flags are nulled.
     */
    @Test
    @DisplayName("removeFlag(): last token clears the field")
    void removeFlagLast() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";
        assertTrue(family.removeFlag("ORGANIC"));
        assertNull(family.flags);
    }

    /**
     * Removing an absent token returns false: {@code remove} is false and the flags are untouched.
     */
    @Test
    @DisplayName("removeFlag(): absent token returns false")
    void removeFlagAbsent() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";
        assertFalse(family.removeFlag("SEASONAL"));
        assertEquals("ORGANIC", family.flags);
    }

    /**
     * Removing from blank flags returns false: {@code getFlagsSet()} takes its blank branch and yields
     * an empty set, so {@code remove} is false.
     */
    @Test
    @DisplayName("removeFlag(): blank flags returns false")
    void removeFlagBlankFlags() {
        ProductFamily family = new ProductFamily();
        family.flags = "   ";
        assertFalse(family.removeFlag("ORGANIC"));
    }

    // --------------------------------------------------
    // hasFlag()
    // --------------------------------------------------

    /**
     * A null token is never present: the first leg {@code token == null} of the guard is true.
     */
    @Test
    @DisplayName("hasFlag(): null token returns false")
    void hasFlagNullToken() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";
        assertFalse(family.hasFlag(null));
    }

    /**
     * A blank token is never present: the first leg is false, the second {@code token.isBlank()} is
     * true.
     */
    @Test
    @DisplayName("hasFlag(): blank token returns false")
    void hasFlagBlankToken() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC";
        assertFalse(family.hasFlag("   "));
    }

    /**
     * A family with null flags carries nothing: the first two legs are false, the third
     * {@code this.flags == null} is true.
     */
    @Test
    @DisplayName("hasFlag(): null flags returns false")
    void hasFlagNullFlags() {
        ProductFamily family = new ProductFamily();
        family.flags = null;
        assertFalse(family.hasFlag("ORGANIC"));
    }

    /**
     * A family with blank flags carries nothing: the first three legs are false, the fourth
     * {@code this.flags.isBlank()} is true.
     */
    @Test
    @DisplayName("hasFlag(): blank flags returns false")
    void hasFlagBlankFlags() {
        ProductFamily family = new ProductFamily();
        family.flags = "   ";
        assertFalse(family.hasFlag("ORGANIC"));
    }

    /**
     * A present token, whitespace ignored, is found: all guard legs are false, the empty segment of
     * {@code "ORGANIC,,SEASONAL"} is dropped by {@code getFlagsSet()} and the trimmed token matches.
     */
    @Test
    @DisplayName("hasFlag(): present token is found")
    void hasFlagPresent() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC,,SEASONAL";
        assertTrue(family.hasFlag(" ORGANIC "));
    }

    /**
     * An absent token is not found: all guard legs are false but the parsed set does not contain it.
     */
    @Test
    @DisplayName("hasFlag(): absent token is not found")
    void hasFlagAbsent() {
        ProductFamily family = new ProductFamily();
        family.flags = "ORGANIC,SEASONAL";
        assertFalse(family.hasFlag("FRUIT"));
    }

    // --------------------------------------------------
    // getChecksum()
    // --------------------------------------------------

    /**
     * The checksum is a pure function of the business fields: two families with identical attributes
     * share it.
     */
    @Test
    @DisplayName("getChecksum(): identical business fields share a checksum")
    void checksumStableForEqualFields() {
        assertEquals(sample().getChecksum(), sample().getChecksum());
    }

    /**
     * Changing any business field — here the description — changes the checksum, so any divergence is
     * detected.
     */
    @Test
    @DisplayName("getChecksum(): a different description alters the checksum")
    void checksumChangesWithDescription() {
        ProductFamily other = sample();
        other.description = "Fresh produce";
        assertNotEquals(sample().getChecksum(), other.getChecksum());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Runs a supplier under a Panache mock that resolves the product's hierarchy to the single given
     * family with no further ancestors.
     *
     * @param product The product whose direct parents resolve to the family.
     * @param family  The single direct parent family, itself parentless.
     * @param body    The assertion body to evaluate under the mock.
     * @return The boolean result of the body.
     */
    private boolean withHierarchy(Product product, ProductFamily family, java.util.function.BooleanSupplier body) {
        @SuppressWarnings("unchecked")
        PanacheQuery<ProductFamily> directQuery = Mockito.mock(PanacheQuery.class);
        Mockito.when(directQuery.list()).thenReturn(List.of(family));
        @SuppressWarnings("unchecked")
        PanacheQuery<ProductFamily> parentsQuery = Mockito.mock(PanacheQuery.class);
        Mockito.when(parentsQuery.list()).thenReturn(List.of());
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(DIRECT_PARENTS_QUERY, product.id)).thenReturn(directQuery);
            panache.when(() -> PanacheEntityBase.find(ANCESTORS_QUERY, family.id)).thenReturn(parentsQuery);
            return body.getAsBoolean();
        }
    }

    /**
     * Builds a fully-populated family with fixed business fields for checksum assertions.
     *
     * @return A sample family with deterministic attributes.
     */
    private ProductFamily sample() {
        ProductFamily family = new ProductFamily();
        family.code = "FRUITS";
        family.description = "Fruits and vegetables";
        family.flags = "ORGANIC,SEASONAL";
        return family;
    }
}
