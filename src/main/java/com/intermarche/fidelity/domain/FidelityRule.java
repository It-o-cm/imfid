package com.intermarche.fidelity.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An administrable earn rule — the sister entity of imvaluation's {@code Offer},
 * built on the same pattern (§13).
 * <p>
 * The common backbone lives in columns (stable {@link #code}, {@link #type} =
 * factory code, printed {@link #label}, {@link #validFrom}/{@link #validTo}
 * window, {@link #priority}, {@link #exclusive}, nullable
 * {@link #monthlyCapPerCard}, {@link #active}); all mechanic-specific
 * parametrics live in the JSON {@link #specification}, validated by the JSON
 * Schema of the rule type's factory (§12, I10). Plugging a new mechanic entails
 * no schema migration — only a new JSON Schema.
 * <p>
 * The rule window is modelled on price lines (§11): a basket is valued with the
 * rules in force at its {@code priceDate}. Versioning a rule means closing the
 * current instance ({@code validTo}) and opening a new one with a stable code —
 * closed rules are never modified or deleted (§13, §18), keeping the valuation
 * of an old ticket at its price date replayable.
 * <p>
 * Fields are public to comply with Quarkus/Panache conventions.
 */
@Entity
@Table(name = "fidelity_rules",
        indexes = {
                @Index(name = "idx_rule_code", columnList = "code"),
                @Index(name = "idx_rule_type", columnList = "type"),
                @Index(name = "idx_rule_active", columnList = "is_active")
        }
)
@Cacheable
public class FidelityRule extends BaseEntity {

    /**
     * Shared thread-safe mapper used to materialize tiers from the specification.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Factory code — percentage on an eligible brand set, N-eligible-items
     * threshold, rate boosted by a visit counter (§12).
     */
    public static final String TYPE_BRAND_TIERED_EARN = "BRAND_TIERED_EARN";

    /**
     * Factory code — percentage on families, on given weekdays (§12).
     */
    public static final String TYPE_CALENDAR_FAMILY_EARN = "CALENDAR_FAMILY_EARN";

    /**
     * Factory code — percentage reserved to a community's members, capped per
     * month and per card (§12).
     */
    public static final String TYPE_COMMUNITY_EARN = "COMMUNITY_EARN";

    /**
     * Factory code — percentage on a fixed day of the month, once per period,
     * capped (§12).
     */
    public static final String TYPE_MONTHLY_DATE_EARN = "MONTHLY_DATE_EARN";

    /**
     * Factory code — purchase-amount tiers to a gain over a period, with an
     * optional mission at the last tier (§12).
     */
    public static final String TYPE_CHALLENGE_EARN = "CHALLENGE_EARN";

    /**
     * Factory code — percentage on a department, subject to prior card activation
     * (§12).
     */
    public static final String TYPE_ECOUPON_EARN = "ECOUPON_EARN";

    /**
     * Factory code — fixed gain unlocked by the highest amount threshold reached
     * by the eligible assiette of the single current ticket (§12).
     */
    public static final String TYPE_TICKET_THRESHOLD_EARN = "TICKET_THRESHOLD_EARN";

    /**
     * Factory code — global basket filter: removes its scopes from every earn base
     * and from the burnable base (§12, §15).
     */
    public static final String TYPE_PROGRAM_EXCLUSION = "PROGRAM_EXCLUSION";

    // --------------------------------------------------
    // Backbone
    // --------------------------------------------------

    /**
     * The unique, stable business key of the rule; kept stable across versions so
     * movements stay traceable (§13).
     */
    @Column(unique = true, nullable = false, length = 50)
    @NotBlank(message = "Rule code is mandatory")
    public String code;

    /**
     * The rule type, i.e. the code of the applier factory that interprets the
     * specification (one of the {@code TYPE_*} constants). A String rather than an
     * enum so a new mechanic is a new factory, never an engine change (§12, I10).
     */
    @Column(nullable = false, length = 50)
    @NotBlank(message = "Rule type is mandatory")
    public String type;

    /**
     * The human label printed on the ticket and echoed in the {@code /earn}
     * response; the only rule datum that travels to the POS (§18).
     */
    @Column(nullable = false, length = 120)
    @NotBlank(message = "Rule label is mandatory")
    public String label;

    /**
     * The mechanic-specific parametrics as a JSON string, validated by the JSON
     * Schema of the rule type's factory (§12): base (brands/families/eans in
     * include/exclude, or wholeStore), rate, thresholds, active days, day of
     * month, challenge tiers. Immutable once the rule is closed (§18).
     */
    @Lob
    @Column(nullable = false)
    @NotBlank(message = "Rule specification is mandatory")
    public String specification;

    /**
     * The advantage type the POS groups this rule's earn under on the ticket
     * (closed {@code AdvantageType} referential, RFP BO-03-03-25). Mandatory on a
     * crediting rule, null on a {@code PROGRAM_EXCLUSION}; immutable once the
     * rule is closed, like the specification (§18).
     */
    @Column(name = "advantage_type", length = 30)
    public String advantageType;

    /**
     * The optional advantage category ({@code AdvantageCategory} referential,
     * RFP BO-03-03-33); null when the rule carries none.
     */
    @Column(name = "advantage_category", length = 30)
    public String advantageCategory;

    /**
     * Start of the validity window (inclusive), interpreted at the program zone.
     */
    @Column(name = "valid_from", nullable = false)
    @NotNull(message = "Rule validFrom is mandatory")
    public LocalDateTime validFrom;

    /**
     * End of the validity window (exclusive); null while the rule is open. Closing
     * a rule sets it to a date not in the past (§18).
     */
    @Column(name = "valid_to")
    public LocalDateTime validTo;

    /**
     * Evaluation priority; rules are evaluated by descending priority (§15).
     */
    @Column(nullable = false)
    public int priority;

    /**
     * When true the rule consumes its lines, which leave the bases of subsequent
     * rules — the loyalty non-cumulation arbitration is entirely data-borne (§15,
     * I2).
     */
    @Column(nullable = false)
    public boolean exclusive;

    /**
     * Optional per-card monthly cap applied by truncation before the community and
     * global caps (§15, I5); null when the rule carries no cap of its own.
     */
    @Column(name = "monthly_cap_per_card", precision = 19, scale = 2)
    public BigDecimal monthlyCapPerCard;

    /**
     * Whether the rule is active; an inactive rule never contributes to an earn.
     */
    @Column(name = "is_active", nullable = false)
    public boolean active = true;

    // --------------------------------------------------
    // Tiers (materialized from the specification)
    // --------------------------------------------------

    /**
     * The tiers materialized from the specification's {@code tiers} array, mirroring
     * the way {@code Offer} materializes its EANs. The JSON specification remains
     * the source of truth; these rows exist so tiers are queryable and rendered in
     * administration. Empty for rules that carry no tiers.
     */
    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderColumn(name = "rank")
    public List<FidelityRuleTier> tiers = new ArrayList<>();

    // --------------------------------------------------
    // Lifecycle Callbacks
    // --------------------------------------------------

    /**
     * Materializes the tiers from the specification before the first insert, then
     * runs the base audit/checksum callback.
     */
    @Override
    @PrePersist
    public void onCreate() {
        rebuildTiersFromSpecification();
        super.onCreate();
    }

    /**
     * Rebuilds the tiers from the specification before every update, then runs the
     * base audit/checksum callback.
     */
    @Override
    @PreUpdate
    public void onUpdate() {
        rebuildTiersFromSpecification();
        super.onUpdate();
    }

    /**
     * Parses the specification and rebuilds the {@link #tiers} list from its
     * {@code tiers} array, if any.
     * <p>
     * Each entry may carry {@code threshold} (visit count or amount), {@code rate}
     * (stored fraction), {@code reward} (fixed gain) and {@code missionRequired}.
     * Guards against a null or blank specification and against malformed JSON so a
     * parse never leaves the entity in a half-built state (§31.2).
     */
    private void rebuildTiersFromSpecification() {
        this.tiers.clear();
        if (this.specification == null || this.specification.isBlank()) {
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(this.specification);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse specification for rule " + this.code + ": " + e.getMessage(), e);
        }
        JsonNode tiersNode = root.get("tiers");
        if (tiersNode == null || !tiersNode.isArray()) {
            return;
        }
        int rank = 0;
        for (JsonNode tierNode : tiersNode) {
            if (!tierNode.isObject()) {
                continue;
            }
            FidelityRuleTier tier = new FidelityRuleTier();
            tier.rule = this;
            tier.rank = rank++;
            tier.threshold = readDecimal(tierNode, "threshold");
            tier.rate = readDecimal(tierNode, "rate");
            tier.rewardAmount = readDecimal(tierNode, "reward");
            JsonNode mission = tierNode.get("missionRequired");
            tier.missionRequired = mission != null && mission.asBoolean(false);
            this.tiers.add(tier);
        }
    }

    /**
     * Reads a decimal field from a JSON node, returning null when the field is
     * absent, null or non-numeric.
     *
     * @param node  The object node to read from.
     * @param field The field name.
     * @return The decimal value, or null.
     */
    private static BigDecimal readDecimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.decimalValue();
    }

    // --------------------------------------------------
    // Domain helpers
    // --------------------------------------------------

    /**
     * Returns the community code carried by the specification of a community-gated
     * rule ({@code COMMUNITY_EARN}, {@code MONTHLY_DATE_EARN}, §12), or null when the
     * rule is not community-gated or the specification is absent/malformed (§31.2).
     * <p>
     * Read by the card-context build and by the community-cap arbitration (§15, I5) so
     * the mapping rule &rarr; community stays sourced from the specification, never
     * duplicated.
     *
     * @return The community code, or null.
     */
    public String communityCodeFromSpec() {
        if (specification == null || specification.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(specification).get("communityCode");
            if (node != null && node.isTextual()) {
                // A textual node's asText() is never null (a JSON null parses as a
                // NullNode, already excluded by isTextual): only blankness matters.
                String value = node.asText();
                return value.isBlank() ? null : value.trim();
            }
        } catch (JsonProcessingException e) {
            return null;
        }
        return null;
    }

    /**
     * Indicates whether the rule is in force at the given instant: active and
     * inside its validity window (validFrom inclusive, validTo exclusive).
     *
     * @param at The instant to test; when null the current program time is used.
     * @return true when the rule is active and its window contains the instant.
     */
    public boolean isInForceAt(LocalDateTime at) {
        LocalDateTime moment = at != null ? at : DateTimeProvider.now();
        if (!active || validFrom == null || moment.isBefore(validFrom)) {
            return false;
        }
        return validTo == null || moment.isBefore(validTo);
    }

    // --------------------------------------------------
    // Panache Active Record Queries
    // --------------------------------------------------

    /**
     * Finds a rule by its unique code.
     *
     * @param code The rule code.
     * @return The rule, or null if none matches.
     */
    public static FidelityRule findByCode(String code) {
        return find("code", code).firstResult();
    }

    /**
     * Lists every rule of a given type.
     *
     * @param type The factory type code.
     * @return The matching rules, never null.
     */
    public static List<FidelityRule> listByType(String type) {
        return list("type", type);
    }

    /**
     * Lists the rules in force at the given instant, by descending priority — the
     * evaluation order of the engine (§15).
     *
     * @param at The instant the rules must be in force at.
     * @return The active rules whose window contains the instant, never null.
     */
    public static List<FidelityRule> listInForceAt(LocalDateTime at) {
        return list("active = true and validFrom <= ?1 and (validTo is null or validTo > ?1) order by priority desc", at);
    }

    // --------------------------------------------------
    // Checksum
    // --------------------------------------------------

    /**
     * Calculates a checksum from the backbone and the specification; the tiers are
     * excluded as they are derived from the specification.
     *
     * @return Checksum integer value.
     */
    @Override
    public int getChecksum() {
        return Objects.hash(code, type, label, specification, validFrom, validTo,
                priority, exclusive, monthlyCapPerCard, active, advantageType, advantageCategory);
    }
}
