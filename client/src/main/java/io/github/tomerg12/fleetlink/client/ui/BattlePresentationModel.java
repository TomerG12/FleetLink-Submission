package io.github.tomerg12.fleetlink.client.ui;

import java.util.Objects;
import java.util.Optional;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentCellView;

/**
 * Tracks battle-screen interaction enablement separately from remote game behavior.
 */
public final class BattlePresentationModel {

    /**
     * Presentation states that determine whether target interaction is locally enabled.
     */
    public enum TurnPresentation {
        /** No authoritative game snapshot is available yet. */
        WAITING_FOR_STATE,

        /** Current snapshot permits the player to select a target. */
        YOUR_TURN,

        /** Current snapshot shows that the opponent owns the turn. */
        OPPONENT_TURN,

        /** Current snapshot represents a finished game. */
        FINISHED
    }

    private TurnPresentation turnPresentation;
    private Coordinate selectedTarget;
    private GameView gameView;
    private boolean operationPending;

    /**
     * Creates the battle presentation in its safe waiting state.
     */
    public BattlePresentationModel() {
        turnPresentation = TurnPresentation.WAITING_FOR_STATE;
    }

    /**
     * Applies a presentation state derived from a future authoritative game snapshot.
     * Changing away from the player's turn clears any local target selection.
     *
     * @param presentation new turn presentation state
     */
    public void applyTurnPresentation(TurnPresentation presentation) {
        turnPresentation = Objects.requireNonNull(presentation, "presentation");
        if (presentation != TurnPresentation.YOUR_TURN) {
            selectedTarget = null;
        }
    }

    /**
     * Applies one complete authoritative snapshot without predicting turn or terminal state.
     *
     * @param authoritativeGame newest server-provided player-specific snapshot
     */
    public void applyGameView(GameView authoritativeGame) {
        gameView = Objects.requireNonNull(authoritativeGame, "authoritativeGame");
        if (authoritativeGame.getPhase() == GamePhase.FINISHED) {
            applyTurnPresentation(TurnPresentation.FINISHED);
        } else if (authoritativeGame.getPhase() != GamePhase.BATTLE) {
            applyTurnPresentation(TurnPresentation.WAITING_FOR_STATE);
        } else if (authoritativeGame.isYourTurn()) {
            applyTurnPresentation(TurnPresentation.YOUR_TURN);
        } else {
            applyTurnPresentation(TurnPresentation.OPPONENT_TURN);
        }
    }

    /**
     * Attempts to select an enemy coordinate for local targeting feedback.
     *
     * @param coordinate requested target
     * @return true when targeting is currently allowed
     */
    public boolean selectTarget(Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        if (!isTargetingEnabled() || gameView != null
                && gameView.getOpponentBoard().getCell(coordinate) != OpponentCellView.UNKNOWN) {
            return false;
        }
        selectedTarget = coordinate;
        return true;
    }

    /**
     * Clears local target selection without changing the turn presentation.
     */
    public void clearSelection() {
        selectedTarget = null;
    }

    /**
     * Returns whether the fire control may be presented as enabled.
     *
     * @return true only during the player's turn with a selected target
     */
    public boolean canFire() {
        return isTargetingEnabled() && selectedTarget != null;
    }

    /**
     * Marks whether one authoritative Battle operation is awaiting reconciliation.
     * Pending work clears local selection and disables duplicate target actions.
     *
     * @param pending true while fire or leave is pending
     */
    public void setOperationPending(boolean pending) {
        operationPending = pending;
        if (pending) {
            selectedTarget = null;
        }
    }

    /**
     * Reports whether the latest authoritative state permits target selection.
     *
     * @return true only on the player's turn with no operation pending
     */
    public boolean isTargetingEnabled() {
        return turnPresentation == TurnPresentation.YOUR_TURN && !operationPending;
    }

    /**
     * Reports whether an authoritative Battle operation is pending.
     *
     * @return true while duplicate actions must remain disabled
     */
    public boolean isOperationPending() {
        return operationPending;
    }

    /**
     * Returns the currently selected target when one exists.
     *
     * @return optional selected target
     */
    public Optional<Coordinate> getSelectedTarget() {
        return Optional.ofNullable(selectedTarget);
    }

    /**
     * Returns the current turn presentation state.
     *
     * @return current battle presentation
     */
    public TurnPresentation getTurnPresentation() {
        return turnPresentation;
    }

    /**
     * Returns the authoritative snapshot currently driving battle presentation.
     *
     * @return optional current game snapshot
     */
    public Optional<GameView> getGameView() {
        return Optional.ofNullable(gameView);
    }
}
