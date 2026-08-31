package com.intermarche.fidelity.imports;

import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.domain.ProductType;
import io.quarkus.hibernate.orm.panache.Panache;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bulk CSV import of the imfid {@link Product} reference (§15, §18).
 * <p>
 * imfid keeps its own product reference (EAN &rarr; brand, families) fed by the
 * very same CSV files as imvaluation — one source, two importers (this one and
 * {@link ProductFamilyCsvResource}, §18); the residual divergence between the two
 * references is absorbed by {@code UNKNOWN_EAN} at earn time (§25.4).
 * <p>
 * Consumed columns (resolved by header name; unknown columns of the shared
 * feed are ignored): EAN (key), NAME, DESCRIPTION, BRAND, REFERENCE_WEIGHT,
 * REFERENCE_VOLUME, PRODUCT_TYPE, UNIT_NAME, ACTIVE.
 * <p>
 * The {@code fid-admin} role guard (§24.1) is attached in the security build step,
 * consistent with the skeleton deferring authentication; the staged fallback and
 * checksum optimization come from {@link ImporterCsvResource}.
 */
@Path("/products/import")
@ApplicationScoped
@RunOnVirtualThread
public class ProductCsvResource extends ImporterCsvResource {

    /** Header name of the natural key: the product EAN. */
    static final String COL_EAN = "EAN";
    /** Header name of the product label. */
    static final String COL_NAME = "NAME";
    /** Header name of the product description. */
    static final String COL_DESCRIPTION = "DESCRIPTION";
    /** Header name of the brand. */
    static final String COL_BRAND = "BRAND";
    /** Header name of the reference weight. */
    static final String COL_REFERENCE_WEIGHT = "REFERENCE_WEIGHT";
    /** Header name of the reference volume. */
    static final String COL_REFERENCE_VOLUME = "REFERENCE_VOLUME";
    /** Header name of the product type enum. */
    static final String COL_PRODUCT_TYPE = "PRODUCT_TYPE";
    /** Header name of the unit label. */
    static final String COL_UNIT_NAME = "UNIT_NAME";
    /** Header name of the active flag. */
    static final String COL_ACTIVE = "ACTIVE";

    /** The columns this importer cannot work without. */
    private static final List<String> REQUIRED_COLUMNS = List.of(
            COL_NAME, COL_DESCRIPTION, COL_BRAND, COL_REFERENCE_WEIGHT,
            COL_REFERENCE_VOLUME, COL_PRODUCT_TYPE, COL_UNIT_NAME, COL_ACTIVE);

    /**
     * Imports or updates products from a CSV stream (§18).
     *
     * @param inputStream The product CSV stream.
     * @return The JSON import report (created/updated counts and errors).
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    public Response importProducts(InputStream inputStream) {
        return this.importCsvStream(inputStream, COL_EAN, REQUIRED_COLUMNS);
    }

    /**
     * Bulk-fetches the products already present for the chunk, keyed by EAN.
     *
     * @param parsedLines The rows of the current chunk.
     * @param targetEans  The distinct EANs present in the chunk.
     * @param counters    The global {@code [created, updated]} counters.
     * @param errors      The accumulating list of definitive row errors.
     * @return A map of EAN to existing {@link Product}, never null.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetEans, int[] counters, List<String> errors) {
        Map<String, Object> contextMap = new HashMap<>();
        if (!parsedLines.isEmpty() && !targetEans.isEmpty()) {
            for (Product product : Product.<Product>list("ean in ?1", targetEans)) {
                contextMap.put(product.ean, product);
            }
        }
        return contextMap;
    }

    /**
     * Creates a new product or updates an existing one, skipping unchanged rows
     * through the checksum optimization.
     *
     * @param data      The row to process.
     * @param entityMap A map of EAN to existing {@link Product}.
     * @param counters  The local {@code [created, updated]} counters to increment.
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        Product product = (Product) entityMap.get(data.code);
        if (product == null) {
            product = new Product();
            product.ean = data.code;
            feedProduct(data, product);
            counters[0]++;
            Panache.getEntityManager().persist(product);
        } else if (product.checksum == null || product.checksum != computeIncomingChecksum(data)) {
            product = Product.findById(product.id);
            feedProduct(data, product);
            counters[1]++;
        }
    }

    /**
     * Looks up a product by EAN for the 1-by-1 fallback.
     *
     * @param data The row to look up.
     * @return The {@link Product}, or null when absent.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return Product.findByEan(data.code);
    }

    /**
     * Populates a product's business fields from a parsed row.
     *
     * @param data    The row carrying the values.
     * @param product The product to populate.
     */
    private void feedProduct(LineData data, Product product) {
        product.name = safeGet(data, COL_NAME);
        product.description = safeGet(data, COL_DESCRIPTION);
        product.brand = safeGet(data, COL_BRAND);
        product.referenceWeight = safeParseBigDecimal(data, COL_REFERENCE_WEIGHT);
        product.referenceVolume = safeParseBigDecimal(data, COL_REFERENCE_VOLUME);
        product.productType = safeParseEnum(ProductType.class, data, COL_PRODUCT_TYPE);
        product.unitName = safeGet(data, COL_UNIT_NAME);
        product.active = safeParseBoolean(data, COL_ACTIVE);
    }

    /**
     * Computes the checksum of a row's incoming values, mirroring
     * {@link Product#getChecksum()} so an unchanged row is left untouched.
     *
     * @param data The row to hash.
     * @return The incoming checksum.
     */
    private int computeIncomingChecksum(LineData data) {
        return Objects.hash(
                data.code,
                safeGet(data, COL_NAME),
                safeGet(data, COL_DESCRIPTION),
                safeGet(data, COL_BRAND),
                safeParseBigDecimal(data, COL_REFERENCE_WEIGHT),
                safeParseBigDecimal(data, COL_REFERENCE_VOLUME),
                safeParseEnum(ProductType.class, data, COL_PRODUCT_TYPE),
                safeGet(data, COL_UNIT_NAME),
                safeParseBoolean(data, COL_ACTIVE)
        );
    }
}
