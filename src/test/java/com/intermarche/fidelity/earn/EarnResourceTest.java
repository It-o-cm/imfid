package com.intermarche.fidelity.earn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.util.ProgramClock;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link EarnResource}: the §27.1 {@code POST /api/earn}
 * projection surface. Every branch of the endpoint is exercised — the two legs of the
 * missing-payload guard (400), the reconciliation rejection (422) including its null and
 * special-character message escaping, and the three legs of the card-resolution guard
 * ({@code null} request, {@code null} card, blank card) plus the trimmed lookup, crossed
 * with the fiscal-instant fallback (basket date vs. program clock).
 * <p>
 * Fully isolated: the {@link ValuationReader}, {@link EarnEngine} and {@link ProgramClock}
 * collaborators are mocked and wired on the package-private injection fields, and the
 * {@link FidelityAccount} static finder is intercepted with {@code mockStatic} in a
 * try-with-resources. No real clock is ever read (§24.6): the fallback instant is the
 * fixed {@link #CLOCK_NOW} stubbed on the mocked {@link ProgramClock}, and the basket
 * date is the fixed {@link #BASKET_DATE}; the endpoint merely forwards the instant, it
 * holds no window or {@code earnYear} boundary of its own.
 */
class EarnResourceTest {

    /**
     * The fixed program-clock fallback instant, used when the basket carries no date.
     */
    private static final LocalDateTime CLOCK_NOW = LocalDateTime.of(2026, 8, 8, 12, 0);

    /**
     * The fixed basket date carried by the valuation request when present (§31.1).
     */
    private static final LocalDateTime BASKET_DATE = LocalDateTime.of(2026, 3, 15, 10, 0);

    /**
     * The system under test, freshly built per test with mocked collaborators.
     */
    private EarnResource resource;

    /**
     * The mocked reader turning the valuation response into valued lines (§22).
     */
    private ValuationReader reader;

    /**
     * The mocked orchestration engine producing the earn projection (§15).
     */
    private EarnEngine engine;

    /**
     * The mocked program clock supplying the fixed fallback instant (§25.1).
     */
    private ProgramClock clock;

    /**
     * Builds a fresh system under test and wires the mocked collaborators before each test.
     */
    @BeforeEach
    void setUp() {
        resource = new EarnResource();
        reader = Mockito.mock(ValuationReader.class);
        engine = Mockito.mock(EarnEngine.class);
        clock = Mockito.mock(ProgramClock.class);
        resource.reader = reader;
        resource.engine = engine;
        resource.clock = clock;
    }

    /**
     * Builds an {@link EarnRequest} carrying a valuation response and the given request.
     *
     * @param valuationRequest The valuation request to embed; may be null.
     * @return A ready earn request with a non-null valuation response.
     */
    private EarnRequest requestWith(ValuationRequest valuationRequest) {
        EarnRequest request = new EarnRequest();
        request.valuationRequest = valuationRequest;
        request.valuationResponse = new ValuationResponse();
        return request;
    }

    /**
     * Stubs the reader/engine happy path and returns the sentinel response the engine
     * result projects to, so a test can assert identity forwarding.
     *
     * @param account The account the engine is expected to receive.
     * @return The sentinel {@link EarnResponse} stubbed on the engine result.
     */
    private EarnResponse stubHappyPath(FidelityAccount account) {
        ValuationReading reading = Mockito.mock(ValuationReading.class);
        Mockito.when(reader.read(any())).thenReturn(reading);
        EarnResult result = Mockito.mock(EarnResult.class);
        EarnResponse projection = new EarnResponse();
        Mockito.when(result.toResponse()).thenReturn(projection);
        Mockito.when(engine.evaluate(eq(reading), any(), any(), anyBoolean())).thenReturn(result);
        return projection;
    }

    /**
     * A null request is unreadable: 400 with the missing-response error, nothing read.
     */
    @Test
    @DisplayName("earn: null request → 400, reader and engine untouched")
    void earnNullRequestReturns400() {
        var response = resource.earn(null);
        assertEquals(400, response.getStatus());
        assertEquals("{\"error\":\"Missing valuation response in /earn request\"}", response.getEntity());
        Mockito.verifyNoInteractions(reader, engine, clock);
    }

    /**
     * A request without a valuation response is unreadable: 400, the second guard leg.
     */
    @Test
    @DisplayName("earn: null valuation response → 400")
    void earnNullValuationResponseReturns400() {
        EarnRequest request = new EarnRequest();
        request.valuationResponse = null;
        var response = resource.earn(request);
        assertEquals(400, response.getStatus());
        assertEquals("{\"error\":\"Missing valuation response in /earn request\"}", response.getEntity());
        Mockito.verifyNoInteractions(reader, engine, clock);
    }

    /**
     * A reconciliation failure rejects with 422 and escapes quotes and backslashes in the
     * reported message (§25.2), and never reaches the engine.
     */
    @Test
    @DisplayName("earn: reconciliation failure → 422 with escaped message")
    void earnReconciliationFailureReturns422Escaped() {
        Mockito.when(reader.read(any()))
                .thenThrow(new ValuationReconciliationException("bad\"quote\\back"));
        var response = resource.earn(requestWith(new ValuationRequest()));
        assertEquals(422, response.getStatus());
        assertEquals("{\"error\":\"bad\\\"quote\\\\back\"}", response.getEntity());
        Mockito.verifyNoInteractions(engine, clock);
    }

    /**
     * A reconciliation failure with a null message yields an empty error field: the null
     * arm of the error builder's ternary.
     */
    @Test
    @DisplayName("earn: reconciliation failure with null message → 422 empty error")
    void earnReconciliationFailureNullMessageReturns422() {
        Mockito.when(reader.read(any()))
                .thenThrow(new ValuationReconciliationException(null));
        var response = resource.earn(requestWith(new ValuationRequest()));
        assertEquals(422, response.getStatus());
        assertEquals("{\"error\":\"\"}", response.getEntity());
    }

    /**
     * A present card and a present basket date: the account is resolved by the trimmed
     * card number and the fiscal instant is the basket date — both guard legs true. The
     * engine receives the reading, the resolved account, the basket date and the current
     * visit flag, and its projection is forwarded verbatim; the clock is never read.
     */
    @Test
    @DisplayName("earn: present card and date → 200, trimmed lookup, basket-date instant")
    void earnCardAndDatePresentReturns200() {
        ValuationRequest valuationRequest = new ValuationRequest();
        valuationRequest.customerCode = "  CARD1  ";
        valuationRequest.createdAt = BASKET_DATE;
        FidelityAccount account = new FidelityAccount();
        EarnResponse projection = stubHappyPath(account);
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber("CARD1")).thenReturn(account);
            var response = resource.earn(requestWith(valuationRequest));
            assertEquals(200, response.getStatus());
            assertSame(projection, response.getEntity());
            accounts.verify(() -> FidelityAccount.findByCardNumber("CARD1"));
        }
        ArgumentCaptor<FidelityAccount> accountArg = ArgumentCaptor.forClass(FidelityAccount.class);
        ArgumentCaptor<LocalDateTime> instantArg = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Boolean> visitArg = ArgumentCaptor.forClass(Boolean.class);
        Mockito.verify(engine).evaluate(any(), accountArg.capture(), instantArg.capture(), visitArg.capture());
        assertSame(account, accountArg.getValue());
        assertEquals(BASKET_DATE, instantArg.getValue());
        assertEquals(Boolean.TRUE, visitArg.getValue());
        Mockito.verifyNoInteractions(clock);
    }

    /**
     * A null valuation request drives both guards to their first leg: the account is null
     * and the fiscal instant falls back to the program clock; no card lookup occurs.
     */
    @Test
    @DisplayName("earn: null valuation request → 200, null account, clock fallback")
    void earnNullValuationRequestUsesClock() {
        EarnResponse projection = stubHappyPath(null);
        Mockito.when(clock.now()).thenReturn(CLOCK_NOW);
        EarnRequest request = requestWith(null);
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            var response = resource.earn(request);
            assertEquals(200, response.getStatus());
            assertSame(projection, response.getEntity());
            accounts.verifyNoInteractions();
        }
        ArgumentCaptor<FidelityAccount> accountArg = ArgumentCaptor.forClass(FidelityAccount.class);
        ArgumentCaptor<LocalDateTime> instantArg = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(engine).evaluate(any(), accountArg.capture(), instantArg.capture(), eq(true));
        assertNull(accountArg.getValue());
        assertEquals(CLOCK_NOW, instantArg.getValue());
    }

    /**
     * A non-null request with a null card and a null date drives the second leg of both
     * guards: no account, program-clock fallback instant.
     */
    @Test
    @DisplayName("earn: null card and null date → 200, null account, clock fallback")
    void earnNullCardNullDateUsesClock() {
        EarnResponse projection = stubHappyPath(null);
        Mockito.when(clock.now()).thenReturn(CLOCK_NOW);
        ValuationRequest valuationRequest = new ValuationRequest();
        valuationRequest.customerCode = null;
        valuationRequest.createdAt = null;
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            var response = resource.earn(requestWith(valuationRequest));
            assertEquals(200, response.getStatus());
            assertSame(projection, response.getEntity());
            accounts.verifyNoInteractions();
        }
        ArgumentCaptor<FidelityAccount> accountArg = ArgumentCaptor.forClass(FidelityAccount.class);
        ArgumentCaptor<LocalDateTime> instantArg = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(engine).evaluate(any(), accountArg.capture(), instantArg.capture(), eq(true));
        assertNull(accountArg.getValue());
        assertEquals(CLOCK_NOW, instantArg.getValue());
    }

    /**
     * A blank card with a present date drives the third leg of the card guard (no account)
     * while the fiscal instant is the basket date; no card lookup, no clock read.
     */
    @Test
    @DisplayName("earn: blank card, present date → 200, null account, basket-date instant")
    void earnBlankCardPresentDate() {
        EarnResponse projection = stubHappyPath(null);
        ValuationRequest valuationRequest = new ValuationRequest();
        valuationRequest.customerCode = "   ";
        valuationRequest.createdAt = BASKET_DATE;
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            var response = resource.earn(requestWith(valuationRequest));
            assertEquals(200, response.getStatus());
            assertSame(projection, response.getEntity());
            accounts.verifyNoInteractions();
        }
        ArgumentCaptor<FidelityAccount> accountArg = ArgumentCaptor.forClass(FidelityAccount.class);
        ArgumentCaptor<LocalDateTime> instantArg = ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(engine).evaluate(any(), accountArg.capture(), instantArg.capture(), eq(true));
        assertNull(accountArg.getValue());
        assertEquals(BASKET_DATE, instantArg.getValue());
        Mockito.verifyNoInteractions(clock);
    }
}
