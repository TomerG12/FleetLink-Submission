package io.github.tomerg12.fleetlink.server.completion;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records eligible completion snapshots off game lanes and retries transient failures in memory.
 * A process crash before a queued snapshot becomes durable may lose that snapshot because T5 does
 * not provide a durable outbox.
 */
public final class CompletionRecorder implements CompletionSink, AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(CompletionRecorder.class.getName());
    private static final long MAX_BACKOFF_MILLIS = 5_000;
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final CompletedGameStore store;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<UUID, PendingCompletion> pending = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final Duration shutdownTimeout;
    private boolean accepting = true;
    private boolean closeStarted;

    /**
     * Creates the production single-threaded completion recorder.
     *
     * @param store durable completed-game store
     */
    public CompletionRecorder(CompletedGameStore store) {
        this(store, Executors.newSingleThreadScheduledExecutor(action -> {
            Thread thread = new Thread(action, "fleetlink-completion-recorder");
            thread.setDaemon(true);
            return thread;
        }), DEFAULT_SHUTDOWN_TIMEOUT);
    }

    /**
     * Creates a recorder with explicit execution and shutdown boundaries for tests.
     *
     * @param store durable completed-game store
     * @param executor retry scheduler owned by this recorder
     * @param shutdownTimeout bounded best-effort shutdown duration
     */
    public CompletionRecorder(CompletedGameStore store, ScheduledExecutorService executor,
                              Duration shutdownTimeout) {
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownTimeout must not be negative");
        }
    }

    /**
     * Queues an eligible immutable snapshot without performing persistence on the caller thread.
     * Equivalent concurrent submissions share one pending entry keyed by game identifier.
     *
     * @param snapshot immutable terminal game snapshot
     */
    @Override
    public void submit(CompletedGameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.hasRegisteredParticipant()) {
            return;
        }
        PendingCompletion created = new PendingCompletion(snapshot);
        synchronized (lifecycleLock) {
            if (!accepting) {
                return;
            }
            PendingCompletion existing = pending.putIfAbsent(snapshot.getGameId(), created);
            if (existing != null) {
                if (!existing.snapshot.equivalentTo(snapshot)) {
                    LOGGER.severe("Conflicting in-memory completion submission for game "
                            + snapshot.getGameId());
                }
                return;
            }
            try {
                executor.execute(() -> attempt(created));
            } catch (RejectedExecutionException exception) {
                LOGGER.log(Level.SEVERE, "Completion executor rejected accepted game "
                        + snapshot.getGameId() + "; snapshot retained for bounded shutdown",
                        exception);
            }
        }
    }

    /**
     * Returns the number of completion snapshots still awaiting a durable outcome.
     *
     * @return pending completion count
     */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Stops accepting work, performs bounded best-effort attempts, and stops the retry executor.
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closeStarted) {
                return;
            }
            closeStarted = true;
            accepting = false;
        }
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            for (PendingCompletion completion : ListCopy.of(pending)) {
                attempt(completion);
            }
            if (!pending.isEmpty()) {
                try {
                    Thread.sleep(Math.min(25, Math.max(1,
                            TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(Math.max(1, shutdownTimeout.toMillis()),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Attempts one durable record and schedules capped backoff after transient failures.
     *
     * @param completion pending snapshot and attempt state
     */
    private void attempt(PendingCompletion completion) {
        if (pending.get(completion.snapshot.getGameId()) != completion) {
            return;
        }
        if (!completion.beginAttempt()) {
            return;
        }
        try {
            store.record(completion.snapshot);
            pending.remove(completion.snapshot.getGameId(), completion);
        } catch (CompletionIntegrityException exception) {
            pending.remove(completion.snapshot.getGameId(), completion);
            LOGGER.log(Level.SEVERE, "Completion integrity failure for game "
                    + completion.snapshot.getGameId(), exception);
        } catch (RuntimeException exception) {
            int attempts = completion.incrementAttempts();
            boolean retryScheduled = scheduleRetry(completion, backoffMillis(attempts));
            String disposition = retryScheduled
                    ? "; retry " + attempts + " scheduled"
                    : "; snapshot retained for bounded shutdown";
            LOGGER.log(Level.WARNING, "Completion persistence failed for game "
                    + completion.snapshot.getGameId() + disposition, exception);
        } finally {
            completion.endAttempt();
        }
    }

    /**
     * Schedules a retry atomically with recorder shutdown so accepted work is not stranded by a
     * submit or retry race. A rejected retry remains in the pending map for bounded close cleanup.
     *
     * @param completion pending snapshot and attempt state
     * @param delayMillis bounded retry delay in milliseconds
     * @return true when the retry was accepted by the scheduler
     */
    private boolean scheduleRetry(PendingCompletion completion, long delayMillis) {
        synchronized (lifecycleLock) {
            if (!accepting) {
                return false;
            }
            try {
                executor.schedule(() -> attempt(completion), delayMillis,
                        TimeUnit.MILLISECONDS);
                return true;
            } catch (RejectedExecutionException exception) {
                LOGGER.log(Level.SEVERE, "Completion executor rejected retry for game "
                        + completion.snapshot.getGameId()
                        + "; snapshot retained for bounded shutdown", exception);
                return false;
            }
        }
    }

    /**
     * Calculates exponential retry delay capped at five seconds.
     *
     * @param attempts number of failed attempts
     * @return bounded delay in milliseconds
     */
    private static long backoffMillis(int attempts) {
        int shift = Math.min(Math.max(0, attempts - 1), 6);
        return Math.min(MAX_BACKOFF_MILLIS, 100L << shift);
    }

    /**
     * Stores one immutable snapshot and its retry attempt count.
     */
    private static final class PendingCompletion {
        private final CompletedGameSnapshot snapshot;
        private final AtomicBoolean attemptInProgress = new AtomicBoolean();
        private int attempts;

        /**
         * Creates pending state for one game identifier.
         *
         * @param snapshot immutable completion snapshot
         */
        private PendingCompletion(CompletedGameSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        /**
         * Increments and returns the failure attempt count.
         *
         * @return updated attempt count
         */
        private synchronized int incrementAttempts() {
            return ++attempts;
        }

        /**
         * Claims exclusive execution for one persistence attempt.
         *
         * @return true when this caller may attempt persistence
         */
        private boolean beginAttempt() {
            return attemptInProgress.compareAndSet(false, true);
        }

        /**
         * Releases exclusive attempt execution after a durable or transient outcome.
         */
        private void endAttempt() {
            attemptInProgress.set(false);
        }
    }

    /**
     * Creates a stable snapshot of pending values for bounded shutdown iteration.
     */
    private static final class ListCopy {

        /**
         * Prevents construction of this collection helper.
         */
        private ListCopy() {
        }

        /**
         * Copies concurrent map values before iteration.
         *
         * @param pending current pending map
         * @return stable value list
         */
        private static java.util.List<PendingCompletion> of(
                ConcurrentHashMap<UUID, PendingCompletion> pending) {
            return java.util.List.copyOf(pending.values());
        }
    }
}
