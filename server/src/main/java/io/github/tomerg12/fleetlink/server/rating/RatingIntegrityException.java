package io.github.tomerg12.fleetlink.server.rating;

/**
 * Reports conflicting or inconsistent process-live registered rating state.
 */
public final class RatingIntegrityException extends RuntimeException {

    /**
     * Creates an integrity failure with a stable diagnostic message.
     *
     * @param message conflict description without credentials or private game state
     */
    public RatingIntegrityException(String message) {
        super(message);
    }
}
