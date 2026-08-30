package io.github.tomerg12.fleetlink.server.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.persistence.ParticipantResult;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies non-blocking handoff, transient retry, idempotent repeats, and bounded shutdown.
 */
class CompletionRecorderTest {

    /**
     * Retries a transient failure while submit returns before persistence completes.
     *
     * @throws Exception if the retry does not complete in time
     */
    @Test
    void transientFailureRetriesWithoutBlockingSubmission() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch recorded = new CountDownLatch(1);
        CompletedGameStore store = snapshot -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary database failure");
            }
            recorded.countDown();
            return CompletionRecordOutcome.RECORDED;
        };
        CompletionRecorder recorder = new CompletionRecorder(store,
                Executors.newSingleThreadScheduledExecutor(), Duration.ofSeconds(1));
        try {
            assertTimeoutPreemptively(Duration.ofMillis(100), () -> recorder.submit(snapshot()));
            assertTrue(recorded.await(2, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                while (recorder.pendingCount() != 0) {
                    Thread.sleep(5);
                }
            });
        } finally {
            recorder.close();
        }
    }

    /**
     * Bounds graceful cleanup even when the database remains unavailable.
     */
    @Test
    void shutdownCleanupIsBounded() {
        CompletedGameStore failing = snapshot -> {
            throw new IllegalStateException("database unavailable");
        };
        CompletionRecorder recorder = new CompletionRecorder(failing,
                Executors.newSingleThreadScheduledExecutor(), Duration.ofMillis(100));
        recorder.submit(snapshot());

        assertTimeoutPreemptively(Duration.ofSeconds(1), recorder::close);
    }

    /**
     * Preserves an accepted completion when submit owns the lifecycle boundary before close.
     *
     * @throws Exception if the coordinated submit and close tasks do not finish in time
     */
    @Test
    void submitWinningLifecycleRaceIsPersistedByClose() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        BlockingInitialExecutor scheduler = new BlockingInitialExecutor();
        CompletionRecorder recorder = new CompletionRecorder(snapshot -> {
            attempts.incrementAndGet();
            return CompletionRecordOutcome.RECORDED;
        }, scheduler, Duration.ofSeconds(1));
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> submission = callers.submit(() -> recorder.submit(snapshot()));
            assertTrue(scheduler.awaitSubmission());
            CountDownLatch closeAttempted = new CountDownLatch(1);
            Future<?> closing = callers.submit(() -> {
                closeAttempted.countDown();
                recorder.close();
            });
            assertTrue(closeAttempted.await(1, TimeUnit.SECONDS));

            scheduler.releaseSubmission();
            submission.get(1, TimeUnit.SECONDS);
            closing.get(1, TimeUnit.SECONDS);

            assertEquals(1, attempts.get());
            assertEquals(0, recorder.pendingCount());
        } finally {
            scheduler.releaseSubmission();
            recorder.close();
            callers.shutdownNow();
        }
    }

    /**
     * Rejects submissions that arrive after close without touching persistence or the executor.
     */
    @Test
    void closeWinningLifecycleRaceRejectsLaterSubmission() {
        AtomicInteger attempts = new AtomicInteger();
        CountingExecutor scheduler = new CountingExecutor();
        CompletionRecorder recorder = new CompletionRecorder(snapshot -> {
            attempts.incrementAndGet();
            return CompletionRecordOutcome.RECORDED;
        }, scheduler, Duration.ofSeconds(1));

        recorder.close();
        assertDoesNotThrow(() -> recorder.submit(snapshot()));

        assertEquals(0, attempts.get());
        assertEquals(0, scheduler.getTaskSubmissions());
        assertEquals(0, recorder.pendingCount());
    }

    /**
     * Retains accepted work for bounded close cleanup when the scheduler rejects its first task.
     */
    @Test
    void acceptedSubmissionSurvivesSchedulerRejection() {
        AtomicInteger attempts = new AtomicInteger();
        RejectingExecutor scheduler = new RejectingExecutor();
        CompletionRecorder recorder = new CompletionRecorder(snapshot -> {
            attempts.incrementAndGet();
            return CompletionRecordOutcome.RECORDED;
        }, scheduler, Duration.ofSeconds(1));

        assertDoesNotThrow(() -> recorder.submit(snapshot()));
        assertEquals(1, recorder.pendingCount());
        assertDoesNotThrow(recorder::close);

        assertEquals(1, attempts.get());
        assertEquals(0, recorder.pendingCount());
    }

    /**
     * Creates one eligible mixed completion snapshot.
     *
     * @return immutable completion snapshot
     */
    private static CompletedGameSnapshot snapshot() {
        CompletedParticipantSnapshot registered = new CompletedParticipantSnapshot(
                UUID.randomUUID(), "Account", false, 1000, ParticipantResult.WIN,
                2, 1, 0, 2, 0, null);
        CompletedParticipantSnapshot guest = new CompletedParticipantSnapshot(
                UUID.randomUUID(), "Guest", true, 1000, ParticipantResult.LOSS,
                1, 0, 0, 1, 0, null);
        Instant completedAt = Instant.now();
        return new CompletedGameSnapshot(UUID.randomUUID(), completedAt.minusSeconds(30),
                completedAt,
                GameEndReason.DISCONNECT, registered.getPlayerId(), List.of(registered, guest));
    }

    /**
     * Delays the initial executor handoff so tests can order submit before close without sleeps.
     */
    private static final class BlockingInitialExecutor extends ScheduledThreadPoolExecutor {
        private final CountDownLatch submissionEntered = new CountDownLatch(1);
        private final CountDownLatch submissionRelease = new CountDownLatch(1);

        /**
         * Creates a single-threaded scheduler for the lifecycle race.
         */
        private BlockingInitialExecutor() {
            super(1);
        }

        /**
         * Blocks the first handoff until the test has started a concurrent close.
         *
         * @param command accepted completion attempt
         */
        @Override
        public void execute(Runnable command) {
            submissionEntered.countDown();
            try {
                submissionRelease.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("submission interrupted", exception);
            }
            super.execute(command);
        }

        /**
         * Waits for submit to enter the executor handoff.
         *
         * @return true when submit reached the handoff before timeout
         * @throws InterruptedException if the test thread is interrupted
         */
        private boolean awaitSubmission() throws InterruptedException {
            return submissionEntered.await(1, TimeUnit.SECONDS);
        }

        /**
         * Releases the delayed executor handoff.
         */
        private void releaseSubmission() {
            submissionRelease.countDown();
        }
    }

    /**
     * Counts initial task handoffs to verify a closed recorder does not schedule work.
     */
    private static class CountingExecutor extends ScheduledThreadPoolExecutor {
        private final AtomicInteger taskSubmissions = new AtomicInteger();

        /**
         * Creates a single-threaded counting scheduler.
         */
        private CountingExecutor() {
            super(1);
        }

        /**
         * Counts and delegates one initial task handoff.
         *
         * @param command completion attempt task
         */
        @Override
        public void execute(Runnable command) {
            taskSubmissions.incrementAndGet();
            super.execute(command);
        }

        /**
         * Returns the number of initial tasks offered to this executor.
         *
         * @return task submission count
         */
        private int getTaskSubmissions() {
            return taskSubmissions.get();
        }
    }

    /**
     * Rejects initial tasks to exercise the recorder's accepted-work fallback.
     */
    private static final class RejectingExecutor extends CountingExecutor {

        /**
         * Creates an executor whose initial handoff is rejected.
         */
        private RejectingExecutor() {
            super();
        }

        /**
         * Rejects every initial completion attempt.
         *
         * @param command completion attempt task
         */
        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("rejected for test");
        }
    }
}
