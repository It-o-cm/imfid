package com.intermarche.fidelity.domain;

import io.quarkus.panache.common.Page;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A product of the imfid reference (§15), aligned with imvaluation's
 * {@code Product}.
 * <p>
 * imfid keeps its own product reference (EAN &rarr; brand, families), imported by
 * the same CSV mechanisms as imvaluation, to resolve earn bases; the two
 * references are imported separately and may diverge — an EAN unknown here never
 * fails {@code /earn}, the line simply stays out of every base and is returned as
 * a warning (§25.4).
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "products",
        indexes = {
                @Index(name = "idx_product_ean", columnList = "ean"),
                @Index(name = "idx_product_brand", columnList = "brand")
        }
)
@Cacheable
public class Product extends BaseEntity {

    // --------------------------------------------------
    // Identification
    // --------------------------------------------------

    /**
     * The EAN (barcode) — the primary lookup key when resolving a valued line.
     */
    @Column(name = "ean", unique = true, nullable = false, length = 13)
    @NotBlank(message = "EAN is mandatory")
    public String ean;

    // --------------------------------------------------
    // Product Details
    // --------------------------------------------------

    /**
     * The product name.
     */
    @Column(nullable = false)
    @NotBlank(message = "Product name is mandatory")
    public String name;

    /**
     * A free-text description of the product.
     */
    @Column(length = 255)
    public String description;

    /**
     * The brand of the product — a first-class scope of the earn bases (brand
     * sets, §13); indexed for base resolution.
     */
    @Column(name = "brand", length = 100)
    public String brand;

    // --------------------------------------------------
    // Dimensions & Weight
    // --------------------------------------------------

    /**
     * Reference weight in kilograms (gram precision); null when not applicable.
     */
    @Column(name = "reference_weight", precision = 10, scale = 3)
    public BigDecimal referenceWeight;

    /**
     * Reference volume in liters (milliliter precision); null when not applicable.
     */
    @Column(name = "reference_volume", precision = 10, scale = 3)
    public BigDecimal referenceVolume;

    /**
     * How the product is quantified and sold (unit, weight, volume).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @NotNull(message = "Product type is mandatory")
    public ProductType productType;

    /**
     * Display unit for the product (e.g. "kg", "pcs", "L").
     */
    @Column(name = "unit_name", length = 20)
    public String unitName;

    // --------------------------------------------------
    // Status
    // --------------------------------------------------

    /**
     * Whether the product definition is globally active.
     */
    @Column(name = "is_active", nullable = false)
    public boolean active = true;

    // --------------------------------------------------
    // Domain helpers
    // --------------------------------------------------

    /**
     * Converts a quantity into standard units based on the product type.
     * <p>
     * Unit products return the quantity as is; weight and volume products divide by
     * the reference weight or volume, with a protected division that returns zero
     * on a null or zero reference (§31.2).
     *
     * @param quantity The quantity to convert.
     * @return The quantity expressed in standard units.
     */
    public BigDecimal standardQuantity(double quantity) {
        if (this.productType == ProductType.UNIT) {
            return BigDecimal.valueOf(quantity);
        }
        BigDecimal reference = this.productType == ProductType.VOLUME ? this.referenceVolume : this.referenceWeight;
        if (reference == null || reference.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(quantity).divide(reference, 6, RoundingMode.HALF_UP);
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a product by its unique EAN code.
     *
     * @param ean The EAN code.
     * @return The product, or null if none matches.
     */
    public static Product findByEan(String ean) {
        return find("ean", ean).firstResult();
    }

    /**
     * Finds a product by EAN, restricted to globally active products.
     *
     * @param ean The EAN code.
     * @return The active product, or null.
     */
    public static Product findActiveByEan(String ean) {
        return find("ean = ?1 and active = true", ean).firstResult();
    }

    /**
     * Finds every product whose EAN belongs to the given collection.
     *
     * @param eans The EANs to resolve.
     * @return The matching products, never null.
     */
    public static List<Product> findByEans(Collection<String> eans) {
        if (eans == null || eans.isEmpty()) {
            return List.of();
        }
        return list("ean in ?1", eans);
    }

    /**
     * Searches products by EAN prefix (numeric query) or by name and brand
     * fragment; an empty query returns the first products by EAN.
     *
     * @param query The raw search term, may be null or blank.
     * @param limit The maximum number of products to return.
     * @return The matching products, never null.
     */
    public static List<Product> search(String query, int limit) {
        String term = query == null ? "" : query.trim().toLowerCase();
        if (term.isEmpty()) {
            return find("order by ean").page(Page.ofSize(limit)).list();
        }
        if (term.chars().allMatch(Character::isDigit)) {
            return find("ean like ?1 order by ean", term + "%").page(Page.ofSize(limit)).list();
        }
        return find("lower(name) like ?1 or lower(brand) like ?1 or ean like ?2 order by name",
                "%" + term + "%", term + "%").page(Page.ofSize(limit)).list();
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum from the product's key attributes.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(ean, name, description, brand, referenceWeight, referenceVolume, productType, unitName, active);
    }
}
