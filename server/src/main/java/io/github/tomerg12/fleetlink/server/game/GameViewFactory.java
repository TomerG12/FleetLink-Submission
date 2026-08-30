package io.github.tomerg12.fleetlink.server.game;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentBoardView;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentCellView;
import io.github.tomerg12.fleetlink.shared.protocol.OwnBoardView;
import io.github.tomerg12.fleetlink.shared.protocol.OwnCellView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import java.util.UUID;

/**
 * Converts authoritative server game state into safe player-specific shared snapshots.
 * The caller must provide a stable game state while a snapshot is being built.
 */
final class GameViewFactory {
    /** Prevents construction of the stateless snapshot factory. */
    private GameViewFactory() {
    }

    /**
     * Builds one player-specific snapshot without exposing undiscovered opponent ships.
     *
     * @param gameId game identifier
     * @param phase authoritative phase
     * @param receiver snapshot recipient
     * @param opponent other participant
     * @param receiverBoard recipient board
     * @param opponentBoard opponent board
     * @param yourTurn whether receiver owns the turn
     * @param winner terminal winner, or null before completion and for NO_CONTEST
     * @param endReason terminal reason, or null before completion
     * @param deadlineEpochMillis authoritative active deadline, or zero when none
     * @param yourTimeoutStrikes receiver's cumulative timeout strikes
     * @param opponentTimeoutStrikes opponent's cumulative timeout strikes
     * @return safe shared snapshot
     */
    static GameView create(UUID gameId, GamePhase phase, PlayerView receiver, PlayerView opponent,
                           Board receiverBoard, Board opponentBoard, boolean yourTurn,
                           PlayerView winner, GameEndReason endReason, long deadlineEpochMillis,
                           int yourTimeoutStrikes, int opponentTimeoutStrikes) {
        OwnCellView[][] ownCells = new OwnCellView[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
        OpponentCellView[][] opponentCells =
                new OpponentCellView[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
        for (int row = 0; row < Coordinate.BOARD_SIZE; row++) {
            for (int column = 0; column < Coordinate.BOARD_SIZE; column++) {
                Coordinate coordinate = new Coordinate(row, column);
                ownCells[row][column] = ownCell(receiverBoard, coordinate);
                opponentCells[row][column] = opponentCell(opponentBoard, coordinate);
            }
        }
        return new GameView(gameId, phase, receiver, opponent, yourTurn,
                new OwnBoardView(ownCells), new OpponentBoardView(opponentCells), winner, endReason,
                deadlineEpochMillis, yourTimeoutStrikes, opponentTimeoutStrikes);
    }

    /**
     * Maps one authoritative own-board cell into the receiver-visible protocol value.
     *
     * @param board receiver's authoritative board
     * @param coordinate board coordinate to map
     * @return safe own-cell view
     */
    private static OwnCellView ownCell(Board board, Coordinate coordinate) {
        if (board.wasFiredAt(coordinate)) {
            return board.hasShipAt(coordinate) ? OwnCellView.HIT : OwnCellView.MISS;
        }
        return board.hasShipAt(coordinate) ? OwnCellView.SHIP : OwnCellView.WATER;
    }

    /**
     * Maps one opponent-board cell without revealing an undiscovered ship location.
     *
     * @param board opponent's authoritative board
     * @param coordinate board coordinate to map
     * @return discovery-safe opponent-cell view
     */
    private static OpponentCellView opponentCell(Board board, Coordinate coordinate) {
        if (!board.wasFiredAt(coordinate)) {
            return OpponentCellView.UNKNOWN;
        }
        return board.hasShipAt(coordinate) ? OpponentCellView.HIT : OpponentCellView.MISS;
    }
}
