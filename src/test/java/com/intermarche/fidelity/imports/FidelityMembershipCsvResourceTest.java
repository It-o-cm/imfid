package com.intermarche.fidelity.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityMembership;
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
 * Plain unit coverage for {@link FidelityMembershipCsvResource}: the bulk CSV import of community
 * memberships (§14, §18). The class holds no clock — the membership window dates come straight from
 * the CSV columns, never from {@code DateTimeProvider} — and no protected division, so it needs
 * neither a fixed instant nor a zero-denominator fixture; it carries no {@code BigDecimal} either.
 * Its branches are the bulk pre-fetch guards ({@code parsedLines.isEmpty()}, the per-row community
 * code guard, and the two {@code !isEmpty()} fetch guards), the three resolution guards of
 * {@code processLineLogic} (account / community / validFrom), the create-vs-update dispatch and its
 * {@code validTo}-changed leg, plus the context-vs-fallback guards of {@code resolveAccount} and
 * {@code resolveCommunity} (§29, §29.6).
 * <p>
 * Fully isolated: the resource is instantiated bare and every collaborator it reaches through the
 * inherited machinery is a Mockito static mock in try-with-resources — the Panache finders
 * ({@code PanacheEntityBase.list/find}, {@code FidelityAccount.findByCardNumber},
 * {@code FidelityCommunity.findByCode}) and the session ({@code Panache.getEntityManager}) — so no
 * {@code persist()} ever hits an absent database.
 */
class FidelityMembershipCsvResourceTest {

    /**
     * Context key under which {@code processChunkWithFallback} stores the accounts map; mirrors the
     * private constant of the resource.
     */
    private static final String CTX_ACCOUNTS = "__CTX_ACCOUNTS__";

    /**
     * Context key under which {@code processChunkWithFallback} stores the communities map; mirrors
     * the private constant of the resource.
     */
    private static final String CTX_COMMUNITIES = "__CTX_COMMUNITIES__";

    /**
     * The system under test, a bare resource with no CDI wiring.
     */
    private final FidelityMembershipCsvResource resource = new FidelityMembershipCsvResource();

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Header names of the fixture rows, in cell order.
     */
    private static final String[] TEST_HEADER = {"CARD_NUMBER", "COMMUNITY_CODE", "VALID_FROM", "VALID_TO"};

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
     * Builds an account with the given card number.
     *
     * @param cardNumber The card number.
     * @return The account.
     */
    private FidelityAccount account(String cardNumber) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = cardNumber;
        return account;
    }

    /**
     * Builds a community with the given code.
     *
     * @param code The community code.
     * @return The community.
     */
    private FidelityCommunity community(String code) {
        FidelityCommunity community = new FidelityCommunity();
        community.code = code;
        return community;
    }

    /**
     * Builds a context map bundling one account and one community, as the bulk pre-fetch would.
     *
     * @param account   The account keyed by its card number.
     * @param community The community keyed by its code.
     * @return The context map holding both entity maps.
     */
    private Map<String, Object> ctx(FidelityAccount account, FidelityCommunity community) {
        Map<String, FidelityAccount> accounts = new HashMap<>();
        accounts.put(account.cardNumber, account);
        Map<String, FidelityCommunity> communities = new HashMap<>();
        communities.put(community.code, community);
        Map<String, Object> map = new HashMap<>();
        map.put(CTX_ACCOUNTS, accounts);
        map.put(CTX_COMMUNITIES, communities);
        return map;
    }

    // --------------------------------------------------
    // importMemberships(): delegation to the staged importer
    // --------------------------------------------------

    /**
     * A header-only stream drives the delegation to {@code importCsvStream} without touching the
     * transaction machinery: the header row is skipped, no chunk is processed and the report carries
     * zero counts.
     */
    @Test
    @DisplayName("importMemberships(): a header-only stream yields an empty report")
    void importMembershipsHeaderOnly() {
        InputStream in = new ByteArrayInputStream(
                "CARD_NUMBER|COMMUNITY_CODE|VALID_FROM|VALID_TO\n".getBytes(StandardCharsets.UTF_8));
        Response response = resource.importMemberships(in);
        assertEquals(200, response.getStatus());
        assertEquals("{\"createdCount\":0, \"updatedCount\":0}", response.getEntity());
    }

    // --------------------------------------------------
    // processChunkWithFallback(): the bulk pre-fetch (§29.6)
    // --------------------------------------------------

    /**
     * First guard true: an empty line list short-circuits before any fetch, returning an empty
     * context.
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
     * First guard false, both fetch guards non-empty, community code guard true: a populated chunk
     * queries and keys the accounts by card number and the communities by code.
     */
    @Test
    @DisplayName("processChunkWithFallback(): a populated chunk keys accounts and communities")
    void processChunkPopulated() {
        FidelityAccount account = account("CARD1");
        FidelityCommunity comm = community("BABY");
        Set<String> targets = new HashSet<>(Set.of("CARD1"));
        List<PanacheEntityBase> foundAccounts = new ArrayList<>();
        foundAccounts.add(account);
        List<PanacheEntityBase> foundCommunities = new ArrayList<>();
        foundCommunities.add(comm);
        int[] counters = {0, 0};
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.list("cardNumber in ?1", targets)).thenReturn(foundAccounts);
            panache.when(() -> PanacheEntityBase.list("code in ?1", new HashSet<>(Set.of("BABY"))))
                    .thenReturn(foundCommunities);
            Map<String, Object> map = resource.processChunkWithFallback(
                    List.of(line("CARD1", "BABY", "2026-02-01", "2026-10-31")), targets, counters, new ArrayList<>());
            assertEquals(2, map.size());
            @SuppressWarnings("unchecked")
            Map<String, FidelityAccount> accounts = (Map<String, FidelityAccount>) map.get(CTX_ACCOUNTS);
            assertSame(account, accounts.get("CARD1"));
            @SuppressWarnings("unchecked")
            Map<String, FidelityCommunity> communities = (Map<String, FidelityCommunity>) map.get(CTX_COMMUNITIES);
            assertSame(comm, communities.get("BABY"));
        }
    }

    /**
     * First guard false, both fetch guards empty, community code guard false: a non-empty chunk with
     * no target card and a blank community code skips both queries — the community code is never
     * added — yet still returns the two (empty) entity maps.
     */
    @Test
    @DisplayName("processChunkWithFallback(): empty targets and a blank code skip both queries")
    void processChunkEmptyTargetsBlankCode() {
        int[] counters = {0, 0};
        Map<String, Object> map = resource.processChunkWithFallback(
                List.of(line("CARD1", "", "2026-02-01", "2026-10-31")), new HashSet<>(), counters, new ArrayList<>());
        assertEquals(2, map.size());
        @SuppressWarnings("unchecked")
        Map<String, FidelityAccount> accounts = (Map<String, FidelityAccount>) map.get(CTX_ACCOUNTS);
        assertTrue(accounts.isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, FidelityCommunity> communities = (Map<String, FidelityCommunity>) map.get(CTX_COMMUNITIES);
        assertTrue(communities.isEmpty());
    }

    // --------------------------------------------------
    // findEntityForLine(): the 1-by-1 fallback carries no entity
    // --------------------------------------------------

    /**
     * A membership row has no single keying entity, so the lookup always returns null.
     */
    @Test
    @DisplayName("findEntityForLine(): always returns null")
    void findEntityForLineNull() {
        assertNull(resource.findEntityForLine(line("CARD1", "BABY", "2026-02-01", "2026-10-31")));
    }

    // --------------------------------------------------
    // processLineLogic(): resolution guards (§29)
    // --------------------------------------------------

    /**
     * Account guard true, context path with a missing card: {@code resolveAccount} finds the accounts
     * map but no matching card, so the row fails with the card-not-found error.
     */
    @Test
    @DisplayName("processLineLogic(): a card absent from the context fails the row")
    void processLineAccountMissingInContext() {
        int[] counters = {0, 0};
        Map<String, Object> map = new HashMap<>();
        map.put(CTX_ACCOUNTS, new HashMap<String, FidelityAccount>());
        map.put(CTX_COMMUNITIES, new HashMap<String, FidelityCommunity>());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(line("CARD1", "BABY", "2026-02-01", "2026-10-31"), map, counters));
        assertEquals("Card 'CARD1' not found.", ex.getMessage());
        assertEquals(0, counters[0]);
        assertEquals(0, counters[1]);
    }

    /**
     * Account guard true, fallback path: with no accounts map in the context {@code resolveAccount}
     * falls back to {@code findByCardNumber}, which returns null, so the row fails.
     */
    @Test
    @DisplayName("processLineLogic(): a card unknown to the fallback lookup fails the row")
    void processLineAccountMissingInFallback() {
        int[] counters = {0, 0};
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber("CARD1")).thenReturn(null);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> resource.processLineLogic(
                            line("CARD1", "BABY", "2026-02-01", "2026-10-31"), new HashMap<>(), counters));
            assertEquals("Card 'CARD1' not found.", ex.getMessage());
        }
    }

    /**
     * Community guard true, blank code (so {@code communityCode == null}): the resolution returns
     * null and the row fails with the community-not-found error naming the null code.
     */
    @Test
    @DisplayName("processLineLogic(): a blank community code fails the row")
    void processLineBlankCommunityCode() {
        int[] counters = {0, 0};
        FidelityAccount account = account("CARD1");
        Map<String, Object> map = new HashMap<>();
        Map<String, FidelityAccount> accounts = new HashMap<>();
        accounts.put("CARD1", account);
        map.put(CTX_ACCOUNTS, accounts);
        map.put(CTX_COMMUNITIES, new HashMap<String, FidelityCommunity>());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(line("CARD1", "", "2026-02-01", "2026-10-31"), map, counters));
        assertEquals("Community 'null' not found.", ex.getMessage());
    }

    /**
     * Community guard true, fallback path with a non-null code: the accounts map resolves the card
     * but no communities map is present, so {@code resolveCommunity} falls back to
     * {@code findByCode}, which returns null and fails the row.
     */
    @Test
    @DisplayName("processLineLogic(): a community unknown to the fallback lookup fails the row")
    void processLineCommunityMissingInFallback() {
        int[] counters = {0, 0};
        FidelityAccount account = account("CARD1");
        Map<String, Object> map = new HashMap<>();
        Map<String, FidelityAccount> accounts = new HashMap<>();
        accounts.put("CARD1", account);
        map.put(CTX_ACCOUNTS, accounts);
        try (MockedStatic<FidelityCommunity> communities = Mockito.mockStatic(FidelityCommunity.class)) {
            communities.when(() -> FidelityCommunity.findByCode("BABY")).thenReturn(null);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> resource.processLineLogic(line("CARD1", "BABY", "2026-02-01", "2026-10-31"), map, counters));
            assertEquals("Community 'BABY' not found.", ex.getMessage());
        }
    }

    /**
     * validFrom guard true: account and community both resolve from the context, but a blank
     * {@code validFrom} column parses to null, so the row fails with the mandatory-validFrom error.
     */
    @Test
    @DisplayName("processLineLogic(): a blank validFrom fails the row")
    void processLineMissingValidFrom() {
        int[] counters = {0, 0};
        FidelityAccount account = account("CARD1");
        FidelityCommunity comm = community("BABY");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resource.processLineLogic(
                        line("CARD1", "BABY", "", "2026-10-31"), ctx(account, comm), counters));
        assertEquals("validFrom is mandatory.", ex.getMessage());
    }

    // --------------------------------------------------
    // processLineLogic(): create branch
    // --------------------------------------------------

    /**
     * Create branch (membership == null): no membership matches
     * {@code (account, community, validFrom)}, so a fresh one is built with the parsed endpoints,
     * counted as created and persisted. The blank {@code validTo} leaves the window open.
     */
    @Test
    @DisplayName("processLineLogic(): an absent membership is created and persisted with an open window")
    void processLineCreate() {
        int[] counters = {0, 0};
        FidelityAccount account = account("CARD1");
        FidelityCommunity comm = community("BABY");
        LocalDate from = LocalDate.of(2026, 2, 1);
        EntityManager em = Mockito.mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityMembership> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class);
             MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and community = ?2 and validFrom = ?3", account, comm, from)).thenReturn(query);
            resource.processLineLogic(
                    line("CARD1", "BABY", "2026-02-01", ""), ctx(account, comm), counters);
            assertEquals(1, counters[0]);
            assertEquals(0, counters[1]);
            Mockito.verify(em).persist(captor.capture());
            FidelityMembership created = (FidelityMembership) captor.getValue();
            assertSame(account, created.account);
            assertSame(comm, created.community);
            assertEquals(from, created.validFrom);
            assertNull(created.validTo);
        }
    }

    /**
     * Create branch with a bounded window: the {@code validTo} column parses to a date and is carried
     * onto the fresh membership.
     */
    @Test
    @DisplayName("processLineLogic(): a created membership carries a parsed validTo")
    void processLineCreateBounded() {
        int[] counters = {0, 0};
        FidelityAccount account = account("CARD1");
        FidelityCommunity comm = community("BABY");
        LocalDate from = LocalDate.of(2026, 2, 1);
        LocalDate to = LocalDate.of(2026, 10, 31);
        EntityManager em = Mockito.mock(EntityManager.class);
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityMembership> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(null);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        try (MockedStatic<Panache> pan = Mockito.mockStatic(Panache.class);
             MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            pan.when(Panache::getEntityManager).thenReturn(em);
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and community = ?2 and validFrom = ?3", account, comm, from)).thenReturn(query);
            resource.processLineLogic(
                    line("CARD1", "BABY", "2026-02-01", "2026-10-31"), ctx(account, comm), counters);
            assertEquals(1, counters[0]);
            Mockito.verify(em).persist(captor.capture());
            FidelityMembership created = (FidelityMembership) captor.getValue();
            assertEquals(to, created.validTo);
        }
    }

    // --------------------------------------------------
    // processLineLogic(): update branch — each leg (§29.6)
    // --------------------------------------------------

    /**
     * Update branch, changed leg true: a matched membership whose {@code validTo} differs from the
     * parsed one has its window closed to the new bound and is counted as updated, with no persist.
     */
    @Test
    @DisplayName("processLineLogic(): a differing validTo updates the membership window")
    void processLineUpdateChangedValidTo() {
        int[] counters = {0, 0};
        FidelityAccount account = account("CARD1");
        FidelityCommunity comm = community("BABY");
        LocalDate from = LocalDate.of(2026, 2, 1);
        FidelityMembership existing = new FidelityMembership();
        existing.account = account;
        existing.community = comm;
        existing.validFrom = from;
        existing.validTo = null;
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityMembership> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(existing);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and community = ?2 and validFrom = ?3", account, comm, from)).thenReturn(query);
            resource.processLineLogic(
                    line("CARD1", "BABY", "2026-02-01", "2026-10-31"), ctx(account, comm), counters);
            assertEquals(0, counters[0]);
            assertEquals(1, counters[1]);
            assertEquals(LocalDate.of(2026, 10, 31), existing.validTo);
        }
    }

    /**
     * Update branch, changed leg false: a matched membership whose {@code validTo} already equals the
     * parsed one is left untouched — neither counter moves and the window is unchanged.
     */
    @Test
    @DisplayName("processLineLogic(): a matching validTo leaves the membership untouched")
    void processLineUpdateUnchangedValidTo() {
        int[] counters = {0, 0};
        FidelityAccount account = account("CARD1");
        FidelityCommunity comm = community("BABY");
        LocalDate from = LocalDate.of(2026, 2, 1);
        FidelityMembership existing = new FidelityMembership();
        existing.account = account;
        existing.community = comm;
        existing.validFrom = from;
        existing.validTo = LocalDate.of(2026, 10, 31);
        @SuppressWarnings("unchecked")
        PanacheQuery<FidelityMembership> query = Mockito.mock(PanacheQuery.class);
        Mockito.when(query.firstResult()).thenReturn(existing);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                    "account = ?1 and community = ?2 and validFrom = ?3", account, comm, from)).thenReturn(query);
            resource.processLineLogic(
                    line("CARD1", "BABY", "2026-02-01", "2026-10-31"), ctx(account, comm), counters);
            assertEquals(0, counters[0]);
            assertEquals(0, counters[1]);
            assertEquals(LocalDate.of(2026, 10, 31), existing.validTo);
        }
    }
}
