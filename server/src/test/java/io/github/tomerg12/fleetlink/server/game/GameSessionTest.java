package io.github.tomerg12.fleetlink.server.game;

import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.overlappingFleet;
import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.player;
import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.validFleet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentCellView;
import io.github.tomerg12.fleetlink.shared.protocol.OwnCellView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShotOutcome;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies authoritative placement, battle, deadline, snapshot, and terminal GameSession behavior.
 */
class GameSessionTest {
    private static final Instant START = Instant.parse("2026-08-22T12:00:00Z");

    /** Proves pre-activation placement is indexed but not playable or externally exposed. */
    @Test
    void rejectsGameplayBeforePlacementActivation() {
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        GameSession game = new GameSession(UUID.randomUUID(), first, second, first.getPlayerId());

        assertFalse(game.submitFleet(first.getPlayerId(), validFleet(), START,
                START.plusSeconds(45)).isAccepted());
        assertEquals(ResultCode.INVALID_GAME_PHASE,
                game.getCurrentGame(first.getPlayerId()).getResultCode());
        assertNull(game.getActiveDeadline());
        assertEquals(0L, game.getDeadlineGeneration());
    }

    /**
     * Proves rejected fleet validation is atomic and a later valid fleet can still commit.
     */
    @Test
    void rejectsInvalidFleetWithoutPartialMutation() {
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        GameSession game = placement(first, second, first.getPlayerId());

        FleetSubmissionResult rejected = submit(
                game, first, overlappingFleet(), START.plusSeconds(1));
        FleetSubmissionResult accepted = submit(
                game, first, validFleet(), START.plusSeconds(2));

        assertFalse(rejected.isAccepted());
        assertEquals(ResultCode.INVALID_FLEET, rejected.getResultCode());
        assertTrue(accepted.isAccepted());
        assertEquals(OwnCellView.SHIP,
                accepted.getGameView().getOwnBoard().getCell(new Coordinate(0, 0)));
    }

    /**
     * Starts battle only after both fleets are accepted and keeps opponent ships secret.
     */
    @Test
    void startsBattleAndBuildsSafeSnapshots() {
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        GameSession game = placement(first, second, first.getPlayerId());

        FleetSubmissionResult firstReady = submit(
                game, first, validFleet(), START.plusSeconds(1));
        FleetSubmissionResult secondReady = submit(
                game, second, validFleet(), START.plusSeconds(2));
        GameView firstView = game.getCurrentGame(first.getPlayerId()).getGameView();

        assertEquals(GamePhase.FLEET_PLACEMENT, firstReady.getGameView().getPhase());
        assertEquals(GamePhase.BATTLE, secondReady.getGameView().getPhase());
        assertTrue(firstView.isYourTurn());
        assertEquals(OwnCellView.SHIP,
                firstView.getOwnBoard().getCell(new Coordinate(0, 0)));
        assertEquals(OpponentCellView.UNKNOWN,
                firstView.getOpponentBoard().getCell(new Coordinate(0, 0)));
    }

    /** Enforces exact placement deadline equality and one-player timeout victory. */
    @Test
    void placementDeadlineEqualityProducesTimeoutWinner() {
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        GameSession game = placement(first, second, first.getPlayerId());
        assertTrue(submit(game, first, validFleet(), START.plusSeconds(10)).isAccepted());

        FleetSubmissionResult late = submit(
                game, second, validFleet(), START.plusSeconds(120));
        GameView finalView = late.getGameView();

        assertFalse(late.isAccepted());
        assertEquals(GamePhase.FINISHED, finalView.getPhase());
        assertEquals(GameEndReason.TIMEOUT, finalView.getEndReason());
        assertEquals(first.getPlayerId(), finalView.getWinner().getPlayerId());
        TerminalGameSnapshot terminal = game.captureTerminalState();
        assertEquals(0, terminal.getPlayerOneTelemetry().getTurnsTaken());
        assertEquals(0, terminal.getPlayerTwoTelemetry().getTurnsTaken());
    }

    /** Ends double-AFK placement as winnerless NO_CONTEST. */
    @Test
    void doubleAfkPlacementProducesNoContest() {
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        GameSession game = placement(first, second, first.getPlayerId());

        assertTrue(game.expireDeadline(game.getDeadlineGeneration(), START.plusSeconds(120),
                START.plusSeconds(165)));
        GameView finalView = game.getCurrentGame(first.getPlayerId()).getGameView();
        TerminalGameSnapshot terminal = game.captureTerminalState();

        assertEquals(GameEndReason.NO_CONTEST, finalView.getEndReason());
        assertNull(finalView.getWinner());
        assertNull(terminal.getWinnerPlayerId());
        assertEquals(0, terminal.getPlayerOneTelemetry().getTurnsTaken());
        assertEquals(0, terminal.getPlayerTwoTelemetry().getTurnsTaken());
    }

    /** First Battle timeout transfers turn and second cumulative timeout loses the game. */
    @Test
    void battleTimeoutStrikesAreCumulativeAndValidMoveDoesNotResetThem() {
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        GameSession game = battle(first, second, first.getPlayerId());
        Instant firstDeadline = game.getActiveDeadline();

        assertTrue(game.expireDeadline(game.getDeadlineGeneration(), firstDeadline,
                firstDeadline.plusSeconds(45)));
        GameView firstStrike = game.getCurrentGame(first.getPlayerId()).getGameView();
        assertEquals(1, firstStrike.getYourTimeoutStrikes());
        assertFalse(firstStrike.isYourTurn());

        ShotResult secondMove = game.fire(second.getPlayerId(), new Coordinate(9, 9),
                firstDeadline.plusSeconds(1), firstDeadline.plusSeconds(46));
        assertTrue(secondMove.isAccepted());
        GameView returnedTurn = game.getCurrentGame(first.getPlayerId()).getGameView();
        assertEquals(1, returnedTurn.getYourTimeoutStrikes());
        assertTrue(returnedTurn.isYourTurn());

        Instant secondDeadline = game.getActiveDeadline();
        assertTrue(game.expireDeadline(game.getDeadlineGeneration(), secondDeadline,
                secondDeadline.plusSeconds(45)));
        GameView finalView = game.getCurrentGame(first.getPlayerId()).getGameView();
        assertEquals(GameEndReason.TIMEOUT, finalView.getEndReason());
        assertEquals(second.getPlayerId(), finalView.getWinner().getPlayerId());
        assertEquals(2, finalView.getYourTimeoutStrikes());
        TerminalGameSnapshot terminal = game.captureTerminalState();
        assertEquals(2, terminal.getPlayerOneTelemetry().getTurnsTaken());
        assertEquals(0, terminal.getPlayerOneTelemetry().getShotsFired());
        assertEquals(1, terminal.getPlayerTwoTelemetry().getTurnsTaken());
        assertEquals(1, terminal.getPlayerTwoTelemetry().getShotsFired());
    }

    /**
     * Enforces turn ownership and rejects a duplicate shot when the shooter regains the turn.
     */
    @Test
    void enforcesTurnAndDuplicateShotRules() {
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        GameSession game = battle(first, second, first.getPlayerId());
        Instant now = START.plusSeconds(3);

        ShotResult firstHit = game.fire(first.getPlayerId(), new Coordinate(0, 0),
                now, now.plusSeconds(45));
        ShotResult wrongTurn = game.fire(first.getPlayerId(), new Coordinate(0, 1),
                now, now.plusSeconds(45));
        ShotResult secondMiss = game.fire(second.getPlayerId(), new Coordinate(9, 9),
                now, now.plusSeconds(45));
        ShotResult duplicate = game.fire(first.getPlayerId(), new Coordinate(0, 0),
                now, now.plusSeconds(45));

        assertEquals(ShotOutcome.HIT, firstHit.getOutcome());
        assertEquals(ResultCode.NOT_YOUR_TURN, wrongTurn.getResultCode());
        assertEquals(ShotOutcome.MISS, secondMiss.getOutcome());
        assertEquals(ResultCode.DUPLICATE_SHOT, duplicate.getResultCode());

        assertTrue(game.leave(second.getPlayerId()).isSuccess());
        TerminalGameSnapshot terminal = game.captureTerminalState();
        ParticipantTelemetrySnapshot firstTelemetry = terminal.getPlayerOneTelemetry();
        ParticipantTelemetrySnapshot secondTelemetry = terminal.getPlayerTwoTelemetry();
        assertEquals(1, firstTelemetry.getShotsFired());
        assertEquals(1, firstTelemetry.getHits());
        assertEquals(0, firstTelemetry.getShipsSunk());
        assertEquals(1, firstTelemetry.getTurnsTaken());
        assertEquals(1, secondTelemetry.getShotsFired());
        assertEquals(0, secondTelemetry.getHits());
        assertEquals(1, secondTelemetry.getTurnsTaken());
    }

    /**
     * Detects sunk ships and ends the game exactly when the final opponent ship cell is hit.
     */
    @Test
    void finishesWhenAllOpponentShipsAreSunk() {
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        GameSession game = battle(first, second, first.getPlayerId());
        List<Coordinate> targets = occupiedFleetCells();
        List<Coordinate> misses = safeMissCells();
        ShotResult finalShot = null;
        Instant now = START.plusSeconds(3);

        for (int index = 0; index < targets.size(); index++) {
            finalShot = game.fire(first.getPlayerId(), targets.get(index),
                    now, now.plusSeconds(45));
            assertTrue(finalShot.isAccepted());
            if (index < targets.size() - 1) {
                ShotResult returnShot = game.fire(second.getPlayerId(), misses.get(index), now,
                        now.plusSeconds(45));
                assertTrue(returnShot.isAccepted());
                assertEquals(ShotOutcome.MISS, returnShot.getOutcome());
            }
        }
        GameView finalView = game.getCurrentGame(first.getPlayerId()).getGameView();
        assertEquals(ShotOutcome.SUNK, finalShot.getOutcome());
        assertEquals(GamePhase.FINISHED, finalView.getPhase());
        assertEquals(GameEndReason.ALL_SHIPS_SUNK, finalView.getEndReason());
        assertEquals(first.getPlayerId(), finalView.getWinner().getPlayerId());
        TerminalGameSnapshot terminal = game.captureTerminalState();
        assertEquals(17, terminal.getPlayerOneTelemetry().getShotsFired());
        assertEquals(17, terminal.getPlayerOneTelemetry().getHits());
        assertEquals(5, terminal.getPlayerOneTelemetry().getShipsSunk());
        assertEquals(17, terminal.getPlayerOneTelemetry().getTurnsTaken());
        assertEquals(16, terminal.getPlayerTwoTelemetry().getShotsFired());
        assertEquals(0, terminal.getPlayerTwoTelemetry().getHits());
        assertEquals(16, terminal.getPlayerTwoTelemetry().getTurnsTaken());
    }

    /**
     * Ends an unfinished game with the opponent as winner for both leave and disconnect paths.
     */
    @Test
    void supportsResignationAndDisconnectTerminalStates() {
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        GameSession resigned = new GameSession(
                UUID.randomUUID(), first, second, first.getPlayerId());
        resigned.activatePlacement(START, START.plusSeconds(120));
        assertTrue(resigned.leave(first.getPlayerId()).isSuccess());
        GameView resignedView = resigned.getCurrentGame(second.getPlayerId()).getGameView();
        assertEquals(GameEndReason.RESIGNATION, resignedView.getEndReason());
        assertEquals(second.getPlayerId(), resignedView.getWinner().getPlayerId());
        assertEquals(0, resigned.captureTerminalState()
                .getPlayerOneTelemetry().getTurnsTaken());

        PlayerView third = player("Third", 1200);
        PlayerView fourth = player("Fourth", 1200);
        GameSession disconnected = new GameSession(
                UUID.randomUUID(), third, fourth, third.getPlayerId());
        disconnected.activatePlacement(START, START.plusSeconds(120));
        assertTrue(disconnected.disconnect(fourth.getPlayerId()).isSuccess());
        GameView disconnectedView = disconnected.getCurrentGame(third.getPlayerId()).getGameView();
        assertEquals(GameEndReason.DISCONNECT, disconnectedView.getEndReason());
        assertEquals(third.getPlayerId(), disconnectedView.getWinner().getPlayerId());
        assertEquals(0, disconnected.captureTerminalState()
                .getPlayerTwoTelemetry().getTurnsTaken());
    }

    /**
     * Captures exactly the authoritative game identity, participants, winner, and end reason.
     */
    @Test
    void terminalSnapshotPreservesAuthoritativeGameState() {
        UUID gameId = UUID.randomUUID();
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1000);
        GameSession game = new GameSession(gameId, first, second, first.getPlayerId());

        assertThrows(IllegalStateException.class, game::captureTerminalState);
        game.activatePlacement(START, START.plusSeconds(120));
        assertTrue(game.leave(first.getPlayerId()).isSuccess());
        TerminalGameSnapshot terminal = game.captureTerminalState();
        assertEquals(gameId, terminal.getGameId());
        assertEquals(START, terminal.getStartedAt());
        assertSame(first, terminal.getPlayerOne());
        assertSame(second, terminal.getPlayerTwo());
        assertEquals(second.getPlayerId(), terminal.getWinnerPlayerId());
        assertEquals(GameEndReason.RESIGNATION, terminal.getEndReason());
    }

    /**
     * Rejects malformed terminal values while allowing a null winner only for NO_CONTEST.
     */
    @Test
    void terminalSnapshotValidatesParticipantAndWinnerIdentity() {
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1000);

        assertThrows(IllegalArgumentException.class, () -> new TerminalGameSnapshot(
                UUID.randomUUID(), START, first, first, telemetry(first), telemetry(first),
                first.getPlayerId(),
                GameEndReason.RESIGNATION));
        assertThrows(IllegalArgumentException.class, () -> new TerminalGameSnapshot(
                UUID.randomUUID(), START, first, second, telemetry(first), telemetry(second),
                UUID.randomUUID(),
                GameEndReason.DISCONNECT));
        assertThrows(IllegalArgumentException.class, () -> new TerminalGameSnapshot(
                UUID.randomUUID(), START, first, second, telemetry(first), telemetry(second),
                null, GameEndReason.TIMEOUT));
        assertThrows(IllegalArgumentException.class, () -> new TerminalGameSnapshot(
                UUID.randomUUID(), START, first, second, telemetry(first), telemetry(second),
                first.getPlayerId(),
                GameEndReason.NO_CONTEST));
    }

    /**
     * Creates one activated placement game with the supplied first Battle participant.
     *
     * @param first first participant
     * @param second second participant
     * @param starter participant that will receive the first Battle turn
     * @return activated placement game
     */
    private static GameSession placement(PlayerView first, PlayerView second, UUID starter) {
        GameSession game = new GameSession(UUID.randomUUID(), first, second, starter);
        game.activatePlacement(START, START.plusSeconds(120));
        return game;
    }

    /**
     * Creates zeroed telemetry for terminal-value validation.
     *
     * @param player participant whose identifier is captured
     * @return valid empty telemetry
     */
    private static ParticipantTelemetrySnapshot telemetry(PlayerView player) {
        return new ParticipantTelemetrySnapshot(player.getPlayerId(), 0, 0, 0, 0);
    }

    /**
     * Submits one fleet with a deterministic Battle deadline derived from the supplied ingress time.
     *
     * @param game authoritative game under test
     * @param player submitting participant
     * @param fleet complete fleet input
     * @param receivedAt deterministic server ingress time
     * @return authoritative fleet-submission result
     */
    private static FleetSubmissionResult submit(GameSession game, PlayerView player,
                                                List<ShipPlacement> fleet,
                                                Instant receivedAt) {
        return game.submitFleet(player.getPlayerId(), fleet,
                receivedAt, receivedAt.plusSeconds(45));
    }

    /**
     * Creates an active Battle game with both deterministic fleets accepted.
     *
     * @param first first participant
     * @param second second participant
     * @param starter participant that receives the first Battle turn
     * @return active Battle game
     */
    private static GameSession battle(PlayerView first, PlayerView second, UUID starter) {
        GameSession game = placement(first, second, starter);
        submit(game, first, validFleet(), START.plusSeconds(1));
        submit(game, second, validFleet(), START.plusSeconds(2));
        return game;
    }

    /**
     * Returns every occupied cell from the standard deterministic test fleet.
     *
     * @return ordered occupied target coordinates
     */
    private static List<Coordinate> occupiedFleetCells() {
        List<Coordinate> cells = new ArrayList<>();
        for (int column = 0; column < 5; column++) {
            cells.add(new Coordinate(0, column));
        }
        for (int column = 0; column < 4; column++) {
            cells.add(new Coordinate(1, column));
        }
        for (int column = 0; column < 3; column++) {
            cells.add(new Coordinate(2, column));
        }
        for (int column = 0; column < 3; column++) {
            cells.add(new Coordinate(3, column));
        }
        for (int column = 0; column < 2; column++) {
            cells.add(new Coordinate(4, column));
        }
        return cells;
    }

    /**
     * Returns enough known-water cells for the opponent to hand turns back during sinking tests.
     *
     * @return ordered safe miss coordinates
     */
    private static List<Coordinate> safeMissCells() {
        List<Coordinate> cells = new ArrayList<>();
        for (int row = 5; row < Coordinate.BOARD_SIZE; row++) {
            for (int column = 0; column < Coordinate.BOARD_SIZE; column++) {
                cells.add(new Coordinate(row, column));
            }
        }
        return cells;
    }
}
