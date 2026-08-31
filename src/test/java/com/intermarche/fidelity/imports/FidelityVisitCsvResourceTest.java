package com.intermarche.fidelity.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.imports.ImporterCsvResource.LineData;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 * Plain unit coverage for {@link FidelityVisitCsvResource}: the bulk CSV import of visits as dated
 * header-only {@link EarnTrace}s with the {@code NO_MOVEMENT} status (§18, §29.1). A visit is a pass
 * at the register, not an earn: each card-carrying {@code ticket-closed} seeds a distinct civil day
 * at the program zone whether it earned or not, so the "4th visit" state is staged from dated headers
 * touching no account balance (§24.4). Each row is idempotent on its ticket reference (§29.4).
 * <p>
 * Fully isolated: the resource is instantiated bare and every collaborator reached through the
 * inherited staged machinery is a Mockito static mock in a try-with-resources — the bulk finder
 * ({@code PanacheEntityBase.list}), the single-row lookup ({@code EarnTrace.findByTicketRef}) and the
 * session ({@code Panache.getEntityManager}) so no {@code persist()} ever hits an absent database.
 * The class holds no clock — it reads {@code now()} nowhere and the {@code fiscalDate} is parsed from
 * the row, never from the campaign horloge — so it needs no {@code DateTimeProvider}; the fiscal
 * civil border is exercised on the two dates straddling it (31 Dec / 1 Jan, §30.3), each stored
 * verbatim.
 * <p>
 * Branches covered: the {@code &&} pre-fetch guard (both legs, each falsified in turn), the
 * already-recorded short-circuit (both arms), the {@code cardNumber == null} guard (both arms), the
 * {@code fiscalDate == null} guard (both arms) and the create path writing the {@code NO_MOVEMENT}
 * header (§29, §29.6).
 */
class FidelityVisitCsvResourceTest {

    /**
     * The system under test, a bare resource with no CDI wiring.
     */
    private final FidelityVisitCsvResource resource = new FidelityVisitCsvResource();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Header names of the fixture rows, in cell order.
     */
    private static final String[] TEST_HEADER = {"TICKET_REF", "CARD_NUMBER", "STORE_CODE", "FISCAL_DATE"};

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
     * Builds a visit trace keyed by its ticket reference.
     *
     * @param ticketRef The ticket reference (natural key).
     * @return The populated trace.
     */
    private EarnTrace trace(String ticketRef) {
        EarnTrace trace = new EarnTrace();
        trace.ticketRef = ticketRef;
        return trace;
    }

    // --------------------------------------------------
    // importVisits(): delegation to the staged importer
    // --------------------------------------------------

    /**
     * A header-only stream drives the delegation to {@code importCsvStream} without touching the
     * transaction machinery: the header is skipped, no chunk is processed and the report is empty.
     */
    @Test
    @DisplayName("importVisits(): a header-only stream yields an empty report")
    void importVisitsHeaderOnly() {
        InputStream in = new ByteArrayInputStream(
                "TICKET_REF|CARD_NUMBER|STORE_CODE|FISCAL_DATE\n".getBytes(StandardCharsets.UTF_8));
        Response response = resource.importVisits(in);
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    // --------------------------------------------------
    // processChunkWithFallback(): the bulk pre-fetch (&& guard)
    // --------------------------------------------------

    /**
     * Both legs true: a non-empty chunk with a distinct reference queries the persisted traces and
     * keys them by ticket reference.
     */
    @Test
    @DisplayName("processChunkWithFallback(): a non-empty chunk keys existing traces by ref")
    void processChunkBothPresent() {
        EarnTrace existing = trace("T1");
        Set<String> targets = new HashSet<>(Set.of("T1"));
        List<PanacheEntityBase> found = new ArrayList<>();
        found.add(existing);
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("ticketRef in ?1", targets)).thenReturn(found);
            Map<String, Object> map = resource.processChunkWithFallback(
                    List.of(line("T1", "CARD1", "STORE1", "2026-03-15")), targets, counters, errors);
            assertEquals(1, map.size());
            assertSame(existing, map.get("T1"));
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
                List.of(), new HashSet<>(Set.of("T1")), counters, new ArrayList<>());
        assertTrue(map.isEmpty());
    }

    /**
     * Second leg false: a non-empty line list with no target reference returns an empty context with
     * no query.
     */
    @Test
    @DisplayName("processChunkWithFallback(): no target reference returns an empty context")
    void processChunkEmptyTargets() {
        int[] counters = {0, 0};
        Map<String, Object> map = resource.processChunkWithFallback(
                List.of(line("T1", "CARD1", "STORE1", "2026-03-15")),
                new HashSet<>(), counters, new ArrayList<>());
        assertTrue(map.isEmpty());
    }

    // --------------------------------------------------
    // findEntityForLine(): the 1-by-1 fallback lookup
    // --------------------------------------------------

    /**
     * The reference is looked up by its ticket reference for the atomic fallback (§29.4).
     */
    @Test
    @DisplayName("findEntityForLine(): a row is looked up by its ticket reference")
    void findEntityForLineByTicketRef() {
        EarnTrace existing = trace("T1");
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class)) {
            traces.when(() -> EarnTrace.findByTicketRef("T1")).thenReturn(existing);
            assertSame(existing, resource.findEntityForLine(
                    line("T1", "CARD1", "STORE1", "2026-03-15")));
        }
    }

    // --------------------------------------------------
    // processLineLogic(): idempotency short-circuit and creation
    // --------------------------------------------------

    /**
     * The already-recorded arm: a reference present in the context map is a no-op — a visit is
     * written once (§29.4) so neither counter moves and nothing is persisted.
     */
    @Test
    @DisplayName("processLineLogic(): an already-recorded reference is a no-op")
    void processLineAlreadyRecorded() {
        Map<String, Object> map = new HashMap<>();
        map.put("T1", trace("T1"));
        int[] counters = {0, 0};
        resource.processLineLogic(line("T1", "CARD1", "STORE1", "2026-03-15"), map, counters);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * The success path (the {@code entityMap.get == null}, {@code cardNumber != null} and
     * {@code fiscalDate != null} arms): a fresh reference writes a header-only {@code NO_MOVEMENT}
     * trace with every context field copied out and counts one creation.
     */
    @Test
    @DisplayName("processLineLogic(): a fresh row writes a NO_MOVEMENT header")
    void processLineCreate() {
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(
                    line("T1", "CARD1", "STORE1", "2026-03-15"), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            Mockito.verify(em).persist(captor.capture());
            EarnTrace created = (EarnTrace) captor.getValue();
            assertEquals("T1", created.ticketRef);
            assertEquals("CARD1", created.cardNumber);
            assertEquals("STORE1", created.storeCode);
            assertEquals(LocalDate.of(2026, 3, 15), created.fiscalDate);
            assertEquals(EarnTrace.STATUS_NO_MOVEMENT, created.status);
        }
    }

    /**
     * The fiscal civil border, 31 December side (§30.3): the {@code fiscalDate} is stored verbatim
     * from the parsed row, never from the campaign clock — paired with the 1st-January case below.
     */
    @Test
    @DisplayName("processLineLogic(): a 31 Dec visit stores that fiscal date verbatim")
    void processLineFiscalBorderDecember() {
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(
                    line("T2", "CARD1", "STORE1", "2025-12-31"), new HashMap<>(), counters);
            Mockito.verify(em).persist(captor.capture());
            assertEquals(LocalDate.of(2025, 12, 31), ((EarnTrace) captor.getValue()).fiscalDate);
        }
    }

    /**
     * The fiscal civil border, 1st January side (§30.3): the day straddling the previous case is
     * likewise stored verbatim, proving the border is read from the parsed date.
     */
    @Test
    @DisplayName("processLineLogic(): a 1 Jan visit stores that fiscal date verbatim")
    void processLineFiscalBorderJanuary() {
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(
                    line("T3", "CARD1", "STORE1", "2026-01-01"), new HashMap<>(), counters);
            Mockito.verify(em).persist(captor.capture());
            assertEquals(LocalDate.of(2026, 1, 1), ((EarnTrace) captor.getValue()).fiscalDate);
        }
    }

    /**
     * The {@code cardNumber == null} arm: a blank card column fails the row — a visit is bound to a
     * card (§29.1).
     */
    @Test
    @DisplayName("processLineLogic(): a blank card number is rejected")
    void processLineMissingCard() {
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                resource.processLineLogic(
                        line("T1", "", "STORE1", "2026-03-15"), new HashMap<>(), counters));
        assertEquals("cardNumber is mandatory for a visit (§29.1).", ex.getMessage());
    }

    /**
     * The {@code fiscalDate == null} arm: a malformed fiscal date fails the row after the card passes.
     */
    @Test
    @DisplayName("processLineLogic(): a malformed fiscal date is rejected")
    void processLineMissingFiscalDate() {
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                resource.processLineLogic(
                        line("T1", "CARD1", "STORE1", "not-a-date"), new HashMap<>(), counters));
        assertEquals("fiscalDate is mandatory.", ex.getMessage());
    }

    /**
     * A blank store column resolves to a null {@code storeCode} while the row still succeeds — the
     * store is copied out but optional (§29.1); asserts the header is written with no store.
     */
    @Test
    @DisplayName("processLineLogic(): a blank store code writes a header with no store")
    void processLineBlankStore() {
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(
                    line("T1", "CARD1", "", "2026-03-15"), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            Mockito.verify(em).persist(captor.capture());
            assertNull(((EarnTrace) captor.getValue()).storeCode);
        }
    }
}
