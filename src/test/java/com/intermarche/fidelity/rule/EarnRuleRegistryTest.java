package com.intermarche.fidelity.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityRule;
import jakarta.enterprise.inject.Instance;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link EarnRuleRegistry} — the startup indexer of the earn
 * rule factories (§12, I10): the {@code type → factory}/{@code type → schema}
 * indexing at boot with its loud failures (blank/duplicate type, malformed schema),
 * the factory lookups, the applier creation guard and the specification validation
 * with its schema-driven errors (§12, §21.3).
 * <p>
 * Pure unit: the CDI {@link Instance} of factories is a Mockito mock iterating an
 * in-memory list; each factory is a mock declaring a type and a raw JSON Schema.
 * The registry reads no clock and no Panache finder, so nothing static is mocked and
 * no {@link com.intermarche.fidelity.domain.util.DateTimeProvider} is involved — the
 * class carries no temporal logic. Both arms of every guard and each leg of every
 * compound guard are exercised: null and blank type, first and duplicate insertion,
 * valid and throwing schema compilation, null/unknown/known lookups, null/empty/blank/
 * malformed/valid/invalid specifications.
 */
class EarnRuleRegistryTest {

    /**
     * A JSON Schema requiring two properties, so an empty object yields several
     * ordered validation messages and a populated object validates.
     */
    private static final String SCHEMA_A =
            "{\"type\":\"object\",\"required\":[\"rate\",\"label\"],"
            + "\"properties\":{\"rate\":{\"type\":\"number\"},\"label\":{\"type\":\"string\"}}}";

    /**
     * A second, permissive schema used to prove multi-type indexing.
     */
    private static final String SCHEMA_B = "{\"type\":\"object\"}";

    /**
     * The rule type interpreted by the first factory.
     */
    private static final String TYPE_A = "TYPE_A";

    /**
     * The rule type interpreted by the second factory.
     */
    private static final String TYPE_B = "TYPE_B";

    /**
     * Builds a mock factory declaring the given rule type and raw schema.
     *
     * @param type   The rule type the factory claims; may be null or blank.
     * @param schema The raw JSON Schema the factory publishes.
     * @return The configured factory mock.
     */
    private EarnRuleApplierFactory factory(String type, String schema) {
        EarnRuleApplierFactory f = Mockito.mock(EarnRuleApplierFactory.class);
        Mockito.when(f.getRuleType()).thenReturn(type);
        Mockito.lenient().when(f.getSchema()).thenReturn(schema);
        return f;
    }

    /**
     * Wraps the given factories in a mocked CDI {@link Instance}, re-iterating the
     * list on each call so a repeated start would still see the factories.
     *
     * @param list The factories to expose.
     * @return The mocked instance.
     */
    @SuppressWarnings("unchecked")
    private Instance<EarnRuleApplierFactory> instanceOf(List<EarnRuleApplierFactory> list) {
        Instance<EarnRuleApplierFactory> instance = Mockito.mock(Instance.class);
        Mockito.when(instance.iterator()).thenAnswer(inv -> list.iterator());
        return instance;
    }

    /**
     * Builds a registry already started with the two default factories A and B.
     *
     * @return The started registry.
     */
    private EarnRuleRegistry startedRegistry() {
        EarnRuleRegistry registry = new EarnRuleRegistry();
        List<EarnRuleApplierFactory> list = new ArrayList<>();
        list.add(factory(TYPE_A, SCHEMA_A));
        list.add(factory(TYPE_B, SCHEMA_B));
        registry.factories = instanceOf(list);
        registry.onStart(null);
        return registry;
    }

    /**
     * onStart indexes every factory: the types, schemas and raw schemas are all
     * populated for both factories.
     */
    @Test
    @DisplayName("onStart indexes every discovered factory")
    void onStartIndexesFactories() {
        EarnRuleRegistry registry = startedRegistry();
        assertEquals(Set.of(TYPE_A, TYPE_B), registry.registeredTypes());
        assertTrue(registry.hasFactory(TYPE_A));
        assertTrue(registry.hasFactory(TYPE_B));
        assertEquals(SCHEMA_A, registry.schemaFor(TYPE_A));
        assertEquals(2, registry.allSchemas().size());
    }

    /**
     * onStart rejects a factory declaring a null rule type (first leg of the
     * {@code type == null || type.isBlank()} guard).
     */
    @Test
    @DisplayName("onStart throws on a null rule type")
    void onStartNullTypeThrows() {
        EarnRuleRegistry registry = new EarnRuleRegistry();
        registry.factories = instanceOf(List.of(factory(null, SCHEMA_A)));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> registry.onStart(null));
        assertTrue(ex.getMessage().contains("blank rule type"));
    }

    /**
     * onStart rejects a factory declaring a blank rule type (second leg of the
     * {@code type == null || type.isBlank()} guard).
     */
    @Test
    @DisplayName("onStart throws on a blank rule type")
    void onStartBlankTypeThrows() {
        EarnRuleRegistry registry = new EarnRuleRegistry();
        registry.factories = instanceOf(List.of(factory("   ", SCHEMA_A)));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> registry.onStart(null));
        assertTrue(ex.getMessage().contains("blank rule type"));
    }

    /**
     * onStart rejects two factories claiming the same type (the {@code previous !=
     * null} arm), naming both classes.
     */
    @Test
    @DisplayName("onStart throws on a duplicate rule type")
    void onStartDuplicateTypeThrows() {
        EarnRuleRegistry registry = new EarnRuleRegistry();
        List<EarnRuleApplierFactory> list = new ArrayList<>();
        list.add(factory(TYPE_A, SCHEMA_A));
        list.add(factory(TYPE_A, SCHEMA_B));
        registry.factories = instanceOf(list);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> registry.onStart(null));
        assertTrue(ex.getMessage().contains("Two earn rule factories claim type '" + TYPE_A + "'"));
    }

    /**
     * onStart wraps a malformed schema compilation failure into a startup error (the
     * catch arm of the schema compilation guard).
     */
    @Test
    @DisplayName("onStart throws on a malformed schema")
    void onStartMalformedSchemaThrows() {
        EarnRuleRegistry registry = new EarnRuleRegistry();
        registry.factories = instanceOf(List.of(factory(TYPE_A, "{ this is not json")));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> registry.onStart(null));
        assertTrue(ex.getMessage().contains("Invalid JSON Schema for earn rule type '" + TYPE_A + "'"));
    }

    /**
     * hasFactory is false for a null type (first leg of {@code type != null &&
     * byType.containsKey(type)}).
     */
    @Test
    @DisplayName("hasFactory is false for a null type")
    void hasFactoryNullFalse() {
        assertFalse(startedRegistry().hasFactory(null));
    }

    /**
     * hasFactory is false for an unknown non-null type (second leg false).
     */
    @Test
    @DisplayName("hasFactory is false for an unknown type")
    void hasFactoryUnknownFalse() {
        assertFalse(startedRegistry().hasFactory("NOPE"));
    }

    /**
     * hasFactory is true for a registered type (both legs true).
     */
    @Test
    @DisplayName("hasFactory is true for a registered type")
    void hasFactoryKnownTrue() {
        assertTrue(startedRegistry().hasFactory(TYPE_A));
    }

    /**
     * factoryFor returns null for a null type (the null arm of the ternary).
     */
    @Test
    @DisplayName("factoryFor returns null for a null type")
    void factoryForNullReturnsNull() {
        assertNull(startedRegistry().factoryFor(null));
    }

    /**
     * factoryFor returns null for an unknown type (non-null arm, absent key).
     */
    @Test
    @DisplayName("factoryFor returns null for an unknown type")
    void factoryForUnknownReturnsNull() {
        assertNull(startedRegistry().factoryFor("NOPE"));
    }

    /**
     * factoryFor returns the indexed factory for a registered type (non-null arm,
     * present key).
     */
    @Test
    @DisplayName("factoryFor returns the factory for a registered type")
    void factoryForKnownReturnsFactory() {
        assertNotNull(startedRegistry().factoryFor(TYPE_A));
    }

    /**
     * createApplier rejects a null rule (the {@code rule == null} arm).
     */
    @Test
    @DisplayName("createApplier throws on a null rule")
    void createApplierNullRuleThrows() {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> startedRegistry().createApplier(null));
        assertTrue(ex.getMessage().contains("rule is mandatory"));
    }

    /**
     * createApplier rejects a rule whose type has no deployed factory (the
     * {@code factory == null} arm).
     */
    @Test
    @DisplayName("createApplier throws for a type without a factory")
    void createApplierUnknownTypeThrows() {
        FidelityRule rule = new FidelityRule();
        rule.type = "NOPE";
        rule.code = "R1";
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> startedRegistry().createApplier(rule));
        assertTrue(ex.getMessage().contains("No earn rule factory deployed for type 'NOPE'"));
    }

    /**
     * createApplier delegates to the factory when the rule's type is registered (the
     * non-null arm), returning exactly the factory-built applier.
     */
    @Test
    @DisplayName("createApplier returns the factory-built applier")
    void createApplierKnownReturnsApplier() {
        EarnRuleRegistry registry = new EarnRuleRegistry();
        EarnRuleApplierFactory f = factory(TYPE_A, SCHEMA_A);
        EarnRuleApplier applier = Mockito.mock(EarnRuleApplier.class);
        FidelityRule rule = new FidelityRule();
        rule.type = TYPE_A;
        rule.code = "R1";
        Mockito.when(f.create(rule)).thenReturn(applier);
        registry.factories = instanceOf(List.of(f));
        registry.onStart(null);
        assertSame(applier, registry.createApplier(rule));
    }

    /**
     * validate reports an unknown-type error when the type is null (the null arm of
     * the schema lookup ternary, then the {@code schema == null} arm).
     */
    @Test
    @DisplayName("validate flags a null type as unknown")
    void validateNullTypeUnknown() {
        List<String> errors = startedRegistry().validate(null, "{}");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Unknown earn rule type"));
    }

    /**
     * validate reports an unknown-type error when the type is not registered
     * (non-null arm, absent schema).
     */
    @Test
    @DisplayName("validate flags an unregistered type as unknown")
    void validateUnknownTypeUnknown() {
        List<String> errors = startedRegistry().validate("NOPE", "{}");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Unknown earn rule type 'NOPE'"));
    }

    /**
     * validate reports an empty-specification error for a null specification (first
     * leg of {@code specification == null || specification.isBlank()}).
     */
    @Test
    @DisplayName("validate flags a null specification as empty")
    void validateNullSpecEmpty() {
        List<String> errors = startedRegistry().validate(TYPE_A, null);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Empty specification for rule type '" + TYPE_A + "'"));
    }

    /**
     * validate reports an empty-specification error for a blank specification (second
     * leg of the empty guard).
     */
    @Test
    @DisplayName("validate flags a blank specification as empty")
    void validateBlankSpecEmpty() {
        List<String> errors = startedRegistry().validate(TYPE_A, "   ");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Empty specification"));
    }

    /**
     * validate reports a malformed-JSON error when the specification does not parse
     * (the catch arm of {@code readTree}).
     */
    @Test
    @DisplayName("validate flags malformed specification JSON")
    void validateMalformedSpec() {
        List<String> errors = startedRegistry().validate(TYPE_A, "{ not json");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Malformed specification JSON for rule type '" + TYPE_A + "'"));
    }

    /**
     * validate returns no error for a specification that satisfies the schema (the
     * {@code messages.isEmpty()} arm).
     */
    @Test
    @DisplayName("validate returns no error for a valid specification")
    void validateValidSpecEmpty() {
        List<String> errors = startedRegistry().validate(TYPE_A, "{\"rate\":5,\"label\":\"x\"}");
        assertTrue(errors.isEmpty());
    }

    /**
     * validate returns the sorted schema messages for a non-conforming specification
     * (the non-empty arm: two required properties missing).
     */
    @Test
    @DisplayName("validate returns the sorted schema errors for an invalid specification")
    void validateInvalidSpecErrors() {
        List<String> errors = startedRegistry().validate(TYPE_A, "{}");
        assertFalse(errors.isEmpty());
        List<String> sorted = new ArrayList<>(errors);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, errors);
    }

    /**
     * schemaFor returns null for a null type (the null arm of the ternary).
     */
    @Test
    @DisplayName("schemaFor returns null for a null type")
    void schemaForNullReturnsNull() {
        assertNull(startedRegistry().schemaFor(null));
    }

    /**
     * schemaFor returns null for an unknown type (non-null arm, absent key).
     */
    @Test
    @DisplayName("schemaFor returns null for an unknown type")
    void schemaForUnknownReturnsNull() {
        assertNull(startedRegistry().schemaFor("NOPE"));
    }

    /**
     * schemaFor returns the raw schema for a registered type (non-null arm, present
     * key).
     */
    @Test
    @DisplayName("schemaFor returns the raw schema for a registered type")
    void schemaForKnownReturnsRaw() {
        assertEquals(SCHEMA_B, startedRegistry().schemaFor(TYPE_B));
    }

    /**
     * allSchemas exposes an unmodifiable snapshot of the registered raw schemas.
     */
    @Test
    @DisplayName("allSchemas is an unmodifiable snapshot")
    void allSchemasUnmodifiable() {
        Map<String, String> schemas = startedRegistry().allSchemas();
        assertEquals(SCHEMA_A, schemas.get(TYPE_A));
        assertThrows(UnsupportedOperationException.class, () -> schemas.put("X", "{}"));
    }

    /**
     * registeredTypes exposes an unmodifiable snapshot of the registered types.
     */
    @Test
    @DisplayName("registeredTypes is an unmodifiable snapshot")
    void registeredTypesUnmodifiable() {
        Set<String> types = startedRegistry().registeredTypes();
        assertTrue(types.contains(TYPE_A));
        assertThrows(UnsupportedOperationException.class, () -> types.add("X"));
    }
}
