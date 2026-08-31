package com.intermarche.fidelity.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.imports.ImporterCsvResource.LineData;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
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
 * Plain unit coverage for {@link FidelityActivationCsvResource}: the bulk CSV import of per-card
 * activations (§14, §18, §31.1). The class holds no clock, no {@code BigDecimal} arithmetic and no
 * protected division, so it needs neither a {@code DateTimeProvider} nor a {@code compareTo}
 * fixture — its branches are the empty-chunk and empty-target guards of the bulk pre-fetch, the
 * account/ruleCode/periodStart mandatory guards, the create-versus-update dispatch and the
 * two-leg period-end/mission diff guard (§29, §29.6).
 * <p>
 * Fully isolated: the resource is instantiated bare and the collaborators it reaches through the
 * inherited machinery are Mockito static mocks in try-with-resources — the bulk finder
 * ({@code PanacheEntityBase.list}), the single-row activation finder
 * ({@code PanacheEntityBase.find(...).firstResult()}), the account fallback lookup
 * ({@code FidelityAccount.findByCardNumber}) and the session ({@code Panache.getEntityManager}) so
 * no {@code persist()} ever hits an absent database. Both arms of the private {@code resolveAccount}
 * dispatch (context map present versus absent) are exercised through the public entry points.
 */
class FidelityActivationCsvResourceTest {

    /**
     * The context key under which the bulk pre-fetch stores the accounts map (mirrors the private
     * constant of the resource).
     */
    private static final String CTX_ACCOUNTS = "__CTX_ACCOUNTS__";

    /**
     * The system under test, a bare resource with no CDI wiring.
     */
    private final FidelityActivationCsvResource resource = new FidelityActivationCsvResource();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Header names of the fixture rows, in cell order.
     */
    private static final String[] TEST_HEADER = {"CARD_NUMBER", "RULE_CODE", "PERIOD_START", "PERIOD_END", "MISSION_DONE"};

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
     * Builds a fidelity account with the given card number.
     *
     * @param card The card number key.
     * @return The populated account.
     */
    private FidelityAccount account(String card) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = card;
        return account;
    }

    /**
     * Wraps an account in a fresh context map keyed on its card number under {@link #CTX_ACCOUNTS}.
     *
     * @param account The account to expose to the line logic.
     * @return The context map holding the single account.
     */
    private Map<String, Object> ctx(FidelityAccount account) {
        Map<String, FidelityAccount> accounts = new HashMap<>();
        accounts.put(account.cardNumber, account);
        Map<String, Object> map = new HashMap<>();
        map.put(CTX_ACCOUNTS, accounts);
        return map;
    }

    /**
     * Builds an existing activation with its business fields set.
     *
     * @param account     The owning account.
     * @param ruleCode    The activated rule code.
     * @param periodStart The period start.
     * @param periodEnd   The period end, may be null.
     * @param missionDone Whether the mission is completed.
     * @return The populated activation.
     */
    private FidelityActivation activation(FidelityAccount account, String ruleCode, LocalDate periodStart,
            LocalDate periodEnd, boolean missionDone) {
        FidelityActivation activation = new FidelityActivation();
        activation.account = account;
        activation.ruleCode = ruleCode;
        activation.periodStart = periodStart;
        activation.periodEnd = periodEnd;
        activation.missionDone = missionDone;
        return activation;
    }

    // --------------------------------------------------
    // importActivations(): delegation to the staged importer
    // --------------------------------------------------

    /**
     * A header-only stream drives the delegation to {@code importCsvStream} without touching the
     * transaction machinery: the header row is skipped, no chunk is processed and the report carries
     * zero counts.
     */
    @Test
    @DisplayName("importActivations(): a header-only stream yields an empty report")
    void importActivationsHeaderOnly() {
        InputStream in = new ByteArrayInputStream(
                "CARD_NUMBER|RULE_CODE|PERIOD_START|PERIOD_END|MISSION_DONE\n".getBytes(StandardCharsets.UTF_8));
        Response response = resource.importActivations(in);
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    // --------------------------------------------------
    // processChunkWithFallback(): the bulk pre-fetch
    // --------------------------------------------------

    /**
     * First guard true: an empty line list short-circuits before any accounts map is built,
     * returning an empty context with no {@link #CTX_ACCOUNTS} key and no query.
     */
    @Test
    @DisplayName("processChunkWithFallback(): an empty line list returns an empty context")
    void processChunkEmptyLines() {
        int[] counters = {0, 0};
        Map<String, Object> map = resource.processChunkWithFallback(
                List.of(), new HashSet<>(Set.of("CARD1")), counters, new ArrayList<>());
        assertTrue(map.isEmpty());
    }

    /**
     * First guard false, second guard false: a non-empty line list with no target key skips the
     * query but still exposes an empty accounts map under {@link #CTX_ACCOUNTS}.
     */
    @Test
    @DisplayName("processChunkWithFallback(): no target key exposes an empty accounts map")
    void processChunkEmptyTargets() {
        int[] counters = {0, 0};
        Map<String, Object> map = resource.processChunkWithFallback(
                List.of(line("CARD1", "ECOUPON_EARN", "2026-01-01", "", "false")), new HashSet<>(),
                counters, new ArrayList<>());
        assertEquals(1, map.size());
        @SuppressWarnings("unchecked")
        Map<String, FidelityAccount> accounts = (Map<String, FidelityAccount>) map.get(CTX_ACCOUNTS);
        assertTrue(accounts.isEmpty());
    }

    /**
     * First guard false, second guard true: a non-empty chunk with distinct keys queries the
     * persisted accounts and keys them by card number under {@link #CTX_ACCOUNTS}.
     */
    @Test
    @DisplayName("processChunkWithFallback(): a non-empty chunk keys existing accounts by card")
    void processChunkBothPresent() {
        FidelityAccount existing = account("CARD1");
        Set<String> targets = new HashSet<>(Set.of("CARD1"));
        List<PanacheEntityBase> found = new ArrayList<>();
        found.add(existing);
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("cardNumber in ?1", targets)).thenReturn(found);
            Map<String, Object> map = resource.processChunkWithFallback(
                    List.of(line("CARD1", "ECOUPON_EARN", "2026-01-01", "", "false")), targets, counters, errors);
            assertEquals(1, map.size());
            @SuppressWarnings("unchecked")
            Map<String, FidelityAccount> accounts = (Map<String, FidelityAccount>) map.get(CTX_ACCOUNTS);
            assertEquals(1, accounts.size());
            assertSame(existing, accounts.get("CARD1"));
        }
    }

    // --------------------------------------------------
    // findEntityForLine(): the 1-by-1 fallback lookup
    // --------------------------------------------------

    /**
     * The activation is resolved inside {@code processLineLogic}, so the 1-by-1 fallback carries no
     * pre-fetched entity and the lookup always returns null.
     */
    @Test
    @DisplayName("findEntityForLine(): always returns null")
    void findEntityForLineNull() {
        assertNull(resource.findEntityForLine(line("CARD1", "ECOUPON_EARN", "2026-01-01", "", "false")));
    }

    // --------------------------------------------------
    // processLineLogic(): mandatory guards
    // --------------------------------------------------

    /**
     * The account guard true, via the context-absent branch of {@code resolveAccount}: an empty
     * context map falls back to {@code findByCardNumber}, which returns null, so an unknown card
     * fails the row.
     */
    @Test
    @DisplayName("processLineLogic(): an unknown card (fallback lookup) fails the row")
    void processLineUnknownCardFallback() {
        int[] counters = {0, 0};
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber("CARD1")).thenReturn(null);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    resource.processLineLogic(
                            line("CARD1", "ECOUPON_EARN", "2026-01-01", "", "false"), new HashMap<>(), counters));
            assertEquals("Card 'CARD1' not found.", ex.getMessage());
        }
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * The account guard true, via the context-present branch of {@code resolveAccount}: a populated
     * context map that lacks the row's card resolves to null, so an unknown card fails the row.
     */
    @Test
    @DisplayName("processLineLogic(): an unknown card (context map) fails the row")
    void processLineUnknownCardContext() {
        int[] counters = {0, 0};
        Map<String, Object> map = ctx(account("OTHER"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                resource.processLineLogic(
                        line("CARD1", "ECOUPON_EARN", "2026-01-01", "", "false"), map, counters));
        assertEquals("Card 'CARD1' not found.", ex.getMessage());
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * The account guard false, the ruleCode guard true: a blank rule code fails the row.
     */
    @Test
    @DisplayName("processLineLogic(): a blank ruleCode fails the row")
    void processLineBlankRuleCode() {
        int[] counters = {0, 0};
        FidelityAccount acc = account("CARD1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                resource.processLineLogic(
                        line("CARD1", "  ", "2026-01-01", "", "false"), ctx(acc), counters));
        assertEquals("ruleCode is mandatory.", ex.getMessage());
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * The account and ruleCode guards false, the periodStart guard true: a malformed period start
     * parses to null and fails the row.
     */
    @Test
    @DisplayName("processLineLogic(): a missing periodStart fails the row")
    void processLineMissingPeriodStart() {
        int[] counters = {0, 0};
        FidelityAccount acc = account("CARD1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                resource.processLineLogic(
                        line("CARD1", "ECOUPON_EARN", "not-a-date", "", "false"), ctx(acc), counters));
        assertEquals("periodStart is mandatory.", ex.getMessage());
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    // --------------------------------------------------
    // processLineLogic(): create branch
    // --------------------------------------------------

    /**
     * The create branch: no activation matches {@code (account, ruleCode, periodStart)}, so a fresh
     * activation is built with the parsed fields, persisted and counted as created; the open period
     * leaves {@code periodEnd} null.
     */
    @Test
    @DisplayName("processLineLogic(): an absent activation is created and persisted")
    void processLineCreate() {
        int[] counters = {0, 0};
        FidelityAccount acc = account("CARD1");
        LocalDate start = LocalDate.of(2026, 3, 15);
        EntityManager em = Mockito.mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityActivation> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class);
             MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and ruleCode = ?2 and periodStart = ?3", acc, "ECOUPON_EARN", start))
                    .thenReturn(query);
            resource.processLineLogic(
                    line("CARD1", "ECOUPON_EARN", "2026-03-15", "", "true"), ctx(acc), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            Mockito.verify(em).persist(captor.capture());
            FidelityActivation created = (FidelityActivation) captor.getValue();
            assertSame(acc, created.account);
            assertEquals("ECOUPON_EARN", created.ruleCode);
            assertEquals(start, created.periodStart);
            assertNull(created.periodEnd);
            assertTrue(created.missionDone);
        }
    }

    // --------------------------------------------------
    // processLineLogic(): update branch — each leg (§29.6)
    // --------------------------------------------------

    /**
     * The update branch, first leg true: the matched activation's period end differs from the parsed
     * one, so it is re-fed and counted as updated with no persist.
     */
    @Test
    @DisplayName("processLineLogic(): a differing period end updates the activation")
    void processLineUpdatePeriodEnd() {
        int[] counters = {0, 0};
        FidelityAccount acc = account("CARD1");
        LocalDate start = LocalDate.of(2026, 3, 15);
        FidelityActivation existing = activation(acc, "ECOUPON_EARN", start, LocalDate.of(2026, 12, 31), false);
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityActivation> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(existing);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and ruleCode = ?2 and periodStart = ?3", acc, "ECOUPON_EARN", start))
                    .thenReturn(query);
            resource.processLineLogic(
                    line("CARD1", "ECOUPON_EARN", "2026-03-15", "", "false"), ctx(acc), counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertNull(existing.periodEnd);
            assertEquals(false, existing.missionDone);
        }
    }

    /**
     * The update branch, first leg false, second leg true: the period end matches but the mission
     * flag differs, so the activation is re-fed and counted as updated.
     */
    @Test
    @DisplayName("processLineLogic(): a differing mission flag updates the activation")
    void processLineUpdateMissionDone() {
        int[] counters = {0, 0};
        FidelityAccount acc = account("CARD1");
        LocalDate start = LocalDate.of(2026, 3, 15);
        LocalDate end = LocalDate.of(2026, 12, 31);
        FidelityActivation existing = activation(acc, "CHALLENGE_EARN", start, end, false);
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityActivation> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(existing);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and ruleCode = ?2 and periodStart = ?3", acc, "CHALLENGE_EARN", start))
                    .thenReturn(query);
            resource.processLineLogic(
                    line("CARD1", "CHALLENGE_EARN", "2026-03-15", "2026-12-31", "true"), ctx(acc), counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals(end, existing.periodEnd);
            assertTrue(existing.missionDone);
        }
    }

    /**
     * The update branch, both legs false: the matched activation already carries the parsed period
     * end and mission flag, so it is a no-op leaving both counters untouched.
     */
    @Test
    @DisplayName("processLineLogic(): an unchanged activation is a no-op")
    void processLineUpdateUnchanged() {
        int[] counters = {0, 0};
        FidelityAccount acc = account("CARD1");
        LocalDate start = LocalDate.of(2026, 3, 15);
        LocalDate end = LocalDate.of(2026, 12, 31);
        FidelityActivation existing = activation(acc, "CHALLENGE_EARN", start, end, true);
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityActivation> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(existing);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and ruleCode = ?2 and periodStart = ?3", acc, "CHALLENGE_EARN", start))
                    .thenReturn(query);
            resource.processLineLogic(
                    line("CARD1", "CHALLENGE_EARN", "2026-03-15", "2026-12-31", "true"), ctx(acc), counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
        }
    }
}
