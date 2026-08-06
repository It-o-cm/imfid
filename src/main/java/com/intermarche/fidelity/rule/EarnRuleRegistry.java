package com.intermarche.fidelity.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.fidelity.domain.FidelityRule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The registry of earn rule factories — the imfid counterpart of imvaluation's
 * {@code OfferSchemaRegistry} (§12, I10).
 * <p>
 * At startup it discovers every {@link EarnRuleApplierFactory} by CDI, indexes them
 * {@code type → factory} and compiles each factory's JSON Schema once. The same
 * compiled schema instance validates a specification at import and at mutation and
 * feeds the admin form generation, so engine and UI cannot drift (§12, §21.3). Two
 * factories claiming the same type fail the startup — a rule in base without an
 * interpreter is a configuration error, not a silent case (§12, I10).
 * <p>
 * Administration refuses a rule whose type has no factory ({@link #hasFactory(String)}
 * false) or whose specification does not validate its schema
 * ({@link #validate(String, String)} returning errors, §12).
 */
@ApplicationScoped
public class EarnRuleRegistry {

    private static final Logger LOGGER = Logger.getLogger(EarnRuleRegistry.class);

    /**
     * Shared mapper used to parse specifications for validation.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The JSON Schema factory pinned to Draft 2020-12; the {@code x-widget} and kin
     * display annotations are unknown keywords and are ignored at validation (§21.3).
     */
    private static final JsonSchemaFactory SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /**
     * The CDI-discovered factories, injected as an {@link Instance} so the registry
     * indexes them at startup (§12).
     */
    @Inject
    Instance<EarnRuleApplierFactory> factories;

    /**
     * The {@code type → factory} index, populated at startup; iteration-ordered for
     * stable logging.
     */
    private final Map<String, EarnRuleApplierFactory> byType = new LinkedHashMap<>();

    /**
     * The {@code type → compiled schema} index, populated at startup.
     */
    private final Map<String, JsonSchema> schemasByType = new LinkedHashMap<>();

    /**
     * The {@code type → raw schema string} index, published to the admin form
     * generator (§21.3).
     */
    private final Map<String, String> rawSchemasByType = new LinkedHashMap<>();

    /**
     * Discovers, indexes and compiles the factories at startup, failing loudly on a
     * duplicate type or an unreadable/malformed schema (§12).
     *
     * @param event The Quarkus startup event.
     */
    void onStart(@Observes StartupEvent event) {
        for (EarnRuleApplierFactory factory : factories) {
            String type = factory.getRuleType();
            if (type == null || type.isBlank()) {
                throw new IllegalStateException("Earn rule factory "
                        + factory.getClass().getName() + " declares a blank rule type");
            }
            EarnRuleApplierFactory previous = byType.putIfAbsent(type, factory);
            if (previous != null) {
                throw new IllegalStateException("Two earn rule factories claim type '" + type
                        + "': " + previous.getClass().getName() + " and " + factory.getClass().getName());
            }
            String rawSchema = factory.getSchema();
            JsonSchema schema;
            try {
                schema = SCHEMA_FACTORY.getSchema(rawSchema);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Invalid JSON Schema for earn rule type '" + type
                        + "' (factory " + factory.getClass().getName() + "): " + e.getMessage(), e);
            }
            schemasByType.put(type, schema);
            rawSchemasByType.put(type, rawSchema);
        }
        LOGGER.infof("Earn rule registry started: %d schemas registered %s",
                schemasByType.size(), new TreeMap<>(rawSchemasByType).keySet());
    }

    /**
     * Indicates whether a factory is deployed for the given rule type (§12).
     *
     * @param type The rule type code; a null type is unknown.
     * @return true when a factory interprets the type.
     */
    public boolean hasFactory(String type) {
        return type != null && byType.containsKey(type);
    }

    /**
     * Returns the factory of a rule type, or null when none is deployed (§12).
     *
     * @param type The rule type code.
     * @return The factory, or null.
     */
    public EarnRuleApplierFactory factoryFor(String type) {
        return type == null ? null : byType.get(type);
    }

    /**
     * Creates an applier for a rule, validating its type has a factory (§12, I10).
     *
     * @param rule The rule to interpret.
     * @return The applier bound to the rule.
     * @throws IllegalArgumentException when the rule's type has no deployed factory.
     */
    public EarnRuleApplier createApplier(FidelityRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("rule is mandatory");
        }
        EarnRuleApplierFactory factory = factoryFor(rule.type);
        if (factory == null) {
            throw new IllegalArgumentException("No earn rule factory deployed for type '" + rule.type
                    + "' (rule " + rule.code + ")");
        }
        return factory.create(rule);
    }

    /**
     * Validates a specification against the schema of the given rule type (§12).
     * <p>
     * Returns the list of validation error messages, empty when the specification is
     * valid; a null or blank specification, malformed JSON, or an unknown type each
     * yield a single descriptive error rather than an exception, so the caller can
     * report the row and move on (§12, §31.2).
     *
     * @param type          The rule type code.
     * @param specification The JSON specification to validate.
     * @return The validation errors, never null; empty when valid.
     */
    public List<String> validate(String type, String specification) {
        JsonSchema schema = type == null ? null : schemasByType.get(type);
        if (schema == null) {
            return List.of("Unknown earn rule type '" + type + "' (no factory deployed)");
        }
        if (specification == null || specification.isBlank()) {
            return List.of("Empty specification for rule type '" + type + "'");
        }
        JsonNode json;
        try {
            json = MAPPER.readTree(specification);
        } catch (Exception e) {
            return List.of("Malformed specification JSON for rule type '" + type + "': " + e.getMessage());
        }
        Set<ValidationMessage> messages = schema.validate(json);
        if (messages.isEmpty()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>(messages.size());
        for (ValidationMessage message : messages) {
            errors.add(message.getMessage());
        }
        Collections.sort(errors);
        return errors;
    }

    /**
     * Returns the raw JSON Schema of a rule type, for the admin form generator, or
     * null when the type is unknown (§21.3).
     *
     * @param type The rule type code.
     * @return The raw schema string, or null.
     */
    public String schemaFor(String type) {
        return type == null ? null : rawSchemasByType.get(type);
    }

    /**
     * Returns the aggregated {@code type → raw schema} map, for the admin form
     * generator (§21.3); an unmodifiable snapshot.
     *
     * @return The registered schemas, never null.
     */
    public Map<String, String> allSchemas() {
        return Collections.unmodifiableMap(rawSchemasByType);
    }

    /**
     * Returns the set of registered rule types (§12); an unmodifiable snapshot.
     *
     * @return The registered types, never null.
     */
    public Set<String> registeredTypes() {
        return Collections.unmodifiableSet(byType.keySet());
    }
}
