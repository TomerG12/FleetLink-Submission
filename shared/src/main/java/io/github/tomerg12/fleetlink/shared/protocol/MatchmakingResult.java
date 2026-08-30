package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Reports whether a matchmaking request succeeded and the resulting server state.
 */
public final class MatchmakingResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ResultCode resultCode;
    private final String message;
    private final MatchmakingState state;

    /**
     * Stores a validated matchmaking outcome created by the public factories.
     *
     * @param resultCode the stable machine-readable outcome
     * @param message the optional success text or required failure text
     * @param state the resulting matchmaking state, or null for failure
     */
    private MatchmakingResult(ResultCode resultCode, String message, MatchmakingState state) {
        this.resultCode = resultCode;
        this.message = message;
        this.state = state;
    }

    /**
     * Creates a successful matchmaking result.
     *
     * @param state the authoritative waiting or matched state
     * @return the success result
     * @throws NullPointerException if the state is null
     */
    public static MatchmakingResult success(MatchmakingState state) {
        return new MatchmakingResult(ResultCode.SUCCESS, "",
                Objects.requireNonNull(state, "state"));
    }

    /**
     * Creates an expected matchmaking failure.
     *
     * @param resultCode the stable non-success outcome
     * @param message the non-blank player-facing explanation
     * @return the validated failure result without a matchmaking state
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the code is success or the message is blank
     */
    public static MatchmakingResult failure(ResultCode resultCode, String message) {
        return new MatchmakingResult(ResultSupport.requireFailureCode(resultCode),
                ResultSupport.requireFailureMessage(message), null);
    }

    /**
     * Indicates whether the server accepted the matchmaking request.
     *
     * @return true when a resulting matchmaking state is available
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
     * Returns the authoritative state produced by a successful request.
     *
     * @return the matchmaking state, or null for a failure
     */
    public MatchmakingState getState() {
        return state;
    }
}
