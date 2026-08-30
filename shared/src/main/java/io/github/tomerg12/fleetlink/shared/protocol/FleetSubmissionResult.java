package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Reports whether the server atomically accepted a complete fleet submission.
 */
public final class FleetSubmissionResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ResultCode resultCode;
    private final String message;
    private final GameView gameView;

    /**
     * Stores a validated fleet outcome created by the public factories.
     *
     * @param resultCode the stable machine-readable outcome
     * @param message the optional success text or required failure text
     * @param gameView the authoritative current snapshot when one is available
     */
    private FleetSubmissionResult(ResultCode resultCode, String message, GameView gameView) {
        this.resultCode = resultCode;
        this.message = message;
        this.gameView = gameView;
    }

    /**
     * Creates a result for an atomically accepted fleet.
     *
     * @param gameView the authoritative snapshot after acceptance
     * @return the successful submission result
     * @throws NullPointerException if the snapshot is null
     */
    public static FleetSubmissionResult accepted(GameView gameView) {
        return new FleetSubmissionResult(ResultCode.SUCCESS, "",
                Objects.requireNonNull(gameView, "gameView"));
    }

    /**
     * Creates an expected fleet rejection without implying partial mutation.
     *
     * @param resultCode the stable non-success outcome
     * @param message the non-blank player-facing explanation
     * @param gameView the unchanged current snapshot, or null if no active game is available
     * @return the validated rejection result
     * @throws NullPointerException if the code or message is null
     * @throws IllegalArgumentException if the code is success or the message is blank
     */
    public static FleetSubmissionResult rejected(ResultCode resultCode, String message,
                                                  GameView gameView) {
        return new FleetSubmissionResult(ResultSupport.requireFailureCode(resultCode),
                ResultSupport.requireFailureMessage(message), gameView);
    }

    /**
     * Indicates whether the complete fleet was accepted.
     *
     * @return true when the server accepted the fleet atomically
     */
    public boolean isAccepted() {
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
     * @return an empty string for acceptance or a non-blank rejection message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the authoritative current snapshot when the server can provide one.
     *
     * @return the current game view, or null when no active game is available
     */
    public GameView getGameView() {
        return gameView;
    }
}
