package io.github.tomerg12.fleetlink.server.persistence;

/**
 * Reports that database-enforced username uniqueness rejected an account registration.
 */
public final class UsernameUnavailableException extends RuntimeException {

    /**
     * Creates the stable internal registration conflict.
     *
     * @param cause persistence failure that enforced the unique username key
     */
    public UsernameUnavailableException(Throwable cause) {
        super("username is unavailable", cause);
    }
}
