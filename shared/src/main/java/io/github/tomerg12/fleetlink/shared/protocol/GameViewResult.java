package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Reports a safe current game snapshot or an expected lookup failure.
 */
public final class GameViewResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ResultCode resultCode;
    private final String message;
    private final GameView gameView;

    /**
     * Stores a validated game lookup outcome created by the public factories.
     *
     * @param resultCode the stable machine-readable outcome
     * @param message the optional success text or required failure text
     * @param gameView the safe snapshot, or null for failure
     */
    private GameViewResult(ResultCode resultCode, String message, GameView gameView) {
        this.resultCode = resultCode;
        this.message = message;
        this.gameView = gameView;
    }

    /**
     * Creates a successful current-game lookup result.
     *
     * @param gameView the safe authoritative snapshot
     * @return the success result
     * @throws NullPointerException if the snapshot is null
     */
    public static GameViewResult success(GameView gameView) {
        return new GameViewResult(ResultCode.SUCCESS, "",
                Objects.requireNonNull(gameView, "gameView"));
    }

    /**
     * Creates an expected current-game lookup failure.
     *
     * @param resultCode the stable non-success outcome
     * @param message the non-blank player-facing explanation
     * @return the validated failure result without a snapshot
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if the code is success or the message is blank
     */
    public static GameViewResult failure(ResultCode resultCode, String message) {
        return new GameViewResult(ResultSupport.requireFailureCode(resultCode),
                ResultSupport.requireFailureMessage(message), null);
    }

    /**
     * Indicates whether a current game snapshot is available.
     *
     * @return true when the lookup succeeded
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
     * Returns the safe current snapshot when the lookup succeeded.
     *
     * @return the game view, or null for a failure
     */
    public GameView getGameView() {
        return gameView;
    }
}
