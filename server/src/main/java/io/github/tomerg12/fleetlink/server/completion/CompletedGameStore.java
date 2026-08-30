package io.github.tomerg12.fleetlink.server.completion;

/**
 * Persists complete terminal game aggregates through an idempotent server-internal boundary.
 */
@FunctionalInterface
public interface CompletedGameStore {

    /**
     * Records one eligible aggregate in a single transaction.
     *
     * @param snapshot validated immutable completion snapshot
     * @return new, equivalent duplicate, or ineligible outcome
     * @throws CompletionIntegrityException when the game identifier contains conflicting data
     * @throws RuntimeException when persistence is temporarily unavailable
     */
    CompletionRecordOutcome record(CompletedGameSnapshot snapshot);
}
