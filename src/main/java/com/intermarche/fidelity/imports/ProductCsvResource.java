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
 * The file format matches imvaluation's product file (9 pipe-delimited columns):
 * {@code ean|name|description|brand|referenceWeight|referenceVolume|productType|unitName|active}.
 * <p>
 * The {@code fid-admin} role guard (§24.1) is attached in the security build step,
 * consistent with the skeleton deferring authentication; the staged fallback and
 * checksum optimization come from {@link ImporterCsvResource}.
 */
@Path("/products/import")
@ApplicationScoped
@RunOnVirtualThread
public class ProductCsvResource extends ImporterCsvResource {

    /**
     * Number of columns expected in the product CSV.
     */
    private static final int COLUMNS = 9;

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
        return this.importCsvStream(inputStream, COLUMNS);
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
        String[] parts = data.parts;
        product.name = safeGet(parts, 1);
        product.description = safeGet(parts, 2);
        product.brand = safeGet(parts, 3);
        product.referenceWeight = safeParseBigDecimal(parts, 4);
        product.referenceVolume = safeParseBigDecimal(parts, 5);
        product.productType = safeParseEnum(ProductType.class, parts, 6);
        product.unitName = safeGet(parts, 7);
        product.active = safeParseBoolean(parts, 8);
    }

    /**
     * Computes the checksum of a row's incoming values, mirroring
     * {@link Product#getChecksum()} so an unchanged row is left untouched.
     *
     * @param data The row to hash.
     * @return The incoming checksum.
     */
    private int computeIncomingChecksum(LineData data) {
        String[] parts = data.parts;
        return Objects.hash(
                data.code,
                safeGet(parts, 1),
                safeGet(parts, 2),
                safeGet(parts, 3),
                safeParseBigDecimal(parts, 4),
                safeParseBigDecimal(parts, 5),
                safeParseEnum(ProductType.class, parts, 6),
                safeGet(parts, 7),
                safeParseBoolean(parts, 8)
        );
    }
}
