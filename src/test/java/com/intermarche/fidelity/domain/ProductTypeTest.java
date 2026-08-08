package com.intermarche.fidelity.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link ProductType}: how a product is quantified and sold, aligned
 * with the imvaluation reference so earn baskets read quantities the same way (§15). The type
 * is a branchless enum — adding a quantification mode is a document revision, so the contract
 * under test is the exact set, order and naming of the three constants plus the synthetic
 * {@code values()}/{@code valueOf(String)} round-trip. Pure logic, no Panache and no clock:
 * nothing here reads a {@code DateTimeProvider}.
 */
class ProductTypeTest {

    /**
     * The quantification nomenclature is closed and ordered exactly as declared (§15): the
     * three types appear in declaration order, so both {@code values()} and each ordinal are
     * pinned.
     */
    @Test
    @DisplayName("values(): closed set of three types in the specified declaration order")
    void valuesHoldsTheClosedOrderedNomenclature() {
        ProductType[] expected = {
                ProductType.UNIT,
                ProductType.WEIGHT,
                ProductType.VOLUME
        };
        assertArrayEquals(expected, ProductType.values());
        assertEquals(3, ProductType.values().length);
    }

    /**
     * {@code values()} returns a fresh defensive array on each call: mutating one copy does
     * not leak into the enum's internal constant table (§31.2 defensive-copy discipline).
     */
    @Test
    @DisplayName("values(): each call yields a fresh array, mutation does not leak")
    void valuesReturnsFreshDefensiveArray() {
        ProductType[] first = ProductType.values();
        first[0] = ProductType.VOLUME;
        assertSame(ProductType.UNIT, ProductType.values()[0]);
    }

    /**
     * Each constant's {@code name()} matches its declared identifier, pinning the spelling
     * that lets earn baskets align with the imvaluation valuation output (§15).
     */
    @Test
    @DisplayName("name(): every constant carries its specified spelling")
    void nameMatchesTheSpecifiedSpelling() {
        assertEquals("UNIT", ProductType.UNIT.name());
        assertEquals("WEIGHT", ProductType.WEIGHT.name());
        assertEquals("VOLUME", ProductType.VOLUME.name());
    }

    /**
     * Each constant sits at its specified ordinal, fixing the declaration order the closed
     * nomenclature relies on — {@code UNIT} first, the default single-scan quantification.
     */
    @Test
    @DisplayName("ordinal(): every constant sits at its specified position")
    void ordinalMatchesDeclarationOrder() {
        assertEquals(0, ProductType.UNIT.ordinal());
        assertEquals(1, ProductType.WEIGHT.ordinal());
        assertEquals(2, ProductType.VOLUME.ordinal());
    }

    /**
     * {@code valueOf(String)} round-trips every declared name back to the same singleton
     * constant, confirming the synthetic parser resolves the whole nomenclature.
     */
    @Test
    @DisplayName("valueOf(): every declared name round-trips to its singleton constant")
    void valueOfRoundTripsEveryConstant() {
        for (ProductType type : ProductType.values()) {
            ProductType resolved = ProductType.valueOf(type.name());
            assertNotNull(resolved);
            assertSame(type, resolved);
        }
    }

    /**
     * {@code valueOf(String)} rejects a name outside the closed nomenclature: an unknown
     * type is not silently coerced but raises {@link IllegalArgumentException} (§15).
     */
    @Test
    @DisplayName("valueOf(): a name outside the nomenclature throws")
    void valueOfRejectsUnknownName() {
        assertThrows(IllegalArgumentException.class, () -> ProductType.valueOf("LENGTH"));
    }

    /**
     * {@code valueOf(String)} rejects a null name with {@link NullPointerException}, covering
     * the null arm of the synthetic parser's argument guard.
     */
    @Test
    @DisplayName("valueOf(): a null name throws NullPointerException")
    void valueOfRejectsNullName() {
        assertThrows(NullPointerException.class, () -> ProductType.valueOf(null));
    }
}
