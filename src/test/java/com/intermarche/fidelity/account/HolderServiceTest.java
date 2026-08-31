package com.intermarche.fidelity.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.domain.CardHolder;
import com.intermarche.fidelity.domain.FidelityAccount;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link HolderService} — the local fallback identity service (§33.3).
 * Every branch is exercised: the four outcomes of {@code localDirectoryEnabled} (config absent,
 * empty, blank → enabled; a real URL → disabled), the CRM-managed refusal shared by
 * {@code upsertHolder} and {@code lookup}, the unknown-card and blank-last-name (null leg and
 * blank leg) refusals of {@code upsertHolder}, its create-vs-update fork with the normalized
 * search columns and the null/blank/value legs of the first-name and phone ternaries, and the
 * phone-over-e-mail-over-name priority of {@code lookup} with its first-name-only and
 * no-criterion refusals and its {@link HolderService#MAX_RESULTS} bound.
 * <p>
 * Fully isolated (§ CLAUDE.md): the {@code @ConfigProperty Optional<String>} field is set
 * explicitly because a plain unit run injects nothing. The Panache finders resolve through
 * {@code mockStatic(PanacheEntityBase.class)} so {@link CardHolder}'s pure normalizers still
 * run for real; every {@code new CardHolder()} of the create path is neutralized with
 * {@code mockConstruction}. No clock is read (§24.6): the service holds no temporal logic.
 */
class HolderServiceTest {

    /**
     * A card number the account finder resolves to {@link #account}.
     */
    private static final String CARD = "2990000000019";

    /**
     * The system under test, freshly built per test.
     */
    private HolderService service;

    /**
     * The account the known card resolves to.
     */
    private FidelityAccount account;

    /**
     * Builds a fresh service with the local directory enabled and a resolvable account.
     */
    @BeforeEach
    void setUp() {
        service = new HolderService();
        service.crmUrl = Optional.empty();
        account = Mockito.mock(FidelityAccount.class);
    }

    /**
     * Wraps a value in a {@link PanacheQuery} mock whose {@code firstResult()} returns it, whose
     * {@code page(...)} returns itself and whose {@code list()} returns the given list.
     *
     * @param first The single result, or null.
     * @param list  The page list.
     * @param <T>   The entity type.
     * @return The query mock.
     */
    private <T> PanacheQuery<T> query(T first, List<T> list) {
        @SuppressWarnings("unchecked")
        PanacheQuery<T> query = (PanacheQuery<T>) Mockito.mock(PanacheQuery.class, invocation -> {
            String name = invocation.getMethod().getName();
            if ("firstResult".equals(name)) {
                return first;
            }
            if ("list".equals(name)) {
                return list;
            }
            if ("page".equals(name)) {
                return invocation.getMock();
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        return query;
    }

    // --------------------------------------------------
    // localDirectoryEnabled
    // --------------------------------------------------

    /**
     * An absent CRM URL enables the local directory — the empty-Optional leg of the guard.
     */
    @Test
    @DisplayName("localDirectoryEnabled: absent config → enabled")
    void localDirectoryEnabledWhenConfigAbsent() {
        service.crmUrl = Optional.empty();
        assertTrue(service.localDirectoryEnabled());
    }

    /**
     * An empty CRM URL enables the local directory — the {@code !url.isBlank()} filter drops the
     * empty string, leaving an empty Optional.
     */
    @Test
    @DisplayName("localDirectoryEnabled: empty config → enabled")
    void localDirectoryEnabledWhenConfigEmpty() {
        service.crmUrl = Optional.of("");
        assertTrue(service.localDirectoryEnabled());
    }

    /**
     * A blank CRM URL enables the local directory — the {@code isBlank()} true leg of the filter
     * predicate on a non-empty but whitespace-only string.
     */
    @Test
    @DisplayName("localDirectoryEnabled: blank config → enabled")
    void localDirectoryEnabledWhenConfigBlank() {
        service.crmUrl = Optional.of("   ");
        assertTrue(service.localDirectoryEnabled());
    }

    /**
     * A real CRM URL disables the local directory — the {@code isBlank()} false leg keeps the
     * value, so the Optional is present and identity is CRM-managed.
     */
    @Test
    @DisplayName("localDirectoryEnabled: a real URL → disabled")
    void localDirectoryDisabledWhenConfigPresent() {
        service.crmUrl = Optional.of("https://crm.example");
        assertFalse(service.localDirectoryEnabled());
    }

    // --------------------------------------------------
    // upsertHolder
    // --------------------------------------------------

    /**
     * With a CRM configured, {@code upsertHolder} refuses before any lookup — the
     * {@code requireLocalDirectory} guard raises the §33.3 CRM-managed refusal.
     */
    @Test
    @DisplayName("upsertHolder: CRM-managed → AdminException")
    void upsertRefusedWhenCrmManaged() {
        service.crmUrl = Optional.of("https://crm.example");
        AdminException error = assertThrows(AdminException.class,
                () -> service.upsertHolder(CARD, "Durand", "Marie", "0612345678", null));
        assertTrue(error.getMessage().contains("managed by the CRM"));
    }

    /**
     * An unknown card is refused — the {@code account == null} true arm.
     */
    @Test
    @DisplayName("upsertHolder: unknown card → AdminException")
    void upsertRefusedWhenCardUnknown() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", "X")).thenReturn(query(null, List.of()));
            AdminException error = assertThrows(AdminException.class,
                    () -> service.upsertHolder("X", "Durand", "Marie", null, null));
            assertTrue(error.getMessage().contains("No card with number 'X'"));
        }
    }

    /**
     * A null last name is refused — the {@code lastName == null} true leg of the mandatory-name
     * guard, carrying the §33.3 citation.
     */
    @Test
    @DisplayName("upsertHolder: null last name → AdminException (§33.3)")
    void upsertRefusedWhenLastNameNull() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", CARD)).thenReturn(query(account, List.of()));
            AdminException error = assertThrows(AdminException.class,
                    () -> service.upsertHolder(CARD, null, "Marie", null, null));
            assertTrue(error.getMessage().contains("§33.3"));
        }
    }

    /**
     * A blank last name is refused — the {@code lastName.isBlank()} true leg (with a non-null
     * name), carrying the §33.3 citation.
     */
    @Test
    @DisplayName("upsertHolder: blank last name → AdminException (§33.3)")
    void upsertRefusedWhenLastNameBlank() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", CARD)).thenReturn(query(account, List.of()));
            AdminException error = assertThrows(AdminException.class,
                    () -> service.upsertHolder(CARD, "   ", "Marie", null, null));
            assertTrue(error.getMessage().contains("§33.3"));
        }
    }

    /**
     * With no holder yet, a new one is created and its search columns are normalized — the
     * {@code holder == null} create arm, with the value legs of the first-name and phone
     * ternaries (both trimmed) and the {@code +33} phone folding into {@code phoneSearch}.
     */
    @Test
    @DisplayName("upsertHolder: unknown holder → created with normalized columns")
    void upsertCreatesHolder() {
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
                MockedConstruction<CardHolder> cons = Mockito.mockConstruction(CardHolder.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", CARD)).thenReturn(query(account, List.of()));
            panache.when(() -> PanacheEntityBase.find("account", account)).thenReturn(query(null, List.of()));
            CardHolder holder = service.upsertHolder(CARD, "  Durand  ", " Marie ",
                    "+33 6 12 34 56 78", "  Marie.DURAND@Example.FR ");
            assertEquals(1, cons.constructed().size());
            assertSame(cons.constructed().get(0), holder);
            assertSame(account, holder.account);
            assertEquals("Durand", holder.lastName);
            assertEquals("DURAND", holder.lastNameSearch);
            assertEquals("Marie", holder.firstName);
            assertEquals("+33 6 12 34 56 78", holder.phone);
            assertEquals("0612345678", holder.phoneSearch);
            assertEquals("marie.durand@example.fr", holder.email);
        }
    }

    /**
     * With a holder already recorded, it is updated in place — the {@code holder != null} update
     * arm reuses the existing row (no new construction, so no duplicate). The null first name and
     * blank phone exercise the {@code == null} and {@code isBlank()} legs that map to null.
     */
    @Test
    @DisplayName("upsertHolder: existing holder → updated in place, null/blank fields cleared")
    void upsertUpdatesExistingHolderNullAndBlankLegs() {
        CardHolder existing = Mockito.mock(CardHolder.class);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
                MockedConstruction<CardHolder> cons = Mockito.mockConstruction(CardHolder.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", CARD)).thenReturn(query(account, List.of()));
            panache.when(() -> PanacheEntityBase.find("account", account)).thenReturn(query(existing, List.of()));
            CardHolder holder = service.upsertHolder(CARD, "Martin", null, "   ", null);
            assertTrue(cons.constructed().isEmpty());
            assertSame(existing, holder);
            assertEquals("Martin", holder.lastName);
            assertNull(holder.firstName);
            assertNull(holder.phone);
            assertNull(holder.phoneSearch);
            assertNull(holder.email);
        }
    }

    /**
     * An update whose first name is blank and whose phone is null clears both — the
     * {@code isBlank()} leg of the first-name ternary and the {@code == null} leg of the phone
     * ternary, completing the leg matrix started by {@link #upsertUpdatesExistingHolderNullAndBlankLegs()}.
     */
    @Test
    @DisplayName("upsertHolder: blank first name and null phone are cleared")
    void upsertClearsBlankFirstNameAndNullPhone() {
        CardHolder existing = Mockito.mock(CardHolder.class);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("cardNumber", CARD)).thenReturn(query(account, List.of()));
            panache.when(() -> PanacheEntityBase.find("account", account)).thenReturn(query(existing, List.of()));
            CardHolder holder = service.upsertHolder(CARD, "Martin", "   ", null, null);
            assertSame(existing, holder);
            assertNull(holder.firstName);
            assertNull(holder.phone);
        }
    }

    // --------------------------------------------------
    // holderOf
    // --------------------------------------------------

    /**
     * {@code holderOf} returns the recorded holder of an account verbatim.
     */
    @Test
    @DisplayName("holderOf: returns the recorded holder")
    void holderOfReturnsRecordedHolder() {
        CardHolder recorded = new CardHolder();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("account", account)).thenReturn(query(recorded, List.of()));
            assertSame(recorded, service.holderOf(account));
        }
    }

    // --------------------------------------------------
    // lookup
    // --------------------------------------------------

    /**
     * With a CRM configured, {@code lookup} refuses before any search — the
     * {@code requireLocalDirectory} guard raises the §33.3 CRM-managed refusal.
     */
    @Test
    @DisplayName("lookup: CRM-managed → AdminException")
    void lookupRefusedWhenCrmManaged() {
        service.crmUrl = Optional.of("https://crm.example");
        assertThrows(AdminException.class, () -> service.lookup("0612345678", null, null, null));
    }

    /**
     * A phone criterion wins over an e-mail and a name both also supplied — the
     * {@code phoneSearch != null} true arm — and it is searched by the {@code +33}-folded phone,
     * bounded by {@link HolderService#MAX_RESULTS}.
     */
    @Test
    @DisplayName("lookup: phone wins over e-mail and name")
    void lookupPrefersPhone() {
        CardHolder match = new CardHolder();
        PanacheQuery<CardHolder> phoneQuery = query(null, List.of(match));
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("phoneSearch", "0612345678")).thenReturn(phoneQuery);
            List<CardHolder> result = service.lookup("+33 6 12 34 56 78", "x@y.fr", "durand", null);
            assertEquals(List.of(match), result);
            Mockito.verify(phoneQuery).page(0, HolderService.MAX_RESULTS);
        }
    }

    /**
     * With no phone, an e-mail criterion wins over a name — the {@code phoneSearch == null} false
     * arm then the {@code emailSearch != null} true arm — searched by the lowercased e-mail.
     */
    @Test
    @DisplayName("lookup: e-mail wins over name when no phone")
    void lookupPrefersEmailOverName() {
        CardHolder match = new CardHolder();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("email", "marie@example.fr"))
                    .thenReturn(query(null, List.of(match)));
            List<CardHolder> result = service.lookup(null, "Marie@Example.FR", "durand", null);
            assertEquals(List.of(match), result);
        }
    }

    /**
     * With neither phone nor e-mail, a name criterion searches by the normalized last name — the
     * {@code nameSearch != null} arm with a null first-name narrowing (the {@code firstNameSearch
     * == null} branch of {@code listByName}).
     */
    @Test
    @DisplayName("lookup: name searches by normalized last name")
    void lookupByName() {
        CardHolder marie = new CardHolder();
        CardHolder jacques = new CardHolder();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find("lastNameSearch", "DURAND"))
                    .thenReturn(query(null, List.of(marie, jacques)));
            List<CardHolder> result = service.lookup(null, null, "durand", null);
            assertEquals(List.of(marie, jacques), result);
        }
    }

    /**
     * A name narrowed by a first name searches by both normalized columns — the
     * {@code firstNameSearch != null} branch of {@code listByName}.
     */
    @Test
    @DisplayName("lookup: name narrowed by first name searches both columns")
    void lookupByNameAndFirstName() {
        CardHolder marie = new CardHolder();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.find(
                            "lastNameSearch = ?1 and upper(firstName) = ?2", "DURAND", "MARIE"))
                    .thenReturn(query(null, List.of(marie)));
            List<CardHolder> result = service.lookup(null, null, "durand", "marie");
            assertEquals(List.of(marie), result);
        }
    }

    /**
     * A first name given without a last name is refused — a blank name normalizes to a null
     * {@code nameSearch}, so no criterion remains and the §33.3 refusal is raised.
     */
    @Test
    @DisplayName("lookup: first name alone → AdminException")
    void lookupRefusedWhenFirstNameOnly() {
        AdminException error = assertThrows(AdminException.class,
                () -> service.lookup(null, null, null, "marie"));
        assertTrue(error.getMessage().contains("§33.3"));
    }

    /**
     * No criterion at all is refused — every normalizer yields null, so the terminal
     * {@code throw} of {@code lookup} fires with the §33.3 required-criterion message.
     */
    @Test
    @DisplayName("lookup: no criterion → AdminException")
    void lookupRefusedWhenNoCriterion() {
        AdminException error = assertThrows(AdminException.class,
                () -> service.lookup(null, null, null, null));
        assertTrue(error.getMessage().contains("A phone, an e-mail or a name is required"));
    }
}
