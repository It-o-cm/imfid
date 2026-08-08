package com.intermarche.fidelity.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Plain unit coverage for {@link BatchScheduler}: the cron driver of the account lifecycle
 * batches (§16, §24.6). The class carries no branches — each of the three scheduled methods
 * delegates to {@link BatchService#run(BatchType, boolean)} in execute mode (never dry-run)
 * with a fixed {@link BatchType} and logs the outcome. Each test asserts the exact type and
 * the {@code false} dry-run flag, and pins the returned {@link BatchResult} so the {@code
 * infof} formatting of {@code accountsAffected}/{@code totalAmount} runs on real values.
 * Every {@code BigDecimal} is asserted by {@code compareTo} at scale 2 / HALF_UP (§30.5).
 */
class BatchSchedulerTest {

    /**
     * The scheduler under test, wired to the mocked batch service.
     */
    private BatchScheduler scheduler;

    /**
     * The mocked batch service capturing the delegated runs.
     */
    @Mock
    private BatchService batchService;

    /**
     * The Mockito lifecycle handle closed after each test.
     */
    private AutoCloseable mocks;

    /**
     * Opens the Mockito mocks and wires the scheduler to the mocked batch service.
     */
    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        scheduler = new BatchScheduler();
        scheduler.batchService = batchService;
    }

    /**
     * Builds a batch result populated with a single line so the logging path reads a
     * non-zero {@code accountsAffected} and a scale-2 total.
     *
     * @param batch  The batch type name carried by the result.
     * @param amount The amount of the single recorded line.
     * @return A ready result.
     */
    private BatchResult resultWith(String batch, String amount) {
        BatchResult result = new BatchResult(batch, false);
        result.add("CARD-1", new BigDecimal(amount));
        return result;
    }

    /**
     * {@link BatchScheduler#scheduledExpiry()} runs the EXPIRY batch in execute mode and
     * logs the outcome without further interactions.
     */
    @Test
    @DisplayName("scheduledExpiry runs EXPIRY in execute mode")
    void scheduledExpiryDelegates() {
        BatchResult result = resultWith("EXPIRY", "43.00");
        when(batchService.run(BatchType.EXPIRY, false)).thenReturn(result);
        scheduler.scheduledExpiry();
        verify(batchService).run(BatchType.EXPIRY, false);
        verifyNoMoreInteractions(batchService);
        assertEquals(1, result.accountsAffected);
        assertEquals(0, result.totalAmount.compareTo(new BigDecimal("43.00")));
        assertEquals(2, result.totalAmount.scale());
    }

    /**
     * {@link BatchScheduler#scheduledPurge()} runs the PURGE batch in execute mode and
     * logs the outcome without further interactions.
     */
    @Test
    @DisplayName("scheduledPurge runs PURGE in execute mode")
    void scheduledPurgeDelegates() {
        BatchResult result = resultWith("PURGE", "12.50");
        when(batchService.run(BatchType.PURGE, false)).thenReturn(result);
        scheduler.scheduledPurge();
        verify(batchService).run(BatchType.PURGE, false);
        verifyNoMoreInteractions(batchService);
        assertEquals(1, result.accountsAffected);
        assertEquals(0, result.totalAmount.compareTo(new BigDecimal("12.50")));
        assertEquals(2, result.totalAmount.scale());
    }

    /**
     * {@link BatchScheduler#scheduledActivationVoid()} runs the ACTIVATION_VOID batch in
     * execute mode and logs the outcome without further interactions.
     */
    @Test
    @DisplayName("scheduledActivationVoid runs ACTIVATION_VOID in execute mode")
    void scheduledActivationVoidDelegates() {
        BatchResult result = resultWith("ACTIVATION_VOID", "0.00");
        when(batchService.run(BatchType.ACTIVATION_VOID, false)).thenReturn(result);
        scheduler.scheduledActivationVoid();
        verify(batchService).run(BatchType.ACTIVATION_VOID, false);
        verifyNoMoreInteractions(batchService);
        assertEquals(1, result.accountsAffected);
        assertEquals(0, result.totalAmount.compareTo(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)));
        assertEquals(2, result.totalAmount.scale());
    }

    /**
     * Closes the Mockito mocks opened for the test.
     *
     * @throws Exception If the mocks fail to close.
     */
    @org.junit.jupiter.api.AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }
}
