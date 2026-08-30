package io.github.tomerg12.fleetlink.shared.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies explicit success/failure result invariants and serialization.
 */
class ResultTypesTest {

    /**
     * Represents payload-free success and stable expected failures.
     */
    @Test
    void createsOperationResults() {
        OperationResult success = OperationResult.success();
        OperationResult failure = OperationResult.failure(
                ResultCode.INVALID_SESSION, "Session expired");

        assertTrue(success.isSuccess());
        assertEquals(ResultCode.SUCCESS, success.getResultCode());
        assertEquals("", success.getMessage());
        assertFalse(failure.isSuccess());
        assertEquals(ResultCode.INVALID_SESSION, failure.getResultCode());
        assertEquals("Session expired", failure.getMessage());
    }

    /**
     * Rejects unusable codes and messages from every failure factory.
     */
    @Test
    void validatesFailureDetails() {
        assertThrows(NullPointerException.class,
                () -> OperationResult.failure(null, "Failure"));
        assertThrows(IllegalArgumentException.class,
                () -> OperationResult.failure(ResultCode.SUCCESS, "Failure"));
        assertThrows(NullPointerException.class,
                () -> OperationResult.failure(ResultCode.INVALID_REQUEST, null));
        assertThrows(IllegalArgumentException.class,
                () -> OperationResult.failure(ResultCode.INVALID_REQUEST, "   "));
    }

    /**
     * Carries session information only for successful connection operations.
     */
    @Test
    void createsSessionResults() {
        SessionInfo session = session();
        SessionResult success = SessionResult.success(session);
        SessionResult failure = SessionResult.failure(
                ResultCode.INVALID_CREDENTIALS, "Invalid credentials");

        assertTrue(success.isSuccess());
        assertSame(session, success.getSessionInfo());
        assertEquals(ResultCode.SUCCESS, success.getResultCode());
        assertEquals("", success.getMessage());
        assertFalse(failure.isSuccess());
        assertNull(failure.getSessionInfo());
        assertEquals(ResultCode.INVALID_CREDENTIALS, failure.getResultCode());
        assertEquals("Invalid credentials", failure.getMessage());
        assertThrows(NullPointerException.class, () -> SessionResult.success(null));
    }

    /**
     * Carries matchmaking state only after a successful request.
     */
    @Test
    void createsMatchmakingResults() {
        MatchmakingResult success = MatchmakingResult.success(MatchmakingState.WAITING);
        MatchmakingResult failure = MatchmakingResult.failure(
                ResultCode.ALREADY_WAITING, "Already waiting");

        assertTrue(success.isSuccess());
        assertEquals(MatchmakingState.WAITING, success.getState());
        assertEquals(ResultCode.SUCCESS, success.getResultCode());
        assertEquals("", success.getMessage());
        assertFalse(failure.isSuccess());
        assertNull(failure.getState());
        assertEquals(ResultCode.ALREADY_WAITING, failure.getResultCode());
        assertEquals("Already waiting", failure.getMessage());
        assertThrows(NullPointerException.class, () -> MatchmakingResult.success(null));
    }

    /**
     * Distinguishes atomic fleet acceptance from an expected rejection.
     */
    @Test
    void createsFleetSubmissionResults() {
        GameView game = gameView();
        FleetSubmissionResult accepted = FleetSubmissionResult.accepted(game);
        FleetSubmissionResult rejected = FleetSubmissionResult.rejected(
                ResultCode.INVALID_FLEET, "Ships overlap", game);
        FleetSubmissionResult missingGame = FleetSubmissionResult.rejected(
                ResultCode.NOT_IN_GAME, "No active game", null);

        assertTrue(accepted.isAccepted());
        assertSame(game, accepted.getGameView());
        assertEquals(ResultCode.SUCCESS, accepted.getResultCode());
        assertEquals("", accepted.getMessage());
        assertFalse(rejected.isAccepted());
        assertSame(game, rejected.getGameView());
        assertEquals(ResultCode.INVALID_FLEET, rejected.getResultCode());
        assertEquals("Ships overlap", rejected.getMessage());
        assertNull(missingGame.getGameView());
        assertThrows(NullPointerException.class, () -> FleetSubmissionResult.accepted(null));
    }

    /**
     * Includes a hit/miss outcome only when the server accepted the shot.
     */
    @Test
    void createsShotResults() {
        GameView game = gameView();
        ShotResult accepted = ShotResult.accepted(ShotOutcome.HIT, game);
        ShotResult rejected = ShotResult.rejected(
                ResultCode.NOT_YOUR_TURN, "Wait for your turn", game);
        ShotResult missingGame = ShotResult.rejected(
                ResultCode.NOT_IN_GAME, "No active game", null);

        assertTrue(accepted.isAccepted());
        assertEquals(ShotOutcome.HIT, accepted.getOutcome());
        assertSame(game, accepted.getGameView());
        assertEquals(ResultCode.SUCCESS, accepted.getResultCode());
        assertEquals("", accepted.getMessage());
        assertFalse(rejected.isAccepted());
        assertNull(rejected.getOutcome());
        assertSame(game, rejected.getGameView());
        assertEquals(ResultCode.NOT_YOUR_TURN, rejected.getResultCode());
        assertEquals("Wait for your turn", rejected.getMessage());
        assertNull(missingGame.getGameView());
        assertThrows(NullPointerException.class,
                () -> ShotResult.accepted(null, game));
        assertThrows(NullPointerException.class,
                () -> ShotResult.accepted(ShotOutcome.MISS, null));
    }

    /**
     * Avoids null-as-control-flow for current-game lookup failures.
     */
    @Test
    void createsGameViewResults() {
        GameView game = gameView();
        GameViewResult success = GameViewResult.success(game);
        GameViewResult failure = GameViewResult.failure(
                ResultCode.NOT_IN_GAME, "No active game");

        assertTrue(success.isSuccess());
        assertSame(game, success.getGameView());
        assertEquals(ResultCode.SUCCESS, success.getResultCode());
        assertEquals("", success.getMessage());
        assertFalse(failure.isSuccess());
        assertNull(failure.getGameView());
        assertEquals(ResultCode.NOT_IN_GAME, failure.getResultCode());
        assertEquals("No active game", failure.getMessage());
        assertThrows(NullPointerException.class, () -> GameViewResult.success(null));
    }

    /**
     * Carries personal statistics only for success and an explicit guest failure otherwise.
     */
    @Test
    void createsPlayerStatisticsResults() {
        PlayerStatisticsView payload = statisticsView();
        PlayerStatisticsResult success = PlayerStatisticsResult.success(payload);
        PlayerStatisticsResult failure = PlayerStatisticsResult.failure(
                ResultCode.REGISTERED_ACCOUNT_REQUIRED, "Registered account required");

        assertTrue(success.isSuccess());
        assertSame(payload, success.getStatistics());
        assertFalse(failure.isSuccess());
        assertNull(failure.getStatistics());
        assertEquals(ResultCode.REGISTERED_ACCOUNT_REQUIRED, failure.getResultCode());
    }

    /**
     * Carries an immutable leaderboard only for a successful result.
     */
    @Test
    void createsLeaderboardResults() {
        LeaderboardResult success = LeaderboardResult.success(List.of(
                new LeaderboardEntryView(1, "Ada", 1200, 2, 1)));
        LeaderboardResult failure = LeaderboardResult.failure(
                ResultCode.INVALID_REQUEST, "Invalid limit");

        assertTrue(success.isSuccess());
        assertEquals(1, success.getEntries().size());
        assertThrows(UnsupportedOperationException.class, () -> success.getEntries().clear());
        assertFalse(failure.isSuccess());
        assertNull(failure.getEntries());
    }

    /**
     * Preserves every result shape through standard Java serialization.
     *
     * @throws IOException if the test cannot serialize a result
     * @throws ClassNotFoundException if the test cannot deserialize a result type
     */
    @Test
    void resultsSurviveSerializationRoundTrip() throws IOException, ClassNotFoundException {
        OperationResult operation = SerializationTestSupport.roundTrip(
                OperationResult.failure(ResultCode.INVALID_SESSION, "Expired"),
                OperationResult.class);
        SessionResult session = SerializationTestSupport.roundTrip(
                SessionResult.success(session()), SessionResult.class);
        MatchmakingResult matchmaking = SerializationTestSupport.roundTrip(
                MatchmakingResult.success(MatchmakingState.WAITING), MatchmakingResult.class);
        FleetSubmissionResult fleet = SerializationTestSupport.roundTrip(
                FleetSubmissionResult.accepted(gameView()), FleetSubmissionResult.class);
        ShotResult shot = SerializationTestSupport.roundTrip(
                ShotResult.accepted(ShotOutcome.MISS, gameView()), ShotResult.class);
        GameViewResult game = SerializationTestSupport.roundTrip(
                GameViewResult.failure(ResultCode.NOT_IN_GAME, "No game"),
                GameViewResult.class);
        PlayerStatisticsResult statistics = SerializationTestSupport.roundTrip(
                PlayerStatisticsResult.success(statisticsView()), PlayerStatisticsResult.class);
        LeaderboardResult leaderboard = SerializationTestSupport.roundTrip(
                LeaderboardResult.success(List.of(
                        new LeaderboardEntryView(1, "Ada", 1200, 2, 1))),
                LeaderboardResult.class);

        assertEquals(ResultCode.INVALID_SESSION, operation.getResultCode());
        assertEquals("Expired", operation.getMessage());
        assertTrue(session.isSuccess());
        assertEquals(MatchmakingState.WAITING, matchmaking.getState());
        assertTrue(fleet.isAccepted());
        assertEquals(ShotOutcome.MISS, shot.getOutcome());
        assertFalse(game.isSuccess());
        assertEquals(MatchOutcome.WIN,
                statistics.getStatistics().getHistory().get(0).getOutcome());
        assertEquals("Ada", leaderboard.getEntries().get(0).getUsername());
    }

    /**
     * Creates safe session information for result tests.
     *
     * @return the new session information
     */
    private static SessionInfo session() {
        return new SessionInfo(UUID.randomUUID(), player("Ada"));
    }

    /**
     * Creates a safe active game snapshot for result tests.
     *
     * @return the new game view
     */
    private static GameView gameView() {
        return new GameView(UUID.randomUUID(), GamePhase.BATTLE, player("Ada"), player("Grace"),
                true, new OwnBoardView(BoardViewTest.ownCells(OwnCellView.WATER)),
                new OpponentBoardView(
                        BoardViewTest.opponentCells(OpponentCellView.UNKNOWN)), null, null);
    }

    /**
     * Creates a safe player view for nested result payloads.
     *
     * @param name the display name
     * @return the new player view
     */
    private static PlayerView player(String name) {
        return new PlayerView(UUID.randomUUID(), name, 1200, false);
    }

    /**
     * Creates representative nested personal statistics for result tests.
     *
     * @return validated statistics payload
     */
    private static PlayerStatisticsView statisticsView() {
        MatchHistoryEntryView history = new MatchHistoryEntryView(
                "Grace", false, MatchOutcome.WIN, GameEndReason.ALL_SHIPS_SUNK,
                4, Duration.ofMinutes(2), 3, 2, 1, 16,
                Instant.parse("2026-08-23T12:00:00Z"));
        return new PlayerStatisticsView(1216, 1, 1, 0,
                1, 3, 2, List.of(history), 0, false);
    }
}
