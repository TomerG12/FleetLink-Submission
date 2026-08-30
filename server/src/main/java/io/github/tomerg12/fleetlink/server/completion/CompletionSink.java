package io.github.tomerg12.fleetlink.server.completion;

/**
 * Accepts immutable completion snapshots after the ordered game delivery lane is released.
 */
@FunctionalInterface
public interface CompletionSink {

    /**
     * Hands one terminal snapshot to non-blocking completion processing.
     *
     * @param snapshot immutable terminal game snapshot
     */
    void submit(CompletedGameSnapshot snapshot);
}
