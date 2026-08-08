package com.intermarche.fidelity.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.imports.ImporterCsvResource.LineData;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link FidelityAdjustmentCsvResource}: the bulk CSV import of signed
 * {@code ADJUSTMENT} movements (§18, §32.1). Each row seeds or corrects a balance, keyed by its
 * reference as the natural idempotency key {@code (ticketRef, ADJUSTMENT, null)} (I8); a re-import
 * is a no-op because a movement is immutable once written.
 * <p>
 * Fully isolated: the resource is instantiated bare and every collaborator it reaches through the
 * inherited machinery is a Mockito static mock in a try-with-resources — the Panache finders
 * ({@code PanacheEntityBase.list/find}), the natural-key lookup ({@code FidelityMovement.findByNaturalKey})
 * and the session ({@code Panache.getEntityManager}) so no {@code persist()} ever hits an absent
 * database. The class holds no clock (the {@code earnYear} is derived from the parsed
 * {@code movementDate}, never from {@code now()}), so it needs no {@code DateTimeProvider}; the
 * fiscal-year boundary is exercised on the two dates straddling the civil border (§30.3). Every
 * {@code BigDecimal} is asserted by {@code compareTo} at scale 2 / HALF_UP (§30.5).
 * <p>
 * Branches covered: the {@code &&} pre-fetch guard (both legs, each falsified in turn), the
 * already-imported short-circuit, the four mandatory-field guards (both arms each), the
 * unknown-card guard reached through both the blank-card and the absent-account paths, and the
 * {@code cardNumber == null} guard of {@code lockAccount} (§29, §29.6).
 */
class FidelityAdjustmentCsvResourceTest {

    /**
     * The system under test, a bare resource with no CDI wiring.
     */
    private final FidelityAdjustmentCsvResource resource = new FidelityAdjustmentCsvResource();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Builds a parsed CSV row whose code is the trimmed first column.
     *
     * @param parts The row's raw columns.
     * @return The line carrying line number 2 and the trimmed key.
     */
    private LineData line(String... parts) {
        return new LineData(2, parts[0].trim(), parts);
    }

    /**
     * Builds an adjustment movement keyed by its reference.
     *
     * @param ticketRef The movement reference (natural key).
     * @return The populated movement.
     */
    private FidelityMovement movement(String ticketRef) {
        FidelityMovement movement = new FidelityMovement();
        movement.type = MovementType.ADJUSTMENT;
        movement.ticketRef = ticketRef;
        return movement;
    }

    /**
     * Builds a fidelity account with a starting balance at scale 2.
     *
     * @param card    The card number key.
     * @param balance The starting balance in euro.
     * @return The populated account.
     */
    private FidelityAccount account(String card, String balance) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = card;
        account.balance = new BigDecimal(balance);
        return account;
    }

    /**
     * Stubs the per-card locked lookup so {@code find("cardNumber", card)} resolves under the
     * pessimistic write lock to the given account.
     *
     * @param panache The active {@link PanacheEntityBase} static mock.
     * @param card    The card number the source will look up.
     * @param account The account the locked query yields, may be null.
     */
    @SuppressWarnings("unchecked")
    private void stubLockedAccount(MockedStatic<PanacheEntityBase> panache, String card, FidelityAccount account) {
        PanacheQuery<FidelityAccount> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.withLock(LockModeType.PESSIMISTIC_WRITE)).thenReturn(query);
        Mockito.when(query.firstResult()).thenReturn(account);
        panache.when(() -> PanacheEntityBase.find("cardNumber", card)).thenReturn(query);
    }

    // --------------------------------------------------
    // importAdjustments(): delegation to the staged importer
    // --------------------------------------------------

    /**
     * A header-only stream drives the delegation to {@code importCsvStream} without touching the
     * transaction machinery: the header is skipped, no chunk is processed and the report is empty.
     */
    @Test
    @DisplayName("importAdjustments(): a header-only stream yields an empty report")
    void importAdjustmentsHeaderOnly() {
        InputStream in = new ByteArrayInputStream(
                "reference|cardNumber|amount|movementDate|reason\n".getBytes(StandardCharsets.UTF_8));
        Response response = resource.importAdjustments(in);
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    // --------------------------------------------------
    // processChunkWithFallback(): the bulk pre-fetch (&& guard)
    // --------------------------------------------------

    /**
     * Both legs true: a non-empty chunk with a distinct key queries the persisted adjustments and
     * keys them by reference.
     */
    @Test
    @DisplayName("processChunkWithFallback(): a non-empty chunk keys existing adjustments by ref")
    void processChunkBothPresent() {
        FidelityMovement existing = movement("REF1");
        Set<String> targets = new HashSet<>(Set.of("REF1"));
        List<PanacheEntityBase> found = new ArrayList<>();
        found.add(existing);
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list(
                            "type = ?1 and ticketRef in ?2", MovementType.ADJUSTMENT, targets))
                    .thenReturn(found);
            Map<String, Object> map = resource.processChunkWithFallback(
                    List.of(line("REF1", "CARD1", "10.00", "2026-03-15", "seed")), targets, counters, errors);
            assertEquals(1, map.size());
            assertSame(existing, map.get("REF1"));
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
                List.of(), new HashSet<>(Set.of("REF1")), counters, new ArrayList<>());
        assertTrue(map.isEmpty());
    }

    /**
     * Second leg false: a non-empty line list with no target reference returns an empty context
     * with no query.
     */
    @Test
    @DisplayName("processChunkWithFallback(): no target reference returns an empty context")
    void processChunkEmptyTargets() {
        int[] counters = {0, 0};
        Map<String, Object> map = resource.processChunkWithFallback(
                List.of(line("REF1", "CARD1", "10.00", "2026-03-15", "seed")),
                new HashSet<>(), counters, new ArrayList<>());
        assertTrue(map.isEmpty());
    }

    // --------------------------------------------------
    // findEntityForLine(): the 1-by-1 fallback lookup
    // --------------------------------------------------

    /**
     * The reference is looked up by its natural key {@code (ref, ADJUSTMENT, null)} (I8).
     */
    @Test
    @DisplayName("findEntityForLine(): a row is looked up by its natural key")
    void findEntityForLineByNaturalKey() {
        FidelityMovement existing = movement("REF1");
        try (MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class)) {
            movements.when(() -> FidelityMovement.findByNaturalKey("REF1", MovementType.ADJUSTMENT, null))
                    .thenReturn(existing);
            assertSame(existing, resource.findEntityForLine(
                    line("REF1", "CARD1", "10.00", "2026-03-15", "seed")));
        }
    }

    // --------------------------------------------------
    // processLineLogic(): idempotency short-circuit and creation
    // --------------------------------------------------

    /**
     * The already-imported arm: a reference present in the context map is a no-op — a movement is
     * immutable (I8, §32.1) so neither counter moves and nothing is persisted.
     */
    @Test
    @DisplayName("processLineLogic(): an already-imported reference is a no-op")
    void processLineAlreadyImported() {
        Map<String, Object> map = new HashMap<>();
        map.put("REF1", movement("REF1"));
        int[] counters = {0, 0};
        resource.processLineLogic(line("REF1", "CARD1", "10.00", "2026-03-15", "seed"), map, counters);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * The success path (the four guards' non-null arms and the {@code cardNumber != null} arm): a
     * fresh reference on a known card scales the amount to the cent, inserts the movement under the
     * per-card lock, bumps the balance and counts one creation.
     */
    @Test
    @DisplayName("processLineLogic(): a fresh row inserts the movement and bumps the balance")
    void processLineCreate() {
        FidelityAccount account = account("CARD1", "5.00");
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class);
             MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            stubLockedAccount(panache, "CARD1", account);
            resource.processLineLogic(
                    line("REF1", "CARD1", "10.005", "2026-03-15", "seed"), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            Mockito.verify(em).persist(captor.capture());
            FidelityMovement created = (FidelityMovement) captor.getValue();
            assertSame(account, created.account);
            assertEquals(MovementType.ADJUSTMENT, created.type);
            assertEquals(0, new BigDecimal("10.01").compareTo(created.amount));
            assertEquals(LocalDate.of(2026, 3, 15), created.movementDate);
            assertEquals(2026, created.earnYear);
            assertNull(created.ruleCode);
            assertEquals("REF1", created.ticketRef);
            assertEquals("seed", created.reason);
            assertEquals(0, new BigDecimal("15.01").compareTo(account.balance));
        }
    }

    /**
     * A negative gesture (§32.1) rounds HALF_UP away from zero and debits the balance, proving the
     * signed scale-2 arithmetic in both directions.
     */
    @Test
    @DisplayName("processLineLogic(): a negative amount debits the balance to the cent")
    void processLineCreateNegative() {
        FidelityAccount account = account("CARD1", "5.00");
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class);
             MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            stubLockedAccount(panache, "CARD1", account);
            resource.processLineLogic(
                    line("REF2", "CARD1", "-2.005", "2025-12-31", "fix"), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, new BigDecimal("2.99").compareTo(account.balance));
        }
    }

    /**
     * The fiscal-year boundary (§30.3): the {@code earnYear} of a 1st-January gesture is the new
     * civil year, mirroring the previous 31st-December case above — the border is read from the
     * parsed date, never from the campaign clock.
     */
    @Test
    @DisplayName("processLineLogic(): earnYear follows the parsed date across the civil border")
    void processLineEarnYearBoundary() {
        FidelityAccount account = account("CARD1", "0.00");
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class);
             MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            stubLockedAccount(panache, "CARD1", account);
            resource.processLineLogic(
                    line("REF3", "CARD1", "1.00", "2026-01-01", "seed"), new HashMap<>(), counters);
            Mockito.verify(em).persist(captor.capture());
            assertEquals(2026, ((FidelityMovement) captor.getValue()).earnYear);
        }
    }

    /**
     * The {@code amount == null} arm: a blank amount column fails the row before any lock.
     */
    @Test
    @DisplayName("processLineLogic(): a blank amount is rejected")
    void processLineMissingAmount() {
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                resource.processLineLogic(
                        line("REF1", "CARD1", "", "2026-03-15", "seed"), new HashMap<>(), counters));
        assertEquals("amount is mandatory.", ex.getMessage());
    }

    /**
     * The {@code movementDate == null} arm: a malformed date fails the row after the amount passes.
     */
    @Test
    @DisplayName("processLineLogic(): a malformed date is rejected")
    void processLineMissingDate() {
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                resource.processLineLogic(
                        line("REF1", "CARD1", "10.00", "not-a-date", "seed"), new HashMap<>(), counters));
        assertEquals("movementDate is mandatory.", ex.getMessage());
    }

    /**
     * The {@code reason == null} arm: a blank reason fails the row — a reason is mandatory for an
     * ADJUSTMENT (§32.1).
     */
    @Test
    @DisplayName("processLineLogic(): a blank reason is rejected")
    void processLineMissingReason() {
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                resource.processLineLogic(
                        line("REF1", "CARD1", "10.00", "2026-03-15", ""), new HashMap<>(), counters));
        assertEquals("reason is mandatory for an ADJUSTMENT (§32.1).", ex.getMessage());
    }

    /**
     * The {@code account == null} arm reached with a provided card: the locked lookup yields no
     * account (unknown card) and the row fails, naming the card. Also covers the
     * {@code cardNumber != null} arm of {@code lockAccount}.
     */
    @Test
    @DisplayName("processLineLogic(): an unknown provided card is rejected")
    void processLineUnknownCard() {
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            stubLockedAccount(panache, "CARD1", null);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    resource.processLineLogic(
                            line("REF1", "CARD1", "10.00", "2026-03-15", "seed"), new HashMap<>(), counters));
            assertEquals("Card 'CARD1' not found.", ex.getMessage());
        }
    }

    /**
     * The {@code account == null} arm reached through the {@code cardNumber == null} arm of
     * {@code lockAccount}: a blank card column resolves to a null card, so no lookup runs and the
     * row fails on the null card.
     */
    @Test
    @DisplayName("processLineLogic(): a blank card resolves to no account")
    void processLineBlankCard() {
        int[] counters = {0, 0};
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                resource.processLineLogic(
                        line("REF1", "", "10.00", "2026-03-15", "seed"), new HashMap<>(), counters));
        assertEquals("Card 'null' not found.", ex.getMessage());
    }
}
