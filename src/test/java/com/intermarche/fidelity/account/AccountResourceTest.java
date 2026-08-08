package com.intermarche.fidelity.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.intermarche.fidelity.domain.FidelityAccount;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link AccountResource}: the §27.2 account read surface
 * ({@code GET /api/accounts/{card}} and {@code .../movements}). Every branch of both
 * endpoints is exercised — the two arms of the unknown-card guard (404 vs. 200) on each
 * endpoint, the two arms of the {@code Math.max(0, page)} clamp (negative vs. non-negative
 * page), and the three outcomes of the page-size ternary {@code size <= 0 ? 50 :
 * Math.min(size, 200)} (default, uncapped, capped).
 * <p>
 * Fully isolated: the {@link AccountService} collaborator is mocked and wired on the
 * package-private injection field, and the {@link FidelityAccount} static finder is
 * intercepted with {@code mockStatic} in a try-with-resources. The endpoint reads no
 * clock (§24.6): it holds no window, visit or {@code earnYear} boundary of its own and
 * merely clamps and forwards the pagination arguments to the service.
 */
class AccountResourceTest {

    /**
     * A card number known to the finder, resolving to {@link #account}.
     */
    private static final String KNOWN_CARD = "CARD-1";

    /**
     * A card number the finder resolves to {@code null} (unknown card, 404).
     */
    private static final String UNKNOWN_CARD = "CARD-X";

    /**
     * The system under test, freshly built per test with a mocked collaborator.
     */
    private AccountResource resource;

    /**
     * The mocked read service producing the summary and movement page (§27.2).
     */
    private AccountService service;

    /**
     * The sentinel account the finder resolves for {@link #KNOWN_CARD}.
     */
    private FidelityAccount account;

    /**
     * Builds a fresh system under test and wires the mocked service before each test.
     */
    @BeforeEach
    void setUp() {
        resource = new AccountResource();
        service = Mockito.mock(AccountService.class);
        resource.service = service;
        account = new FidelityAccount();
    }

    /**
     * A known card yields 200 carrying the service summary verbatim (guard non-null arm).
     */
    @Test
    @DisplayName("account: known card → 200 with the service summary")
    void accountKnownCardReturns200() {
        AccountViews.Summary summary = new AccountViews.Summary();
        Mockito.when(service.summary(account)).thenReturn(summary);
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber(KNOWN_CARD)).thenReturn(account);
            Response response = resource.account(KNOWN_CARD);
            assertEquals(200, response.getStatus());
            assertSame(summary, response.getEntity());
        }
    }

    /**
     * An unknown card yields 404 with the error body, the service untouched (guard null arm).
     */
    @Test
    @DisplayName("account: unknown card → 404, service untouched")
    void accountUnknownCardReturns404() {
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber(UNKNOWN_CARD)).thenReturn(null);
            Response response = resource.account(UNKNOWN_CARD);
            assertEquals(404, response.getStatus());
            assertEquals("{\"error\":\"Unknown card\"}", response.getEntity());
        }
        Mockito.verifyNoInteractions(service);
    }

    /**
     * An unknown card on the movements endpoint yields 404, the service untouched (guard
     * null arm of the second endpoint).
     */
    @Test
    @DisplayName("movements: unknown card → 404, service untouched")
    void movementsUnknownCardReturns404() {
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber(UNKNOWN_CARD)).thenReturn(null);
            Response response = resource.movements(UNKNOWN_CARD, 3, 20);
            assertEquals(404, response.getStatus());
            assertEquals("{\"error\":\"Unknown card\"}", response.getEntity());
        }
        Mockito.verifyNoInteractions(service);
    }

    /**
     * A known card with a positive page and an in-range size: the guard non-null arm, the
     * non-negative arm of {@code Math.max}, and the middle branch of the size ternary
     * ({@code size > 0} and {@code size <= 200}) forward {@code (5, 20)} verbatim.
     */
    @Test
    @DisplayName("movements: positive page, in-range size → 200, forwarded (5, 20)")
    void movementsPositivePageInRangeSize() {
        AccountViews.MovementPage page = new AccountViews.MovementPage();
        Mockito.when(service.movements(account, 5, 20)).thenReturn(page);
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber(KNOWN_CARD)).thenReturn(account);
            Response response = resource.movements(KNOWN_CARD, 5, 20);
            assertEquals(200, response.getStatus());
            assertSame(page, response.getEntity());
        }
        Mockito.verify(service).movements(account, 5, 20);
    }

    /**
     * A negative page clamps to 0 (the negative arm of {@code Math.max}); the size is left
     * untouched to isolate the clamp on the page index alone.
     */
    @Test
    @DisplayName("movements: negative page → clamped to page index 0")
    void movementsNegativePageClampsToZero() {
        Mockito.when(service.movements(account, 0, 20)).thenReturn(new AccountViews.MovementPage());
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber(KNOWN_CARD)).thenReturn(account);
            Response response = resource.movements(KNOWN_CARD, -7, 20);
            assertEquals(200, response.getStatus());
        }
        Mockito.verify(service).movements(account, 0, 20);
    }

    /**
     * A non-positive size falls back to the default page size of 50 (the {@code size <= 0}
     * arm of the ternary).
     */
    @Test
    @DisplayName("movements: size 0 → default page size 50")
    void movementsNonPositiveSizeUsesDefault() {
        Mockito.when(service.movements(account, 2, 50)).thenReturn(new AccountViews.MovementPage());
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber(KNOWN_CARD)).thenReturn(account);
            Response response = resource.movements(KNOWN_CARD, 2, 0);
            assertEquals(200, response.getStatus());
        }
        Mockito.verify(service).movements(account, 2, 50);
    }

    /**
     * A size beyond the maximum is capped to 200, never rejected (the {@code size > 200}
     * arm of {@code Math.min}, §31.3).
     */
    @Test
    @DisplayName("movements: oversized size → capped to 200")
    void movementsOversizedSizeCapsAt200() {
        Mockito.when(service.movements(account, 0, 200)).thenReturn(new AccountViews.MovementPage());
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber(KNOWN_CARD)).thenReturn(account);
            Response response = resource.movements(KNOWN_CARD, 0, 5000);
            assertEquals(200, response.getStatus());
        }
        Mockito.verify(service).movements(account, 0, 200);
    }
}
