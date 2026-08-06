package com.intermarche.fidelity.domain;

/**
 * How a product is quantified and sold, aligned with the imvaluation reference.
 * <p>
 * imfid keeps its own product reference (EAN &rarr; brand, families) to resolve
 * earn baskets (§15); the type distinguishes unit, weighted and volume items so
 * quantities read the same way as in the valuation output.
 */
public enum ProductType {

    /**
     * Sold by a single unit (e.g. a cereal box): the scan adds one item.
     */
    UNIT,

    /**
     * Sold by weight (e.g. fruit, vegetables, deli meat).
     */
    WEIGHT,

    /**
     * Sold by volume (e.g. bulk liquids, fuel).
     */
    VOLUME
}
