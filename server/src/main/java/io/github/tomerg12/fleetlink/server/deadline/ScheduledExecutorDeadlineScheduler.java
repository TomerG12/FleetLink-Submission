package io.github.tomerg12.fleetlink.server.deadline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production deadline wake-up service backed by daemon scheduled workers.
 * Workers only invoke the lightweight task supplied by GameCoordinator; authoritative sequencing,
 * GameSession mutation, and remote callbacks run on a separate execution resource.
 */
public final class ScheduledExecutorDeadlineScheduler implements DeadlineScheduler {
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final Clock clock;
    private final ScheduledThreadPoolExecutor executor;

    /**
     * Creates a production scheduler using a small independent daemon-worker pool.
     *
     * @param clock server clock used to convert absolute deadlines to scheduling delays
     * @throws NullPointerException if clock is null
     */
    public ScheduledExecutorDeadlineScheduler(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors());
        executor = new ScheduledThreadPoolExecutor(workers, runnable -> {
            Thread thread = new Thread(runnable,
                    "fleetlink-game-deadline-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    /**
     * Schedules a single lightweight expiry notification.
     *
     * @param deadline absolute deadline to wake for
     * @param task notification task
     * @return best-effort cancellation handle
     * @throws NullPointerException if deadline or task is null
     */
    @Override
    public Handle schedule(Instant deadline, Runnable task) {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(task, "task");
        long delayNanos;
        try {
            delayNanos = Math.max(0L, Duration.between(clock.instant(), deadline).toNanos());
        } catch (ArithmeticException exception) {
            delayNanos = Long.MAX_VALUE;
        }
        var future = executor.schedule(task, delayNanos, TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    /**
     * Cancels pending wake-ups and interrupts scheduler workers during server shutdown.
     */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
