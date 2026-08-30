package io.github.tomerg12.fleetlink.server.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Provides the authoritative per-game admission boundary required before D-013 execution.
 * Timestamp capture, monotonic sequence assignment, FIFO insertion, and drain ownership are atomic
 * under one short per-game monitor. Drains run on virtual threads, never on deadline scheduler
 * workers, and at most one drain is active for a game while different games remain independent.
 */
final class GameCommandSequencer implements AutoCloseable {
    private final Clock clock;
    private final ExecutorService drainExecutor;
    private final ConcurrentHashMap<UUID, AdmissionLane> lanes = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private volatile boolean closed;

    /**
     * Creates a sequencer using the authoritative server clock and virtual-thread drain execution.
     *
     * @param clock server clock used while admitting user commands
     * @throws NullPointerException if clock is null
     */
    GameCommandSequencer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        drainExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Reserves sequencer lifecycle authority for one mandatory command that will be bound shortly.
     * The reservation prevents shutdown from starting between game indexing and required first
     * admission. It must be admitted or closed by the same thread that creates it.
     *
     * @return open mandatory-admission reservation
     * @throws IllegalStateException if shutdown has already stopped new admissions
     */
    PreparedAdmission reserveRequiredAdmission() {
        lifecycleLock.readLock().lock();
        try {
            ensureOpen();
            return new PreparedAdmission();
        } catch (RuntimeException exception) {
            lifecycleLock.readLock().unlock();
            throw exception;
        }
    }

    /**
     * Atomically admits a synchronous user command and waits without busy spinning for its result.
     * The short lifecycle read lock prevents shutdown from racing between accepted admission and
     * drain scheduling while still allowing unrelated games to admit commands concurrently.
     *
     * @param gameId game whose FIFO receives the command
     * @param command command receiving its authoritative server ingress timestamp
     * @param <T> command result type
     * @return completed command result
     * @throws NullPointerException if gameId or command is null
     * @throws IllegalStateException if shutdown has already stopped new admissions
     */
    <T> T submit(UUID gameId, Function<Instant, T> command) {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(command, "command");
        CompletableFuture<T> result = new CompletableFuture<>();
        lifecycleLock.readLock().lock();
        try {
            ensureOpen();
            AdmissionLane lane = lanes.computeIfAbsent(gameId, ignored -> new AdmissionLane());
            synchronized (lane) {
                Instant receivedAt = clock.instant();
                long sequence = lane.nextSequence++;
                lane.queue.addLast(new QueuedCommand(sequence, () -> {
                    try {
                        result.complete(command.apply(receivedAt));
                    } catch (Throwable throwable) {
                        result.completeExceptionally(throwable);
                    }
                }));
                ensureDrain(lane);
            }
        } finally {
            lifecycleLock.readLock().unlock();
        }
        try {
            return result.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    /**
     * Atomically admits asynchronous infrastructure work, including deadline expiry commands.
     * The caller returns immediately after admission; execution always occurs on the drain executor.
     * Work arriving after shutdown begins is ignored because deadline cancellation is best effort.
     *
     * @param gameId game whose FIFO receives the work
     * @param command work to execute later under the coordinator's D-013 lane
     * @throws NullPointerException if gameId or command is null
     */
    void enqueue(UUID gameId, Runnable command) {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(command, "command");
        lifecycleLock.readLock().lock();
        try {
            if (closed) {
                return;
            }
            AdmissionLane lane = lanes.computeIfAbsent(gameId, ignored -> new AdmissionLane());
            synchronized (lane) {
                long sequence = lane.nextSequence++;
                lane.queue.addLast(new QueuedCommand(sequence, command));
                ensureDrain(lane);
            }
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * Starts one drain when a lane transitions from idle to non-empty.
     *
     * @param lane per-game lane whose queued commands require a drain
     */
    private void ensureDrain(AdmissionLane lane) {
        if (lane.draining) {
            return;
        }
        lane.draining = true;
        drainExecutor.execute(() -> drain(lane));
    }

    /**
     * Consumes one game's queue strictly in authoritative admission order.
     *
     * @param lane per-game lane being drained
     */
    private void drain(AdmissionLane lane) {
        while (true) {
            QueuedCommand queued;
            synchronized (lane) {
                queued = lane.queue.pollFirst();
                if (queued == null) {
                    lane.draining = false;
                    return;
                }
            }
            queued.command().run();
        }
    }

    /**
     * Rejects synchronous admissions after shutdown begins.
     *
     * @throws IllegalStateException if the sequencer is closed
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("game command sequencer is closed");
        }
    }

    /**
     * Holds the lifecycle read lock until one required first command is admitted. The admitted
     * command is execution-gated so callers may publish related state and release outer locks before
     * callbacks or other command work begins.
     */
    final class PreparedAdmission implements AutoCloseable {
        private boolean completed;

        /**
         * Admits the mandatory command as the first command for a newly allocated game identifier.
         *
         * @param gameId newly allocated game identifier
         * @param command mandatory command to execute after the returned gate is released
         * @return execution gate used after the caller releases its outer synchronization
         * @throws IllegalStateException if this reservation was already completed or the game lane
         *         already contains work
         */
        AdmittedCommand admitFirst(UUID gameId, Runnable command) {
            Objects.requireNonNull(gameId, "gameId");
            Objects.requireNonNull(command, "command");
            if (completed) {
                throw new IllegalStateException("prepared admission is already completed");
            }
            CompletableFuture<Void> release = new CompletableFuture<>();
            CompletableFuture<Void> result = new CompletableFuture<>();
            try {
                AdmissionLane lane = lanes.computeIfAbsent(gameId, ignored -> new AdmissionLane());
                synchronized (lane) {
                    if (lane.nextSequence != 0 || !lane.queue.isEmpty() || lane.draining) {
                        throw new IllegalStateException(
                                "required admission must be first for a new game");
                    }
                    long sequence = lane.nextSequence++;
                    lane.queue.addLast(new QueuedCommand(sequence, () -> {
                        release.join();
                        try {
                            command.run();
                            result.complete(null);
                        } catch (Throwable throwable) {
                            result.completeExceptionally(throwable);
                        }
                    }));
                    ensureDrain(lane);
                }
                completed = true;
                return new AdmittedCommand(release, result);
            } finally {
                if (completed) {
                    lifecycleLock.readLock().unlock();
                }
            }
        }

        /**
         * Releases the lifecycle reservation when game creation fails before command admission.
         */
        @Override
        public void close() {
            if (!completed) {
                completed = true;
                lifecycleLock.readLock().unlock();
            }
        }
    }

    /**
     * Controls execution of one already admitted mandatory command.
     *
     * @param release future that opens the execution gate
     * @param result future completed by the admitted command
     */
    record AdmittedCommand(CompletableFuture<Void> release,
                           CompletableFuture<Void> result) {
        /**
         * Opens the execution gate and waits for the authoritative command to finish.
         *
         * @throws CompletionException if command execution fails
         */
        void releaseAndAwait() {
            release.complete(null);
            result.join();
        }
    }

    /**
     * Stops new admissions atomically with drain scheduling, then waits for already admitted work
     * and active drains to finish. Drains are not interrupted because an admitted authoritative
     * command or callback delivery must not be abandoned during normal server shutdown. Repeated or
     * concurrent close calls all wait for the same executor termination before returning.
     */
    @Override
    public void close() {
        lifecycleLock.writeLock().lock();
        try {
            if (!closed) {
                closed = true;
                drainExecutor.shutdown();
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
        boolean interrupted = false;
        while (!drainExecutor.isTerminated()) {
            try {
                drainExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Holds one game's atomic admission metadata, FIFO, and single-drain ownership flag. */
    private static final class AdmissionLane {
        private final ArrayDeque<QueuedCommand> queue = new ArrayDeque<>();
        private long nextSequence;
        private boolean draining;
    }

    /**
     * Stores monotonic admission identity together with executable command work.
     *
     * @param sequence authoritative per-game admission sequence
     * @param command deferred command execution
     */
    private record QueuedCommand(long sequence, Runnable command) {
    }
}
