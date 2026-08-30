package io.github.tomerg12.fleetlink.server.deadline;

import java.time.Instant;

/**
 * Schedules deadline wake-ups without owning or mutating gameplay state.
 * Implementations invoke the supplied task only as an expiry notification; the task must hand work
 * to the authoritative per-game command sequencer before any game mutation occurs.
 */
public interface DeadlineScheduler extends AutoCloseable {

    /**
     * Schedules one expiry notification for an absolute server deadline.
     *
     * @param deadline absolute deadline to wake for
     * @param task lightweight notification task that must not perform gameplay work directly
     * @return cancellation handle for best-effort stale-task cleanup
     * @throws NullPointerException if deadline or task is null
     */
    Handle schedule(Instant deadline, Runnable task);

    /**
     * Stops accepting new deadline work and cancels pending scheduler tasks.
     */
    @Override
    void close();

    /**
     * Represents one scheduled deadline registration.
     */
    @FunctionalInterface
    interface Handle {
        /**
         * Attempts to cancel the scheduled notification.
         * Correctness must not depend on this cancellation succeeding.
         *
         * @return true when cancellation succeeded
         */
        boolean cancel();
    }
}
