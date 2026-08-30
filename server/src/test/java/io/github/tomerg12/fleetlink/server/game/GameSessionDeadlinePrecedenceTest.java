package io.github.tomerg12.fleetlink.server.game;

import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.player;
import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.validFleet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies that authoritative deadline comparison precedes request-data validation for a valid turn. */
class GameSessionDeadlinePrecedenceTest {
    private static final Instant START = Instant.parse("2026-08-22T12:00:00Z");

    /**
     * Proves an otherwise invalid target received exactly at the deadline still consumes the
     * authoritative first timeout strike instead of escaping through target validation.
     */
    @Test
    void exactDeadlineTimesOutBeforeTargetValidation() {
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        GameSession game = battle(first, second);
        Instant deadline = game.getActiveDeadline();

        ShotResult result = game.fire(first.getPlayerId(), null, deadline,
                deadline.plusSeconds(45));
        GameView firstView = game.getCurrentGame(first.getPlayerId()).getGameView();

        assertFalse(result.isAccepted());
        assertEquals(ResultCode.NOT_YOUR_TURN, result.getResultCode());
        assertEquals(1, firstView.getYourTimeoutStrikes());
        assertFalse(firstView.isYourTurn());
        assertNull(firstView.getWinner());
        assertNull(firstView.getEndReason());
    }

    /**
     * Proves stale commands are rejected for turn ownership before their old timestamps can expire
     * the newer player's authoritative turn.
     */
    @Test
    void staleOldTurnCommandCannotExpireNewerTurn() {
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        GameSession game = battle(first, second);
        Instant firstDeadline = game.getActiveDeadline();

        ShotResult firstTimeout = game.fire(first.getPlayerId(), null, firstDeadline,
                firstDeadline.plusSeconds(45));
        assertFalse(firstTimeout.isAccepted());
        Instant secondDeadline = game.getActiveDeadline();
        ShotResult stale = game.fire(first.getPlayerId(), null, secondDeadline,
                secondDeadline.plusSeconds(45));
        GameView secondView = game.getCurrentGame(second.getPlayerId()).getGameView();

        assertEquals(ResultCode.NOT_YOUR_TURN, stale.getResultCode());
        assertTrue(secondView.isYourTurn());
        assertEquals(0, secondView.getYourTimeoutStrikes());
        assertEquals(1, secondView.getOpponentTimeoutStrikes());
        assertEquals(secondDeadline.toEpochMilli(), secondView.getDeadlineEpochMillis());
        assertFalse(game.isFinished());
        assertNull(secondView.getEndReason());
    }

    /**
     * Creates an active Battle state with the first participant owning the first turn.
     *
     * @param first first participant and starting player
     * @param second second participant
     * @return active Battle game
     */
    private static GameSession battle(PlayerView first, PlayerView second) {
        GameSession game = new GameSession(UUID.randomUUID(), first, second, first.getPlayerId());
        game.activatePlacement(START, START.plusSeconds(120));
        assertTrue(game.submitFleet(first.getPlayerId(), validFleet(), START.plusSeconds(1),
                START.plusSeconds(46)).isAccepted());
        assertTrue(game.submitFleet(second.getPlayerId(), validFleet(), START.plusSeconds(2),
                START.plusSeconds(47)).isAccepted());
        return game;
    }
}
