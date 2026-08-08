package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.intermarche.fidelity.domain.FidelityAccount;
import com.intermarche.fidelity.domain.util.ProgramClock;
import com.intermarche.fidelity.earn.EarnEngine;
import com.intermarche.fidelity.earn.EarnResult;
import com.intermarche.fidelity.earn.ValuationReading;
import com.intermarche.fidelity.earn.ValuationReconciliationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/**
 * Plain unit coverage for {@link SimulatorUiResource}: the empty {@code GET} render, the
 * {@code POST simulate} read (§23.5, §30.2) and its private {@code parseDateTime} helper
 * (§26.3, §31.2). Every collaborator is mocked with Mockito: the {@link com.intermarche.fidelity.earn.ValuationReader}
 * and {@link EarnEngine}, the program {@link ProgramClock} as a fixed clock so no test ever
 * reads the real wall time (§24.6), and the {@link FidelityAccount#findByCardNumber(String)}
 * static finder plus the {@link SimulatorView} factories with {@code mockStatic} in
 * try-with-resources where a branch is observed through them.
 * <p>
 * The {@code static native} Qute template of the nested {@code Templates} class has no
 * instrumentable body, so under a plain unit run every guard and ternary argument of the
 * render is evaluated fully (that computation carries the branches) and then the native
 * boundary raises an {@link UnsatisfiedLinkError}; each render test drives one arm and
 * asserts that boundary, verifying the pre-boundary work (the engine call, the factory call)
 * on the mocks. Each guard is covered on both arms and, for compound guards, on each leg
 * (§29, §29.6): the {@code response == null} ternary, the {@code card == null || card.isBlank()}
 * legs, the {@code account == null} note ternary, both catch arms of {@code simulate}, the
 * {@code value == null || value.isBlank()} legs of {@code parseDateTime}, its
 * {@code length() == 16} / {@code length() == 10} / else ternary legs and its parse-failure
 * catch.
 */
class SimulatorUiResourceTest {

    /**
     * A fixed program date-time driving the default and fallback evaluation instant — the
     * campaign never reads the real clock (§24.6).
     */
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 9, 12, 0, 0);

    /**
     * A fixed program date backing the empty-form prefill (§25.1).
     */
    private static final LocalDate FIXED_TODAY = LocalDate.of(2026, 8, 9);

    /**
     * The system under test, freshly built per test with its mocked collaborators.
     */
    private SimulatorUiResource resource;

    /**
     * The mocked valuation reader.
     */
    private com.intermarche.fidelity.earn.ValuationReader reader;

    /**
     * The mocked earn engine.
     */
    private EarnEngine engine;

    /**
     * The mocked program clock, fixed to {@link #FIXED_NOW} / {@link #FIXED_TODAY}.
     */
    private ProgramClock clock;

    /**
     * A reusable reading returned by the reader mock on the success paths.
     */
    private ValuationReading reading;

    /**
     * Wires a fresh resource with its mocked collaborators and a fixed clock.
     */
    @BeforeEach
    void setUp() {
        resource = new SimulatorUiResource();
        reader = mock(com.intermarche.fidelity.earn.ValuationReader.class);
        engine = mock(EarnEngine.class);
        clock = mock(ProgramClock.class);
        resource.reader = reader;
        resource.engine = engine;
        resource.clock = clock;
        when(clock.now()).thenReturn(FIXED_NOW);
        when(clock.today()).thenReturn(FIXED_TODAY);
        reading = new ValuationReading(List.of(), BigDecimal.ZERO, List.of());
        when(reader.read(any())).thenReturn(reading);
        when(engine.evaluate(any(), any(), any(), eq(true))).thenReturn(new EarnResult(List.of()));
    }

    /**
     * Builds a bare account carrying the given card number.
     *
     * @param cardNumber The card number.
     * @return The account.
     */
    private FidelityAccount account(String cardNumber) {
        FidelityAccount account = new FidelityAccount();
        account.cardNumber = cardNumber;
        return account;
    }

    // --------------------------------------------------
    // GET simulator
    // --------------------------------------------------

    /**
     * {@code simulator}: the empty render prefills the reference card and the program day at
     * 10:00, then reaches the native template boundary.
     */
    @Test
    @DisplayName("simulator: empty form reaches the native boundary")
    void simulatorEmpty() {
        assertThrows(UnsatisfiedLinkError.class, () -> resource.simulator());
        verify(clock).today();
    }

    // --------------------------------------------------
    // POST simulate — success paths
    // --------------------------------------------------

    /**
     * {@code simulate}: a null response takes the {@code "{}"} ternary arm, a null card takes
     * the first {@code card == null} leg to a null account and thus the note ternary true arm,
     * and a null date drives {@code parseDateTime} to its first guard leg, so the engine is
     * evaluated at the program {@code now}.
     */
    @Test
    @DisplayName("simulate: null response, null card, null date")
    void simulateNullEverything() {
        ArgumentCaptor<LocalDateTime> when = ArgumentCaptor.forClass(LocalDateTime.class);
        assertThrows(UnsatisfiedLinkError.class, () -> resource.simulate(null, null, null));
        verify(engine).evaluate(eq(reading), isNull(), when.capture(), eq(true));
        assertEquals(FIXED_NOW, when.getValue());
    }

    /**
     * {@code simulate}: a non-null response takes the false ternary arm, a blank card takes the
     * second {@code card.isBlank()} leg to a null account (note true arm), and a blank date
     * drives {@code parseDateTime} to its second guard leg, so the engine is evaluated at the
     * program {@code now}.
     */
    @Test
    @DisplayName("simulate: non-null response, blank card, blank date")
    void simulateBlankCardBlankDate() {
        ArgumentCaptor<LocalDateTime> when = ArgumentCaptor.forClass(LocalDateTime.class);
        assertThrows(UnsatisfiedLinkError.class, () -> resource.simulate("{}", "   ", "   "));
        verify(engine).evaluate(eq(reading), isNull(), when.capture(), eq(true));
        assertEquals(FIXED_NOW, when.getValue());
    }

    /**
     * {@code simulate}: a non-blank card drives both legs of the account guard false and looks
     * the trimmed card up; a known account takes the note ternary false arm, and a length-16
     * date parses through the {@code + ":00"} branch to the exact minute.
     */
    @Test
    @DisplayName("simulate: known card, length-16 date")
    void simulateKnownCardLength16() {
        FidelityAccount account = account("CARD");
        ArgumentCaptor<LocalDateTime> when = ArgumentCaptor.forClass(LocalDateTime.class);
        try (MockedStatic<FidelityAccount> accounts = mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber("CARD")).thenReturn(account);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.simulate("{}", "CARD ", "2026-08-09T10:00"));
            accounts.verify(() -> FidelityAccount.findByCardNumber("CARD"));
        }
        verify(engine).evaluate(eq(reading), eq(account), when.capture(), eq(true));
        assertEquals(LocalDateTime.of(2026, 8, 9, 10, 0, 0), when.getValue());
    }

    /**
     * {@code simulate}: a non-blank card whose lookup yields null takes the note ternary true
     * arm through {@code account == null}, and a length-10 date parses through the
     * {@code + "T00:00:00"} branch to midnight.
     */
    @Test
    @DisplayName("simulate: unknown card, length-10 date")
    void simulateUnknownCardLength10() {
        ArgumentCaptor<LocalDateTime> when = ArgumentCaptor.forClass(LocalDateTime.class);
        try (MockedStatic<FidelityAccount> accounts = mockStatic(FidelityAccount.class)) {
            accounts.when(() -> FidelityAccount.findByCardNumber("ZZZ")).thenReturn(null);
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.simulate("{}", "ZZZ", "2026-08-09"));
        }
        verify(engine).evaluate(eq(reading), isNull(), when.capture(), eq(true));
        assertEquals(LocalDateTime.of(2026, 8, 9, 0, 0, 0), when.getValue());
    }

    /**
     * {@code simulate}: a full ISO date-time of neither 16 nor 10 characters takes the else
     * ternary arm of {@code parseDateTime} and parses verbatim.
     */
    @Test
    @DisplayName("simulate: length-19 date takes the else parse arm")
    void simulateLength19Date() {
        ArgumentCaptor<LocalDateTime> when = ArgumentCaptor.forClass(LocalDateTime.class);
        assertThrows(UnsatisfiedLinkError.class,
                () -> resource.simulate("{}", null, "2026-08-09T10:00:00"));
        verify(engine).evaluate(eq(reading), isNull(), when.capture(), eq(true));
        assertEquals(LocalDateTime.of(2026, 8, 9, 10, 0, 0), when.getValue());
    }

    /**
     * {@code simulate}: an unparseable non-blank date drives {@code parseDateTime} into its
     * catch arm, falling back to the program {@code now}.
     */
    @Test
    @DisplayName("simulate: malformed date falls back to now")
    void simulateMalformedDate() {
        ArgumentCaptor<LocalDateTime> when = ArgumentCaptor.forClass(LocalDateTime.class);
        assertThrows(UnsatisfiedLinkError.class,
                () -> resource.simulate("{}", null, "notadate"));
        verify(engine).evaluate(eq(reading), isNull(), when.capture(), eq(true));
        assertEquals(FIXED_NOW, when.getValue());
    }

    // --------------------------------------------------
    // POST simulate — error paths
    // --------------------------------------------------

    /**
     * {@code simulate}: a {@link ValuationReconciliationException} from the reader takes the
     * first catch arm and builds an error view carrying the §22.1 prefix.
     */
    @Test
    @DisplayName("simulate: reconciliation failure takes the first catch")
    void simulateReconciliationFailure() {
        when(reader.read(any())).thenThrow(new ValuationReconciliationException("boom"));
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        try (MockedStatic<SimulatorView> views = mockStatic(SimulatorView.class)) {
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.simulate("{}", null, null));
            views.verify(() -> SimulatorView.error(any(), any(), any(), message.capture()));
        }
        assertTrue(message.getValue().startsWith("Réconciliation §22.1 échouée : "));
        assertTrue(message.getValue().contains("boom"));
    }

    /**
     * {@code simulate}: any other exception from the reader takes the generic catch arm and
     * builds an error view carrying the invalid-couple prefix.
     */
    @Test
    @DisplayName("simulate: generic failure takes the second catch")
    void simulateGenericFailure() {
        when(reader.read(any())).thenThrow(new IllegalStateException("nope"));
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        try (MockedStatic<SimulatorView> views = mockStatic(SimulatorView.class)) {
            assertThrows(UnsatisfiedLinkError.class,
                    () -> resource.simulate("{}", null, null));
            views.verify(() -> SimulatorView.error(any(), any(), any(), message.capture()));
        }
        assertTrue(message.getValue().startsWith("Couple invalide : "));
        assertTrue(message.getValue().contains("nope"));
    }
}
