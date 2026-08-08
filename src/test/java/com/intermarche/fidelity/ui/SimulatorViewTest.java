package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.earn.EarnResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage of {@link SimulatorView}: the three factories, the null-collapsing
 * ternaries of the private constructor (both arms each), and the three presence predicates.
 */
final class SimulatorViewTest {

    /**
     * {@link SimulatorView#empty(String, String)} is not submitted, carries no result / error /
     * note and echoes the prefilled card and date; response defaults to the empty string.
     */
    @Test
    void emptyBuildsInitialView() {
        SimulatorView view = SimulatorView.empty("CARD-1", "2026-01-02T00:00:00");
        assertFalse(view.submitted);
        assertEquals("", view.response);
        assertEquals("CARD-1", view.card);
        assertEquals("2026-01-02T00:00:00", view.date);
        assertNull(view.result);
        assertNull(view.error);
        assertNull(view.note);
        assertFalse(view.isHasResult());
        assertFalse(view.isHasError());
        assertFalse(view.isHasNote());
    }

    /**
     * {@link SimulatorView#empty(String, String)} with null card and date exercises the null arms
     * of the card and date ternaries, collapsing both to the empty string.
     */
    @Test
    void emptyCollapsesNullCardAndDate() {
        SimulatorView view = SimulatorView.empty(null, null);
        assertEquals("", view.card);
        assertEquals("", view.date);
    }

    /**
     * {@link SimulatorView#of(String, String, String, EarnResult, String)} is submitted, keeps the
     * given non-null response, card, date, result and note, and reports no error.
     */
    @Test
    void ofBuildsResultViewWithNote() {
        EarnResult result = new EarnResult(List.of());
        SimulatorView view = SimulatorView.of("{\"total\":0}", "CARD-9", "2026-06-30T12:00:00",
                result, "unknown card");
        assertTrue(view.submitted);
        assertEquals("{\"total\":0}", view.response);
        assertEquals("CARD-9", view.card);
        assertEquals("2026-06-30T12:00:00", view.date);
        assertSame(result, view.result);
        assertNull(view.error);
        assertEquals("unknown card", view.note);
        assertTrue(view.isHasResult());
        assertFalse(view.isHasError());
        assertTrue(view.isHasNote());
    }

    /**
     * {@link SimulatorView#of(String, String, String, EarnResult, String)} with a null response
     * exercises the null arm of the response ternary, while a null note leaves {@code isHasNote}
     * false; the non-null result still reports present.
     */
    @Test
    void ofCollapsesNullResponseAndKeepsNullNote() {
        EarnResult result = new EarnResult(List.of());
        SimulatorView view = SimulatorView.of(null, "CARD-2", "2026-07-01T00:00:00", result, null);
        assertEquals("", view.response);
        assertSame(result, view.result);
        assertNull(view.note);
        assertTrue(view.isHasResult());
        assertFalse(view.isHasNote());
    }

    /**
     * {@link SimulatorView#error(String, String, String, String)} is submitted, carries the error
     * message, has no result nor note, and echoes the pasted response, card and date.
     */
    @Test
    void errorBuildsErrorView() {
        SimulatorView view = SimulatorView.error("bad json", "CARD-3", "2026-12-31T23:59:59",
                "parse failure");
        assertTrue(view.submitted);
        assertEquals("bad json", view.response);
        assertEquals("CARD-3", view.card);
        assertEquals("2026-12-31T23:59:59", view.date);
        assertNull(view.result);
        assertEquals("parse failure", view.error);
        assertNull(view.note);
        assertFalse(view.isHasResult());
        assertTrue(view.isHasError());
        assertFalse(view.isHasNote());
    }
}
