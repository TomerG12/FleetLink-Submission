package io.github.tomerg12.fleetlink.server.completion;

/**
 * Reports conflicting authoritative completion data under an existing game identifier.
 */
public final class CompletionIntegrityException extends RuntimeException {

    /**
     * Creates an integrity failure with a stable diagnostic message.
     *
     * @param message conflict description without credential or private board data
     */
    public CompletionIntegrityException(String message) {
        super(message);
    }

    /**
     * Creates an integrity failure that retains the persistence cause.
     *
     * @param message conflict description
     * @param cause persistence cause
     */
    public CompletionIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
