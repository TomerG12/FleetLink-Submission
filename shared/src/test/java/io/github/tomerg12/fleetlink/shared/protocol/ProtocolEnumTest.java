package io.github.tomerg12.fleetlink.shared.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/**
 * Locks down the stable enum vocabulary used by the initial protocol.
 */
class ProtocolEnumTest {

    /**
     * Defines the two supported placement directions without game logic.
     */
    @Test
    void definesOrientations() {
        assertArrayEquals(new Orientation[]{Orientation.HORIZONTAL, Orientation.VERTICAL},
                Orientation.values());
    }

    /**
     * Defines the ship identifiers carried by fleet submissions.
     */
    @Test
    void definesShipTypes() {
        assertArrayEquals(new ShipType[]{ShipType.CARRIER, ShipType.BATTLESHIP,
                ShipType.CRUISER, ShipType.SUBMARINE, ShipType.DESTROYER}, ShipType.values());
    }

    /**
     * Defines the phases represented in authoritative snapshots.
     */
    @Test
    void definesGamePhases() {
        assertArrayEquals(new GamePhase[]{GamePhase.FLEET_PLACEMENT, GamePhase.BATTLE,
                GamePhase.FINISHED}, GamePhase.values());
    }

    /**
     * Allows own-board snapshots to reveal the receiving player's ships.
     */
    @Test
    void definesOwnCellStates() {
        assertArrayEquals(new OwnCellView[]{OwnCellView.WATER, OwnCellView.SHIP,
                OwnCellView.MISS, OwnCellView.HIT}, OwnCellView.values());
    }

    /**
     * Excludes every undiscovered-ship state from opponent snapshots.
     */
    @Test
    void definesOnlyDiscoveredOpponentCellStates() {
        assertArrayEquals(new OpponentCellView[]{OpponentCellView.UNKNOWN,
                OpponentCellView.MISS, OpponentCellView.HIT}, OpponentCellView.values());
    }

    /**
     * Defines the outcomes available only for accepted shot operations.
     */
    @Test
    void definesShotOutcomes() {
        assertArrayEquals(new ShotOutcome[]{ShotOutcome.MISS, ShotOutcome.HIT,
                ShotOutcome.SUNK}, ShotOutcome.values());
    }

    /**
     * Defines the terminal reasons carried by finished game snapshots.
     */
    @Test
    void definesGameEndReasons() {
        assertArrayEquals(new GameEndReason[]{GameEndReason.ALL_SHIPS_SUNK,
                GameEndReason.RESIGNATION, GameEndReason.DISCONNECT, GameEndReason.TIMEOUT,
                GameEndReason.NO_CONTEST}, GameEndReason.values());
    }

    /**
     * Defines the registered player's result in one history entry.
     */
    @Test
    void definesMatchOutcomes() {
        assertArrayEquals(new MatchOutcome[]{MatchOutcome.WIN, MatchOutcome.LOSS},
                MatchOutcome.values());
    }

    /**
     * Defines successful matchmaking states without choosing an algorithm.
     */
    @Test
    void definesMatchmakingStates() {
        assertArrayEquals(new MatchmakingState[]{MatchmakingState.WAITING,
                MatchmakingState.MATCHED}, MatchmakingState.values());
    }

    /**
     * Defines all player-facing rematch negotiation states.
     */
    @Test
    void definesRematchStates() {
        assertArrayEquals(new RematchState[]{RematchState.AVAILABLE,
                RematchState.REQUESTED_BY_YOU, RematchState.REQUESTED_BY_OPPONENT,
                RematchState.ACCEPTED, RematchState.DECLINED, RematchState.EXPIRED},
                RematchState.values());
    }

    /**
     * Exercises every stable result code used instead of rule exceptions.
     */
    @Test
    void definesResultCodes() {
        assertArrayEquals(new ResultCode[]{ResultCode.SUCCESS, ResultCode.INVALID_CREDENTIALS,
                ResultCode.USERNAME_UNAVAILABLE, ResultCode.INVALID_SESSION,
                ResultCode.ALREADY_WAITING, ResultCode.NOT_WAITING, ResultCode.NOT_IN_GAME,
                ResultCode.INVALID_GAME_PHASE, ResultCode.INVALID_FLEET,
                ResultCode.FLEET_ALREADY_SUBMITTED, ResultCode.NOT_YOUR_TURN,
                ResultCode.DUPLICATE_SHOT, ResultCode.INVALID_TARGET,
                ResultCode.REMATCH_NOT_AVAILABLE, ResultCode.REMATCH_ALREADY_PENDING,
                ResultCode.INVALID_REQUEST, ResultCode.REGISTERED_ACCOUNT_REQUIRED},
                ResultCode.values());
    }
}
