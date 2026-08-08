package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intermarche.fidelity.account.AccountService;
import com.intermarche.fidelity.account.AccountViews;
import com.intermarche.fidelity.admin.AdminException;
import com.intermarche.fidelity.admin.AdminService;
import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.AppUser;
import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityActivation;
import com.intermarche.fidelity.domain.FidelityCommunity;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.util.ProgramClock;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * Plain unit coverage for {@link CardUiResource}: the filtered card list, the card sheet and
 * the five write mutations (create, adjust, transfer, resiliate, membership, activation) with
 * the shared {@code parseStatus}/{@code parseDate}/{@code buildWhere} helpers (§23.1, §28,
 * §32.1). Every collaborator is mocked: the {@link AccountService} and {@link AdminService}
 * with Mockito, the program {@link ProgramClock} as a fixed clock so no test ever reads the
 * real wall time (§24.6), and the Panache static finders — {@link PanacheEntityBase} for the
 * inherited {@code count}/{@code find}, {@link FidelityAccount#findByCardNumber(String)},
 * {@link EarnTrace#countVisits(String, LocalDate, LocalDate)},
 * {@link FidelityReservation#findActiveForAccount(FidelityAccount)},
 * {@link FidelityActivation#listForAccount(FidelityAccount)} and
 * {@link FidelityCommunity#listAllByCode()} — with {@code mockStatic} in try-with-resources.
 * <p>
 * The {@code static native} Qute templates of the nested {@code Templates} class have no
 * instrumentable body, so under a plain unit run the {@code list} and {@code detail} render
 * methods evaluate every guard and ternary argument fully (that computation carries the
 * branches) and then reach the native boundary, which raises an {@link UnsatisfiedLinkError};
 * each render test drives one arm and asserts that boundary. The 404 arm of {@code detail}
 * and every POST mutation return a real {@link Response} that is asserted directly. Each guard
 * is covered on both arms and, for compound guards, on each leg (§29, §29.6): the sort
 * whitelist ternary, the {@code desc} ternary, the {@code number}/{@code status} filter puts,
 * the empty and non-empty account loop, the {@code account == null} 404 arm, the
 * {@code number != null && !number.isBlank()} legs of {@code buildWhere}, the
 * {@code status == null || status.isBlank()} legs plus the {@code valueOf} success/failure of
 * {@code parseStatus}, the {@code value == null || value.isBlank()} legs plus the
 * {@code parse} success/failure of {@code parseDate}, and both the try and catch arm of every
 * mutation.
 */
class CardUiResourceTest {

    /**
     * A fixed program date driving the month bounds — the campaign never reads the real clock.
     */
    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 8, 15);

    /**
     * The system under test, freshly built per test with its mocked collaborators.
     */
    private CardUiResource resource;

    /**
     * The mocked account read service.
     */
    private AccountService accountService;

    /**
     * The mocked mutation service.
     */
    private AdminService admin;

    /**
     * The mocked program clock, fixed to {@link #FIXED_TODAY}.
     */
    private ProgramClock clock;

    /**
     * Wires a fresh resource with its mocked collaborators and a fixed clock.
     */
    @BeforeEach
    void setUp() {
        resource = new CardUiResource();
        accountService = mock(AccountService.class);
        admin = mock(AdminService.class);
        clock = mock(ProgramClock.class);
        resource.accountService = accountService;
        resource.admin = admin;
        resource.clock = clock;
        when(clock.today()).thenReturn(FIXED_TODAY);
        when(clock.monthStart(any())).thenReturn(LocalDate.of(2026, 8, 1));
        when(clock.monthEnd(any())).thenReturn(LocalDate.of(2026, 8, 31));
    }

    /**
     * Builds a security context resolving the write role to the given decision.
     *
     * @param canWrite Whether the user holds the {@code fid-admin} role.
     * @return The mocked security context.
     */
    private SecurityContext context(boolean canWrite) {
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.isUserInRole(AppUser.ROLE_FID_ADMIN)).thenReturn(canWrite);
        return securityContext;
    }

    /**
     * Builds a card account with the given number and default active state.
     *
     * @param cardNumber The card number.
     * @return The account.
     */
    private FidelityAccount account(String cardNumber) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = cardNumber;
        account.status = AccountStatus.ACTIVE;
        account.balance = new BigDecimal("12.50");
        account.lastUsedAt = LocalDateTime.of(2026, 8, 10, 9, 30);
        return account;
    }

    // --------------------------------------------------
    // list
    // --------------------------------------------------

    /**
     * {@code list}: an unknown sort key falls back to the default, {@code dir="asc"} takes the
     * ascending ternary arm, a null number and null status leave both filter puts unset and
     * an empty account page skips the loop body; the render reaches the native boundary.
     */
    @Test
    @DisplayName("list: unknown sort, asc, no filters, empty page")
    void listDefaultsEmpty() {
        PanacheQuery<FidelityAccount> query = mockedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<EarnTrace> earn = mockStatic(EarnTrace.class)) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Map.class))).thenReturn(0L);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Map.class))).thenReturn(query);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list(null, null, "bogus", "asc", 1, null, false, null));
        }
    }

    /**
     * {@code list}: a whitelisted sort key keeps the requested column, {@code dir="DESC"} takes
     * the descending ternary arm through {@code equalsIgnoreCase}, a non-blank number and a
     * valid status set both filter puts and drive both {@code buildWhere} legs true, and a
     * one-row page executes the loop body (visit count and {@code CardRow.of}).
     */
    @Test
    @DisplayName("list: valid sort, desc, both filters, one row")
    void listFiltersOneRow() {
        FidelityAccount account = account("C1");
        PanacheQuery<FidelityAccount> query = mockedQuery(List.of(account));
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<EarnTrace> earn = mockStatic(EarnTrace.class)) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Map.class))).thenReturn(30L);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Map.class))).thenReturn(query);
            earn.when(() -> EarnTrace.countVisits(eq("C1"), any(), any())).thenReturn(4L);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list("AB", "ACTIVE", "balance", "DESC", 1, "hello", true, context(true)));
            earn.verify(() -> EarnTrace.countVisits(eq("C1"), any(), any()));
        }
    }

    /**
     * {@code list}: a blank number takes the {@code !number.isBlank()} false leg of
     * {@code buildWhere} while still setting the number filter put (the string is non-null),
     * and an unknown status drives {@code parseStatus} to its {@code valueOf} failure so the
     * status filter is set but the WHERE clause has no status predicate.
     */
    @Test
    @DisplayName("list: blank number and unknown status")
    void listBlankNumberUnknownStatus() {
        PanacheQuery<FidelityAccount> query = mockedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<EarnTrace> earn = mockStatic(EarnTrace.class)) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Map.class))).thenReturn(0L);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Map.class))).thenReturn(query);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list("   ", "XXX", "cardNumber", "asc", 1, null, false, context(false)));
        }
    }

    /**
     * {@code list}: a blank status drives the {@code status.isBlank()} second leg of
     * {@code parseStatus} to true, returning a null status filter.
     */
    @Test
    @DisplayName("list: blank status takes the isBlank leg")
    void listBlankStatus() {
        PanacheQuery<FidelityAccount> query = mockedQuery(List.of());
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<EarnTrace> earn = mockStatic(EarnTrace.class)) {
            panache.when(() -> PanacheEntityBase.count(anyString(), any(Map.class))).thenReturn(0L);
            panache.when(() -> PanacheEntityBase.find(anyString(), any(Map.class))).thenReturn(query);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.list(null, "   ", "cardNumber", "asc", 1, null, false, context(true)));
        }
    }

    /**
     * Builds a mocked Panache query returning the given list from its paged terminal.
     *
     * @param accounts The accounts the query yields.
     * @return The mocked query.
     */
    @SuppressWarnings("unchecked")
    private PanacheQuery<FidelityAccount> mockedQuery(List<FidelityAccount> accounts) {
        PanacheQuery<FidelityAccount> query = mock(PanacheQuery.class);
        when(query.page(anyInt(), anyInt())).thenReturn(query);
        when(query.list()).thenReturn(accounts);
        return query;
    }

    // --------------------------------------------------
    // detail
    // --------------------------------------------------

    /**
     * {@code detail}: the {@code account == null} arm returns a 404 without touching the
     * services.
     */
    @Test
    @DisplayName("detail: unknown card returns 404")
    void detailUnknownCard() {
        try (MockedStatic<FidelityAccount> accounts = mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber("nope")).thenReturn(null);
            Response response = resource.detail("nope", 1, null, false, context(true));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
            assertEquals("Unknown card", response.getEntity());
        }
    }

    /**
     * {@code detail}: the {@code account != null} arm assembles the sheet from every read
     * collaborator and reaches the native template boundary.
     */
    @Test
    @DisplayName("detail: known card renders the sheet")
    void detailKnownCard() {
        FidelityAccount account = account("C1");
        AccountViews.Summary summary = new AccountViews.Summary();
        AccountViews.MovementPage movements = new AccountViews.MovementPage();
        when(accountService.summary(account)).thenReturn(summary);
        when(accountService.movements(eq(account), anyInt(), anyInt())).thenReturn(movements);
        try (MockedStatic<FidelityAccount> accounts = mockStatic(FidelityAccount.class);
                MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
                MockedStatic<FidelityReservation> reservations = mockStatic(FidelityReservation.class);
                MockedStatic<FidelityActivation> activations = mockStatic(FidelityActivation.class);
                MockedStatic<FidelityCommunity> communities = mockStatic(FidelityCommunity.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber("C1")).thenReturn(account);
            panache.when(() -> PanacheEntityBase.count("account", account)).thenReturn(60L);
            reservations.when(() -> FidelityReservation.findActiveForAccount(account)).thenReturn(null);
            activations.when(() -> FidelityActivation.listForAccount(account)).thenReturn(List.of());
            communities.when(FidelityCommunity::listAllByCode).thenReturn(List.of());
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.detail("C1", 1, "hi", true, context(true)));
        }
    }

    // --------------------------------------------------
    // create
    // --------------------------------------------------

    /**
     * {@code create}: a successful creation redirects to the new card sheet with a success
     * notice; the default status parses to {@code ACTIVE}.
     */
    @Test
    @DisplayName("create: success redirects to the new sheet")
    void createSuccess() {
        when(admin.createCard(AccountStatus.ACTIVE)).thenReturn(account("C100"));
        Response response = resource.create("ACTIVE");
        verify(admin).createCard(AccountStatus.ACTIVE);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/cards/C100", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Card+C100+created"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code create}: an {@link AdminException} redirects back to the list with a failure
     * notice.
     */
    @Test
    @DisplayName("create: refusal redirects to the list")
    void createFailure() {
        when(admin.createCard(AccountStatus.ACTIVE)).thenThrow(new AdminException("boom"));
        Response response = resource.create("ACTIVE");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/cards", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=boom"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // adjust
    // --------------------------------------------------

    /**
     * {@code adjust}: a successful adjustment redirects to the card sheet with a success
     * notice.
     */
    @Test
    @DisplayName("adjust: success redirects to the sheet")
    void adjustSuccess() {
        BigDecimal amount = new BigDecimal("5.00");
        when(admin.adjustCard("C1", amount, "gift")).thenReturn(account("C1"));
        Response response = resource.adjust("C1", amount, "gift");
        verify(admin).adjustCard("C1", amount, "gift");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/cards/C1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Adjustment+posted"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code adjust}: an {@link AdminException} redirects back to the card sheet with a failure
     * notice.
     */
    @Test
    @DisplayName("adjust: refusal redirects to the sheet")
    void adjustFailure() {
        BigDecimal amount = new BigDecimal("5.00");
        when(admin.adjustCard("C1", amount, "")).thenThrow(new AdminException("reason required"));
        Response response = resource.adjust("C1", amount, "");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/cards/C1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=reason+required"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // transfer
    // --------------------------------------------------

    /**
     * {@code transfer}: a successful transfer redirects to the target sheet naming both cards.
     */
    @Test
    @DisplayName("transfer: success redirects to the target sheet")
    void transferSuccess() {
        when(admin.transferCard("C1")).thenReturn(account("C2"));
        Response response = resource.transfer("C1");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/cards/C2", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Transferred+from+C1+to+C2"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code transfer}: an {@link AdminException} redirects back to the source sheet with a
     * failure notice.
     */
    @Test
    @DisplayName("transfer: refusal redirects to the source sheet")
    void transferFailure() {
        when(admin.transferCard("C1")).thenThrow(new AdminException("active lease"));
        Response response = resource.transfer("C1");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/cards/C1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=active+lease"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // resiliate
    // --------------------------------------------------

    /**
     * {@code resiliate}: a successful resiliation redirects to the card sheet with a success
     * notice.
     */
    @Test
    @DisplayName("resiliate: success redirects to the sheet")
    void resiliateSuccess() {
        when(admin.resiliateCard("C1")).thenReturn(account("C1"));
        Response response = resource.resiliate("C1");
        verify(admin).resiliateCard("C1");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/cards/C1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Card+resiliated"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code resiliate}: an {@link AdminException} redirects back to the card sheet with a
     * failure notice.
     */
    @Test
    @DisplayName("resiliate: refusal redirects to the sheet")
    void resiliateFailure() {
        when(admin.resiliateCard("C1")).thenThrow(new AdminException("active lease"));
        Response response = resource.resiliate("C1");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/cards/C1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=active+lease"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // membership
    // --------------------------------------------------

    /**
     * {@code membership}: a successful upsert redirects to the card sheet; a valid
     * {@code validFrom} parses to a date and a blank {@code validTo} takes the
     * {@code isBlank} leg to yield a null window end.
     */
    @Test
    @DisplayName("membership: success parses a date and a blank end")
    void membershipSuccess() {
        Response response = resource.membership("C1", "FAMILY", "2026-01-15", "");
        verify(admin).upsertMembership("C1", "FAMILY", LocalDate.of(2026, 1, 15), null);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/cards/C1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Membership+saved"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code membership}: an {@link AdminException} redirects with a failure notice; a null
     * {@code validFrom} takes the first {@code parseDate} leg and a malformed {@code validTo}
     * takes the {@code parse} failure branch, both yielding null dates.
     */
    @Test
    @DisplayName("membership: refusal with null and malformed dates")
    void membershipFailure() {
        when(admin.upsertMembership(eq("C1"), eq("FAMILY"), isNull(), isNull()))
                .thenThrow(new AdminException("overlap"));
        Response response = resource.membership("C1", "FAMILY", null, "bad-date");
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/cards/C1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=overlap"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }

    // --------------------------------------------------
    // activation
    // --------------------------------------------------

    /**
     * {@code activation}: a successful upsert redirects to the card sheet; a valid
     * {@code periodStart} parses to a date and a null {@code periodEnd} takes the first
     * {@code parseDate} leg to yield a null period end.
     */
    @Test
    @DisplayName("activation: success parses a date and a null end")
    void activationSuccess() {
        Response response = resource.activation("C1", "R1", "2026-02-01", null, true);
        verify(admin).setActivation("C1", "R1", LocalDate.of(2026, 2, 1), null, true);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/cards/C1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=Activation+saved"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=true"));
    }

    /**
     * {@code activation}: an {@link AdminException} redirects with a failure notice; a
     * malformed {@code periodStart} takes the {@code parse} failure branch and a blank
     * {@code periodEnd} takes the {@code isBlank} leg, both yielding null dates.
     */
    @Test
    @DisplayName("activation: refusal with malformed and blank dates")
    void activationFailure() {
        when(admin.setActivation(eq("C1"), eq("R1"), isNull(), isNull(), eq(false)))
                .thenThrow(new AdminException("unknown rule"));
        Response response = resource.activation("C1", "R1", "oops", "   ", false);
        assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
        assertEquals("/ui/cards/C1", response.getLocation().getPath());
        assertTrue(response.getLocation().getQuery().contains("notice=unknown+rule"));
        assertTrue(response.getLocation().getQuery().contains("noticeOk=false"));
    }
}
