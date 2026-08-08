package com.intermarche.fidelity.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.fidelity.account.LedgerService;
import com.intermarche.fidelity.domain.AccountStatus;
import com.intermarche.fidelity.domain.EarnTrace;
import com.intermarche.fidelity.domain.EarnTraceLine;
import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.FidelityMovement;
import com.intermarche.fidelity.domain.FidelityReservation;
import com.intermarche.fidelity.domain.MovementType;
import com.intermarche.fidelity.domain.PendingReturn;
import com.intermarche.fidelity.domain.ReservationState;
import com.intermarche.fidelity.domain.util.DateTimeProvider;
import com.intermarche.fidelity.earn.EarnEngine;
import com.intermarche.fidelity.earn.EarnResponse;
import com.intermarche.fidelity.earn.EarnResult;
import com.intermarche.fidelity.earn.ValuationReader;
import com.intermarche.fidelity.earn.ValuationReading;
import com.intermarche.fidelity.earn.ValuationReconciliationException;
import com.intermarche.fidelity.earn.ValuationRequest;
import com.intermarche.fidelity.earn.ValuationResponse;
import com.intermarche.fidelity.earn.WarningCode;
import com.intermarche.fidelity.rule.ValuedLine;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Plain unit coverage for {@link IngestionService}: the fiscal event ingestion — the
 * authority of the credit whose recalc at ingestion prevails (§26.1), whose every write
 * takes the per-card lock (§30.1) and whose movements are idempotent by their natural key
 * (I8). It exercises the fifth-pass rules: the visit traced whether the ticket earned or
 * not (§29.1), the expired lease confirmed anyway with a warning (§29.2), the return held
 * before its origin and replayed (§29.3), the return debit bounded by the line earn (§29.4),
 * the refund-to-card out of every cap (§29.5), the resiliated account traced without moving
 * (§34.2) and the transfer-chain routing (§32.2).
 * <p>
 * Fully isolated: {@link ValuationReader}, {@link EarnEngine}, {@link LedgerService} and the
 * {@link ObjectMapper} are Mockito mocks injected into the package-private fields. The Panache
 * static finders ({@link EarnTrace}, {@link FidelityAccount}, {@link FidelityReservation},
 * {@link PendingReturn}, {@link FidelityMovement}) are intercepted with {@code mockStatic} in
 * try-with-resources, and the {@code new EarnTrace()} / {@code new EarnTraceLine()} /
 * {@code new PendingReturn()} constructions the service performs are neutralized with
 * {@code mockConstruction} so no {@code persist()} ever hits the (absent) session. The clock
 * is fixed through {@link DateTimeProvider#setFixedDateTime(LocalDateTime)} (§24.6): every
 * time-driven decision reads the fixed instant, never the campaign day. The pure computational
 * helpers (line debit, prorata trace split, card resolution, displayed total, JSON) are
 * asserted directly by reflection to the cent (§29.6, §31.2).
 */
class IngestionServiceTest {

    /**
     * The fixed program instant every clock read resolves to (§24.6).
     */
    private static final LocalDateTime FIXED = LocalDateTime.of(2026, 3, 15, 10, 0);

    /**
     * The fiscal date of the closure events.
     */
    private static final LocalDate FISCAL = LocalDate.of(2026, 3, 15);

    /**
     * The fiscal date of the return events.
     */
    private static final LocalDate RETURN_FISCAL = LocalDate.of(2026, 3, 20);

    /**
     * The ticket reference of the closure fixtures.
     */
    private static final String TICKET = "S1-100";

    /**
     * The return ticket reference of the return fixtures.
     */
    private static final String RETURN_TICKET = "S1-200";

    /**
     * The system under test, freshly built per test with mocked collaborators.
     */
    private IngestionService service;

    /**
     * The mocked valuation reader turning the couple into valued lines (§22).
     */
    private ValuationReader reader;

    /**
     * The mocked earn engine recomputing the earn at ingestion (§26.1).
     */
    private EarnEngine engine;

    /**
     * The mocked ledger writer holding the lock and posting the movements (§30.1).
     */
    private LedgerService ledger;

    /**
     * The mocked JSON mapper serializing payloads and replaying held returns.
     */
    private ObjectMapper objectMapper;

    /**
     * Wires a fresh service with its mocked collaborators and fixes the clock before each test.
     */
    @BeforeEach
    void setUp() {
        service = new IngestionService();
        reader = Mockito.mock(ValuationReader.class);
        engine = Mockito.mock(EarnEngine.class);
        ledger = Mockito.mock(LedgerService.class);
        objectMapper = Mockito.mock(ObjectMapper.class);
        service.reader = reader;
        service.engine = engine;
        service.ledger = ledger;
        service.objectMapper = objectMapper;
        DateTimeProvider.setFixedDateTime(FIXED);
    }

    /**
     * Clears the fixed clock after each test so no fixation leaks across the campaign (§24.6).
     */
    @AfterEach
    void tearDown() {
        DateTimeProvider.clear();
    }

    // --------------------------------------------------
    // Fixtures
    // --------------------------------------------------

    /**
     * Builds a fidelity account carrying a card number and a status.
     *
     * @param card   The card number.
     * @param status The account status.
     * @return The account.
     */
    private FidelityAccount account(String card, AccountStatus status) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = card;
        account.status = status;
        return account;
    }

    /**
     * Builds a valuation request carrying a customer code and a store code.
     *
     * @param customerCode The authoritative card (§27.4).
     * @param storeCode    The store code.
     * @return The request.
     */
    private ValuationRequest request(String customerCode, String storeCode) {
        ValuationRequest request = new ValuationRequest();
        request.customerCode = customerCode;
        request.storeCode = storeCode;
        return request;
    }

    /**
     * Builds a closure event with a matching request card and one valued response.
     *
     * @param card         The event card field.
     * @param customerCode The request customer code (authoritative), or null for no request.
     * @param fiscalDate   The fiscal date, or null to default to today (§30.3).
     * @return The closure event.
     */
    private EventDtos.TicketClosed closed(String card, String customerCode, LocalDate fiscalDate) {
        EventDtos.TicketClosed event = new EventDtos.TicketClosed();
        event.ticketRef = TICKET;
        event.card = card;
        event.fiscalDate = fiscalDate;
        event.valuationRequest = customerCode == null ? null : request(customerCode, "ST1");
        event.valuationResponse = new ValuationResponse();
        return event;
    }

    /**
     * Adds a displayed earn entry to a closure event (trace only, §26.1).
     *
     * @param event  The closure event.
     * @param amount The displayed amount.
     * @return The same event, for chaining.
     */
    private EventDtos.TicketClosed withDisplayed(EventDtos.TicketClosed event, String amount) {
        EventDtos.DisplayedEarn displayed = new EventDtos.DisplayedEarn();
        displayed.ruleCode = "R1";
        displayed.amount = new BigDecimal(amount);
        event.displayedEarn.add(displayed);
        return event;
    }

    /**
     * Builds an earn result carrying a single post-cap entry over one valued line.
     *
     * @param rule   The rule code.
     * @param amount The granted earn.
     * @param base   The eligible assiette.
     * @param lineId The contributing line id.
     * @return The result.
     */
    private EarnResult resultWithEntry(String rule, String amount, String base, String lineId) {
        ValuedLine line = new ValuedLine(lineId, "EAN-" + lineId, BigDecimal.ONE,
                new BigDecimal(base), new BigDecimal(base), false);
        EarnResult result = new EarnResult(List.of(line));
        result.entries.add(new EarnResponse.Entry(rule, rule + "-label",
                new BigDecimal(amount), new BigDecimal(base), List.of(lineId)));
        return result;
    }

    /**
     * Builds an empty earn result carrying no line and no entry (§29.1).
     *
     * @return The empty result.
     */
    private EarnResult emptyResult() {
        return new EarnResult(List.of());
    }

    /**
     * Builds an origin trace line as a Mockito mock (so its {@code persist()} is a no-op)
     * carrying the earn detail the return debit reads and advances (§29.4).
     *
     * @param lineId         The valuation line id.
     * @param ruleCode       The crediting rule code.
     * @param earn           The earn granted on the line.
     * @param quantity       The eligible quantity, or null.
     * @param returnedAmount The earn already debited back.
     * @param returnedQty    The quantity already returned.
     * @return The trace line mock.
     */
    private EarnTraceLine traceLine(String lineId, String ruleCode, String earn, String quantity,
                                    String returnedAmount, String returnedQty) {
        EarnTraceLine line = Mockito.mock(EarnTraceLine.class);
        line.lineId = lineId;
        line.ruleCode = ruleCode;
        line.ean = "EAN-" + lineId;
        line.earnAmount = new BigDecimal(earn);
        line.quantity = quantity == null ? null : new BigDecimal(quantity);
        line.returnedAmount = new BigDecimal(returnedAmount);
        line.returnedQuantity = new BigDecimal(returnedQty);
        return line;
    }

    /**
     * Builds a return event carrying a return ticket, an origin ticket and a fiscal date.
     *
     * @param originTicketRef The origin ticket reference, or null.
     * @return The return event.
     */
    private EventDtos.TicketReturn returnEvent(String originTicketRef) {
        EventDtos.TicketReturn event = new EventDtos.TicketReturn();
        event.returnTicketRef = RETURN_TICKET;
        event.originTicketRef = originTicketRef;
        event.fiscalDate = RETURN_FISCAL;
        return event;
    }

    /**
     * Builds a returned line carrying a line id and an optional quantity.
     *
     * @param lineId   The origin line id, or null.
     * @param quantity The returned quantity, or null.
     * @return The returned line.
     */
    private EventDtos.ReturnLine returnLine(String lineId, Double quantity) {
        EventDtos.ReturnLine line = new EventDtos.ReturnLine();
        line.lineId = lineId;
        line.quantity = quantity;
        return line;
    }

    /**
     * Builds an {@link EarnTrace} mock carrying live warning and line collections so the
     * upsert clear and the finalize add-all operate on real lists.
     *
     * @return The trace mock.
     */
    private EarnTrace traceMock() {
        EarnTrace trace = Mockito.mock(EarnTrace.class);
        trace.warnings = new ArrayList<>();
        trace.lines = new ArrayList<>();
        return trace;
    }

    /**
     * Reflectively invokes a private method of the service, unwrapping any reflective failure.
     *
     * @param name  The method name.
     * @param types The parameter types.
     * @param args  The arguments.
     * @return The method result, or null for a void method.
     */
    private Object invoke(String name, Class<?>[] types, Object... args) {
        try {
            Method method = IngestionService.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(service, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
        }
    }

    // --------------------------------------------------
    // ingestClosed(): orchestration
    // --------------------------------------------------

    /**
     * The nominal closure: a known active card recomputes its earn, posts one EARN, traces a
     * SUCCESS header and, the displayed earn matching, raises no drift warning — the earning
     * path with the {@code account != null} arm, the {@code status != RESILIATED} arm, the
     * reader-success arm, the non-empty entries loop and the {@code compareTo == 0} arm of the
     * mismatch guard (§26.1).
     */
    @Test
    @DisplayName("ingestClosed(): a known active card posts EARN and traces SUCCESS")
    void ingestClosedHappyPath() {
        EventDtos.TicketClosed event = withDisplayed(closed("CARD1", "CARD1", FISCAL), "2.00");
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE);
        Mockito.when(reader.read(any())).thenReturn(new ValuationReading(List.of(), new BigDecimal("10.00"), List.of()));
        Mockito.when(engine.evaluate(any(), any(), any(), eq(false))).thenReturn(resultWithEntry("R1", "2.00", "10.00", "L1"));
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class);
             MockedStatic<PendingReturn> pendings = Mockito.mockStatic(PendingReturn.class);
             MockedConstruction<EarnTrace> traceCons = Mockito.mockConstruction(EarnTrace.class,
                     (mock, ctx) -> { mock.warnings = new ArrayList<>(); mock.lines = new ArrayList<>(); });
             MockedConstruction<EarnTraceLine> lineCons = Mockito.mockConstruction(EarnTraceLine.class)) {
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(account);
            pendings.when(() -> PendingReturn.listAwaitingOrigin(TICKET)).thenReturn(List.of());
            service.ingestClosed(event);
            EarnTrace trace = traceCons.constructed().get(0);
            assertEquals(EarnTrace.STATUS_SUCCESS, trace.status);
            assertEquals(0, trace.recalculatedEarn.compareTo(new BigDecimal("2.00")));
            assertEquals(0, trace.displayedEarn.compareTo(new BigDecimal("2.00")));
            assertTrue(trace.warnings.isEmpty());
            Mockito.verify(ledger).lock("CARD1");
            Mockito.verify(ledger).post(eq(account), eq(MovementType.EARN),
                    argThat(a -> a.compareTo(new BigDecimal("2.00")) == 0), eq(FISCAL), eq("R1"),
                    eq(TICKET), eq(List.of("L1")), isNull());
        }
    }

    /**
     * The event card disagreeing with the authoritative request card raises CARD_MISMATCH and
     * still credits — the all-legs-true case of {@code event.card != null && card != null &&
     * !event.card.equals(card)} (§27.4).
     */
    @Test
    @DisplayName("ingestClosed(): a card disagreeing with the request warns CARD_MISMATCH")
    void ingestClosedCardMismatch() {
        EventDtos.TicketClosed event = closed("OTHER", "CARD1", FISCAL);
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE);
        Mockito.when(reader.read(any())).thenReturn(new ValuationReading(List.of(), new BigDecimal("0.00"), List.of()));
        Mockito.when(engine.evaluate(any(), any(), any(), eq(false))).thenReturn(emptyResult());
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class);
             MockedStatic<PendingReturn> pendings = Mockito.mockStatic(PendingReturn.class);
             MockedConstruction<EarnTrace> traceCons = Mockito.mockConstruction(EarnTrace.class,
                     (mock, ctx) -> { mock.warnings = new ArrayList<>(); mock.lines = new ArrayList<>(); })) {
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(account);
            pendings.when(() -> PendingReturn.listAwaitingOrigin(TICKET)).thenReturn(List.of());
            service.ingestClosed(event);
            EarnTrace trace = traceCons.constructed().get(0);
            assertTrue(trace.warnings.contains(WarningCode.CARD_MISMATCH.name()));
        }
    }

    /**
     * A null event card never triggers the mismatch — the first leg false arm of the
     * mismatch guard; the credit proceeds on the request card alone (§27.4).
     */
    @Test
    @DisplayName("ingestClosed(): a null event card raises no mismatch")
    void ingestClosedNullEventCardNoMismatch() {
        EventDtos.TicketClosed event = closed(null, "CARD1", FISCAL);
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE);
        Mockito.when(reader.read(any())).thenReturn(new ValuationReading(List.of(), new BigDecimal("0.00"), List.of()));
        Mockito.when(engine.evaluate(any(), any(), any(), eq(false))).thenReturn(emptyResult());
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class);
             MockedStatic<PendingReturn> pendings = Mockito.mockStatic(PendingReturn.class);
             MockedConstruction<EarnTrace> traceCons = Mockito.mockConstruction(EarnTrace.class,
                     (mock, ctx) -> { mock.warnings = new ArrayList<>(); mock.lines = new ArrayList<>(); })) {
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(account);
            pendings.when(() -> PendingReturn.listAwaitingOrigin(TICKET)).thenReturn(List.of());
            service.ingestClosed(event);
            EarnTrace trace = traceCons.constructed().get(0);
            assertFalse(trace.warnings.contains(WarningCode.CARD_MISMATCH.name()));
        }
    }

    /**
     * A blank event card and no request resolves to a null card — the second leg false arm of
     * the mismatch guard ({@code card == null}) and the {@code card == null} arm of the account
     * resolution, which traces NO_MOVEMENT without touching the ledger (§20, §27.4).
     */
    @Test
    @DisplayName("ingestClosed(): a blank card resolves to null and traces NO_MOVEMENT")
    void ingestClosedBlankCardNoMovement() {
        EventDtos.TicketClosed event = closed("   ", null, FISCAL);
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedConstruction<EarnTrace> traceCons = Mockito.mockConstruction(EarnTrace.class,
                     (mock, ctx) -> { mock.warnings = new ArrayList<>(); mock.lines = new ArrayList<>(); })) {
            service.ingestClosed(event);
            EarnTrace trace = traceCons.constructed().get(0);
            assertEquals(EarnTrace.STATUS_NO_MOVEMENT, trace.status);
            assertNull(trace.storeCode);
            Mockito.verifyNoInteractions(ledger);
        }
    }

    /**
     * An unknown card (the transfer chain resolves to nothing) traces NO_MOVEMENT — the
     * {@code resolveActive} branch of the ternary and the {@code account == null} arm (§20,
     * §32.2).
     */
    @Test
    @DisplayName("ingestClosed(): an unknown card traces NO_MOVEMENT")
    void ingestClosedUnknownCardNoMovement() {
        EventDtos.TicketClosed event = withDisplayed(closed("CARD1", "CARD1", FISCAL), "1.00");
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class);
             MockedConstruction<EarnTrace> traceCons = Mockito.mockConstruction(EarnTrace.class,
                     (mock, ctx) -> { mock.warnings = new ArrayList<>(); mock.lines = new ArrayList<>(); })) {
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(null);
            service.ingestClosed(event);
            EarnTrace trace = traceCons.constructed().get(0);
            assertEquals(EarnTrace.STATUS_NO_MOVEMENT, trace.status);
            assertEquals(0, trace.displayedEarn.compareTo(new BigDecimal("1.00")));
            Mockito.verifyNoInteractions(ledger);
        }
    }

    /**
     * An event on a resiliated account locks, warns RESILIATED_ACCOUNT and traces NO_MOVEMENT
     * without any movement — the {@code status == RESILIATED} arm (§34.2). It reuses an existing
     * trace, exercising the upsert replay branch that clears the prior lines and warnings (§26.1).
     */
    @Test
    @DisplayName("ingestClosed(): a resiliated account traces NO_MOVEMENT and clears the prior trace")
    void ingestClosedResiliatedAccount() {
        EventDtos.TicketClosed event = closed("CARD1", "CARD1", FISCAL);
        FidelityAccount account = account("CARD1", AccountStatus.RESILIATED);
        EarnTrace existing = traceMock();
        existing.lines.add(Mockito.mock(EarnTraceLine.class));
        existing.warnings.add("STALE");
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            traces.when(() -> EarnTrace.findByTicketRef(TICKET)).thenReturn(existing);
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(account);
            service.ingestClosed(event);
            assertEquals(EarnTrace.STATUS_NO_MOVEMENT, existing.status);
            assertTrue(existing.lines.isEmpty());
            assertEquals(List.of(WarningCode.RESILIATED_ACCOUNT.name()), existing.warnings);
            Mockito.verify(ledger).lock("CARD1");
            Mockito.verify(ledger, Mockito.never()).post(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    /**
     * A reconciliation failure at ingestion traces FAILED and creates no movement — the catch
     * arm of the reader read (§25.2, §26.1).
     */
    @Test
    @DisplayName("ingestClosed(): a reconciliation failure traces FAILED, no movement")
    void ingestClosedReconciliationFails() {
        EventDtos.TicketClosed event = closed("CARD1", "CARD1", FISCAL);
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE);
        Mockito.when(reader.read(any())).thenThrow(new ValuationReconciliationException("incoherent"));
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class);
             MockedConstruction<EarnTrace> traceCons = Mockito.mockConstruction(EarnTrace.class,
                     (mock, ctx) -> { mock.warnings = new ArrayList<>(); mock.lines = new ArrayList<>(); })) {
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(account);
            service.ingestClosed(event);
            EarnTrace trace = traceCons.constructed().get(0);
            assertEquals(EarnTrace.STATUS_FAILED, trace.status);
            Mockito.verify(ledger, Mockito.never()).post(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    /**
     * A recomputed earn drifting from the displayed one raises EARN_MISMATCH — the recalc
     * prevails but the drift is traced; the both-legs-true case of the mismatch guard (§26.1).
     */
    @Test
    @DisplayName("ingestClosed(): a recalc drifting from the displayed earn warns EARN_MISMATCH")
    void ingestClosedEarnMismatch() {
        EventDtos.TicketClosed event = withDisplayed(closed("CARD1", "CARD1", FISCAL), "5.00");
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE);
        Mockito.when(reader.read(any())).thenReturn(new ValuationReading(List.of(), new BigDecimal("10.00"), List.of()));
        Mockito.when(engine.evaluate(any(), any(), any(), eq(false))).thenReturn(resultWithEntry("R1", "2.00", "10.00", "L1"));
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class);
             MockedStatic<PendingReturn> pendings = Mockito.mockStatic(PendingReturn.class);
             MockedConstruction<EarnTrace> traceCons = Mockito.mockConstruction(EarnTrace.class,
                     (mock, ctx) -> { mock.warnings = new ArrayList<>(); mock.lines = new ArrayList<>(); });
             MockedConstruction<EarnTraceLine> lineCons = Mockito.mockConstruction(EarnTraceLine.class)) {
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(account);
            pendings.when(() -> PendingReturn.listAwaitingOrigin(TICKET)).thenReturn(List.of());
            service.ingestClosed(event);
            EarnTrace trace = traceCons.constructed().get(0);
            assertTrue(trace.warnings.contains(WarningCode.EARN_MISMATCH.name()));
        }
    }

    /**
     * A closure carrying no displayed earn has a null displayed total, so no drift is possible
     * — the first leg false arm of the mismatch guard; the empty entries loop leaves a zero
     * recalc and a null displayed trace (§26.1, §29.1).
     */
    @Test
    @DisplayName("ingestClosed(): no displayed earn means no mismatch and a null displayed trace")
    void ingestClosedNoDisplayedNoMismatch() {
        EventDtos.TicketClosed event = closed("CARD1", "CARD1", FISCAL);
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE);
        Mockito.when(reader.read(any())).thenReturn(new ValuationReading(List.of(), new BigDecimal("0.00"), List.of()));
        Mockito.when(engine.evaluate(any(), any(), any(), eq(false))).thenReturn(emptyResult());
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class);
             MockedStatic<PendingReturn> pendings = Mockito.mockStatic(PendingReturn.class);
             MockedConstruction<EarnTrace> traceCons = Mockito.mockConstruction(EarnTrace.class,
                     (mock, ctx) -> { mock.warnings = new ArrayList<>(); mock.lines = new ArrayList<>(); })) {
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(account);
            pendings.when(() -> PendingReturn.listAwaitingOrigin(TICKET)).thenReturn(List.of());
            service.ingestClosed(event);
            EarnTrace trace = traceCons.constructed().get(0);
            assertEquals(EarnTrace.STATUS_SUCCESS, trace.status);
            assertNull(trace.displayedEarn);
            assertEquals(0, trace.recalculatedEarn.compareTo(new BigDecimal("0.00")));
            assertFalse(trace.warnings.contains(WarningCode.EARN_MISMATCH.name()));
            Mockito.verify(ledger, Mockito.never()).post(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    /**
     * A closure without a fiscal date and without a request resolves the day from the fixed
     * clock (§30.3) and a null store — the else arms of the fiscal-date and store-code ternaries,
     * and the request-null branch of the card resolution (§27.4).
     */
    @Test
    @DisplayName("ingestClosed(): a missing fiscal date defaults to today and a missing request yields a null store")
    void ingestClosedDefaultsFiscalDateAndStore() {
        EventDtos.TicketClosed event = closed("CARD1", null, null);
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE);
        Mockito.when(reader.read(any())).thenReturn(new ValuationReading(List.of(), new BigDecimal("0.00"), List.of()));
        Mockito.when(engine.evaluate(any(), any(), any(), eq(false))).thenReturn(emptyResult());
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class);
             MockedStatic<PendingReturn> pendings = Mockito.mockStatic(PendingReturn.class);
             MockedConstruction<EarnTrace> traceCons = Mockito.mockConstruction(EarnTrace.class,
                     (mock, ctx) -> { mock.warnings = new ArrayList<>(); mock.lines = new ArrayList<>(); })) {
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(account);
            pendings.when(() -> PendingReturn.listAwaitingOrigin(TICKET)).thenReturn(List.of());
            service.ingestClosed(event);
            EarnTrace trace = traceCons.constructed().get(0);
            assertEquals(FIXED.toLocalDate(), trace.fiscalDate);
            assertNull(trace.storeCode);
        }
    }

    /**
     * A closure carrying a reservation id confirms the reservation into a BURN at ingestion —
     * the {@code reservationId != null} arm delegating to the confirmation (§29.2). The lease
     * being live, no expiry warning is raised.
     */
    @Test
    @DisplayName("ingestClosed(): a carried reservation is confirmed into a BURN")
    void ingestClosedConfirmsReservation() {
        EventDtos.TicketClosed event = closed("CARD1", "CARD1", FISCAL);
        event.reservationId = 7L;
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE);
        Mockito.when(reader.read(any())).thenReturn(new ValuationReading(List.of(), new BigDecimal("0.00"), List.of()));
        Mockito.when(engine.evaluate(any(), any(), any(), eq(false))).thenReturn(emptyResult());
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.ACTIVE;
        reservation.account = account;
        reservation.amount = new BigDecimal("5.00");
        reservation.ticketRef = "RTICKET";
        Mockito.when(reservation.isExpiredAt(any())).thenReturn(false);
        Mockito.when(ledger.lock("CARD1")).thenReturn(account);
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class);
             MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class);
             MockedStatic<PendingReturn> pendings = Mockito.mockStatic(PendingReturn.class);
             MockedConstruction<EarnTrace> traceCons = Mockito.mockConstruction(EarnTrace.class,
                     (mock, ctx) -> { mock.warnings = new ArrayList<>(); mock.lines = new ArrayList<>(); })) {
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(account);
            panache.when(() -> PanacheEntityBase.findById(7L)).thenReturn(reservation);
            pendings.when(() -> PendingReturn.listAwaitingOrigin(TICKET)).thenReturn(List.of());
            service.ingestClosed(event);
            assertEquals(ReservationState.CONFIRMED, reservation.state);
            Mockito.verify(ledger).post(eq(account), eq(MovementType.BURN),
                    argThat(a -> a.compareTo(new BigDecimal("-5.00")) == 0), eq(FISCAL), isNull(),
                    eq("RTICKET"), eq(List.of()), isNull());
            EarnTrace trace = traceCons.constructed().get(0);
            assertFalse(trace.warnings.contains(WarningCode.EXPIRED_LEASE_CONFIRMED.name()));
        }
    }

    // --------------------------------------------------
    // confirmReservationAtIngestion(): the burn confirmation (§29.2)
    // --------------------------------------------------

    /**
     * A missing reservation confirms nothing — the first leg of the state guard (§29.2).
     */
    @Test
    @DisplayName("confirmReservationAtIngestion(): a missing reservation is a no-op")
    void confirmMissingReservation() {
        List<String> warnings = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(null);
            invoke("confirmReservationAtIngestion", new Class<?>[]{Long.class, LocalDate.class, List.class},
                    1L, FISCAL, warnings);
            Mockito.verifyNoInteractions(ledger);
            assertTrue(warnings.isEmpty());
        }
    }

    /**
     * An already confirmed reservation is left untouched — the second leg of the state guard,
     * the idempotency of the confirmation (§29.2).
     */
    @Test
    @DisplayName("confirmReservationAtIngestion(): a confirmed reservation is idempotent")
    void confirmAlreadyConfirmed() {
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.CONFIRMED;
        List<String> warnings = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(reservation);
            invoke("confirmReservationAtIngestion", new Class<?>[]{Long.class, LocalDate.class, List.class},
                    1L, FISCAL, warnings);
            Mockito.verifyNoInteractions(ledger);
        }
    }

    /**
     * A released reservation is left untouched — the third leg of the state guard (§29.2).
     */
    @Test
    @DisplayName("confirmReservationAtIngestion(): a released reservation is a no-op")
    void confirmReleased() {
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.RELEASED;
        List<String> warnings = new ArrayList<>();
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(reservation);
            invoke("confirmReservationAtIngestion", new Class<?>[]{Long.class, LocalDate.class, List.class},
                    1L, FISCAL, warnings);
            Mockito.verifyNoInteractions(ledger);
        }
    }

    /**
     * A reservation whose account cannot be locked confirms nothing — the {@code account == null}
     * arm after the lock (§30.1).
     */
    @Test
    @DisplayName("confirmReservationAtIngestion(): an unlockable account is a no-op")
    void confirmAccountNull() {
        FidelityAccount owner = account("RCARD", AccountStatus.ACTIVE);
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.ACTIVE;
        reservation.account = owner;
        List<String> warnings = new ArrayList<>();
        Mockito.when(ledger.lock("RCARD")).thenReturn(null);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(reservation);
            invoke("confirmReservationAtIngestion", new Class<?>[]{Long.class, LocalDate.class, List.class},
                    1L, FISCAL, warnings);
            Mockito.verify(ledger).lock("RCARD");
            Mockito.verify(ledger, Mockito.never()).post(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    /**
     * A reservation already in the EXPIRED state is confirmed anyway with the expiry warning —
     * the first leg of the expiry OR guard, short-circuiting before the lease test (§29.2).
     */
    @Test
    @DisplayName("confirmReservationAtIngestion(): an EXPIRED-state reservation confirms with a warning")
    void confirmExpiredState() {
        FidelityAccount owner = account("RCARD", AccountStatus.ACTIVE);
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.EXPIRED;
        reservation.account = owner;
        reservation.amount = new BigDecimal("4.00");
        reservation.ticketRef = "RT";
        List<String> warnings = new ArrayList<>();
        Mockito.when(ledger.lock("RCARD")).thenReturn(owner);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(reservation);
            invoke("confirmReservationAtIngestion", new Class<?>[]{Long.class, LocalDate.class, List.class},
                    1L, FISCAL, warnings);
            assertEquals(List.of(WarningCode.EXPIRED_LEASE_CONFIRMED.name()), warnings);
            assertEquals(ReservationState.CONFIRMED, reservation.state);
            Mockito.verify(ledger).post(eq(owner), eq(MovementType.BURN),
                    argThat(a -> a.compareTo(new BigDecimal("-4.00")) == 0), eq(FISCAL), isNull(),
                    eq("RT"), eq(List.of()), isNull());
        }
    }

    /**
     * An active reservation whose lease has expired at the fixed instant confirms with the
     * expiry warning — the second leg of the expiry OR guard ({@code isExpiredAt} true), the
     * state being not EXPIRED (§29.2).
     */
    @Test
    @DisplayName("confirmReservationAtIngestion(): an active-but-lapsed lease confirms with a warning")
    void confirmActiveLeaseExpired() {
        FidelityAccount owner = account("RCARD", AccountStatus.ACTIVE);
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.ACTIVE;
        reservation.account = owner;
        reservation.amount = new BigDecimal("4.00");
        reservation.ticketRef = "RT";
        Mockito.when(reservation.isExpiredAt(FIXED)).thenReturn(true);
        List<String> warnings = new ArrayList<>();
        Mockito.when(ledger.lock("RCARD")).thenReturn(owner);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(reservation);
            invoke("confirmReservationAtIngestion", new Class<?>[]{Long.class, LocalDate.class, List.class},
                    1L, FISCAL, warnings);
            assertEquals(List.of(WarningCode.EXPIRED_LEASE_CONFIRMED.name()), warnings);
            assertEquals(ReservationState.CONFIRMED, reservation.state);
        }
    }

    /**
     * An active reservation whose lease is still live confirms without any warning — both legs
     * of the expiry OR guard false, tested at the fixed instant so the boundary is deterministic
     * (§24.6, §29.2).
     */
    @Test
    @DisplayName("confirmReservationAtIngestion(): a live lease confirms without a warning")
    void confirmActiveLeaseLive() {
        FidelityAccount owner = account("RCARD", AccountStatus.ACTIVE);
        FidelityReservation reservation = Mockito.mock(FidelityReservation.class);
        reservation.state = ReservationState.ACTIVE;
        reservation.account = owner;
        reservation.amount = new BigDecimal("4.00");
        reservation.ticketRef = "RT";
        Mockito.when(reservation.isExpiredAt(FIXED)).thenReturn(false);
        List<String> warnings = new ArrayList<>();
        Mockito.when(ledger.lock("RCARD")).thenReturn(owner);
        try (MockedStatic<PanacheEntityBase> panache = Mockito.mockStatic(PanacheEntityBase.class)) {
            panache.when(() -> PanacheEntityBase.findById(1L)).thenReturn(reservation);
            invoke("confirmReservationAtIngestion", new Class<?>[]{Long.class, LocalDate.class, List.class},
                    1L, FISCAL, warnings);
            assertTrue(warnings.isEmpty());
            assertEquals(ReservationState.CONFIRMED, reservation.state);
            Mockito.verify(ledger).post(eq(owner), eq(MovementType.BURN), any(), eq(FISCAL), isNull(),
                    eq("RT"), eq(List.of()), isNull());
        }
    }

    // --------------------------------------------------
    // ingestReturn(): hold vs process (§29.3)
    // --------------------------------------------------

    /**
     * A return without an origin reference is held awaiting its origin — the origin-null arm of
     * the ternary and the new-hold arm of the idempotency guard (§29.3).
     */
    @Test
    @DisplayName("ingestReturn(): a return with no origin reference is held")
    void ingestReturnNoOriginHeld() {
        EventDtos.TicketReturn event = returnEvent(null);
        try (MockedStatic<PendingReturn> pendings = Mockito.mockStatic(PendingReturn.class);
             MockedConstruction<PendingReturn> cons = Mockito.mockConstruction(PendingReturn.class)) {
            pendings.when(() -> PendingReturn.findByReturnTicketRef(RETURN_TICKET)).thenReturn(null);
            service.ingestReturn(event);
            assertEquals(1, cons.constructed().size());
            PendingReturn held = cons.constructed().get(0);
            assertEquals(RETURN_TICKET, held.returnTicketRef);
            Mockito.verify(held).persist();
            Mockito.verifyNoInteractions(ledger);
        }
    }

    /**
     * A return whose origin is not yet ingested is held, and an already-held return is not held
     * twice — the origin-not-found arm and the already-held arm of the idempotency guard (§29.3).
     */
    @Test
    @DisplayName("ingestReturn(): an already-held return is not duplicated")
    void ingestReturnAlreadyHeld() {
        EventDtos.TicketReturn event = returnEvent("O1");
        PendingReturn existing = Mockito.mock(PendingReturn.class);
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class);
             MockedStatic<PendingReturn> pendings = Mockito.mockStatic(PendingReturn.class);
             MockedConstruction<PendingReturn> cons = Mockito.mockConstruction(PendingReturn.class)) {
            traces.when(() -> EarnTrace.findByTicketRef("O1")).thenReturn(null);
            pendings.when(() -> PendingReturn.findByReturnTicketRef(RETURN_TICKET)).thenReturn(existing);
            service.ingestReturn(event);
            assertTrue(cons.constructed().isEmpty());
        }
    }

    /**
     * A return whose origin is ingested is processed against it — the origin-found arm delegating
     * to the return application (§29.3, §29.4). The origin carries no card, so the debit is
     * skipped and only the (absent) refund is considered.
     */
    @Test
    @DisplayName("ingestReturn(): a return with an ingested origin is processed")
    void ingestReturnProcessed() {
        EventDtos.TicketReturn event = returnEvent("O1");
        EarnTrace origin = new EarnTrace();
        origin.cardNumber = null;
        try (MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class)) {
            traces.when(() -> EarnTrace.findByTicketRef("O1")).thenReturn(origin);
            service.ingestReturn(event);
            Mockito.verifyNoInteractions(ledger);
        }
    }

    // --------------------------------------------------
    // processReturn(): the transfer-chain routing (§32.2)
    // --------------------------------------------------

    /**
     * A return on an origin bearing no card debits nothing — the {@code cardNumber == null} arm
     * and the first leg false of the live-account guard (§32.2, §34.2).
     */
    @Test
    @DisplayName("processReturn(): a cardless origin skips the debit")
    void processReturnCardlessOrigin() {
        EventDtos.TicketReturn event = returnEvent("O1");
        EarnTrace origin = new EarnTrace();
        origin.cardNumber = null;
        invoke("processReturn", new Class<?>[]{EventDtos.TicketReturn.class, EarnTrace.class}, event, origin);
        Mockito.verifyNoInteractions(ledger);
    }

    /**
     * A return whose card resolves to no live account debits nothing — the resolve-null arm, the
     * first leg false of the live-account guard (§32.2).
     */
    @Test
    @DisplayName("processReturn(): a return on an unresolvable card skips the debit")
    void processReturnUnresolvedCard() {
        EventDtos.TicketReturn event = returnEvent("O1");
        EarnTrace origin = new EarnTrace();
        origin.cardNumber = "CARD1";
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(null);
            invoke("processReturn", new Class<?>[]{EventDtos.TicketReturn.class, EarnTrace.class}, event, origin);
            Mockito.verifyNoInteractions(ledger);
        }
    }

    /**
     * A return routed to a resiliated account never moves the balance — the second leg false of
     * the live-account guard (§34.2).
     */
    @Test
    @DisplayName("processReturn(): a return on a resiliated account skips the debit")
    void processReturnResiliatedAccount() {
        EventDtos.TicketReturn event = returnEvent("O1");
        EarnTrace origin = new EarnTrace();
        origin.cardNumber = "CARD1";
        FidelityAccount account = account("CARD1", AccountStatus.RESILIATED);
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(account);
            invoke("processReturn", new Class<?>[]{EventDtos.TicketReturn.class, EarnTrace.class}, event, origin);
            Mockito.verify(ledger, Mockito.never()).post(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    /**
     * A return already applied on the ticket is not applied again — the {@code hasMovement} true
     * arm of the whole-ticket idempotency guard (§29.4).
     */
    @Test
    @DisplayName("processReturn(): an already-applied return is not applied twice")
    void processReturnIdempotent() {
        EventDtos.TicketReturn event = returnEvent("O1");
        event.lines.add(returnLine("L1", 1.0));
        EarnTrace origin = new EarnTrace();
        origin.cardNumber = "CARD1";
        origin.lines.add(traceLine("L1", "R1", "5.00", "1", "0.00", "0"));
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE);
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class)) {
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(account);
            movements.when(() -> FidelityMovement.hasMovementForTicket(RETURN_TICKET, MovementType.RETURN_DEBIT))
                    .thenReturn(true);
            invoke("processReturn", new Class<?>[]{EventDtos.TicketReturn.class, EarnTrace.class}, event, origin);
            Mockito.verify(ledger).lock("CARD1");
            Mockito.verify(ledger, Mockito.never()).post(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    /**
     * A fresh return on a live account applies the return debit — the both-legs-true live-account
     * guard and the {@code hasMovement} false arm (§29.4, §32.2).
     */
    @Test
    @DisplayName("processReturn(): a fresh return on a live account applies the debit")
    void processReturnAppliesDebit() {
        EventDtos.TicketReturn event = returnEvent("O1");
        event.lines.add(returnLine("L1", 1.0));
        EarnTrace origin = new EarnTrace();
        origin.cardNumber = "CARD1";
        origin.lines.add(traceLine("L1", "R1", "5.00", "1", "0.00", "0"));
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE);
        try (MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class);
             MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class)) {
            accounts.when(() -> FidelityAccount.resolveActive("CARD1")).thenReturn(account);
            movements.when(() -> FidelityMovement.hasMovementForTicket(RETURN_TICKET, MovementType.RETURN_DEBIT))
                    .thenReturn(false);
            invoke("processReturn", new Class<?>[]{EventDtos.TicketReturn.class, EarnTrace.class}, event, origin);
            Mockito.verify(ledger).post(eq(account), eq(MovementType.RETURN_DEBIT),
                    argThat(a -> a.compareTo(new BigDecimal("-5.00")) == 0), eq(RETURN_FISCAL), eq("R1"),
                    eq(RETURN_TICKET), eq(List.of("L1")), isNull());
        }
    }

    // --------------------------------------------------
    // applyReturnDebit(): the per-rule return debit bounded by the line earn (§29.4)
    // --------------------------------------------------

    /**
     * The return debit skips null and id-less returned lines, prorates the quantity (present and
     * defaulted), matches by line id, ignores fully-returned lines and cumulates by rule — every
     * leg of the returned-line guard, both arms of the quantity ternary, both arms of the line-id
     * match, both arms of the positive-debit guard and the merge/compute reuse of a repeated rule
     * (§29.4).
     */
    @Test
    @DisplayName("applyReturnDebit(): cumulates the per-rule debit bounded by the line earn")
    void applyReturnDebitCumulatesByRule() {
        EventDtos.TicketReturn event = returnEvent("O1");
        event.lines.add(null);
        event.lines.add(returnLine(null, 1.0));
        event.lines.add(returnLine("L1", 1.0));
        event.lines.add(returnLine("L2", null));
        event.lines.add(returnLine("L3", 1.0));
        event.lines.add(returnLine("LX", 1.0));
        EarnTrace origin = new EarnTrace();
        EarnTraceLine l1 = traceLine("L1", "R1", "5.00", "2", "0.00", "0");
        EarnTraceLine l2 = traceLine("L2", "R1", "3.00", "1", "0.00", "0");
        EarnTraceLine l3 = traceLine("L3", "R2", "1.00", "1", "1.00", "1");
        origin.lines.add(l1);
        origin.lines.add(l2);
        origin.lines.add(l3);
        FidelityAccount account = account("CARD1", AccountStatus.ACTIVE);
        invoke("applyReturnDebit", new Class<?>[]{EventDtos.TicketReturn.class, EarnTrace.class, FidelityAccount.class},
                event, origin, account);
        assertEquals(0, l1.returnedAmount.compareTo(new BigDecimal("2.50")));
        assertEquals(0, l2.returnedAmount.compareTo(new BigDecimal("3.00")));
        Mockito.verify(l1).persist();
        Mockito.verify(l2).persist();
        Mockito.verify(ledger).post(eq(account), eq(MovementType.RETURN_DEBIT),
                argThat(a -> a.compareTo(new BigDecimal("-5.50")) == 0), eq(RETURN_FISCAL), eq("R1"),
                eq(RETURN_TICKET), eq(List.of("L1", "L2")), isNull());
        Mockito.verify(ledger, Mockito.times(1)).post(any(), eq(MovementType.RETURN_DEBIT), any(), any(), any(), any(), any(), any());
    }

    // --------------------------------------------------
    // lineDebit(): the per-line return debit arithmetic (§29.4, §31.2)
    // --------------------------------------------------

    /**
     * Invokes the private {@code lineDebit} on a trace line for a requested quantity.
     *
     * @param traceLine    The origin trace line.
     * @param qtyRequested The requested return quantity.
     * @return The computed debit.
     */
    private BigDecimal lineDebit(EarnTraceLine traceLine, String qtyRequested) {
        return (BigDecimal) invoke("lineDebit", new Class<?>[]{EarnTraceLine.class, BigDecimal.class},
                traceLine, new BigDecimal(qtyRequested));
    }

    /**
     * A null original quantity defaults the denominator to one — the first leg false of the
     * original-quantity guard; the full earn is debited (§29.4, §31.2).
     */
    @Test
    @DisplayName("lineDebit(): a null original quantity defaults to one")
    void lineDebitNullQuantity() {
        EarnTraceLine line = traceLine("L1", "R1", "4.00", null, "0.00", "0");
        BigDecimal debit = lineDebit(line, "1");
        assertEquals(0, debit.compareTo(new BigDecimal("4.00")));
        assertEquals(0, line.returnedAmount.compareTo(new BigDecimal("4.00")));
    }

    /**
     * A zero original quantity defaults the denominator to one — the second leg false of the
     * original-quantity guard (§29.4, §31.2).
     */
    @Test
    @DisplayName("lineDebit(): a zero original quantity defaults to one")
    void lineDebitZeroQuantity() {
        EarnTraceLine line = traceLine("L1", "R1", "4.00", "0", "0.00", "0");
        BigDecimal debit = lineDebit(line, "1");
        assertEquals(0, debit.compareTo(new BigDecimal("4.00")));
    }

    /**
     * A line already fully returned yields no debit — the {@code returnableQty <= 0} arm (§29.4).
     */
    @Test
    @DisplayName("lineDebit(): a fully-returned line yields zero")
    void lineDebitFullyReturned() {
        EarnTraceLine line = traceLine("L1", "R1", "4.00", "1", "0.00", "1");
        BigDecimal debit = lineDebit(line, "1");
        assertEquals(0, debit.compareTo(BigDecimal.ZERO));
        assertEquals(0, line.returnedAmount.compareTo(new BigDecimal("0.00")));
    }

    /**
     * A non-positive requested quantity yields no debit — the {@code thisQty <= 0} arm, the
     * returnable quantity being positive (§29.4).
     */
    @Test
    @DisplayName("lineDebit(): a zero requested quantity yields zero")
    void lineDebitZeroRequested() {
        EarnTraceLine line = traceLine("L1", "R1", "4.00", "2", "0.00", "0");
        BigDecimal debit = lineDebit(line, "0");
        assertEquals(0, debit.compareTo(BigDecimal.ZERO));
    }

    /**
     * The debit is bounded by the residual earn of the line — the {@code debit > residual} arm:
     * a line partly returned before caps the new debit at what is left (§29.4).
     */
    @Test
    @DisplayName("lineDebit(): the debit is bounded by the residual earn")
    void lineDebitBoundedByResidual() {
        EarnTraceLine line = traceLine("L1", "R1", "5.00", "1", "4.00", "0");
        BigDecimal debit = lineDebit(line, "1");
        assertEquals(0, debit.compareTo(new BigDecimal("1.00")));
        assertEquals(0, line.returnedAmount.compareTo(new BigDecimal("5.00")));
    }

    /**
     * A line whose residual earn is already zero yields no debit even for a positive raw share —
     * the {@code debit <= 0} arm after the residual bound; the counters are left untouched (§29.4).
     */
    @Test
    @DisplayName("lineDebit(): a zero residual yields no debit and no counter advance")
    void lineDebitZeroResidual() {
        EarnTraceLine line = traceLine("L1", "R1", "5.00", "2", "5.00", "0");
        BigDecimal debit = lineDebit(line, "1");
        assertEquals(0, debit.compareTo(BigDecimal.ZERO));
        assertEquals(0, line.returnedAmount.compareTo(new BigDecimal("5.00")));
        assertEquals(0, line.returnedQuantity.compareTo(new BigDecimal("0")));
    }

    // --------------------------------------------------
    // applyRefundToCard(): the refund-to-card out of every cap (§28.6, §29.5)
    // --------------------------------------------------

    /**
     * Builds a return event carrying a refund-to-card of the given card and amount.
     *
     * @param card   The refunded card, or null.
     * @param amount The refunded amount, or null.
     * @return The return event with the refund.
     */
    private EventDtos.TicketReturn refundEvent(String card, String amount) {
        EventDtos.TicketReturn event = returnEvent("O1");
        EventDtos.RefundToCard refund = new EventDtos.RefundToCard();
        refund.card = card;
        refund.amount = amount == null ? null : new BigDecimal(amount);
        event.refundToCard = refund;
        return event;
    }

    /**
     * A return carrying no refund refunds nothing — the first leg of the refund guard (§28.6).
     */
    @Test
    @DisplayName("applyRefundToCard(): no refund block is a no-op")
    void refundNoBlock() {
        EventDtos.TicketReturn event = returnEvent("O1");
        invoke("applyRefundToCard", new Class<?>[]{EventDtos.TicketReturn.class}, event);
        Mockito.verifyNoInteractions(ledger);
    }

    /**
     * A refund with no card refunds nothing — the second leg of the refund guard (§28.6).
     */
    @Test
    @DisplayName("applyRefundToCard(): a cardless refund is a no-op")
    void refundNoCard() {
        EventDtos.TicketReturn event = refundEvent(null, "3.00");
        invoke("applyRefundToCard", new Class<?>[]{EventDtos.TicketReturn.class}, event);
        Mockito.verifyNoInteractions(ledger);
    }

    /**
     * A refund with no amount refunds nothing — the third leg of the refund guard (§28.6).
     */
    @Test
    @DisplayName("applyRefundToCard(): an amountless refund is a no-op")
    void refundNoAmount() {
        EventDtos.TicketReturn event = refundEvent("CARDR", null);
        invoke("applyRefundToCard", new Class<?>[]{EventDtos.TicketReturn.class}, event);
        Mockito.verifyNoInteractions(ledger);
    }

    /**
     * A refund of a zero amount refunds nothing — the fourth leg of the refund guard (§28.6).
     */
    @Test
    @DisplayName("applyRefundToCard(): a zero-amount refund is a no-op")
    void refundZeroAmount() {
        EventDtos.TicketReturn event = refundEvent("CARDR", "0.00");
        invoke("applyRefundToCard", new Class<?>[]{EventDtos.TicketReturn.class}, event);
        Mockito.verifyNoInteractions(ledger);
    }

    /**
     * A refund already posted on the ticket is not posted again — the {@code hasMovement} true
     * arm of the refund idempotency guard (§29.5).
     */
    @Test
    @DisplayName("applyRefundToCard(): an already-refunded ticket is a no-op")
    void refundAlreadyPosted() {
        EventDtos.TicketReturn event = refundEvent("CARDR", "3.00");
        try (MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class)) {
            movements.when(() -> FidelityMovement.hasMovementForTicket(RETURN_TICKET, MovementType.REFUND_CREDIT))
                    .thenReturn(true);
            invoke("applyRefundToCard", new Class<?>[]{EventDtos.TicketReturn.class}, event);
            Mockito.verifyNoInteractions(ledger);
        }
    }

    /**
     * A refund to an unknown card refunds nothing — the first leg of the account guard (§32.2).
     */
    @Test
    @DisplayName("applyRefundToCard(): a refund to an unknown card is a no-op")
    void refundUnknownAccount() {
        EventDtos.TicketReturn event = refundEvent("CARDR", "3.00");
        try (MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            movements.when(() -> FidelityMovement.hasMovementForTicket(RETURN_TICKET, MovementType.REFUND_CREDIT))
                    .thenReturn(false);
            accounts.when(() -> FidelityAccount.resolveActive("CARDR")).thenReturn(null);
            invoke("applyRefundToCard", new Class<?>[]{EventDtos.TicketReturn.class}, event);
            Mockito.verify(ledger, Mockito.never()).post(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    /**
     * A refund to a resiliated card refunds nothing — the second leg of the account guard (§34.2).
     */
    @Test
    @DisplayName("applyRefundToCard(): a refund to a resiliated card is a no-op")
    void refundResiliatedAccount() {
        EventDtos.TicketReturn event = refundEvent("CARDR", "3.00");
        FidelityAccount account = account("CARDR", AccountStatus.RESILIATED);
        try (MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            movements.when(() -> FidelityMovement.hasMovementForTicket(RETURN_TICKET, MovementType.REFUND_CREDIT))
                    .thenReturn(false);
            accounts.when(() -> FidelityAccount.resolveActive("CARDR")).thenReturn(account);
            invoke("applyRefundToCard", new Class<?>[]{EventDtos.TicketReturn.class}, event);
            Mockito.verify(ledger, Mockito.never()).post(any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    /**
     * A refund to a live card posts a REFUND_CREDIT of the absolute amount, out of every cap and
     * with a null rule code — the all-guards-passed path (§28.6, §29.5, §32.2). A negative carried
     * amount (a signum non-zero) is credited by its absolute value.
     */
    @Test
    @DisplayName("applyRefundToCard(): a live card is credited a REFUND_CREDIT of the absolute amount")
    void refundPosted() {
        EventDtos.TicketReturn event = refundEvent("CARDR", "-3.00");
        FidelityAccount account = account("CARDR", AccountStatus.ACTIVE);
        try (MockedStatic<FidelityMovement> movements = Mockito.mockStatic(FidelityMovement.class);
             MockedStatic<FidelityAccount> accounts = Mockito.mockStatic(FidelityAccount.class)) {
            movements.when(() -> FidelityMovement.hasMovementForTicket(RETURN_TICKET, MovementType.REFUND_CREDIT))
                    .thenReturn(false);
            accounts.when(() -> FidelityAccount.resolveActive("CARDR")).thenReturn(account);
            invoke("applyRefundToCard", new Class<?>[]{EventDtos.TicketReturn.class}, event);
            Mockito.verify(ledger).lock("CARDR");
            Mockito.verify(ledger).post(eq(account), eq(MovementType.REFUND_CREDIT),
                    argThat(a -> a.compareTo(new BigDecimal("3.00")) == 0), eq(RETURN_FISCAL), isNull(),
                    eq(RETURN_TICKET), eq(List.of()), isNull());
        }
    }

    // --------------------------------------------------
    // replayPendingReturns(): the held-return replay (§29.3)
    // --------------------------------------------------

    /**
     * No held return leaves nothing to replay — the empty-list arm (§29.3).
     */
    @Test
    @DisplayName("replayPendingReturns(): an empty backlog is a no-op")
    void replayEmptyBacklog() {
        try (MockedStatic<PendingReturn> pendings = Mockito.mockStatic(PendingReturn.class)) {
            pendings.when(() -> PendingReturn.listAwaitingOrigin(TICKET)).thenReturn(List.of());
            invoke("replayPendingReturns", new Class<?>[]{String.class}, TICKET);
            Mockito.verifyNoInteractions(objectMapper);
        }
    }

    /**
     * A held return is deserialized, processed against the just-ingested origin and removed — the
     * non-empty backlog, the read-success arm and the delete (§29.3). The origin carries no card,
     * so the replayed return only reaches the (absent) refund.
     */
    @Test
    @DisplayName("replayPendingReturns(): a held return is replayed and removed")
    void replayHeldReturn() {
        EarnTrace origin = new EarnTrace();
        origin.cardNumber = null;
        PendingReturn pending = Mockito.mock(PendingReturn.class);
        pending.returnTicketRef = RETURN_TICKET;
        pending.payload = "{}";
        EventDtos.TicketReturn replayed = returnEvent(TICKET);
        try (MockedStatic<PendingReturn> pendings = Mockito.mockStatic(PendingReturn.class);
             MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class)) {
            pendings.when(() -> PendingReturn.listAwaitingOrigin(TICKET)).thenReturn(List.of(pending));
            traces.when(() -> EarnTrace.findByTicketRef(TICKET)).thenReturn(origin);
            Mockito.when(objectMapper.readValue(anyString(), eq(EventDtos.TicketReturn.class))).thenReturn(replayed);
            invoke("replayPendingReturns", new Class<?>[]{String.class}, TICKET);
            Mockito.verify(pending).delete();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A held return whose payload cannot be deserialized is logged and still removed — the
     * read-failure catch arm followed by the delete (§29.3).
     */
    @Test
    @DisplayName("replayPendingReturns(): an unreadable held return is logged and removed")
    void replayHeldReturnFails() {
        EarnTrace origin = new EarnTrace();
        origin.cardNumber = null;
        PendingReturn pending = Mockito.mock(PendingReturn.class);
        pending.returnTicketRef = RETURN_TICKET;
        pending.payload = "broken";
        try (MockedStatic<PendingReturn> pendings = Mockito.mockStatic(PendingReturn.class);
             MockedStatic<EarnTrace> traces = Mockito.mockStatic(EarnTrace.class)) {
            pendings.when(() -> PendingReturn.listAwaitingOrigin(TICKET)).thenReturn(List.of(pending));
            traces.when(() -> EarnTrace.findByTicketRef(TICKET)).thenReturn(origin);
            Mockito.when(objectMapper.readValue(anyString(), eq(EventDtos.TicketReturn.class)))
                    .thenThrow(new RuntimeException("boom"));
            invoke("replayPendingReturns", new Class<?>[]{String.class}, TICKET);
            Mockito.verify(pending).delete();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --------------------------------------------------
    // resolveCardNumber(): the authoritative card (§27.4)
    // --------------------------------------------------

    /**
     * The request customer code is authoritative and trimmed — the all-legs-true path (§27.4).
     */
    @Test
    @DisplayName("resolveCardNumber(): the request customer code is authoritative and trimmed")
    void resolveFromRequest() {
        EventDtos.TicketClosed event = closed("EVT", "  CARD1  ", FISCAL);
        assertEquals("CARD1", invoke("resolveCardNumber", new Class<?>[]{EventDtos.TicketClosed.class}, event));
    }

    /**
     * A missing request falls back to the event card, trimmed — the first leg false of the
     * request guard and the event-card branch (§27.4).
     */
    @Test
    @DisplayName("resolveCardNumber(): a missing request falls back to the event card")
    void resolveFallbackToEventCard() {
        EventDtos.TicketClosed event = closed("  EVT  ", null, FISCAL);
        assertEquals("EVT", invoke("resolveCardNumber", new Class<?>[]{EventDtos.TicketClosed.class}, event));
    }

    /**
     * A request with a null customer code and a null event card resolves to null — the second leg
     * false of the request guard and the null event-card branch (§27.4).
     */
    @Test
    @DisplayName("resolveCardNumber(): a null request card and a null event card resolve to null")
    void resolveNullCustomerNullCard() {
        EventDtos.TicketClosed event = new EventDtos.TicketClosed();
        event.valuationRequest = request(null, "ST1");
        event.card = null;
        assertNull(invoke("resolveCardNumber", new Class<?>[]{EventDtos.TicketClosed.class}, event));
    }

    /**
     * A request with a blank customer code and a blank event card resolves to null — the third leg
     * false of the request guard and the blank event-card branch (§27.4).
     */
    @Test
    @DisplayName("resolveCardNumber(): a blank request card and a blank event card resolve to null")
    void resolveBlankCustomerBlankCard() {
        EventDtos.TicketClosed event = new EventDtos.TicketClosed();
        event.valuationRequest = request("   ", "ST1");
        event.card = "   ";
        assertNull(invoke("resolveCardNumber", new Class<?>[]{EventDtos.TicketClosed.class}, event));
    }

    // --------------------------------------------------
    // addTraceLines(): the pro-rata trace split (§24.2, §29.4)
    // --------------------------------------------------

    /**
     * The per-line trace splits the earn pro-rata by eligible net with a last-line remainder, and
     * an unresolved line contributes a zero base, a null EAN and a unit quantity — the prorata arm,
     * the last-line arm and all three {@code line != null} ternaries on both arms (§24.2, §29.4).
     */
    @Test
    @DisplayName("addTraceLines(): splits the earn pro-rata with a last-line remainder")
    void addTraceLinesProrata() {
        EarnTrace trace = new EarnTrace();
        EarnResponse.Entry entry = new EarnResponse.Entry("R1", "R1-label", new BigDecimal("6.00"),
                new BigDecimal("30.00"), Arrays.asList("L1", "L2", "L3"));
        Map<String, ValuedLine> lineById = new LinkedHashMap<>();
        lineById.put("L1", new ValuedLine("L1", "EAN1", new BigDecimal("2"), new BigDecimal("10.00"), new BigDecimal("10.00"), false));
        lineById.put("L2", new ValuedLine("L2", "EAN2", new BigDecimal("3"), new BigDecimal("20.00"), new BigDecimal("20.00"), false));
        invoke("addTraceLines", new Class<?>[]{EarnTrace.class, EarnResponse.Entry.class, Map.class},
                trace, entry, lineById);
        assertEquals(3, trace.lines.size());
        assertEquals(0, trace.lines.get(0).earnAmount.compareTo(new BigDecimal("2.00")));
        assertEquals(0, trace.lines.get(1).earnAmount.compareTo(new BigDecimal("4.00")));
        assertEquals(0, trace.lines.get(2).earnAmount.compareTo(new BigDecimal("0.00")));
        assertEquals(0, trace.lines.get(0).baseAmount.compareTo(new BigDecimal("10.00")));
        assertEquals(0, trace.lines.get(2).baseAmount.compareTo(new BigDecimal("0.00")));
        assertNull(trace.lines.get(2).ean);
        assertEquals(0, trace.lines.get(2).quantity.compareTo(BigDecimal.ONE));
    }

    /**
     * A zero total base takes the non-prorata zero branch for the non-last lines — the
     * {@code totalBase.signum() > 0} false arm; every line earns zero (§31.2).
     */
    @Test
    @DisplayName("addTraceLines(): a zero total base yields zero for the non-last lines")
    void addTraceLinesZeroBase() {
        EarnTrace trace = new EarnTrace();
        EarnResponse.Entry entry = new EarnResponse.Entry("R1", "R1-label", new BigDecimal("0.00"),
                new BigDecimal("0.00"), Arrays.asList("A", "B"));
        Map<String, ValuedLine> lineById = new LinkedHashMap<>();
        lineById.put("A", new ValuedLine("A", "EANA", BigDecimal.ONE, new BigDecimal("0.00"), new BigDecimal("0.00"), false));
        lineById.put("B", new ValuedLine("B", "EANB", BigDecimal.ONE, new BigDecimal("0.00"), new BigDecimal("0.00"), false));
        invoke("addTraceLines", new Class<?>[]{EarnTrace.class, EarnResponse.Entry.class, Map.class},
                trace, entry, lineById);
        assertEquals(2, trace.lines.size());
        assertEquals(0, trace.lines.get(0).earnAmount.compareTo(new BigDecimal("0.00")));
        assertEquals(0, trace.lines.get(1).earnAmount.compareTo(new BigDecimal("0.00")));
    }

    // --------------------------------------------------
    // collectWarnings(): reading warnings into the trace (§25.4)
    // --------------------------------------------------

    /**
     * A reading warning with an EAN is suffixed by it, one without an EAN is not — both arms of
     * the EAN ternary (§25.4, §27.1).
     */
    @Test
    @DisplayName("collectWarnings(): an EAN warning is suffixed, a bare warning is not")
    void collectWarningsEanSuffix() {
        EarnResult result = emptyResult();
        result.warnings.add(new EarnResponse.Warning(WarningCode.UNKNOWN_EAN.name(), "3000000000001", "unknown"));
        result.warnings.add(new EarnResponse.Warning(WarningCode.UNKNOWN_EAN.name(), null, "generic"));
        List<String> warnings = new ArrayList<>();
        invoke("collectWarnings", new Class<?>[]{EarnResult.class, List.class}, result, warnings);
        assertEquals(List.of(WarningCode.UNKNOWN_EAN.name() + ":3000000000001", WarningCode.UNKNOWN_EAN.name()),
                warnings);
    }

    // --------------------------------------------------
    // displayedTotal(): the displayed earn sum (§26.1)
    // --------------------------------------------------

    /**
     * A null displayed list carries no displayed total — the first leg of the emptiness guard
     * (§26.1, §31.2).
     */
    @Test
    @DisplayName("displayedTotal(): a null displayed list is null")
    void displayedTotalNull() {
        EventDtos.TicketClosed event = new EventDtos.TicketClosed();
        event.displayedEarn = null;
        assertNull(invoke("displayedTotal", new Class<?>[]{EventDtos.TicketClosed.class}, event));
    }

    /**
     * An empty displayed list carries no displayed total — the second leg of the emptiness guard
     * (§26.1).
     */
    @Test
    @DisplayName("displayedTotal(): an empty displayed list is null")
    void displayedTotalEmpty() {
        EventDtos.TicketClosed event = new EventDtos.TicketClosed();
        assertNull(invoke("displayedTotal", new Class<?>[]{EventDtos.TicketClosed.class}, event));
    }

    /**
     * A displayed list sums its non-null amounts, skipping null entries and null amounts — the
     * both-legs-true accumulation and both skip arms (§26.1, §31.2).
     */
    @Test
    @DisplayName("displayedTotal(): sums the non-null amounts, skipping the rest")
    void displayedTotalSum() {
        EventDtos.TicketClosed event = new EventDtos.TicketClosed();
        withDisplayed(event, "2.00");
        event.displayedEarn.add(null);
        EventDtos.DisplayedEarn nullAmount = new EventDtos.DisplayedEarn();
        nullAmount.amount = null;
        event.displayedEarn.add(nullAmount);
        withDisplayed(event, "1.50");
        BigDecimal total = (BigDecimal) invoke("displayedTotal", new Class<?>[]{EventDtos.TicketClosed.class}, event);
        assertEquals(0, total.compareTo(new BigDecimal("3.50")));
    }

    // --------------------------------------------------
    // toJson(): the debug payload serialization (§31.2)
    // --------------------------------------------------

    /**
     * A null value serializes to null — the null arm (§31.2).
     */
    @Test
    @DisplayName("toJson(): a null value is null")
    void toJsonNull() {
        assertNull(invoke("toJson", new Class<?>[]{Object.class}, new Object[]{null}));
    }

    /**
     * A serializable value is serialized — the success arm (§31.2).
     *
     * @throws Exception never; declared for the mapper stub.
     */
    @Test
    @DisplayName("toJson(): a serializable value is written")
    void toJsonSuccess() throws Exception {
        Mockito.when(objectMapper.writeValueAsString(any())).thenReturn("{\"a\":1}");
        assertEquals("{\"a\":1}", invoke("toJson", new Class<?>[]{Object.class}, "value"));
    }

    /**
     * A value the mapper cannot serialize yields null rather than throwing — the catch arm; a
     * trace payload is a debugging aid, not a correctness dependency (§31.2).
     *
     * @throws Exception never; declared for the mapper stub.
     */
    @Test
    @DisplayName("toJson(): a serialization failure yields null")
    void toJsonFailure() throws Exception {
        Mockito.when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
        assertNull(invoke("toJson", new Class<?>[]{Object.class}, "value"));
    }

    // --------------------------------------------------
    // today() / nowAtProgram(): the program clock (§24.6, §30.3)
    // --------------------------------------------------

    /**
     * The current fiscal day and program instant resolve from the fixed clock, never the real one
     * (§24.6, §30.3).
     */
    @Test
    @DisplayName("today()/nowAtProgram(): resolve from the fixed program clock")
    void clockResolvesFromFixedInstant() {
        assertEquals(FIXED, invoke("nowAtProgram", new Class<?>[]{}));
        assertEquals(FIXED.toLocalDate(), invoke("today", new Class<?>[]{}));
    }
}
