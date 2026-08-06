package com.intermarche.fidelity.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A logical grouping of products (§13, §15), aligned with imvaluation's
 * {@code ProductFamily}.
 * <p>
 * A family can contain products (leaves) and sub-families, forming a directed
 * acyclic graph: a product or a family may belong to several parents. Families
 * are a first-class scope of the earn bases (family sets, includes/excludes,
 * §13); the flag tokens support the cross-cutting exclusions carried in rule
 * specifications, none of them hard-coded (§13).
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "product_families",
        uniqueConstraints = @UniqueConstraint(columnNames = "code")
)
@Cacheable
public class ProductFamily extends BaseEntity {

    /**
     * The unique business code of the family.
     */
    @Column(name = "code", nullable = false, length = 50, unique = true)
    @NotBlank(message = "Family code is mandatory")
    public String code;

    /**
     * A free-text description of the family.
     */
    @Column(name = "description", length = 255)
    public String description;

    /**
     * A comma-separated set of flag tokens (e.g. "ORGANIC,SEASONAL,FRUIT").
     */
    @Column(name = "flags", length = 1000)
    public String flags;

    // --------------------------------------------------
    // Relations
    // --------------------------------------------------

    /**
     * The products directly belonging to this family.
     */
    @ManyToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    public Set<Product> products = new HashSet<>();

    /**
     * The sub-families contained within this family.
     */
    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "parent_product_family_id")
    public Set<ProductFamily> productFamilies = new HashSet<>();

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a family by its unique code.
     *
     * @param code The family code.
     * @return The family, or null if none matches.
     */
    public static ProductFamily findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Retrieves every ancestor family of a product, traversing the graph upwards.
     *
     * @param product The product to search for.
     * @return The set of ancestor families, empty when the product belongs to none
     *         or is null.
     */
    public static Set<ProductFamily> findAllFamiliesForProduct(Product product) {
        Set<ProductFamily> hierarchy = new HashSet<>();
        if (product == null || product.id == null) {
            return hierarchy;
        }
        List<ProductFamily> directParents = find(
                "select pf from ProductFamily pf join pf.products p where p.id = ?1",
                product.id
        ).list();
        for (ProductFamily parent : directParents) {
            findAncestorsRecursive(parent, hierarchy);
        }
        return hierarchy;
    }

    /**
     * Checks whether a flag token is present anywhere in a product's family
     * hierarchy (direct or ancestor).
     *
     * @param product The product to check.
     * @param flag    The flag token to search for (case-sensitive).
     * @return true when the flag is found in the hierarchy, false otherwise.
     */
    public static boolean productHasFlag(Product product, String flag) {
        if (product == null || flag == null || flag.isBlank()) {
            return false;
        }
        for (ProductFamily family : findAllFamiliesForProduct(product)) {
            if (family.hasFlag(flag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recursive helper collecting all ancestors of a family, guarding against
     * cycles and multiple parents.
     *
     * @param family    The family whose parents are sought.
     * @param ancestors The accumulating set of ancestor families.
     */
    static void findAncestorsRecursive(ProductFamily family, Set<ProductFamily> ancestors) {
        if (ancestors.contains(family)) {
            return;
        }
        ancestors.add(family);
        List<ProductFamily> parents = find(
                "select parent from ProductFamily parent join parent.productFamilies child where child.id = ?1",
                family.id
        ).list();
        for (ProductFamily parent : parents) {
            findAncestorsRecursive(parent, ancestors);
        }
    }

    /**
     * Collects every distinct flag token defined across the families, sorted
     * alphabetically.
     *
     * @param query An optional fragment the flag must contain, may be null or blank.
     * @param limit The maximum number of flags to return.
     * @return The matching flags, never null.
     */
    public static List<String> searchFlags(String query, int limit) {
        String term = query == null ? "" : query.trim().toLowerCase();
        TreeSet<String> distinct = new TreeSet<>();
        for (ProductFamily family : ProductFamily.<ProductFamily>list("flags is not null")) {
            for (String flag : family.flags.split(",")) {
                String trimmed = flag.trim();
                if (!trimmed.isEmpty() && (term.isEmpty() || trimmed.toLowerCase().contains(term))) {
                    distinct.add(trimmed);
                }
            }
        }
        return distinct.stream().limit(limit).collect(Collectors.toList());
    }

    // --------------------------------------------------
    // Flag Management
    // --------------------------------------------------

    /**
     * Adds a trimmed token to the flags if not already present.
     *
     * @param token The token to add; ignored when null or blank.
     */
    public void addFlag(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        Set<String> currentFlags = getFlagsSet();
        if (currentFlags.add(token.trim())) {
            updateFlagsFromSet(currentFlags);
        }
    }

    /**
     * Removes a token from the flags; clears the field when the last one is removed.
     *
     * @param token The token to remove; a null token removes nothing.
     * @return true when the token was present and removed.
     */
    public boolean removeFlag(String token) {
        if (token == null) {
            return false;
        }
        Set<String> currentFlags = getFlagsSet();
        if (currentFlags.remove(token.trim())) {
            if (currentFlags.isEmpty()) {
                this.flags = null;
            } else {
                updateFlagsFromSet(currentFlags);
            }
            return true;
        }
        return false;
    }

    /**
     * Checks whether a flag token is present, ignoring surrounding whitespace.
     *
     * @param token The token to check for.
     * @return true when the token is present, false otherwise.
     */
    public boolean hasFlag(String token) {
        if (token == null || token.isBlank() || this.flags == null || this.flags.isBlank()) {
            return false;
        }
        return getFlagsSet().contains(token.trim());
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Parses the comma-separated flags string into a set of trimmed tokens.
     *
     * @return The set of flag tokens, never null.
     */
    private Set<String> getFlagsSet() {
        if (this.flags == null || this.flags.isBlank()) {
            return new HashSet<>();
        }
        return Arrays.stream(this.flags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Serializes a set of flags back into the comma-separated string.
     *
     * @param flagSet The set of flags to serialize.
     */
    private void updateFlagsFromSet(Set<String> flagSet) {
        this.flags = String.join(",", flagSet);
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum from the family's code, description and flags; children
     * are excluded for stability.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, description, flags);
    }
}
