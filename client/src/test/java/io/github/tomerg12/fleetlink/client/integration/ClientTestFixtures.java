package io.github.tomerg12.fleetlink.client.integration;

import java.util.List;
import java.util.UUID;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentBoardView;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentCellView;
import io.github.tomerg12.fleetlink.shared.protocol.Orientation;
import io.github.tomerg12.fleetlink.shared.protocol.OwnBoardView;
import io.github.tomerg12.fleetlink.shared.protocol.OwnCellView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchState;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.SessionInfo;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShipType;

/**
 * Creates deterministic safe protocol values for client integration tests.
 */
final class ClientTestFixtures {
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID OPPONENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID GAME_ID = UUID.fromString("00000000-0000-0000-0000-000000000013");

    /**
     * Prevents construction because fixtures are static test support.
     */
    private ClientTestFixtures() {
    }

    /**
     * Creates the deterministic guest session used by coordinator and gateway tests.
     *
     * @return safe temporary session information
     */
    static SessionInfo session() {
        return new SessionInfo(SESSION_ID, player());
    }

    /**
     * Creates a deterministic registered session for account-operation tests.
     *
     * @return safe registered session information
     */
    static SessionInfo registeredSession() {
        return new SessionInfo(SESSION_ID,
                new PlayerView(PLAYER_ID, "AccountUser", 1000, false));
    }

    /**
     * Creates a replacement session for the same registered player identity.
     *
     * @return second exact session with the existing account player ID
     */
    static SessionInfo replacementRegisteredSession() {
        return new SessionInfo(UUID.fromString("00000000-0000-0000-0000-000000000020"),
                new PlayerView(PLAYER_ID, "AccountUser", 1000, false));
    }

    /**
     * Creates a safe fleet-placement snapshot for the deterministic guest.
     *
     * @return authoritative fleet-placement snapshot
     */
    static GameView fleetPlacementGame() {
        return fleetPlacementGame(GAME_ID);
    }

    /** Creates a placement snapshot with a selected game identity. */
    static GameView fleetPlacementGame(UUID gameId) {
        return game(gameId, GamePhase.FLEET_PLACEMENT, false, null, null, null, null);
    }

    /**
     * Creates a safe battle snapshot for the deterministic guest.
     *
     * @return authoritative battle snapshot with the guest owning the turn
     */
    static GameView battleGame() {
        return battleGame(true);
    }

    /**
     * Creates a safe battle snapshot with a selected authoritative turn owner.
     *
     * @param yourTurn whether the deterministic guest owns the turn
     * @return authoritative battle snapshot
     */
    static GameView battleGame(boolean yourTurn) {
        return game(GAME_ID, GamePhase.BATTLE, yourTurn, null, null, null, null);
    }

    /**
     * Creates a battle snapshot after the server resolved one opponent target.
     *
     * @param yourTurn whether the deterministic guest owns the next turn
     * @param coordinate resolved target coordinate
     * @param cell authoritative discovery-only target state
     * @return authoritative post-shot battle snapshot
     */
    static GameView battleGameAfterShot(boolean yourTurn, Coordinate coordinate,
                                        OpponentCellView cell) {
        return game(GAME_ID, GamePhase.BATTLE, yourTurn, coordinate, cell, null, null);
    }

    /**
     * Creates a terminal snapshot for the deterministic guest.
     *
     * @param playerWins whether the receiving guest is the authoritative winner
     * @return authoritative finished game snapshot
     */
    static GameView finishedGame(boolean playerWins) {
        return finishedGame(GAME_ID, playerWins);
    }

    /** Creates a terminal snapshot with a selected game identity. */
    static GameView finishedGame(UUID gameId, boolean playerWins) {
        PlayerView opponent = opponent();
        PlayerView winner = playerWins ? player() : opponent;
        return game(gameId, GamePhase.FINISHED, false, null, null, winner,
                GameEndReason.ALL_SHIPS_SUNK);
    }

    /** Creates a rematch callback status for the deterministic completed game and opponent. */
    static RematchStatusView rematchStatus(RematchState state) {
        return new RematchStatusView(GAME_ID, opponent(), state);
    }

    /** Creates a rematch callback status with selected correlation values. */
    static RematchStatusView rematchStatus(UUID gameId, PlayerView opponent, RematchState state) {
        return new RematchStatusView(gameId, opponent, state);
    }

    /** Returns the deterministic opponent for callback-correlation tests. */
    static PlayerView opponentView() {
        return opponent();
    }

    /**
     * Creates the complete valid deterministic fleet used by real server integration.
     *
     * @return one legal placement for every required ship type
     */
    static List<ShipPlacement> validFleet() {
        return List.of(
                new ShipPlacement(ShipType.CARRIER,
                        new Coordinate(0, 0), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.BATTLESHIP,
                        new Coordinate(1, 0), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.CRUISER,
                        new Coordinate(2, 0), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.SUBMARINE,
                        new Coordinate(3, 0), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.DESTROYER,
                        new Coordinate(4, 0), Orientation.HORIZONTAL));
    }

    /**
     * Creates the deterministic safe player description.
     *
     * @return guest player view
     */
    private static PlayerView player() {
        return new PlayerView(PLAYER_ID, "Guest Alpha", 1000, true);
    }

    /**
     * Creates one valid non-terminal game snapshot with secrecy-preserving boards.
     *
     * @param phase requested non-terminal phase
     * @param yourTurn whether the deterministic guest owns the turn
     * @return valid authoritative game snapshot
     */
    private static GameView game(UUID gameId, GamePhase phase, boolean yourTurn,
                                 Coordinate resolvedTarget,
                                 OpponentCellView resolvedCell, PlayerView winner,
                                 GameEndReason endReason) {
        OwnCellView[][] own = new OwnCellView[10][10];
        OpponentCellView[][] opponent = new OpponentCellView[10][10];
        for (int row = 0; row < 10; row++) {
            for (int column = 0; column < 10; column++) {
                own[row][column] = OwnCellView.WATER;
                opponent[row][column] = OpponentCellView.UNKNOWN;
            }
        }
        if (phase == GamePhase.BATTLE) {
            own[0][0] = OwnCellView.SHIP;
        }
        if (resolvedTarget != null) {
            opponent[resolvedTarget.getRow()][resolvedTarget.getColumn()] = resolvedCell;
        }
        return new GameView(gameId, phase, player(), opponent(), yourTurn,
                new OwnBoardView(own), new OpponentBoardView(opponent), winner, endReason);
    }

    /**
     * Creates the deterministic opponent description.
     *
     * @return guest opponent view
     */
    private static PlayerView opponent() {
        return new PlayerView(OPPONENT_ID, "Guest Bravo", 1000, true);
    }
}
