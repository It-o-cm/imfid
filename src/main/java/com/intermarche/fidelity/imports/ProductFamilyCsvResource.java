package com.intermarche.fidelity.imports;

import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.domain.ProductFamily;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bulk CSV import of the imfid {@link ProductFamily} reference (§13, §15, §18).
 * <p>
 * Second of the two importers reading imvaluation's product files — one source,
 * two importers (§18). It creates families and reconciles their two relations:
 * member products (by EAN) and sub-families (by code).
 * <p>
 * The file format matches imvaluation's family file (5 pipe-delimited columns):
 * {@code code|description|flags|product_eans|family_codes}, where
 * {@code product_eans} and {@code family_codes} are comma-separated lists.
 * <p>
 * A missing product or sub-family, or a self-reference, raises an exception that
 * rolls the chunk back and triggers the staged fallback so the offending row is
 * isolated (§18). The {@code fid-admin} role guard (§24.1) is attached in the
 * security build step.
 */
@Path("/product-families/import")
@ApplicationScoped
@RunOnVirtualThread
public class ProductFamilyCsvResource extends ImporterCsvResource {

    /**
     * Number of columns expected in the family CSV.
     */
    private static final int COLUMNS = 5;

    /**
     * Context key holding the pre-fetched products map (EAN &rarr; Product).
     */
    private static final String CTX_PRODUCTS = "__CTX_PRODUCTS__";

    /**
     * Context key holding the pre-fetched sub-families map (code &rarr; Family).
     */
    private static final String CTX_SUB_FAMILIES = "__CTX_SUB_FAMILIES__";

    /**
     * Imports or updates product families from a CSV stream (§18).
     *
     * @param inputStream The family CSV stream.
     * @return The JSON import report (created/updated counts and errors).
     */
    @POST
    @Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_OCTET_STREAM})
    @Produces(MediaType.APPLICATION_JSON)
    public Response importProductFamilies(InputStream inputStream) {
        return this.importCsvStream(inputStream, COLUMNS);
    }

    /**
     * Bulk-fetches the families of the chunk plus every referenced product and
     * sub-family, stored under {@link #CTX_PRODUCTS} and {@link #CTX_SUB_FAMILIES}.
     *
     * @param parsedLines The rows of the current chunk.
     * @param targetCodes The distinct family codes present in the chunk.
     * @param counters    The global {@code [created, updated]} counters.
     * @param errors      The accumulating list of definitive row errors.
     * @return The context map bundling families, products and sub-families.
     */
    @Override
    protected Map<String, Object> processChunkWithFallback(List<LineData> parsedLines, Set<String> targetCodes, int[] counters, List<String> errors) {
        Map<String, Object> contextMap = new HashMap<>();
        if (parsedLines.isEmpty()) {
            return contextMap;
        }
        for (ProductFamily family : ProductFamily.<ProductFamily>list("code in ?1", targetCodes)) {
            contextMap.put(family.code, family);
        }
        contextMap.put(CTX_PRODUCTS, fetchProducts(collectEans(parsedLines)));
        contextMap.put(CTX_SUB_FAMILIES, fetchSubFamilies(collectSubFamilyCodes(parsedLines)));
        return contextMap;
    }

    /**
     * Collects the distinct product EANs referenced across the chunk.
     *
     * @param parsedLines The rows of the chunk.
     * @return The set of referenced EANs, never null.
     */
    private Set<String> collectEans(List<LineData> parsedLines) {
        Set<String> eans = new HashSet<>();
        for (LineData data : parsedLines) {
            eans.addAll(parseCodes(safeGet(data.parts, 3)));
        }
        return eans;
    }

    /**
     * Collects the distinct sub-family codes referenced across the chunk.
     *
     * @param parsedLines The rows of the chunk.
     * @return The set of referenced sub-family codes, never null.
     */
    private Set<String> collectSubFamilyCodes(List<LineData> parsedLines) {
        Set<String> codes = new HashSet<>();
        for (LineData data : parsedLines) {
            codes.addAll(parseCodes(safeGet(data.parts, 4)));
        }
        return codes;
    }

    /**
     * Bulk-fetches products by EAN into a map.
     *
     * @param eans The EANs to fetch.
     * @return A map of EAN to {@link Product}, never null.
     */
    private static Map<String, Product> fetchProducts(Set<String> eans) {
        Map<String, Product> productMap = new HashMap<>();
        if (!eans.isEmpty()) {
            for (Product product : Product.<Product>list("ean in ?1", eans)) {
                productMap.put(product.ean, product);
            }
        }
        return productMap;
    }

    /**
     * Bulk-fetches sub-families by code into a map.
     *
     * @param codes The sub-family codes to fetch.
     * @return A map of code to {@link ProductFamily}, never null.
     */
    private static Map<String, ProductFamily> fetchSubFamilies(Set<String> codes) {
        Map<String, ProductFamily> subFamilyMap = new HashMap<>();
        if (!codes.isEmpty()) {
            for (ProductFamily sub : ProductFamily.<ProductFamily>list("code in ?1", codes)) {
                subFamilyMap.put(sub.code, sub);
            }
        }
        return subFamilyMap;
    }

    /**
     * Creates a new family or updates an existing one, always reconciling its
     * product and sub-family links; the updated counter reflects a checksum change.
     *
     * @param data      The row to process.
     * @param entityMap The context map bundling families, products and sub-families.
     * @param counters  The local {@code [created, updated]} counters to increment.
     */
    @Override
    protected void processLineLogic(LineData data, Map<String, Object> entityMap, int[] counters) {
        List<String> requestedEans = parseCodes(safeGet(data.parts, 3));
        List<String> requestedSubCodes = parseCodes(safeGet(data.parts, 4));
        Map<String, Product> productMap = retrieveProducts(entityMap, requestedEans);
        Map<String, ProductFamily> subFamilyMap = retrieveSubFamilies(entityMap, requestedSubCodes);
        ProductFamily family = (ProductFamily) entityMap.get(data.code);
        if (family == null) {
            family = new ProductFamily();
            family.code = data.code;
            feedFamily(data, family, requestedEans, productMap, requestedSubCodes, subFamilyMap);
            counters[0]++;
            Panache.getEntityManager().persist(family);
        } else {
            boolean changed = family.checksum == null || family.checksum != computeIncomingChecksum(data);
            family = ProductFamily.findById(family.id);
            feedFamily(data, family, requestedEans, productMap, requestedSubCodes, subFamilyMap);
            if (changed) {
                counters[1]++;
            }
        }
    }

    /**
     * Looks up a family by code for the 1-by-1 fallback.
     *
     * @param data The row to look up.
     * @return The {@link ProductFamily}, or null when absent.
     */
    @Override
    protected Object findEntityForLine(LineData data) {
        return ProductFamily.findByCode(data.code);
    }

    /**
     * Populates a family's fields and reconciles its product and sub-family links.
     *
     * @param data              The row carrying the values.
     * @param family            The family to populate.
     * @param requestedEans     The product EANs the family must contain.
     * @param productMap        The available products by EAN.
     * @param requestedSubCodes The sub-family codes the family must contain.
     * @param subFamilyMap      The available sub-families by code.
     */
    private void feedFamily(LineData data, ProductFamily family, List<String> requestedEans, Map<String, Product> productMap,
                            List<String> requestedSubCodes, Map<String, ProductFamily> subFamilyMap) {
        family.description = safeGet(data.parts, 1);
        family.flags = safeGet(data.parts, 2);
        linkProducts(family, requestedEans, productMap);
        linkSubFamilies(family, requestedSubCodes, subFamilyMap);
    }

    /**
     * Clears and re-links the family's member products.
     *
     * @param family        The family to link.
     * @param requestedEans The product EANs to link.
     * @param productMap    The available products by EAN.
     * @throws IllegalArgumentException when a referenced EAN is unknown.
     */
    private static void linkProducts(ProductFamily family, List<String> requestedEans, Map<String, Product> productMap) {
        family.products.clear();
        for (String ean : requestedEans) {
            Product product = productMap.get(ean);
            if (product == null) {
                throw new IllegalArgumentException("Product EAN '" + ean + "' not found.");
            }
            family.products.add(product);
        }
    }

    /**
     * Clears and re-links the family's sub-families, rejecting self-references.
     *
     * @param family            The family to link.
     * @param requestedSubCodes The sub-family codes to link.
     * @param subFamilyMap      The available sub-families by code.
     * @throws IllegalArgumentException when a code is unknown or equal to the family's own.
     */
    private static void linkSubFamilies(ProductFamily family, List<String> requestedSubCodes, Map<String, ProductFamily> subFamilyMap) {
        family.productFamilies.clear();
        for (String subCode : requestedSubCodes) {
            if (subCode.equals(family.code)) {
                throw new IllegalArgumentException("Family '" + subCode + "' cannot contain itself.");
            }
            ProductFamily sub = subFamilyMap.get(subCode);
            if (sub == null) {
                throw new IllegalArgumentException("SubFamily code '" + subCode + "' not found.");
            }
            family.productFamilies.add(sub);
        }
    }

    /**
     * Retrieves the products map from the context, or looks it up freshly in the
     * 1-by-1 fallback where the context is absent.
     *
     * @param entityMap     The context map.
     * @param requestedEans The EANs required by the current row.
     * @return A map of EAN to {@link Product}, never null.
     */
    private static Map<String, Product> retrieveProducts(Map<String, Object> entityMap, List<String> requestedEans) {
        @SuppressWarnings("unchecked")
        Map<String, Product> productMap = (Map<String, Product>) entityMap.get(CTX_PRODUCTS);
        if (productMap == null) {
            productMap = fetchProducts(new HashSet<>(requestedEans));
        }
        return productMap;
    }

    /**
     * Retrieves the sub-families map from the context, or looks it up freshly in
     * the 1-by-1 fallback where the context is absent.
     *
     * @param entityMap         The context map.
     * @param requestedSubCodes The sub-family codes required by the current row.
     * @return A map of code to {@link ProductFamily}, never null.
     */
    private static Map<String, ProductFamily> retrieveSubFamilies(Map<String, Object> entityMap, List<String> requestedSubCodes) {
        @SuppressWarnings("unchecked")
        Map<String, ProductFamily> subFamilyMap = (Map<String, ProductFamily>) entityMap.get(CTX_SUB_FAMILIES);
        if (subFamilyMap == null) {
            subFamilyMap = fetchSubFamilies(new HashSet<>(requestedSubCodes));
        }
        return subFamilyMap;
    }

    /**
     * Computes the checksum of a row's incoming values, mirroring
     * {@link ProductFamily#getChecksum()} (children excluded).
     *
     * @param data The row to hash.
     * @return The incoming checksum.
     */
    private int computeIncomingChecksum(LineData data) {
        return Objects.hash(
                data.code,
                safeGet(data.parts, 1),
                safeGet(data.parts, 2)
        );
    }
}
