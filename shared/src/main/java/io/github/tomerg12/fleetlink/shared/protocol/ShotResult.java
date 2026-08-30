package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Reports an accepted shot outcome or an expected rejection with authoritative state.
 */
public final class ShotResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ResultCode resultCode;
    private final String message;
    private final ShotOutcome outcome;
    private final GameView gameView;

    /**
     * Stores a validated shot outcome created by the public factories.
     *
     * @param resultCode the stable machine-readable outcome
     * @param message the optional success text or required failure text
     * @param outcome the outcome of an accepted shot, or null for rejection
     * @param gameView the authoritative current snapshot when one is available
     */
    private ShotResult(ResultCode resultCode, String message, ShotOutcome outcome,
                       GameView gameView) {
        this.resultCode = resultCode;
        this.message = message;
        this.outcome = outcome;
        this.gameView = gameView;
    }

    /**
     * Creates a result for an accepted, authoritatively processed shot.
     *
     * @param outcome the server-calculated shot outcome
     * @param gameView the authoritative snapshot after processing
     * @return the accepted shot result
     * @throws NullPointerException if either argument is null
     */
    public static ShotResult accepted(ShotOutcome outcome, GameView gameView) {
        return new ShotResult(ResultCode.SUCCESS, "",
                Objects.requireNonNull(outcome, "outcome"),
                Objects.requireNonNull(gameView, "gameView"));
    }

    /**
     * Creates an expected shot rejection without a calculated hit or miss.
     *
     * @param resultCode the stable non-success outcome
     * @param message the non-blank player-facing explanation
     * @param gameView the unchanged current snapshot, or null if no active game is available
     * @return the validated rejection result
     * @throws NullPointerException if the code or message is null
     * @throws IllegalArgumentException if the code is success or the message is blank
     */
    public static ShotResult rejected(ResultCode resultCode, String message, GameView gameView) {
        return new ShotResult(ResultSupport.requireFailureCode(resultCode),
                ResultSupport.requireFailureMessage(message), null, gameView);
    }

    /**
     * Indicates whether the server accepted and processed the shot.
     *
     * @return true when an authoritative shot outcome is available
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
     * Returns the server-calculated result of an accepted shot.
     *
     * @return the shot outcome, or null when the request was rejected
     */
    public ShotOutcome getOutcome() {
        return outcome;
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
