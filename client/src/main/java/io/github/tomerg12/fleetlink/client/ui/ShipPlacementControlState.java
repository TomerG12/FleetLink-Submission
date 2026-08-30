package io.github.tomerg12.fleetlink.client.ui;

import java.util.Objects;

import io.github.tomerg12.fleetlink.client.integration.ClientPhase;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;

/**
 * Derives immutable Ship Placement control availability from reconciled lifecycle state and local
 * fleet completeness without owning transitions, remote operations, or JavaFX controls.
 */
final class ShipPlacementControlState {

    /** Describes the placement screen copy associated with one immutable control decision. */
    enum Presentation {
        /** The player may arrange and submit a fleet. */
        PLACING,
        /** A fleet submission is awaiting an authoritative result. */
        SUBMISSION_PENDING,
        /** The accepted fleet is waiting for the opponent. */
        WAITING_FOR_OPPONENT,
        /** A voluntary forfeit is awaiting an authoritative result. */
        FORFEIT_PENDING
    }

    private final boolean editingEnabled;
    private final boolean confirmEnabled;
    private final boolean forfeitEnabled;
    private final Presentation presentation;

    /**
     * Creates one immutable placement-control decision.
     *
     * @param editingEnabled whether local arrangement controls are available
     * @param confirmEnabled whether a complete fleet may be submitted
     * @param forfeitEnabled whether a voluntary forfeit may begin
     * @param presentation player-facing lifecycle presentation
     */
    private ShipPlacementControlState(boolean editingEnabled, boolean confirmEnabled,
                                      boolean forfeitEnabled, Presentation presentation) {
        this.editingEnabled = editingEnabled;
        this.confirmEnabled = confirmEnabled;
        this.forfeitEnabled = forfeitEnabled;
        this.presentation = Objects.requireNonNull(presentation, "presentation");
    }

    /**
     * Evaluates the placement-owned phase matrix from the newest authoritative game phase.
     *
     * @param clientPhase reconciled client lifecycle phase
     * @param gamePhase newest authoritative game phase
     * @param fleetComplete whether the local arrangement is complete
     * @return immutable placement control decisions
     * @throws IllegalArgumentException if the supplied lifecycle belongs to another screen
     */
    static ShipPlacementControlState evaluate(ClientPhase clientPhase, GamePhase gamePhase,
                                              boolean fleetComplete) {
        Objects.requireNonNull(clientPhase, "clientPhase");
        Objects.requireNonNull(gamePhase, "gamePhase");
        return switch (clientPhase) {
            case SHIP_PLACEMENT -> requirePlacement(gamePhase,
                    new ShipPlacementControlState(true, fleetComplete, true,
                            Presentation.PLACING));
            case SUBMITTING_FLEET -> requirePlacement(gamePhase,
                    new ShipPlacementControlState(false, false, false,
                            Presentation.SUBMISSION_PENDING));
            case WAITING_FOR_BATTLE -> requirePlacement(gamePhase,
                    new ShipPlacementControlState(false, false, true,
                            Presentation.WAITING_FOR_OPPONENT));
            case LEAVING_GAME -> requirePlacement(gamePhase,
                    new ShipPlacementControlState(false, false, false,
                            Presentation.FORFEIT_PENDING));
            default -> throw new IllegalArgumentException(
                    "phase is not owned by Ship Placement: " + clientPhase);
        };
    }

    /**
     * Rejects a placement client phase paired with a non-placement authoritative snapshot.
     *
     * @param gamePhase authoritative game phase
     * @param state already derived placement state
     * @return the supplied state when the authoritative phase is fleet placement
     */
    private static ShipPlacementControlState requirePlacement(
            GamePhase gamePhase, ShipPlacementControlState state) {
        if (gamePhase != GamePhase.FLEET_PLACEMENT) {
            throw new IllegalArgumentException(
                    "Ship Placement requires an authoritative fleet-placement game");
        }
        return state;
    }

    /**
     * Reports whether the board and local arrangement actions are available.
     *
     * @return true when local editing is enabled
     */
    boolean isEditingEnabled() {
        return editingEnabled;
    }

    /**
     * Reports whether Confirm Fleet is available.
     *
     * @return true when submission is enabled
     */
    boolean isConfirmEnabled() {
        return confirmEnabled;
    }

    /**
     * Reports whether the placement Forfeit action is available.
     *
     * @return true when voluntary forfeit is enabled
     */
    boolean isForfeitEnabled() {
        return forfeitEnabled;
    }

    /**
     * Returns the placement lifecycle presentation selected by the matrix.
     *
     * @return immutable presentation category
     */
    Presentation getPresentation() {
        return presentation;
    }
}
