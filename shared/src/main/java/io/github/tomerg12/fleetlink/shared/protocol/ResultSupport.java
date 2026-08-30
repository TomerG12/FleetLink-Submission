package io.github.tomerg12.fleetlink.shared.protocol;

import java.util.Objects;

/**
 * Centralizes the small invariants shared by explicit protocol result DTOs.
 */
final class ResultSupport {

    /**
     * Prevents construction because result validation is stateless.
     */
    private ResultSupport() {
    }

    /**
     * Requires a machine-readable code that represents a failure.
     *
     * @param resultCode the code supplied to a failure factory
     * @return the validated non-success code
     * @throws NullPointerException if the code is null
     * @throws IllegalArgumentException if the code is {@link ResultCode#SUCCESS}
     */
    static ResultCode requireFailureCode(ResultCode resultCode) {
        Objects.requireNonNull(resultCode, "resultCode");
        if (resultCode == ResultCode.SUCCESS) {
            throw new IllegalArgumentException("a failure result requires a failure code");
        }
        return resultCode;
    }

    /**
     * Requires a non-blank display message for an expected failure.
     *
     * @param message the failure message supplied by the server
     * @return the validated message
     * @throws NullPointerException if the message is null
     * @throws IllegalArgumentException if the message is blank
     */
    static String requireFailureMessage(String message) {
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("a failure result requires a message");
        }
        return message;
    }
}
