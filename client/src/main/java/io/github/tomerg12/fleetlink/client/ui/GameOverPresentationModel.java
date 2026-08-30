package io.github.tomerg12.fleetlink.client.ui;

import java.util.Objects;

import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;

/**
 * Derives completed-match presentation only from one authoritative terminal snapshot.
 */
public final class GameOverPresentationModel {
    private final GameView gameView;
    private final boolean noContest;
    private final boolean winner;

    /**
     * Creates final presentation from a validated server-provided finished game.
     *
     * @param gameView authoritative terminal game snapshot
     * @throws NullPointerException if the snapshot is null
     * @throws IllegalArgumentException if the snapshot is not terminal
     */
    public GameOverPresentationModel(GameView gameView) {
        this.gameView = Objects.requireNonNull(gameView, "gameView");
        if (gameView.getPhase() != GamePhase.FINISHED) {
            throw new IllegalArgumentException("Game Over requires a finished game snapshot");
        }
        noContest = gameView.getEndReason() == GameEndReason.NO_CONTEST;
        winner = !noContest && gameView.getWinner() != null
                && gameView.getWinner().getPlayerId().equals(gameView.getPlayer().getPlayerId());
    }

    /**
     * Returns the authoritative terminal snapshot driving the screen.
     *
     * @return finished game snapshot
     */
    public GameView getGameView() {
        return gameView;
    }

    /**
     * Reports whether the server declared a winnerless placement outcome.
     *
     * @return true when the terminal result has no winner or loser
     */
    public boolean isNoContest() {
        return noContest;
    }

    /**
     * Reports whether the receiving player is the server-declared winner.
     *
     * @return true when the receiving player won
     */
    public boolean isWinner() {
        return winner;
    }

    /**
     * Returns the headline derived from the authoritative terminal outcome.
     *
     * @return victory, defeat, or no-contest headline
     */
    public String resultTitle() {
        if (noContest) {
            return "NO CONTEST";
        }
        return winner ? "VICTORY" : "DEFEAT";
    }

    /**
     * Describes the server-declared terminal reason without inventing statistics.
     *
     * @return player-facing final result detail
     */
    public String resultDetail() {
        GameEndReason reason = gameView.getEndReason();
        return switch (reason) {
            case ALL_SHIPS_SUNK -> winner
                    ? "All enemy ships were sunk."
                    : "Your fleet was sunk.";
            case RESIGNATION -> winner
                    ? gameView.getOpponent().getDisplayName() + " resigned."
                    : "You left the game.";
            case DISCONNECT -> winner
                    ? gameView.getOpponent().getDisplayName() + " disconnected."
                    : "The game ended after your connection closed.";
            case TIMEOUT -> winner
                    ? gameView.getOpponent().getDisplayName() + " lost on timeout."
                    : "You lost on timeout.";
            case NO_CONTEST -> "Neither player submitted a fleet before the placement deadline.";
        };
    }
}
