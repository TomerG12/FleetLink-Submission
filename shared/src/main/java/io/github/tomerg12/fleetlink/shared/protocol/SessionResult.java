package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Reports session establishment success or an expected login, registration, or guest failure.
 */
public final class SessionResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ResultCode resultCode;
    private final String message;
    private final SessionInfo sessionInfo;

    /**
     * Stores a validated session outcome created by the public factories.
     *
     * @param resultCode the stable machine-readable outcome
     * @param message the optional success text or required failure text
     * @param sessionInfo the established session, or null for failure
     */
    private SessionResult(ResultCode resultCode, String message, SessionInfo sessionInfo) {
        this.resultCode = resultCode;
        this.message = message;
        this.sessionInfo = sessionInfo;
    }

    /**
     * Creates a successful session establishment result.
     *
     * @param sessionInfo the new safe session information
     * @return the success result
     * @throws NullPointerException if the session information is null
     */
    public static SessionResult success(SessionInfo sessionInfo) {
        return new SessionResult(ResultCode.SUCCESS, "",
                Objects.requireNonNull(sessionInfo, "sessionInfo"));
    }

    /**
     * Creates an expected session establishment failure.
     *
     * @param resultCode the stable non-success outcome
     * @param message the non-blank player-facing explanation
     * @return the validated failure result without a session
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the code is success or the message is blank
     */
    public static SessionResult failure(ResultCode resultCode, String message) {
        return new SessionResult(ResultSupport.requireFailureCode(resultCode),
                ResultSupport.requireFailureMessage(message), null);
    }

    /**
     * Indicates whether the server established a session.
     *
     * @return true when session information is available
     */
    public boolean isSuccess() {
        return resultCode == ResultCode.SUCCESS;
    }

    /**
     * Returns the stable outcome code.
     *
     * @return the result code
     */
    public ResultCode getResultCode() {
        return resultCode;
    }

    /**
     * Returns the player-facing outcome message.
     *
     * @return an empty string for success or a non-blank failure message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the established session when the operation succeeded.
     *
     * @return the session information, or null for a failure
     */
    public SessionInfo getSessionInfo() {
        return sessionInfo;
    }
}
