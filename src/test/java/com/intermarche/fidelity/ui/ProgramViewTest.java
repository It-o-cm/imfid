package com.intermarche.fidelity.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.intermarche.fidelity.domain.BatchRunLog;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plain unit coverage for {@link ProgramView}, the Programme screen view model (§23.4). The
 * class carries no clock and no Panache access: it copies its constructor arguments, coalesces
 * a null notice to the empty string and exposes two boolean guards. Both arms of the notice
 * ternary, both arms of {@link ProgramView#isHasNotice()} and both arms of
 * {@link ProgramView.Batch#isHasRun()} are exercised; the nested {@link ProgramView.Setting}
 * and {@link ProgramView.Batch} rows are asserted field by field. No boot, no H2, no static
 * finder and no real clock (§24.6).
 */
class ProgramViewTest {

    /**
     * Asserts that the constructor stores every argument verbatim and, for a non-null notice,
     * keeps it as supplied. Covers the non-null arm of the {@code notice == null ? "" : notice}
     * ternary and the {@code canWrite}/{@code noticeOk} pass-through.
     */
    @Test
    @DisplayName("constructor keeps a non-null notice verbatim")
    void constructorKeepsNonNullNotice() {
        List<ProgramView.Setting> settings = List.of(new ProgramView.Setting("k", "l", "v"));
        List<ProgramView.Batch> batches = List.of(new ProgramView.Batch("EXPIRY", null));
        ProgramView view = new ProgramView(settings, batches, true, "saved", true);
        assertSame(settings, view.settings);
        assertSame(batches, view.batches);
        assertTrue(view.canWrite);
        assertEquals("saved", view.notice);
        assertTrue(view.noticeOk);
    }

    /**
     * Asserts that a null notice is coalesced to the empty string. Covers the null arm of the
     * {@code notice == null ? "" : notice} ternary and the {@code false} values of the two
     * boolean flags.
     */
    @Test
    @DisplayName("constructor coalesces a null notice to empty")
    void constructorCoalescesNullNotice() {
        ProgramView view = new ProgramView(List.of(), List.of(), false, null, false);
        assertEquals("", view.notice);
        assertFalse(view.canWrite);
        assertFalse(view.noticeOk);
    }

    /**
     * Asserts that a non-empty notice is reported as present. Covers the {@code true} arm of
     * {@link ProgramView#isHasNotice()}.
     */
    @Test
    @DisplayName("isHasNotice is true for a non-empty notice")
    void isHasNoticeTrue() {
        ProgramView view = new ProgramView(List.of(), List.of(), true, "done", true);
        assertTrue(view.isHasNotice());
    }

    /**
     * Asserts that an empty (here, coalesced-from-null) notice is reported as absent. Covers the
     * {@code false} arm of {@link ProgramView#isHasNotice()}.
     */
    @Test
    @DisplayName("isHasNotice is false for an empty notice")
    void isHasNoticeFalse() {
        ProgramView view = new ProgramView(List.of(), List.of(), true, null, false);
        assertFalse(view.isHasNotice());
    }

    /**
     * Asserts that a setting row copies its three fields verbatim.
     */
    @Test
    @DisplayName("Setting copies its fields verbatim")
    void settingCopiesFields() {
        ProgramView.Setting setting = new ProgramView.Setting("program.zone", "Fuseau", "Europe/Paris");
        assertEquals("program.zone", setting.key);
        assertEquals("Fuseau", setting.label);
        assertEquals("Europe/Paris", setting.value);
    }

    /**
     * Asserts that a batch card with a non-null last run copies its fields and reports a run.
     * Covers the {@code true} arm of {@link ProgramView.Batch#isHasRun()}.
     */
    @Test
    @DisplayName("Batch with a last run reports hasRun")
    void batchHasRun() {
        BatchRunLog lastRun = new BatchRunLog();
        ProgramView.Batch batch = new ProgramView.Batch("EXPIRY", lastRun);
        assertEquals("EXPIRY", batch.type);
        assertSame(lastRun, batch.lastRun);
        assertTrue(batch.isHasRun());
    }

    /**
     * Asserts that a batch card that never ran carries a null last run and reports no run.
     * Covers the {@code false} arm of {@link ProgramView.Batch#isHasRun()}.
     */
    @Test
    @DisplayName("Batch without a last run reports no run")
    void batchNeverRan() {
        ProgramView.Batch batch = new ProgramView.Batch("PURGE", null);
        assertEquals("PURGE", batch.type);
        assertNull(batch.lastRun);
        assertFalse(batch.isHasRun());
    }
}
