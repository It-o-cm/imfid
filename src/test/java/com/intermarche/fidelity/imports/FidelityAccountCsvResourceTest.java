package com.intermarche.fidelity.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityProgramSetting;
import com.intermarche.fidelity.imports.ImporterCsvResource.LineData;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
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
 * Plain unit coverage for {@link FidelityAccountCsvResource}: the bulk CSV import of loyalty
 * cards (§14, §18, §33.1). The class holds no clock, no {@code BigDecimal} arithmetic and no
 * protected division, so it needs neither a {@code DateTimeProvider} nor a {@code compareTo}
 * fixture — its branches are the create/update dispatch, the generated-versus-provided card
 * number, the field-diff guard and the EAN-13 card-number synthesis (§33.1).
 * <p>
 * Fully isolated: the class is instantiated bare and the collaborators it reaches through
 * inherited machinery are Mockito static mocks in try-with-resources — the Panache finders
 * ({@code PanacheEntityBase.list/count/findById}, {@code FidelityAccount.findByCardNumber}),
 * the program-setting reader ({@code FidelityProgramSetting.getString}) and the session
 * ({@code Panache.getEntityManager}) so no {@code persist()} ever hits an absent database. The
 * pure helpers (field diff, card-number body, EAN-13 check digit) are asserted directly by
 * reflection so every guard leg is exercised in isolation (§29, §29.6).
 */
class FidelityAccountCsvResourceTest {

    /**
     * The default reserved card prefix returned by the mocked program setting (§33.1).
     */
    private static final String PREFIX = "29";

    /**
     * The system under test, a bare resource with no CDI wiring.
     */
    private final FidelityAccountCsvResource resource = new FidelityAccountCsvResource();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Header names of the fixture rows, in cell order.
     */
    private static final String[] TEST_HEADER = {"CARD_NUMBER", "STATUS", "ACTIVATED_AT", "LAST_USED_AT", "TRANSFERRED_TO_CARD"};

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
     * Builds a fidelity account with its imported fields set.
     *
     * @param card        The card number key.
     * @param status      The account status.
     * @param activatedAt The activation instant, may be null.
     * @param lastUsedAt  The last-use instant, may be null.
     * @param transferred The successor card, may be null.
     * @return The populated account.
     */
    private FidelityAccount account(String card, AccountStatus status, LocalDateTime activatedAt,
            LocalDateTime lastUsedAt, String transferred) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = card;
        account.status = status;
        account.activatedAt = activatedAt;
        account.lastUsedAt = lastUsedAt;
        account.transferredToCard = transferred;
        return account;
    }

    /**
     * Reflectively invokes a declared method of the resource, unwrapping reflective failures.
     *
     * @param name  The method name.
     * @param types The parameter types.
     * @param args  The arguments.
     * @return The method result, or null for a void method.
     */
    private Object invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method method = FidelityAccountCsvResource.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(resource, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
    }

    // --------------------------------------------------
    // importAccounts(): delegation to the staged importer
    // --------------------------------------------------

    /**
     * A header-only stream drives the delegation to {@code importCsvStream} without touching the
     * transaction machinery: the header row is skipped, no chunk is processed and the report
     * carries zero counts.
     */
    @Test
    @DisplayName("importAccounts(): a header-only stream yields an empty report")
    void importAccountsHeaderOnly() {
        InputStream in = new ByteArrayInputStream(
                "CARD_NUMBER|STATUS|ACTIVATED_AT|LAST_USED_AT|TRANSFERRED_TO_CARD\n".getBytes(StandardCharsets.UTF_8));
        Response response = resource.importAccounts(in);
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    // --------------------------------------------------
    // processChunkWithFallback(): the bulk pre-fetch
    // --------------------------------------------------

    /**
     * Both legs true: a non-empty chunk with distinct keys queries the persisted accounts and
     * keys them by card number.
     */
    @Test
    @DisplayName("processChunkWithFallback(): a non-empty chunk keys existing accounts by card")
    void processChunkBothPresent() {
        FidelityAccount existing = account("CARD1", AccountStatus.ACTIVE, null, null, null);
        Set<String> targets = new HashSet<>(Set.of("CARD1"));
        List<PanacheEntityBase> found = new ArrayList<>();
        found.add(existing);
        int[] counters = {0, 0};
        List<String> errors = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("cardNumber in ?1", targets)).thenReturn(found);
            Map<String, Object> map = resource.processChunkWithFallback(
                    List.of(line("CARD1", "ACTIVE", "", "", "")), targets, counters, errors);
            assertEquals(1, map.size());
            assertSame(existing, map.get("CARD1"));
        }
    }

    /**
     * First leg false: an empty line list short-circuits before the second leg, returning an
     * empty context with no query.
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
     * Second leg false: a non-empty line list with no target key returns an empty context with
     * no query.
     */
    @Test
    @DisplayName("processChunkWithFallback(): no target key returns an empty context")
    void processChunkEmptyTargets() {
        int[] counters = {0, 0};
        Map<String, Object> map = resource.processChunkWithFallback(
                List.of(line("CARD1", "ACTIVE", "", "", "")), new HashSet<>(), counters, new ArrayList<>());
        assertTrue(map.isEmpty());
    }

    // --------------------------------------------------
    // findEntityForLine(): the 1-by-1 fallback lookup
    // --------------------------------------------------

    /**
     * The empty-key arm: a blank first column resolves to null so the row becomes a creation,
     * without any lookup.
     */
    @Test
    @DisplayName("findEntityForLine(): a blank key resolves to null")
    void findEntityForLineBlank() {
        assertNull(resource.findEntityForLine(line("", "ACTIVE", "", "", "")));
    }

    /**
     * The provided-key arm: a card number is looked up by {@code findByCardNumber}.
     */
    @Test
    @DisplayName("findEntityForLine(): a provided key is looked up by card number")
    void findEntityForLineProvided() {
        FidelityAccount existing = account("CARD1", AccountStatus.ACTIVE, null, null, null);
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber("CARD1")).thenReturn(existing);
            assertSame(existing, resource.findEntityForLine(line("CARD1", "ACTIVE", "", "", "")));
        }
    }

    // --------------------------------------------------
    // processLineLogic(): create / update dispatch
    // --------------------------------------------------

    /**
     * The empty-code create arm: a blank key generates a fresh EAN-13 card number, defaults the
     * blank status to ACTIVE and persists a new account, bumping the created counter.
     */
    @Test
    @DisplayName("processLineLogic(): a blank code creates an account with a generated card")
    void processLineCreateGenerated() {
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class);
             MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            settings.when(() -> FidelityProgramSetting.getString(FidelityProgramSetting.KEY_CARD_PREFIX, PREFIX))
                    .thenReturn(PREFIX);
            panache.when(PanacheEntityBase::count).thenReturn(0L);
            accounts.when(() -> FidelityAccount.findByCardNumber(anyString())).thenReturn(null);
            resource.processLineLogic(line("", "", "", "", ""), new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            Mockito.verify(em).persist(captor.capture());
            FidelityAccount created = (FidelityAccount) captor.getValue();
            assertEquals(13, created.cardNumber.length());
            assertTrue(created.cardNumber.startsWith(PREFIX));
            assertEquals(AccountStatus.ACTIVE, created.status);
            assertNull(created.activatedAt);
            assertNull(created.lastUsedAt);
            assertNull(created.transferredToCard);
        }
    }

    /**
     * The provided-code create arm: a keyed row absent from the context map creates an account
     * keyed on the given card, feeds its parsed fields and honours the parsed status.
     */
    @Test
    @DisplayName("processLineLogic(): a provided code absent from the map creates an account")
    void processLineCreateProvided() {
        int[] counters = {0, 0};
        EntityManager em = Mockito.mock(EntityManager.class);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            resource.processLineLogic(
                    line("CARD9", "RESILIATED", "2026-01-02T03:04", "2026-05-06T07:08", "SUCC9"),
                    new HashMap<>(), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            Mockito.verify(em).persist(captor.capture());
            FidelityAccount created = (FidelityAccount) captor.getValue();
            assertEquals("CARD9", created.cardNumber);
            assertEquals(AccountStatus.RESILIATED, created.status);
            assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4), created.activatedAt);
            assertEquals(LocalDateTime.of(2026, 5, 6, 7, 8), created.lastUsedAt);
            assertEquals("SUCC9", created.transferredToCard);
        }
    }

    /**
     * The changed-update arm: an existing account whose fields differ is re-read by id, fed the
     * incoming values and counted as updated; the created counter stays put and nothing is
     * persisted.
     */
    @Test
    @DisplayName("processLineLogic(): a changed existing account is updated")
    void processLineUpdateChanged() {
        FidelityAccount existing = account("CARD9", AccountStatus.RESILIATED, null, null, null);
        existing.id = 5L;
        FidelityAccount reread = account("CARD9", AccountStatus.RESILIATED, null, null, null);
        reread.id = 5L;
        Map<String, Object> map = new HashMap<>();
        map.put("CARD9", existing);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(5L)).thenReturn(reread);
            resource.processLineLogic(line("CARD9", "ACTIVE", "", "", ""), map, counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals(AccountStatus.ACTIVE, reread.status);
        }
    }

    /**
     * The unchanged arm: an existing account matching the row leaves both counters untouched and
     * performs no re-read.
     */
    @Test
    @DisplayName("processLineLogic(): an unchanged existing account is a no-op")
    void processLineUpdateUnchanged() {
        FidelityAccount existing = account("CARD9", AccountStatus.ACTIVE, null, null, null);
        existing.id = 5L;
        Map<String, Object> map = new HashMap<>();
        map.put("CARD9", existing);
        int[] counters = {0, 0};
        resource.processLineLogic(line("CARD9", "ACTIVE", "", "", ""), map, counters);
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    // --------------------------------------------------
    // hasChanged(): each leg of the field-diff guard (§29.6)
    // --------------------------------------------------

    /**
     * Invokes the private diff guard for a row against an account.
     *
     * @param data    The parsed row.
     * @param account The persisted account.
     * @return true when the account must be updated.
     */
    private boolean hasChanged(LineData data, FidelityAccount account) {
        return (boolean) invoke("hasChanged", new Class<?>[]{LineData.class, FidelityAccount.class}, data, account);
    }

    /**
     * First leg true: the status differs while the ternary reads a non-null parsed status.
     */
    @Test
    @DisplayName("hasChanged(): a differing status forces an update")
    void hasChangedStatus() {
        FidelityAccount existing = account("CARD", AccountStatus.RESILIATED, null, null, null);
        assertTrue(hasChanged(line("CARD", "ACTIVE", "", "", ""), existing));
    }

    /**
     * Second leg true: the status matches but the activation instant differs.
     */
    @Test
    @DisplayName("hasChanged(): a differing activation instant forces an update")
    void hasChangedActivatedAt() {
        FidelityAccount existing = account("CARD", AccountStatus.ACTIVE,
                LocalDateTime.of(2026, 1, 1, 0, 0), null, null);
        assertTrue(hasChanged(line("CARD", "ACTIVE", "", "", ""), existing));
    }

    /**
     * Third leg true: status and activation match but the last-use instant differs.
     */
    @Test
    @DisplayName("hasChanged(): a differing last-use instant forces an update")
    void hasChangedLastUsedAt() {
        FidelityAccount existing = account("CARD", AccountStatus.ACTIVE,
                null, LocalDateTime.of(2026, 2, 2, 0, 0), null);
        assertTrue(hasChanged(line("CARD", "ACTIVE", "", "", ""), existing));
    }

    /**
     * Fourth leg true: the first three match but the successor card differs.
     */
    @Test
    @DisplayName("hasChanged(): a differing successor card forces an update")
    void hasChangedTransferred() {
        FidelityAccount existing = account("CARD", AccountStatus.ACTIVE, null, null, "OLD");
        assertTrue(hasChanged(line("CARD", "ACTIVE", "", "", ""), existing));
    }

    /**
     * Every leg false: an account matching the row is left untouched.
     */
    @Test
    @DisplayName("hasChanged(): a fully matching account is unchanged")
    void hasChangedNone() {
        FidelityAccount existing = account("CARD", AccountStatus.ACTIVE, null, null, null);
        assertFalse(hasChanged(line("CARD", "ACTIVE", "", "", ""), existing));
    }

    /**
     * The null arm of the status ternary: a blank status column defaults to ACTIVE, which matches
     * an ACTIVE account, so nothing changes.
     */
    @Test
    @DisplayName("hasChanged(): a blank status defaults to ACTIVE and matches")
    void hasChangedBlankStatusDefaultsActive() {
        FidelityAccount existing = account("CARD", AccountStatus.ACTIVE, null, null, null);
        assertFalse(hasChanged(line("CARD", "", "", "", ""), existing));
    }

    // --------------------------------------------------
    // generateCardNumber(): EAN-13 synthesis (§33.1)
    // --------------------------------------------------

    /**
     * The free-candidate arm: the first synthesized number is free and returned straight away,
     * carrying the prefix and a valid EAN-13 check digit.
     */
    @Test
    @DisplayName("generateCardNumber(): a free first candidate is returned")
    void generateCardNumberFree() {
        try (MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            settings.when(() -> FidelityProgramSetting.getString(FidelityProgramSetting.KEY_CARD_PREFIX, PREFIX))
                    .thenReturn(PREFIX);
            panache.when(PanacheEntityBase::count).thenReturn(0L);
            accounts.when(() -> FidelityAccount.findByCardNumber(anyString())).thenReturn(null);
            String card = (String) invoke("generateCardNumber", new Class<?>[]{});
            assertEquals(13, card.length());
            assertTrue(card.startsWith(PREFIX));
            int body = (int) invoke("ean13CheckDigit", new Class<?>[]{String.class}, card.substring(0, 12));
            assertEquals(card.charAt(12) - '0', body);
        }
    }

    /**
     * The taken-candidate arm: the first synthesized number collides and the loop advances the
     * sequence to the next free one.
     */
    @Test
    @DisplayName("generateCardNumber(): a taken candidate advances to the next")
    void generateCardNumberLoops() {
        FidelityAccount taken = account("29", AccountStatus.ACTIVE, null, null, null);
        try (MockedStatic<FidelityProgramSetting> settings = Mockito.mockStatic(FidelityProgramSetting.class);
             MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            settings.when(() -> FidelityProgramSetting.getString(FidelityProgramSetting.KEY_CARD_PREFIX, PREFIX))
                    .thenReturn(PREFIX);
            panache.when(PanacheEntityBase::count).thenReturn(0L);
            accounts.when(() -> FidelityAccount.findByCardNumber(anyString())).thenReturn(taken, (FidelityAccount) null);
            String card = (String) invoke("generateCardNumber", new Class<?>[]{});
            String body = (String) invoke("buildBody", new Class<?>[]{String.class, long.class}, PREFIX, 2L);
            int check = (int) invoke("ean13CheckDigit", new Class<?>[]{String.class}, body);
            assertEquals(body + check, card);
        }
    }

    // --------------------------------------------------
    // buildBody(): padding and truncation
    // --------------------------------------------------

    /**
     * The nominal path: a short prefix and a small sequence yield a left-padded 12-digit body,
     * both truncation guards false.
     */
    @Test
    @DisplayName("buildBody(): a small sequence is left-padded to 12 digits")
    void buildBodyNominal() {
        String body = (String) invoke("buildBody", new Class<?>[]{String.class, long.class}, PREFIX, 1L);
        assertEquals("290000000001", body);
    }

    /**
     * The sequence-overflow guard true (body-overflow false): a sequence wider than its field is
     * truncated to its low digits, keeping the body at 12.
     */
    @Test
    @DisplayName("buildBody(): an oversized sequence is truncated to its low digits")
    void buildBodySequenceTruncated() {
        String body = (String) invoke("buildBody", new Class<?>[]{String.class, long.class}, PREFIX, 12345678901L);
        assertEquals("292345678901", body);
    }

    /**
     * The body-overflow guard true (sequence-overflow false): an over-long prefix pushes the body
     * past 12 digits and it is truncated to its low 12.
     */
    @Test
    @DisplayName("buildBody(): an over-long prefix truncates the body to 12 digits")
    void buildBodyBodyTruncated() {
        String body = (String) invoke("buildBody", new Class<?>[]{String.class, long.class}, "2999999999999", 5L);
        assertEquals("999999999995", body);
    }

    // --------------------------------------------------
    // ean13CheckDigit(): weighting and completion
    // --------------------------------------------------

    /**
     * A known EAN-13 body exercises both ternary arms (even index weight 1, odd index weight 3)
     * and completes the weighted sum to the next multiple of ten.
     */
    @Test
    @DisplayName("ean13CheckDigit(): a known body yields its check digit")
    void ean13CheckDigitKnown() {
        int check = (int) invoke("ean13CheckDigit", new Class<?>[]{String.class}, "400638133393");
        assertEquals(1, check);
    }

    /**
     * A body whose weighted sum is already a multiple of ten completes to a zero check digit.
     */
    @Test
    @DisplayName("ean13CheckDigit(): a multiple-of-ten sum yields a zero check digit")
    void ean13CheckDigitZero() {
        int check = (int) invoke("ean13CheckDigit", new Class<?>[]{String.class}, "000000000000");
        assertEquals(0, check);
    }
}
