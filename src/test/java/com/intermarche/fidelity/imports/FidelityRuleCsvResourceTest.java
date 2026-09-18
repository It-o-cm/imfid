package com.intermarche.fidelity.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.AdvantageType;
import com.intermarche.fidelity.domain.FidelityRule;
import com.intermarche.fidelity.imports.ImporterCsvResource.LineData;
import com.intermarche.fidelity.rule.EarnRuleRegistry;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link FidelityRuleCsvResource}: the bulk CSV import of administrable
 * earn rules (§12, §18). The class holds no clock and no protected division, so it needs neither a
 * {@code DateTimeProvider} nor a zero-denominator fixture; its only {@code BigDecimal} — the
 * per-card {@link FidelityRule#monthlyCapPerCard} — is asserted by {@code compareTo} (§29.6). Its
 * branches are the bulk pre-fetch compound guard, the type/specification validation ({@code type ==
 * null}, unknown factory, non-empty violations), the create/update dispatch, the checksum
 * optimization compound guard, the {@code validFrom} guard, the priority default ternary, the
 * specification reconstruction and the {@code active} default guard.
 * <p>
 * Fully isolated: the resource is instantiated bare, its {@link EarnRuleRegistry} is a Mockito mock
 * and the collaborators reached through the inherited machinery are Mockito static mocks in
 * try-with-resources — the Panache finders ({@code PanacheEntityBase.list/findById},
 * {@code FidelityRule.findByCode}) and the session ({@code Panache.getEntityManager}) — so no
 * {@code persist()} ever hits an absent database. The private helpers ({@code validateRule},
 * {@code feedRule}, {@code specificationOf}, {@code parseActive}, {@code computeIncomingChecksum},
 * {@code priorityOf}) are exercised through {@code processLineLogic} and by direct reflection, each
 * guard covered on both arms and each compound guard on every leg (§29, §29.6).
 */
class FidelityRuleCsvResourceTest {

    /**
     * The rule type used by every accepting fixture (a deployed factory).
     */
    private static final String TYPE = "BRAND_TIERED_EARN";

    /**
     * The system under test, a bare resource with no CDI wiring.
     */
    private final FidelityRuleCsvResource resource = new FidelityRuleCsvResource();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Header names of the fixture rows, in cell order.
     */
    private static final String[] TEST_HEADER = {"CODE", "TYPE", "LABEL", "VALID_FROM", "VALID_TO", "PRIORITY", "EXCLUSIVE", "MONTHLY_CAP_PER_CARD", "ACTIVE", "ADVANTAGE_TYPE", "ADVANTAGE_CATEGORY", "SPECIFICATION"};

    /**
     * Builds a header-bound CSV row from positional fixture cells: the header
     * maps {@link #TEST_HEADER} onto the cell positions and the first name is
     * the key column.
     *
     * @param parts The row's raw cells.
     * @return The line carrying line number 2 and the header-resolved key.
     */
    private LineData line(String... parts) {
        Map<String, Integer> header = new LinkedHashMap<>();
        for (int i = 0; i < TEST_HEADER.length; i++) {
            header.put(TEST_HEADER[i], i);
        }
        return new LineData(2, header, parts, TEST_HEADER[0]);
    }

    /**
     * Builds a nominal twelve-column rule row with a deployed type, a valid window and
     * the mandatory PRODUCT advantage type (RFP BO-03-03-25).
     *
     * @return The row {@code BRAND01|BRAND_TIERED_EARN|Brand boost|from|to|5|true|50.00|true|PRODUCT||spec}.
     */
    private LineData ruleLine() {
        return line("BRAND01", TYPE, "Brand boost", "2026-01-01T00:00", "2026-12-31T00:00",
                "5", "true", "50.00", "true", "PRODUCT", "", "{\"rate\":0.05}");
    }

    /**
     * Wraps the PRODUCT advantage type in a {@link PanacheQuery} mock, stubbed onto the
     * referential lookup of {@code feedRule} (RFP BO-03-03-25).
     *
     * @return The query mock resolving to an {@link AdvantageType}.
     */
    private PanacheQuery<?> advantageQuery() {
        PanacheQuery<?> query = Mockito.mock(PanacheQuery.class,
                invocation -> "firstResult".equals(invocation.getMethod().getName())
                        ? new AdvantageType() : Mockito.RETURNS_DEFAULTS.answer(invocation));
        return query;
    }

    /**
     * Builds a registry accepting any type and any specification, so the validation passes.
     *
     * @return A mock registry with {@code hasFactory} true and {@code validate} empty.
     */
    private EarnRuleRegistry acceptingRegistry() {
        EarnRuleRegistry reg = Mockito.mock(EarnRuleRegistry.class);
        Mockito.when(reg.hasFactory(Mockito.anyString())).thenReturn(true);
        Mockito.when(reg.validate(Mockito.anyString(), Mockito.any())).thenReturn(List.of());
        return reg;
    }

    /**
     * Reflectively invokes a declared method of the resource, unwrapping reflective failures.
     *
     * @param name  The method name.
     * @param types The parameter types.
     * @param args  The arguments.
     * @return The method result.
     */
    private Object invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method method = FidelityRuleCsvResource.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(resource, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
    }

    /**
     * Computes the incoming checksum of a row through the private helper.
     *
     * @param data The parsed row.
     * @return The incoming checksum the update guard compares against.
     */
    private int incomingChecksum(LineData data) {
        return (int) invoke("computeIncomingChecksum", new Class<?>[]{LineData.class}, data);
    }

    // --------------------------------------------------
    // importRules(): delegation to the staged importer
    // --------------------------------------------------

    /**
     * A header-only stream drives the delegation to {@code importCsvStream} without touching the
     * transaction machinery: the header row is skipped, no chunk is processed and the report carries
     * zero counts.
     */
    @Test
    @DisplayName("importRules(): a header-only stream yields an empty report")
    void importRulesHeaderOnly() {
        InputStream in = new ByteArrayInputStream(
                ("CODE|TYPE|LABEL|VALID_FROM|VALID_TO|PRIORITY|EXCLUSIVE|MONTHLY_CAP_PER_CARD|ACTIVE|ADVANTAGE_TYPE|ADVANTAGE_CATEGORY|SPECIFICATION\n")
                        .getBytes(StandardCharsets.UTF_8));
        Response response = resource.importRules(in);
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    // --------------------------------------------------
    // processChunkWithFallback(): the bulk pre-fetch (compound && guard, §29.6)
    // --------------------------------------------------

    /**
     * Both legs true: a non-empty chunk with a distinct key queries the persisted rules and keys them
     * by code.
     */
    @Test
    @DisplayName("processChunkWithFallback(): a non-empty chunk keys existing rules by code")
    void processChunkBothPresent() {
        FidelityRule existing = new FidelityRule();
        existing.code = "BRAND01";
        Set<String> targets = new HashSet<>(Set.of("BRAND01"));
        List<PanacheEntityBase> found = new ArrayList<>();
        found.add(existing);
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("code in ?1", targets)).thenReturn(found);
            Map<String, Object> map = resource.processChunkWithFallback(
                    List.of(ruleLine()), targets, counters, errors);
            assertEquals(1, map.size());
            assertSame(existing, map.get("BRAND01"));
        }
    }

    /**
     * First leg false: an empty line list short-circuits before the second leg, returning an empty
     * context with no query.
     */
    @Test
    @DisplayName("processChunkWithFallback(): an empty line list returns an empty context")
    void processChunkEmptyLines() {
        int[] counters = {0, 0};
        Map<String, Object> map = resource.processChunkWithFallback(
                List.of(), new HashSet<>(Set.of("BRAND01")), counters, new ArrayList<>());
        assertTrue(map.isEmpty());
    }

    /**
     * Second leg false: a non-empty line list with no target key returns an empty context with no
     * query.
     */
    @Test
    @DisplayName("processChunkWithFallback(): no target key returns an empty context")
    void processChunkEmptyTargets() {
        int[] counters = {0, 0};
        Map<String, Object> map = resource.processChunkWithFallback(
                List.of(ruleLine()), new HashSet<>(), counters, new ArrayList<>());
        assertTrue(map.isEmpty());
    }

    // --------------------------------------------------
    // findEntityForLine(): the 1-by-1 fallback lookup
    // --------------------------------------------------

    /**
     * A provided key is looked up by {@code findByCode}, returning the persisted rule.
     */
    @Test
    @DisplayName("findEntityForLine(): a code is looked up by findByCode")
    void findEntityForLineFound() {
        FidelityRule existing = new FidelityRule();
        existing.code = "BRAND01";
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class)) {
            rules.when(() -> FidelityRule.findByCode("BRAND01")).thenReturn(existing);
            assertSame(existing, resource.findEntityForLine(ruleLine()));
        }
    }

    /**
     * An absent code resolves to null so the row becomes a creation in the 1-by-1 fallback.
     */
    @Test
    @DisplayName("findEntityForLine(): an absent code resolves to null")
    void findEntityForLineAbsent() {
        try (MockedStatic<FidelityRule> rules = Mockito.mockStatic(FidelityRule.class)) {
            rules.when(() -> FidelityRule.findByCode("BRAND01")).thenReturn(null);
            assertNull(resource.findEntityForLine(ruleLine()));
        }
    }

    // --------------------------------------------------
    // validateRule(): type and specification guards (§12, I10)
    // --------------------------------------------------

    /**
     * The {@code type == null} arm: a blank type column resolves to a null type and is refused before
     * the registry is ever consulted.
     */
    @Test
    @DisplayName("processLineLogic(): a blank type is refused with 'missing rule type'")
    void validateRuleMissingType() {
        resource.registry = Mockito.mock(EarnRuleRegistry.class);
        LineData data = line("BRAND01", "", "Brand boost", "2026-01-01T00:00", "2026-12-31T00:00",
                "5", "true", "50.00", "true", "PRODUCT", "", "{\"rate\":0.05}");
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(data, new HashMap<>(), counters));
        assertEquals("missing rule type", ex.getMessage());
        Mockito.verifyNoInteractions(resource.registry);
    }

    /**
     * The unknown-factory arm: a non-null type with no deployed factory is refused with a descriptive
     * message and the specification is never validated.
     */
    @Test
    @DisplayName("processLineLogic(): an unknown type is refused with 'unknown rule type'")
    void validateRuleUnknownType() {
        EarnRuleRegistry reg = Mockito.mock(EarnRuleRegistry.class);
        Mockito.when(reg.hasFactory("MYSTERY")).thenReturn(false);
        resource.registry = reg;
        LineData data = line("BRAND01", "MYSTERY", "Brand boost", "2026-01-01T00:00", "2026-12-31T00:00",
                "5", "true", "50.00", "true", "PRODUCT", "", "{\"rate\":0.05}");
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(data, new HashMap<>(), counters));
        assertEquals("unknown rule type 'MYSTERY' (no factory deployed)", ex.getMessage());
        Mockito.verify(reg, Mockito.never()).validate(Mockito.anyString(), Mockito.any());
    }

    /**
     * The non-empty-violations arm: a deployed type whose specification fails its schema is refused,
     * the violations joined into the message.
     */
    @Test
    @DisplayName("processLineLogic(): an invalid specification is refused with the joined violations")
    void validateRuleInvalidSpecification() {
        EarnRuleRegistry reg = Mockito.mock(EarnRuleRegistry.class);
        Mockito.when(reg.hasFactory(TYPE)).thenReturn(true);
        Mockito.when(reg.validate(Mockito.eq(TYPE), Mockito.any()))
                .thenReturn(List.of("rate is required", "rate must be a number"));
        resource.registry = reg;
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(ruleLine(), new HashMap<>(), counters));
        assertEquals("specification invalid for type 'BRAND_TIERED_EARN': "
                + "rate is required; rate must be a number", ex.getMessage());
    }

    // --------------------------------------------------
    // processLineLogic(): create / update dispatch (compound || guard, §29.6)
    // --------------------------------------------------

    /**
     * The create arm: a code absent from the context map creates a rule, feeds every backbone field
     * (priority non-null branch, active {@code true} leg), bumps the created counter and persists it.
     * The {@code monthlyCapPerCard} is asserted by {@code compareTo} (§29.6).
     */
    @Test
    @DisplayName("processLineLogic(): a code absent from the map creates and persists a rule")
    void processLineCreate() {
        resource.registry = acceptingRegistry();
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class);
             MockedStatic<PanacheEntityBase> base = Mockito.mockStatic(PanacheEntityBase.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            base.when(() -> PanacheEntityBase.find("code", "PRODUCT")).thenReturn(advantageQuery());
            resource.processLineLogic(ruleLine(), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            Mockito.verify(em).persist(captor.capture());
            FidelityRule created = (FidelityRule) captor.getValue();
            assertEquals("BRAND01", created.code);
            assertEquals(TYPE, created.type);
            assertEquals("Brand boost", created.label);
            assertEquals(LocalDateTime.parse("2026-01-01T00:00"), created.validFrom);
            assertEquals(LocalDateTime.parse("2026-12-31T00:00"), created.validTo);
            assertEquals(5, created.priority);
            assertTrue(created.exclusive);
            assertEquals(0, created.monthlyCapPerCard.compareTo(new BigDecimal("50.00")));
            assertTrue(created.active);
            assertEquals("{\"rate\":0.05}", created.specification);
        }
    }

    /**
     * The create arm with blank optionals: a missing priority defaults to 0 (priority null branch of
     * {@code feedRule}), a missing validTo stays null, a missing cap stays null, a blank exclusive is
     * false and a blank active defaults to true (the {@code value == null} leg of {@code parseActive}).
     */
    @Test
    @DisplayName("processLineLogic(): blank optionals default priority to 0 and active to true")
    void processLineCreateBlankOptionals() {
        resource.registry = acceptingRegistry();
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        LineData data = line("BRAND02", TYPE, "Plain", "2026-01-01T00:00", "", "", "", "", "", "PRODUCT", "", "{}");
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class);
             MockedStatic<PanacheEntityBase> base = Mockito.mockStatic(PanacheEntityBase.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            base.when(() -> PanacheEntityBase.find("code", "PRODUCT")).thenReturn(advantageQuery());
            resource.processLineLogic(data, new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            Mockito.verify(em).persist(captor.capture());
            FidelityRule created = (FidelityRule) captor.getValue();
            assertEquals(0, created.priority);
            assertNull(created.validTo);
            assertNull(created.monthlyCapPerCard);
            assertTrue(created.active);
            assertTrue(!created.exclusive);
        }
    }

    /**
     * The {@code validFrom == null} arm of {@code feedRule}: a valid type and specification pass
     * validation, but a blank {@code validFrom} column raises before any counter moves or persist.
     */
    @Test
    @DisplayName("processLineLogic(): a blank validFrom is refused before create")
    void processLineCreateMissingValidFrom() {
        resource.registry = acceptingRegistry();
        int[] counters = {0, 0};
        LineData data = line("BRAND03", TYPE, "Plain", "", "2026-12-31T00:00",
                "5", "true", "50.00", "true", "PRODUCT", "", "{}");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(data, new HashMap<>(), counters));
        assertEquals("missing or malformed validFrom (ISO date-time)", ex.getMessage());
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * The update arm, first leg true: an existing rule with a null checksum is re-read by id, fed the
     * incoming values and counted as updated; the created counter stays put and nothing is persisted.
     */
    @Test
    @DisplayName("processLineLogic(): a null-checksum rule is re-read and updated")
    void processLineUpdateNullChecksum() {
        resource.registry = acceptingRegistry();
        FidelityRule existing = new FidelityRule();
        existing.code = "BRAND01";
        existing.id = 5L;
        existing.checksum = null;
        FidelityRule reread = new FidelityRule();
        reread.code = "BRAND01";
        reread.id = 5L;
        Map<String, Object> map = new HashMap<>();
        map.put("BRAND01", existing);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(5L)).thenReturn(reread);
            panache.when(() -> PanacheEntityBase.find("code", "PRODUCT")).thenReturn(advantageQuery());
            resource.processLineLogic(ruleLine(), map, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Brand boost", reread.label);
            assertEquals(0, reread.monthlyCapPerCard.compareTo(new BigDecimal("50.00")));
        }
    }

    /**
     * The update arm, first leg false and second leg true: an existing rule with a non-null checksum
     * that differs from the incoming one is re-read by id and counted as updated.
     */
    @Test
    @DisplayName("processLineLogic(): a differing checksum re-reads and updates the rule")
    void processLineUpdateDifferingChecksum() {
        resource.registry = acceptingRegistry();
        LineData data = ruleLine();
        FidelityRule existing = new FidelityRule();
        existing.code = "BRAND01";
        existing.id = 7L;
        existing.checksum = incomingChecksum(data) + 1;
        FidelityRule reread = new FidelityRule();
        reread.code = "BRAND01";
        reread.id = 7L;
        Map<String, Object> map = new HashMap<>();
        map.put("BRAND01", existing);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(reread);
            panache.when(() -> PanacheEntityBase.find("code", "PRODUCT")).thenReturn(advantageQuery());
            resource.processLineLogic(data, map, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals("Brand boost", reread.label);
        }
    }

    /**
     * Both legs false: an existing rule whose non-null checksum matches the incoming one is a no-op —
     * neither counter moves, nothing is re-read and nothing is persisted.
     */
    @Test
    @DisplayName("processLineLogic(): a matching checksum leaves the rule untouched")
    void processLineUnchanged() {
        resource.registry = acceptingRegistry();
        LineData data = ruleLine();
        FidelityRule existing = new FidelityRule();
        existing.code = "BRAND01";
        existing.id = 9L;
        existing.checksum = incomingChecksum(data);
        Map<String, Object> map = new HashMap<>();
        map.put("BRAND01", existing);
        int[] counters = {0, 0};
        resource.processLineLogic(data, map, counters);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    // --------------------------------------------------
    // specificationOf(): reconstruction of the last, delimiter-bearing column
    // --------------------------------------------------

    /**
     * The short-row arm: a row with fewer cells than the header-resolved SPECIFICATION index has no
     * specification cell, so the result is null.
     */
    @Test
    @DisplayName("specificationOf(): a row without the specification cell yields null")
    void specificationOfShortRow() {
        assertNull(invoke("specificationOf", new Class<?>[]{LineData.class}, line("a", "b")));
    }

    /**
     * The single-cell arm, non-null: exactly as many cells as the header returns the trimmed lone
     * specification.
     */
    @Test
    @DisplayName("specificationOf(): a single specification cell is trimmed")
    void specificationOfSingleColumn() {
        String[] parts = new String[10];
        parts[9] = "  {\"rate\":0.05}  ";
        assertEquals("{\"rate\":0.05}",
                invoke("specificationOf", new Class<?>[]{LineData.class}, line(parts)));
    }

    /**
     * The single-cell arm, null: exactly as many cells as the header with a null specification cell
     * yields null (the {@code single == null ? null} ternary).
     */
    @Test
    @DisplayName("specificationOf(): a null single specification cell yields null")
    void specificationOfSingleColumnNull() {
        String[] parts = new String[10];
        parts[9] = null;
        assertNull(invoke("specificationOf", new Class<?>[]{LineData.class}, line(parts)));
    }

    /**
     * The join arm: more cells than the header re-joins the tail with the pipe delimiter from the
     * SPECIFICATION index on, treating a null tail cell as an empty fragment (both legs of the
     * in-loop null ternary, and the {@code i > specIndex} guard on both arms).
     */
    @Test
    @DisplayName("specificationOf(): a delimiter-bearing specification is re-joined")
    void specificationOfJoinedTail() {
        String[] parts = new String[12];
        parts[9] = "{\"a\":1";
        parts[10] = null;
        parts[11] = "}";
        assertEquals("{\"a\":1||}",
                invoke("specificationOf", new Class<?>[]{LineData.class}, line(parts)));
    }

    // --------------------------------------------------
    // parseActive(): the active default guard (compound || guard, §29.6)
    // --------------------------------------------------

    /**
     * First leg true: a blank ACTIVE column resolves to a null value, so the rule defaults to active.
     */
    @Test
    @DisplayName("parseActive(): a blank column defaults to true")
    void parseActiveBlank() {
        String[] parts = new String[10];
        parts[8] = "";
        assertEquals(Boolean.TRUE,
                invoke("parseActive", new Class<?>[]{LineData.class}, line(parts)));
    }

    /**
     * First leg false, second leg true: an explicit {@code true} keeps the rule active.
     */
    @Test
    @DisplayName("parseActive(): an explicit true stays active")
    void parseActiveTrue() {
        String[] parts = new String[10];
        parts[8] = "true";
        assertEquals(Boolean.TRUE,
                invoke("parseActive", new Class<?>[]{LineData.class}, line(parts)));
    }

    /**
     * First leg false, second leg false: an explicit {@code false} deactivates the rule.
     */
    @Test
    @DisplayName("parseActive(): an explicit false deactivates")
    void parseActiveFalse() {
        String[] parts = new String[10];
        parts[8] = "false";
        assertEquals(Boolean.FALSE,
                invoke("parseActive", new Class<?>[]{LineData.class}, line(parts)));
    }

    // --------------------------------------------------
    // priorityOf(): the priority default ternary inside the checksum
    // --------------------------------------------------

    /**
     * Both arms of the {@code priority != null ? priority : 0} ternary hash identically: a blank
     * priority (null branch) and an explicit {@code 0} (non-null branch) yield the same incoming
     * checksum, all other fields equal.
     */
    @Test
    @DisplayName("computeIncomingChecksum(): a blank priority hashes as an explicit 0")
    void priorityOfBlankHashesAsZero() {
        LineData blank = line("BRAND01", TYPE, "L", "2026-01-01T00:00", "2026-12-31T00:00",
                "", "true", "50.00", "true", "PRODUCT", "", "{\"rate\":0.05}");
        LineData zero = line("BRAND01", TYPE, "L", "2026-01-01T00:00", "2026-12-31T00:00",
                "0", "true", "50.00", "true", "PRODUCT", "", "{\"rate\":0.05}");
        assertEquals(incomingChecksum(zero), incomingChecksum(blank));
    }
}
