package io.github.tomerg12.fleetlink.server.completion;

/**
 * Distinguishes a new durable aggregate from an equivalent idempotent repeat.
 */
public enum CompletionRecordOutcome {
    /** A new aggregate was committed. */
    RECORDED,

    /** Equivalent authoritative data was already durable. */
    ALREADY_RECORDED,

    /** The game contained only guests and therefore required no persistent history. */
    NOT_ELIGIBLE
}
