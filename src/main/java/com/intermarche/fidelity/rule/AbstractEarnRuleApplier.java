package com.intermarche.fidelity.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.domain.Product;
import com.intermarche.fidelity.domain.ProductFamily;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Base class for the earn appliers, carrying the machinery every mechanic shares:
 * the parsed specification, the assiette scope resolution (INCLUDE/EXCLUDE by
 * brand/family/EAN, §13) against the imfid product reference (§15), and the single
 * per-rule centime rounding (I4).
 * <p>
 * An applier holds the {@link FidelityRule} and its parsed specification; the
 * specification stays the source of truth (§13). Scope resolution reads the product
 * reference by EAN — a line whose EAN is unknown here simply matches no brand and
 * no family, so it stays out of the assiette (never a failure, §25.4). Subclasses
 * add only their calculation and their context predicate.
 */
public abstract class AbstractEarnRuleApplier implements EarnRuleApplier {

    /**
     * Shared thread-safe mapper used to parse the specification.
     */
    protected static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The rule being interpreted; source of the code, label and specification (§13).
     */
    protected final FidelityRule rule;

    /**
     * The parsed specification tree; never null (an empty object when blank).
     */
    protected final JsonNode spec;

    /**
     * Whole-store assiette flag: when true every line is in scope before the
     * excludes apply (§13).
     */
    protected final boolean wholeStore;

    /**
     * Included brands of the assiette (§13).
     */
    protected final Set<String> includeBrands;

    /**
     * Included family codes of the assiette (§13).
     */
    protected final Set<String> includeFamilies;

    /**
     * Included EANs of the assiette (§13).
     */
    protected final Set<String> includeEans;

    /**
     * Excluded brands removed from the assiette (§13).
     */
    protected final Set<String> excludeBrands;

    /**
     * Excluded family codes removed from the assiette (§13).
     */
    protected final Set<String> excludeFamilies;

    /**
     * Excluded EANs removed from the assiette (§13).
     */
    protected final Set<String> excludeEans;

    /**
     * Builds the applier, parsing the rule's specification and resolving its assiette
     * scopes (§12, §13).
     *
     * @param rule The rule to interpret; must not be null and must carry a
     *             specification validated against the factory schema.
     */
    protected AbstractEarnRuleApplier(FidelityRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("rule is mandatory");
        }
        this.rule = rule;
        this.spec = parse(rule);
        JsonNode scope = spec.path("scope");
        this.wholeStore = scope.path("wholeStore").asBoolean(false);
        JsonNode include = scope.path("include");
        JsonNode exclude = scope.path("exclude");
        this.includeBrands = readStringSet(include, "brands");
        this.includeFamilies = readStringSet(include, "families");
        this.includeEans = readStringSet(include, "eans");
        this.excludeBrands = readStringSet(exclude, "brands");
        this.excludeFamilies = readStringSet(exclude, "families");
        this.excludeEans = readStringSet(exclude, "eans");
    }

    // --------------------------------------------------
    // Assiette resolution (§13, §15)
    // --------------------------------------------------

    /**
     * Indicates whether a line belongs to this rule's assiette and may earn: a valid
     * earn candidate (not consumed by a commercial offer, strictly positive net,
     * I2) that the scopes retain (§13, §15).
     *
     * @param line The valued line to test; a null line is never eligible.
     * @return true when the line feeds this rule's assiette.
     */
    protected boolean isEligibleLine(ValuedLine line) {
        return line != null && line.isEarnCandidate() && isInScope(line);
    }

    /**
     * Resolves the assiette scope of a line: retained by at least one include (or
     * {@code wholeStore}) and by no exclude (§13). Brand and families are resolved
     * from the product reference by EAN; an unknown EAN matches no brand and no
     * family (§25.4).
     *
     * @param line The valued line to test; a null line is out of scope.
     * @return true when the scopes retain the line.
     */
    protected boolean isInScope(ValuedLine line) {
        if (line == null) {
            return false;
        }
        String ean = line.ean;
        Product product = ean != null ? Product.findByEan(ean) : null;
        String brand = product != null ? product.brand : null;
        Set<String> familyCodes = familyCodesOf(product);

        boolean included = wholeStore
                || (ean != null && includeEans.contains(ean))
                || (brand != null && includeBrands.contains(brand))
                || intersects(familyCodes, includeFamilies);
        if (!included) {
            return false;
        }
        boolean excluded = (ean != null && excludeEans.contains(ean))
                || (brand != null && excludeBrands.contains(brand))
                || intersects(familyCodes, excludeFamilies);
        return !excluded;
    }

    /**
     * Collects the eligible lines of the basket, in encounter order (§15).
     *
     * @param lines The valued basket lines; a null list yields an empty result.
     * @return The eligible lines, never null.
     */
    protected List<ValuedLine> eligibleLines(List<ValuedLine> lines) {
        List<ValuedLine> retained = new ArrayList<>();
        if (lines == null) {
            return retained;
        }
        for (ValuedLine line : lines) {
            if (isEligibleLine(line)) {
                retained.add(line);
            }
        }
        return retained;
    }

    /**
     * Sums the net assiette of the given lines — the sum of their non-negative TTC
     * nets (§15, §22.1).
     *
     * @param lines The lines to sum; a null list sums to zero.
     * @return The assiette, euro at scale 2, never null.
     */
    protected BigDecimal assietteOf(List<ValuedLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        if (lines != null) {
            for (ValuedLine line : lines) {
                total = total.add(line.earnBaseAmount());
            }
        }
        return round(total);
    }

    /**
     * Collects the ids of the given lines, in encounter order.
     *
     * @param lines The lines whose ids are collected; a null list yields an empty list.
     * @return The line ids, never null.
     */
    protected List<String> lineIdsOf(List<ValuedLine> lines) {
        List<String> ids = new ArrayList<>();
        if (lines != null) {
            for (ValuedLine line : lines) {
                ids.add(line.lineId);
            }
        }
        return ids;
    }

    /**
     * Counts the eligible items of the given lines for the "N eligible items"
     * threshold: units for a UNIT product, one product per weighing for a
     * WEIGHT/VOLUME product; an unknown EAN counts as one item (§22.2).
     *
     * @param lines The eligible lines to count.
     * @return The eligible item count.
     */
    protected int countEligibleItems(List<ValuedLine> lines) {
        int count = 0;
        if (lines == null) {
            return 0;
        }
        for (ValuedLine line : lines) {
            Product product = line.ean != null ? Product.findByEan(line.ean) : null;
            if (product != null && product.productType != null
                    && product.productType.name().equals("UNIT")) {
                count += Math.max(0, line.quantity.setScale(0, RoundingMode.FLOOR).intValue());
            } else {
                // A weighing (or an unresolved EAN) counts as one product (§22.2).
                count += 1;
            }
        }
        return count;
    }

    // --------------------------------------------------
    // Calculation helpers (I4, §30.5)
    // --------------------------------------------------

    /**
     * Rounds an amount to euro scale 2 HALF_UP — the single per-rule rounding of the
     * calculation (I4, §30.5).
     *
     * @param value The raw amount; null is read as zero.
     * @return The amount at scale 2, never null.
     */
    protected BigDecimal round(BigDecimal value) {
        BigDecimal base = value != null ? value : BigDecimal.ZERO;
        return base.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Applies a rate to an assiette and rounds once at the centime (I4).
     *
     * @param assiette The base amount; null is read as zero.
     * @param rate     The rate as a stored fraction (0.05 = 5 %); null is read as zero.
     * @return The rounded earn, never null.
     */
    protected BigDecimal applyRate(BigDecimal assiette, BigDecimal rate) {
        if (assiette == null || rate == null) {
            return round(BigDecimal.ZERO);
        }
        return round(assiette.multiply(rate));
    }

    /**
     * Builds an earn entry for this rule from an assiette, a computed amount and the
     * contributing lines (§15).
     *
     * @param amount   The rounded earn amount.
     * @param assiette The eligible assiette.
     * @param lines    The contributing lines.
     * @return The earn entry, never null.
     */
    protected EarnEntry entry(BigDecimal amount, BigDecimal assiette, List<ValuedLine> lines) {
        return new EarnEntry(rule.code, rule.label, amount, assiette, lineIdsOf(lines));
    }

    /**
     * Returns the empty entry of this rule — a failed predicate or a non-producer.
     *
     * @return The empty entry, never null.
     */
    protected EarnEntry none() {
        return EarnEntry.none(rule.code, rule.label);
    }

    // --------------------------------------------------
    // Specification reading (§31.2 null guards)
    // --------------------------------------------------

    /**
     * Reads a decimal field from the specification root.
     *
     * @param field   The field name.
     * @param fallback The value returned when the field is absent or non-numeric.
     * @return The decimal value, or the fallback.
     */
    protected BigDecimal decimal(String field, BigDecimal fallback) {
        JsonNode node = spec.get(field);
        if (node == null || node.isNull() || !node.isNumber()) {
            return fallback;
        }
        return node.decimalValue();
    }

    /**
     * Reads an integer field from the specification root.
     *
     * @param field   The field name.
     * @param fallback The value returned when the field is absent or non-integral.
     * @return The integer value, or the fallback.
     */
    protected int integer(String field, int fallback) {
        JsonNode node = spec.get(field);
        if (node == null || node.isNull() || !node.isNumber()) {
            return fallback;
        }
        return node.asInt(fallback);
    }

    /**
     * Reads a string field from the specification root. Under the {@code isTextual}
     * guard, {@code asText()} is never null (a JSON null is a {@code NullNode},
     * already excluded): only blankness needs normalizing.
     *
     * @param field The field name.
     * @return The trimmed value, or null when absent or blank.
     */
    protected String text(String field) {
        JsonNode node = spec.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value.trim();
    }

    /**
     * Reads the tiers array of the specification, if any — the visit-boosted rates
     * of the socle or the amount tiers of a challenge (§12, §13).
     *
     * @return The tiers node array, or an empty node when absent.
     */
    protected JsonNode tiersNode() {
        JsonNode node = spec.get("tiers");
        return node != null && node.isArray() ? node : MAPPER.createArrayNode();
    }

    // --------------------------------------------------
    // Internal helpers
    // --------------------------------------------------

    /**
     * Parses a rule's specification into a JSON tree, guarding a null or blank
     * specification and malformed JSON (§31.2).
     *
     * @param rule The rule whose specification is parsed.
     * @return The parsed tree, or an empty object when blank.
     * @throws IllegalStateException when the specification is not valid JSON.
     */
    private static JsonNode parse(FidelityRule rule) {
        String specification = rule.specification;
        if (specification == null || specification.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(specification);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse specification for rule "
                    + rule.code + ": " + e.getMessage(), e);
        }
    }

    /**
     * Reads a string array field from a JSON object into a set of trimmed, non-blank
     * values.
     *
     * @param parent The parent node; may be a missing node.
     * @param field  The array field name.
     * @return The set of values, never null.
     */
    private static Set<String> readStringSet(JsonNode parent, String field) {
        Set<String> values = new HashSet<>();
        JsonNode node = parent == null ? null : parent.get(field);
        if (node != null && node.isArray()) {
            // Iterating a Jackson array never yields a Java null, and a textual
            // node's asText() is never null: the JSON guards alone are needed.
            for (JsonNode element : node) {
                if (element.isTextual()) {
                    String value = element.asText();
                    if (!value.isBlank()) {
                        values.add(value.trim());
                    }
                }
            }
        }
        return values;
    }

    /**
     * Resolves the family codes a product belongs to (direct and ancestor), never
     * null (§13, §15).
     *
     * @param product The product; a null product yields an empty set.
     * @return The family codes, never null.
     */
    private static Set<String> familyCodesOf(Product product) {
        Set<String> codes = new HashSet<>();
        if (product == null) {
            return codes;
        }
        for (ProductFamily family : ProductFamily.findAllFamiliesForProduct(product)) {
            if (family.code != null) {
                codes.add(family.code);
            }
        }
        return codes;
    }

    /**
     * Indicates whether two sets share at least one element.
     *
     * @param a The first set; a null set never intersects.
     * @param b The second set; a null set never intersects.
     * @return true when the sets share an element.
     */
    private static boolean intersects(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        for (String value : smaller) {
            if (larger.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
