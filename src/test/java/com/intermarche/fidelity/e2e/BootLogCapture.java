package com.intermarche.fidelity.e2e;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logmanager.ExtLogRecord;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Test-only boot-log recorder for the group A scenarios (e2escenarios-imfid.md "## A").
 * <p>
 * A1 asserts the three contractual startup log lines emitted by the seed
 * ({@code Dev/test dataset loaded: …}), the earn rule registry
 * ({@code Earn rule registry started: …}) and the security bootstrap
 * ({@code Bootstrap users created: …}). Quarkus configures logging before the
 * {@code StartupEvent} observers run, so this bean attaches a JBoss LogManager handler
 * to the root logger at observer priority 1 — earlier than the registry and bootstrap
 * (CDI default 2500) and the seed ({@code @Priority(2700)}) — and every record they emit
 * afterwards is captured, in emission order, into a static list the test reads. Being a
 * CDI bean in the test sources, it takes part in the single application boot; the handler
 * is harmless for every other group's run.
 */
@ApplicationScoped
public class BootLogCapture {

    /**
     * The captured formatted log messages, in emission order, populated during boot.
     */
    private static final CopyOnWriteArrayList<String> MESSAGES = new CopyOnWriteArrayList<>();

    /**
     * Guards against installing the handler twice should the startup event ever fire again.
     */
    private static volatile boolean installed = false;

    /**
     * Installs the root-logger capture handler as early as possible in the startup
     * sequence, before the registry, bootstrap and seed observers log.
     *
     * @param event The Quarkus startup event.
     */
    void install(@Observes @Priority(1) StartupEvent event) {
        if (installed) {
            return;
        }
        installed = true;
        Logger root = Logger.getLogger("");
        root.addHandler(new CaptureHandler());
    }

    /**
     * Returns the messages captured since the handler was installed at boot.
     *
     * @return The captured formatted messages, in emission order.
     */
    static List<String> messages() {
        return MESSAGES;
    }

    /**
     * A {@link Handler} that appends each record's fully formatted message to the static
     * capture list, resolving the JBoss printf style ({@code infof}) when present.
     */
    private static final class CaptureHandler extends Handler {

        /**
         * Constructs the handler at the permissive level so no record is filtered out.
         */
        private CaptureHandler() {
            setLevel(Level.ALL);
        }

        /**
         * Captures the formatted message of a record, tolerating a null record or message.
         *
         * @param record The log record to capture.
         */
        @Override
        public void publish(LogRecord record) {
            if (record == null) {
                return;
            }
            String formatted = record instanceof ExtLogRecord ext ? ext.getFormattedMessage() : record.getMessage();
            if (formatted != null) {
                MESSAGES.add(formatted);
            }
        }

        /**
         * Flushes the handler; nothing is buffered, so this is a no-op.
         */
        @Override
        public void flush() {
            // Nothing buffered.
        }

        /**
         * Closes the handler; nothing is held, so this is a no-op.
         */
        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
