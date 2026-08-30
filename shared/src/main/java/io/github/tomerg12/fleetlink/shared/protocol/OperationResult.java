package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;

/**
 * Reports success or an expected failure for an operation that has no additional return payload.
 */
public final class OperationResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ResultCode resultCode;
    private final String message;

    /**
     * Stores a validated operation outcome created by the public factories.
     *
     * @param resultCode the stable machine-readable outcome
     * @param message the optional success text or required failure text
     */
    private OperationResult(ResultCode resultCode, String message) {
        this.resultCode = resultCode;
        this.message = message;
    }

    /**
     * Creates a successful operation result.
     *
     * @return a success result with no failure message
     */
    public static OperationResult success() {
        return new OperationResult(ResultCode.SUCCESS, "");
    }

    /**
     * Creates an expected operation failure that clients can branch on without parsing text.
     *
     * @param resultCode the stable non-success outcome
     * @param message the non-blank player-facing explanation
     * @return the validated failure result
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the code is success or the message is blank
     */
    public static OperationResult failure(ResultCode resultCode, String message) {
        return new OperationResult(ResultSupport.requireFailureCode(resultCode),
                ResultSupport.requireFailureMessage(message));
    }

    /**
     * Indicates whether the server accepted and completed the operation.
     *
     * @return true when the result code is success
     */
    public boolean isSuccess() {
        return resultCode == ResultCode.SUCCESS;
    }

    /**
     * Returns the stable outcome code used for client control flow.
     *
     * @return the result code
     */
    public ResultCode getResultCode() {
        return resultCode;
    }

    /**
     * Returns the display message supplied for a failure.
     *
     * @return an empty string for success or a non-blank failure message
     */
    public String getMessage() {
        return message;
    }
}
