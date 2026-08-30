package io.github.tomerg12.fleetlink.server.completion;

/**
 * Signals that a valid rating transition must wait for an earlier durable revision.
 */
public final class CompletionPredecessorPendingException extends RuntimeException {

    /**
     * Creates a retryable predecessor-pending failure.
     *
     * @param message diagnostic explanation
     */
    public CompletionPredecessorPendingException(String message) {
        super(message);
    }
}
