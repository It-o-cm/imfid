package com.intermarche.fidelity.ui;

import com.intermarche.fidelity.domain.BatchRunLog;

import java.util.List;

/**
 * The view model of the Programme screen (§23.4): the editable settings and the batch
 * supervision cards.
 * <p>
 * Public fields are resolved by Qute.
 */
public final class ProgramView {

    /**
     * The program settings.
     */
    public final List<Setting> settings;

    /**
     * The batches with their last run.
     */
    public final List<Batch> batches;

    /**
     * Whether the user may write (§21.4).
     */
    public final boolean canWrite;

    /**
     * A one-shot notice, or empty.
     */
    public final String notice;

    /**
     * Whether the notice reports a success.
     */
    public final boolean noticeOk;

    /**
     * Builds the program view.
     *
     * @param settings The settings.
     * @param batches  The batches.
     * @param canWrite Whether the user may write.
     * @param notice   A one-shot notice, or null.
     * @param noticeOk Whether the notice reports a success.
     */
    public ProgramView(List<Setting> settings, List<Batch> batches, boolean canWrite, String notice, boolean noticeOk) {
        this.settings = settings;
        this.batches = batches;
        this.canWrite = canWrite;
        this.notice = notice == null ? "" : notice;
        this.noticeOk = noticeOk;
    }

    /**
     * Indicates whether a notice must be shown.
     *
     * @return true when a notice is present.
     */
    public boolean isHasNotice() {
        return !notice.isEmpty();
    }

    /**
     * One editable program setting.
     */
    public static final class Setting {

        /**
         * The setting key.
         */
        public final String key;

        /**
         * The human label.
         */
        public final String label;

        /**
         * The current value.
         */
        public final String value;

        /**
         * Builds a setting row.
         *
         * @param key   The key.
         * @param label The label.
         * @param value The value.
         */
        public Setting(String key, String label, String value) {
            this.key = key;
            this.label = label;
            this.value = value;
        }
    }

    /**
     * One batch supervision card.
     */
    public static final class Batch {

        /**
         * The batch type name.
         */
        public final String type;

        /**
         * The last run, or null when it never ran.
         */
        public final BatchRunLog lastRun;

        /**
         * Builds a batch card.
         *
         * @param type    The batch type name.
         * @param lastRun The last run, or null.
         */
        public Batch(String type, BatchRunLog lastRun) {
            this.type = type;
            this.lastRun = lastRun;
        }

        /**
         * Indicates whether the batch has ever run.
         *
         * @return true when a last run exists.
         */
        public boolean isHasRun() {
            return lastRun != null;
        }
    }
}
