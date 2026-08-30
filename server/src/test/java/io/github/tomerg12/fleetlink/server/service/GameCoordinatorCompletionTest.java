package io.github.tomerg12.fleetlink.server.service;

import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.validFleet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.completion.CompletedGameSnapshot;
import io.github.tomerg12.fleetlink.server.completion.CompletionRecordOutcome;
import io.github.tomerg12.fleetlink.server.completion.CompletionRecorder;
import io.github.tomerg12.fleetlink.server.game.GameSession;
import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.server.matchmaking.MatchmakingService;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingState;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies centralized terminal transition capture and off-lane completion handoff after T5.5
 * sequencing.
 */
class GameCoordinatorCompletionTest {
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-22T12:00:00Z");

    /**
     * Captures resignation exactly once with authoritative registered and guest snapshots.
     */
    @Test
    void resignationCapturesOneImmutableMixedSnapshot() {
        Fixture fixture = new Fixture();
        try {
            PlayerView registered = player("Account", false, 1234);
            PlayerView guest = player("Guest", true, 1000);
            GameSession game = fixture.createGame(registered, guest, registered.getPlayerId());
            fixture.coordinator.activateMatchedGame(game);
            assertTrue(fixture.coordinator.leaveGame(guest.getPlayerId()).isSuccess());
            assertFalse(fixture.coordinator.disconnect(guest.getPlayerId()).isSuccess());
            assertEquals(1, fixture.recorded.size());
            CompletedGameSnapshot snapshot = fixture.recorded.getFirst();
            assertEquals(game.getGameId(), snapshot.getGameId());
            assertEquals(COMPLETED_AT, snapshot.getCompletedAt());
            assertEquals(GameEndReason.RESIGNATION, snapshot.getEndReason());
            assertEquals(2, snapshot.getParticipants().size());
            assertEquals(1234, snapshot.getParticipants().stream()
                    .filter(participant -> !participant.isGuest()).findFirst().orElseThrow()
                    .getRatingAtMatch());
        } finally {
            fixture.coordinator.close();
        }
    }

    /**
     * Captures ALL_SHIPS_SUNK only when the final authoritative shot transitions the game.
     */
    @Test
    void finalShotCapturesAllShipsSunkTransition() {
        Fixture fixture = new Fixture();
        try {
            PlayerView first = player("First", false, 1000);
            PlayerView second = player("Second", false, 1000);
            GameSession game = fixture.createGame(first, second, first.getPlayerId());
            RatingObservingCallback firstCallback = new RatingObservingCallback(
                    fixture.ratings, first.getPlayerId());
            fixture.callbacks.register(first, firstCallback);
            fixture.callbacks.register(second, new RecordingCallback());
            fixture.coordinator.activateMatchedGame(game);
            fixture.coordinator.submitFleet(first.getPlayerId(), validFleet());
            fixture.coordinator.submitFleet(second.getPlayerId(), validFleet());
            List<Coordinate> targets = occupiedTargets();
            List<Coordinate> misses = safeMisses();
            for (int index = 0; index < targets.size(); index++) {
                fixture.coordinator.fire(first.getPlayerId(), targets.get(index));
                if (index + 1 < targets.size()) {
                    fixture.coordinator.fire(second.getPlayerId(), misses.get(index));
                }
            }
            assertEquals(1, fixture.recorded.size());
            assertEquals(GameEndReason.ALL_SHIPS_SUNK,
                    fixture.recorded.getFirst().getEndReason());
            CompletedGameSnapshot completion = fixture.recorded.getFirst();
            assertEquals(COMPLETED_AT, completion.getStartedAt());
            assertEquals(1016, fixture.ratings.current(first.getPlayerId()).getRating());
            assertEquals(984, fixture.ratings.current(second.getPlayerId()).getRating());
            assertEquals(1016, firstCallback.lastObservedRating);
            assertEquals(16, completion.getParticipants().stream()
                    .filter(participant -> participant.getPlayerId().equals(first.getPlayerId()))
                    .findFirst().orElseThrow().getRatingDelta());
            assertEquals(0L, completion.getParticipants().getFirst()
                    .getRatingRevisionBefore());
        } finally {
            fixture.coordinator.close();
        }
    }

    /**
     * Returns the terminal result and callback before a blocked database write is released.
     *
     * @throws Exception if asynchronous recording does not start in time
     */
    @Test
    void persistenceLatencyDoesNotDelayGameOverCallbackOrResult() throws Exception {
        CountDownLatch persistenceStarted = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);
        CompletionRecorder recorder = new CompletionRecorder(snapshot -> {
            persistenceStarted.countDown();
            try {
                releasePersistence.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return CompletionRecordOutcome.RECORDED;
        });
        GameCoordinator coordinator = null;
        try {
            GameSessionManager games = new GameSessionManager();
            ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
            coordinator = new GameCoordinator(games, callbacks, recorder,
                    Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));
            PlayerView registered = player("Account", false, 1000);
            PlayerView guest = player("Guest", true, 1000);
            RecordingCallback callback = new RecordingCallback();
            callbacks.register(registered, callback);
            callbacks.register(guest, new RecordingCallback());
            GameSession game = games.createGame(
                    registered, guest, registered.getPlayerId());
            coordinator.activateMatchedGame(game);
            GameCoordinator finalCoordinator = coordinator;
            assertTimeoutPreemptively(Duration.ofMillis(500),
                    () -> assertTrue(finalCoordinator.leaveGame(guest.getPlayerId()).isSuccess()));
            assertEquals(1, callback.gameStateChanges);
            assertTrue(persistenceStarted.await(1, TimeUnit.SECONDS));
        } finally {
            releasePersistence.countDown();
            if (coordinator != null) coordinator.close();
            recorder.close();
        }
    }

    /**
     * Keeps the authoritative FINISHED result and callback unchanged when every database attempt
     * fails.
     */
    @Test
    void databaseFailureCannotChangeFinishedGameOrCallback() {
        CompletionRecorder recorder = new CompletionRecorder(snapshot -> {
            throw new IllegalStateException("database unavailable");
        }, java.util.concurrent.Executors.newSingleThreadScheduledExecutor(),
                Duration.ofMillis(100));
        GameCoordinator coordinator = null;
        try {
            GameSessionManager games = new GameSessionManager();
            ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
            coordinator = new GameCoordinator(games, callbacks, recorder,
                    Clock.fixed(COMPLETED_AT, ZoneOffset.UTC));
            PlayerView registered = player("Account", false, 1000);
            PlayerView guest = player("Guest", true, 1000);
            RecordingCallback callback = new RecordingCallback();
            callbacks.register(registered, callback);
            callbacks.register(guest, new RecordingCallback());
            GameSession game = games.createGame(
                    registered, guest, registered.getPlayerId());
            coordinator.activateMatchedGame(game);

            assertTrue(coordinator.leaveGame(guest.getPlayerId()).isSuccess());
            GameView finalView = coordinator.getCurrentGame(
                    registered.getPlayerId()).getGameView();
            assertEquals(io.github.tomerg12.fleetlink.shared.protocol.GamePhase.FINISHED,
                    finalView.getPhase());
            assertEquals(registered.getPlayerId(), finalView.getWinner().getPlayerId());
            assertEquals(GameEndReason.RESIGNATION, finalView.getEndReason());
            assertEquals(1, callback.gameStateChanges);
        } finally {
            if (coordinator != null) {
                coordinator.close();
            }
            recorder.close();
        }
    }

    /**
     * Uses a terminal live update for immediate next-match capture while persistence is blocked.
     *
     * @throws Exception if the controlled persistence attempt does not start
     */
    @Test
    void nextMatchUsesUpdatedRatingBeforeCompletionPersistenceFinishes() throws Exception {
        CountDownLatch persistenceStarted = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);
        CompletionRecorder recorder = new CompletionRecorder(snapshot -> {
            persistenceStarted.countDown();
            try {
                releasePersistence.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return CompletionRecordOutcome.RECORDED;
        });
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, recorder,
                Clock.fixed(COMPLETED_AT, ZoneOffset.UTC), ratings);
        MatchmakingService matchmaking = new MatchmakingService(
                games, callbacks, coordinator, ratings, (sessionId, playerId) -> true);
        try {
            PlayerView player = player("Continuing", false, 1000);
            PlayerView firstOpponent = player("FirstOpponent", false, 1000);
            PlayerView nextOpponent = player("NextOpponent", false, 1000);
            for (PlayerView participant : List.of(player, firstOpponent, nextOpponent)) {
                ratings.seedIfAbsent(participant.getPlayerId(), participant.getRating(), 0L);
                callbacks.register(participant, new RecordingCallback());
            }
            GameSession firstGame = games.createGame(
                    player, firstOpponent, player.getPlayerId());
            coordinator.activateMatchedGame(firstGame);

            assertTrue(coordinator.leaveGame(firstOpponent.getPlayerId()).isSuccess());
            assertTrue(persistenceStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1016, ratings.current(player.getPlayerId()).getRating());

            assertEquals(MatchmakingState.WAITING,
                    matchmaking.join(nextOpponent.getPlayerId(), nextOpponent).getState());
            assertEquals(MatchmakingState.MATCHED,
                    matchmaking.join(player.getPlayerId(), player).getState());
            GameView nextGameView = games.findByPlayerId(player.getPlayerId()).orElseThrow()
                    .getCurrentGame(player.getPlayerId()).getGameView();
            assertEquals(1016, nextGameView.getPlayer().getRating());
            assertEquals(1000, player.getRating());
        } finally {
            releasePersistence.countDown();
            coordinator.close();
            recorder.close();
        }
    }

    /**
     * Prevents matchmaking admission after terminal mutation but before live rating and completion
     * mapping finalization publish the participant's availability.
     *
     * @throws Exception if controlled terminal or matchmaking work does not complete in time
     */
    @Test
    void terminalMutationCannotExposeStaleRatingToNextMatchmaking() throws Exception {
        CountDownLatch terminalMutated = new CountDownLatch(1);
        CountDownLatch releaseFinalization = new CountDownLatch(1);
        List<CompletedGameSnapshot> recorded = new ArrayList<>();
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, recorded::add,
                Clock.fixed(COMPLETED_AT, ZoneOffset.UTC), ratings, () -> {
                    terminalMutated.countDown();
                    try {
                        releaseFinalization.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("terminal finalization observer interrupted",
                                exception);
                    }
                });
        MatchmakingService matchmaking = new MatchmakingService(
                games, callbacks, coordinator, ratings, (sessionId, playerId) -> true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PlayerView winner = player("Winner", false, 1000);
            PlayerView loser = player("Loser", false, 1000);
            PlayerView nextOpponent = player("NextOpponent", false, 1000);
            for (PlayerView participant : List.of(winner, loser, nextOpponent)) {
                ratings.seedIfAbsent(participant.getPlayerId(), participant.getRating(), 0L);
                callbacks.register(participant, new RecordingCallback());
            }
            GameSession oldGame = games.createGame(winner, loser, winner.getPlayerId());
            coordinator.activateMatchedGame(oldGame);
            assertEquals(MatchmakingState.WAITING,
                    matchmaking.join(nextOpponent.getPlayerId(), nextOpponent).getState());

            Future<Boolean> terminalResult = executor.submit(
                    () -> coordinator.leaveGame(loser.getPlayerId()).isSuccess());
            assertTrue(terminalMutated.await(1, TimeUnit.SECONDS));
            assertTrue(oldGame.isFinished());
            assertEquals(1000, ratings.current(winner.getPlayerId()).getRating());

            MatchmakingResult prematureJoin = matchmaking.join(winner.getPlayerId(), winner);

            assertEquals(ResultCode.INVALID_REQUEST, prematureJoin.getResultCode());
            assertFalse(matchmaking.isWaiting(winner.getPlayerId()));
            assertTrue(matchmaking.isWaiting(nextOpponent.getPlayerId()));
            assertEquals(oldGame.getGameId(), games.findByPlayerId(winner.getPlayerId())
                    .orElseThrow().getGameId());

            releaseFinalization.countDown();
            assertTrue(terminalResult.get(1, TimeUnit.SECONDS));
            assertEquals(1016, ratings.current(winner.getPlayerId()).getRating());
            assertEquals(1, recorded.size());

            assertEquals(MatchmakingState.MATCHED,
                    matchmaking.join(winner.getPlayerId(), winner).getState());
            GameSession nextGame = games.findByPlayerId(winner.getPlayerId()).orElseThrow();
            assertFalse(oldGame.getGameId().equals(nextGame.getGameId()));
            assertEquals(1016, nextGame.getCurrentGame(winner.getPlayerId()).getGameView()
                    .getPlayer().getRating());
        } finally {
            releaseFinalization.countDown();
            executor.shutdownNow();
            coordinator.close();
        }
    }

    /**
     * Creates a transport-safe player view for the requested identity type.
     *
     * @param name display name
     * @param guest guest flag
     * @param rating authoritative rating
     * @return player view
     */
    private static PlayerView player(String name, boolean guest, int rating) {
        return new PlayerView(UUID.randomUUID(), name, rating, guest);
    }

    /**
     * Returns every occupied cell in the standard deterministic fleet.
     *
     * @return ordered target coordinates
     */
    private static List<Coordinate> occupiedTargets() {
        List<Coordinate> targets = new ArrayList<>();
        int[] lengths = {5, 4, 3, 3, 2};
        for (int row = 0; row < lengths.length; row++) {
            for (int column = 0; column < lengths[row]; column++) {
                targets.add(new Coordinate(row, column));
            }
        }
        return targets;
    }

    /**
     * Returns enough known water cells for alternating opponent turns.
     *
     * @return ordered miss coordinates
     */
    private static List<Coordinate> safeMisses() {
        List<Coordinate> misses = new ArrayList<>();
        for (int column = 0; column < 10; column++) {
            misses.add(new Coordinate(9, column));
        }
        for (int column = 0; column < 7; column++) {
            misses.add(new Coordinate(8, column));
        }
        return misses;
    }

    /**
     * Wires one coordinator with synchronous snapshot collection after lane release.
     */
    private static final class Fixture {
        private final GameSessionManager games = new GameSessionManager();
        private final ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        private final RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        private final List<CompletedGameSnapshot> recorded = new ArrayList<>();
        private final GameCoordinator coordinator = new GameCoordinator(games, callbacks,
                recorded::add, Clock.fixed(COMPLETED_AT, ZoneOffset.UTC), ratings);

        /**
         * Seeds registered participants and creates their pre-activation game.
         *
         * @param first first participant
         * @param second second participant
         * @param starter first Battle participant
         * @return indexed game
         */
        private GameSession createGame(PlayerView first, PlayerView second, UUID starter) {
            seed(first);
            seed(second);
            return games.createGame(first, second, starter);
        }

        /**
         * Seeds one registered participant while leaving guests outside the live registry.
         *
         * @param player participant to seed when registered
         */
        private void seed(PlayerView player) {
            if (!player.isGuest()) {
                ratings.seedIfAbsent(player.getPlayerId(), player.getRating(), 0L);
            }
        }
    }

    /**
     * Counts authoritative game-state callbacks.
     */
    private static final class RecordingCallback implements FleetLinkClientCallback {
        private int gameStateChanges;

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) {
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
            gameStateChanges++;
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchRequested(RematchStatusView rematchStatus) {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus) {
        }
    }

    /**
     * Observes process-live rating state from callback delivery to prove terminal ordering.
     */
    private static final class RatingObservingCallback implements FleetLinkClientCallback {
        private final RegisteredRatingRegistry ratings;
        private final UUID playerId;
        private volatile int lastObservedRating;

        /**
         * Creates a callback observation for one registered player.
         *
         * @param ratings process-live rating authority
         * @param playerId observed registered identifier
         */
        private RatingObservingCallback(RegisteredRatingRegistry ratings, UUID playerId) {
            this.ratings = ratings;
            this.playerId = playerId;
        }

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) {
            lastObservedRating = ratings.current(playerId).getRating();
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
            lastObservedRating = ratings.current(playerId).getRating();
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchRequested(RematchStatusView rematchStatus) {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus) {
        }
    }
}
