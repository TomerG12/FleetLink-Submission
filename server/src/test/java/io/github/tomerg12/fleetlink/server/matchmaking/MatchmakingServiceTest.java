package io.github.tomerg12.fleetlink.server.matchmaking;

import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.player;
import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.validFleet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.game.GameSession;
import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.server.service.ClientCallbackRegistry;
import io.github.tomerg12.fleetlink.server.service.GameCoordinator;
import io.github.tomerg12.fleetlink.server.session.SessionRegistry;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingState;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionInfo;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import java.rmi.RemoteException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies rating-aware waiting, tie-breaking, cancellation, concurrency, and callback behavior.
 */
class MatchmakingServiceTest {

    /**
     * Keeps out-of-range ratings waiting and selects the oldest player when two eligible ratings
     * are equally distant from the joining player.
     */
    @Test
    void matchesNearestEligibleRatingAndBreaksTieByWaitOrder() {
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        PlayerView low = player("Low", 1000);
        PlayerView high = player("High", 1400);
        PlayerView middle = player("Middle", 1200);
        MatchmakingService service = new MatchmakingService(
                games, callbacks, ratings(low, high, middle), () -> true, 200);
        RecordingCallback lowCallback = new RecordingCallback(false);
        RecordingCallback highCallback = new RecordingCallback(false);
        RecordingCallback middleCallback = new RecordingCallback(false);
        callbacks.register(low, lowCallback);
        callbacks.register(high, highCallback);
        callbacks.register(middle, middleCallback);

        assertEquals(MatchmakingState.WAITING, service.join(low.getPlayerId(), low).getState());
        assertEquals(MatchmakingState.WAITING, service.join(high.getPlayerId(), high).getState());
        assertEquals(MatchmakingState.MATCHED,
                service.join(middle.getPlayerId(), middle).getState());

        assertEquals(1, lowCallback.matchFoundCount);
        assertEquals(0, highCallback.matchFoundCount);
        assertEquals(1, middleCallback.matchFoundCount);
        assertTrue(service.isWaiting(high.getPlayerId()));
        assertFalse(service.isWaiting(low.getPlayerId()));
        assertTrue(games.hasActiveGame(low.getPlayerId()));
        assertTrue(games.hasActiveGame(middle.getPlayerId()));
    }

    /**
     * Uses 1000 as a guest's temporary matchmaking rating without mutating the PlayerView rating.
     */
    @Test
    void usesFixedTemporaryGuestRatingForMatchmakingOnly() {
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        PlayerView registered = player("Registered", 1000);
        PlayerView guest = new PlayerView(UUID.randomUUID(), "Guest", 5000, true);
        MatchmakingService service = new MatchmakingService(
                games, callbacks, ratings(registered), () -> true, 200);
        callbacks.register(registered, new RecordingCallback(false));
        callbacks.register(guest, new RecordingCallback(false));

        assertEquals(MatchmakingState.WAITING,
                service.join(registered.getPlayerId(), registered).getState());
        assertEquals(MatchmakingState.MATCHED,
                service.join(guest.getPlayerId(), guest).getState());

        assertEquals(5000, guest.getRating());
        assertTrue(games.hasActiveGame(registered.getPlayerId()));
        assertTrue(games.hasActiveGame(guest.getPlayerId()));
    }

    /**
     * Rejects duplicate waiting requests and removes both waiting indexes on cancellation.
     */
    @Test
    void rejectsDuplicateWaitingAndCancelsDirectly() {
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        PlayerView player = player("Waiting", 1200);
        MatchmakingService service = new MatchmakingService(
                games, callbacks, ratings(player), () -> true, 200);
        callbacks.register(player, new RecordingCallback(false));

        assertEquals(MatchmakingState.WAITING,
                service.join(player.getPlayerId(), player).getState());
        assertEquals(ResultCode.ALREADY_WAITING,
                service.join(player.getPlayerId(), player).getResultCode());
        assertTrue(service.cancel(player.getPlayerId()).isSuccess());
        assertFalse(service.isWaiting(player.getPlayerId()));
        assertEquals(ResultCode.NOT_WAITING,
                service.cancel(player.getPlayerId()).getResultCode());
    }

    /**
     * Keeps an authoritative match committed when one client callback fails.
     */
    @Test
    void callbackFailureDoesNotRollbackMatch() {
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        MatchmakingService service = new MatchmakingService(
                games, callbacks, ratings(first, second), () -> true, 200);
        callbacks.register(first, new RecordingCallback(true));
        RecordingCallback secondCallback = new RecordingCallback(false);
        callbacks.register(second, secondCallback);

        service.join(first.getPlayerId(), first);
        assertEquals(MatchmakingState.MATCHED,
                service.join(second.getPlayerId(), second).getState());

        assertTrue(games.hasActiveGame(first.getPlayerId()));
        assertTrue(games.hasActiveGame(second.getPlayerId()));
        assertEquals(1, secondCallback.matchFoundCount);
    }

    /**
     * Prevents two concurrent joiners from consuming the same waiting player.
     *
     * @throws Exception if the test executor cannot complete the competing joins
     */
    @Test
    void concurrentJoinersCreateAtMostOneMatchWithWaitingPlayer() throws Exception {
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        PlayerView waiting = player("Waiting", 1200);
        PlayerView firstJoiner = player("FirstJoiner", 1200);
        PlayerView secondJoiner = player("SecondJoiner", 1200);
        MatchmakingService service = new MatchmakingService(games, callbacks,
                ratings(waiting, firstJoiner, secondJoiner), () -> true, 200);
        RecordingCallback waitingCallback = new RecordingCallback(false);
        RecordingCallback firstCallback = new RecordingCallback(false);
        RecordingCallback secondCallback = new RecordingCallback(false);
        callbacks.register(waiting, waitingCallback);
        callbacks.register(firstJoiner, firstCallback);
        callbacks.register(secondJoiner, secondCallback);
        assertEquals(MatchmakingState.WAITING,
                service.join(waiting.getPlayerId(), waiting).getState());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MatchmakingResult> firstResult = executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.join(firstJoiner.getPlayerId(), firstJoiner);
            });
            Future<MatchmakingResult> secondResult = executor.submit(() -> {
                ready.countDown();
                start.await();
                return service.join(secondJoiner.getPlayerId(), secondJoiner);
            });
            ready.await();
            start.countDown();

            MatchmakingResult first = firstResult.get();
            MatchmakingResult second = secondResult.get();
            int matchedCount = (first.getState() == MatchmakingState.MATCHED ? 1 : 0)
                    + (second.getState() == MatchmakingState.MATCHED ? 1 : 0);
            int waitingCount = (first.getState() == MatchmakingState.WAITING ? 1 : 0)
                    + (second.getState() == MatchmakingState.WAITING ? 1 : 0);

            assertEquals(1, matchedCount);
            assertEquals(1, waitingCount);
            assertEquals(1, waitingCallback.matchFoundCount);
            assertEquals(1, firstCallback.matchFoundCount + secondCallback.matchFoundCount);
            assertFalse(service.isWaiting(waiting.getPlayerId()));
            assertTrue(games.hasActiveGame(waiting.getPlayerId()));
            assertTrue(service.isWaiting(firstJoiner.getPlayerId())
                    || service.isWaiting(secondJoiner.getPlayerId()));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Rebuilds registered waiting and game views from live rating rather than stale session data.
     */
    @Test
    void newlyCreatedGameCapturesUpdatedLiveRating() {
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        PlayerView staleSession = player("Updated", 1000);
        PlayerView priorOpponent = player("Prior", 1000);
        PlayerView nextOpponent = player("Next", 1000);
        RegisteredRatingRegistry ratings = ratings(
                staleSession, priorOpponent, nextOpponent);
        ratings.applyRatedGame(UUID.randomUUID(), staleSession, priorOpponent,
                staleSession.getPlayerId());
        MatchmakingService service = new MatchmakingService(
                games, callbacks, ratings, () -> true, 200);
        callbacks.register(staleSession, new RecordingCallback(false));
        callbacks.register(nextOpponent, new RecordingCallback(false));

        assertEquals(MatchmakingState.WAITING,
                service.join(nextOpponent.getPlayerId(), nextOpponent).getState());
        assertEquals(MatchmakingState.MATCHED,
                service.join(staleSession.getPlayerId(), staleSession).getState());

        GameView view = games.findByPlayerId(staleSession.getPlayerId()).orElseThrow()
                .getCurrentGame(staleSession.getPlayerId()).getGameView();
        assertEquals(1016, view.getPlayer().getRating());
        assertEquals(1000, staleSession.getRating());
    }

    /**
     * Proves a player view resolved before termination cannot commit waiting after the final locked
     * exact-session validation.
     *
     * @throws Exception if deterministic coordination cannot complete
     */
    @Test
    void capturedOldJoinFailsWhenTerminationWinsBeforeFinalValidation() throws Exception {
        SessionRegistry sessions = new SessionRegistry();
        SessionInfo session = sessions.createGuest("Ending");
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ratings);
        CountDownLatch finalCheckReached = new CountDownLatch(1);
        CountDownLatch releaseFinalCheck = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        MatchmakingService service = new MatchmakingService(
                games, callbacks, coordinator, ratings, (sessionId, playerId) -> {
                    if (checks.incrementAndGet() == 2) {
                        finalCheckReached.countDown();
                        try {
                            releaseFinalCheck.await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("session guard interrupted", exception);
                        }
                    }
                    return sessions.findSession(sessionId)
                            .map(current -> current.getPlayer().getPlayerId().equals(playerId))
                            .orElse(false);
                }, () -> true, 200);
        callbacks.register(session.getPlayer(), new RecordingCallback(false));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MatchmakingResult> result = executor.submit(() -> service.join(
                    session.getSessionId(), session.getPlayer()));
            assertTrue(finalCheckReached.await(2, TimeUnit.SECONDS));
            assertTrue(sessions.beginTermination(session.getSessionId()).isPresent());
            releaseFinalCheck.countDown();

            assertEquals(ResultCode.INVALID_SESSION,
                    result.get(2, TimeUnit.SECONDS).getResultCode());
            assertFalse(service.isWaiting(session.getPlayer().getPlayerId()));
            assertFalse(games.hasActiveGame(session.getPlayer().getPlayerId()));
        } finally {
            releaseFinalCheck.countDown();
            executor.shutdownNow();
            coordinator.close();
        }
    }

    /** Exact termination removes only the waiting entry owned by the ending session. */
    @Test
    void waitingCommitIsRemovedByExactTerminationBarrier() {
        SessionRegistry sessions = new SessionRegistry();
        SessionInfo session = sessions.createGuest("Waiting");
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ratings);
        MatchmakingService service = new MatchmakingService(
                games, callbacks, coordinator, ratings,
                (sessionId, playerId) -> sessions.findSession(sessionId)
                        .map(current -> current.getPlayer().getPlayerId().equals(playerId))
                        .orElse(false));
        callbacks.register(session.getPlayer(), new RecordingCallback(false));
        try {
            assertEquals(MatchmakingState.WAITING, service.join(
                    session.getSessionId(), session.getPlayer()).getState());
            assertTrue(sessions.beginTermination(session.getSessionId()).isPresent());
            service.terminateSession(session.getSessionId(), session.getPlayer().getPlayerId());
            assertFalse(service.isWaiting(session.getPlayer().getPlayerId()));
            assertEquals(ResultCode.INVALID_SESSION, service.join(
                    session.getSessionId(), session.getPlayer()).getResultCode());
        } finally {
            coordinator.close();
        }
    }

    /** A joining player prunes an invalid exact-session candidate instead of creating a game. */
    @Test
    void staleWaitingCandidateIsPrunedBeforeConsumption() {
        SessionRegistry sessions = new SessionRegistry();
        SessionInfo stale = sessions.createGuest("Stale");
        SessionInfo joining = sessions.createGuest("Joining");
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ratings);
        MatchmakingService service = new MatchmakingService(
                games, callbacks, coordinator, ratings,
                (sessionId, playerId) -> sessions.findSession(sessionId)
                        .map(current -> current.getPlayer().getPlayerId().equals(playerId))
                        .orElse(false));
        callbacks.register(stale.getPlayer(), new RecordingCallback(false));
        callbacks.register(joining.getPlayer(), new RecordingCallback(false));
        try {
            assertEquals(MatchmakingState.WAITING, service.join(
                    stale.getSessionId(), stale.getPlayer()).getState());
            assertTrue(sessions.beginTermination(stale.getSessionId()).isPresent());

            assertEquals(MatchmakingState.WAITING, service.join(
                    joining.getSessionId(), joining.getPlayer()).getState());
            assertFalse(service.isWaiting(stale.getPlayer().getPlayerId()));
            assertTrue(service.isWaiting(joining.getPlayer().getPlayerId()));
            assertFalse(games.hasActiveGame(stale.getPlayer().getPlayerId()));
        } finally {
            coordinator.close();
        }
    }

    /** Common prepared creation activates once with the authoritative 120-second placement window. */
    @Test
    void preparedCreationUsesControlledActivationTimeAndPlacementWindow() {
        Instant activationTime = Instant.parse("2026-08-24T15:00:00Z");
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ignored -> { },
                Clock.fixed(activationTime, ZoneOffset.UTC));
        PlayerView first = new PlayerView(UUID.randomUUID(), "First", 1000, true);
        PlayerView second = new PlayerView(UUID.randomUUID(), "Second", 1000, true);
        MatchmakingService service = new MatchmakingService(
                games, callbacks, coordinator, ratings,
                (sessionId, playerId) -> sessionId.equals(playerId));
        callbacks.register(first, new RecordingCallback(false));
        callbacks.register(second, new RecordingCallback(false));
        try {
            assertEquals(MatchmakingState.WAITING,
                    service.join(first.getPlayerId(), first).getState());
            assertEquals(MatchmakingState.MATCHED,
                    service.join(second.getPlayerId(), second).getState());
            GameView view = games.findByPlayerId(first.getPlayerId()).orElseThrow()
                    .getCurrentGame(first.getPlayerId()).getGameView();
            assertEquals(GamePhase.FLEET_PLACEMENT, view.getPhase());
            assertEquals(activationTime.plusSeconds(120).toEpochMilli(),
                    view.getDeadlineEpochMillis());
        } finally {
            coordinator.close();
        }
    }

    /** A concurrent disconnect admitted after common creation cannot overtake match activation. */
    @Test
    void preparedCreationAdmitsActivationBeforeConcurrentDisconnect() throws Exception {
        Instant activationTime = Instant.parse("2026-08-24T15:00:00Z");
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ignored -> { },
                Clock.fixed(activationTime, ZoneOffset.UTC));
        PlayerView first = new PlayerView(UUID.randomUUID(), "First", 1000, true);
        PlayerView second = new PlayerView(UUID.randomUUID(), "Second", 1000, true);
        BlockingMatchFoundCallback blockingCallback = new BlockingMatchFoundCallback();
        MatchmakingService service = new MatchmakingService(
                games, callbacks, coordinator, ratings,
                (sessionId, playerId) -> sessionId.equals(playerId));
        callbacks.register(first, blockingCallback);
        callbacks.register(second, new RecordingCallback(false));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            assertEquals(MatchmakingState.WAITING,
                    service.join(first.getPlayerId(), first).getState());
            Future<MatchmakingResult> matching = executor.submit(() ->
                    service.join(second.getPlayerId(), second));
            assertTrue(blockingCallback.awaitMatchFound());
            GameView placement = games.findByPlayerId(first.getPlayerId()).orElseThrow()
                    .getCurrentGame(first.getPlayerId()).getGameView();
            assertEquals(GamePhase.FLEET_PLACEMENT, placement.getPhase());
            assertEquals(activationTime.plusSeconds(120).toEpochMilli(),
                    placement.getDeadlineEpochMillis());

            CountDownLatch disconnectStarted = new CountDownLatch(1);
            Future<OperationResult> disconnect = executor.submit(() -> {
                disconnectStarted.countDown();
                return coordinator.disconnect(first.getPlayerId());
            });
            assertTrue(disconnectStarted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,
                    () -> disconnect.get(100, TimeUnit.MILLISECONDS));

            blockingCallback.releaseMatchFound();
            assertEquals(MatchmakingState.MATCHED,
                    matching.get(3, TimeUnit.SECONDS).getState());
            assertTrue(disconnect.get(3, TimeUnit.SECONDS).isSuccess());
            assertEquals(GamePhase.FINISHED, games.findByPlayerId(first.getPlayerId())
                    .orElseThrow().getCurrentGame(second.getPlayerId()).getGameView().getPhase());
        } finally {
            blockingCallback.releaseMatchFound();
            executor.shutdownNow();
            coordinator.close();
        }
    }

    /** Rematch game creation refreshes registered ratings from the live registry. */
    @Test
    void rematchCreationRefreshesRegisteredRatings() {
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        PlayerView first = player("First", 1000);
        PlayerView second = player("Second", 1000);
        RegisteredRatingRegistry ratings = ratings(first, second);
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ratings);
        MatchmakingService service = new MatchmakingService(
                games, callbacks, coordinator, ratings,
                (sessionId, playerId) -> sessionId.equals(playerId));
        callbacks.register(first, new RecordingCallback(false));
        callbacks.register(second, new RecordingCallback(false));
        io.github.tomerg12.fleetlink.server.game.GameSession source = games.createGame(
                first, second, first.getPlayerId());
        source.activatePlacement(Instant.parse("2026-08-24T12:00:00Z"),
                Instant.parse("2026-08-24T12:02:00Z"));
        source.leave(second.getPlayerId());
        games.markTerminalFinalizationComplete(source.getGameId());
        ratings.applyRatedGame(source.getGameId(), first, second, first.getPlayerId());
        try {
            assertTrue(service.createRematch(source.getGameId(), first.getPlayerId(), first,
                    second.getPlayerId(), second).isSuccess());
            GameView firstView = games.findByPlayerId(first.getPlayerId()).orElseThrow()
                    .getCurrentGame(first.getPlayerId()).getGameView();
            GameView secondView = games.findByPlayerId(second.getPlayerId()).orElseThrow()
                    .getCurrentGame(second.getPlayerId()).getGameView();
            assertEquals(1016, firstView.getPlayer().getRating());
            assertEquals(984, secondView.getPlayer().getRating());
        } finally {
            coordinator.close();
        }
    }

    /** Final rematch validation rejects waiting players and missing callbacks. */
    @Test
    void rematchCreationRejectsWaitingParticipantAndMissingCallback() {
        try (RematchFixture waiting = new RematchFixture(true, true)) {
            assertEquals(MatchmakingState.WAITING,
                    waiting.service.join(waiting.first.getPlayerId(), waiting.first).getState());
            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    waiting.service.createRematch(waiting.source.getGameId(),
                            waiting.first.getPlayerId(), waiting.first,
                            waiting.second.getPlayerId(), waiting.second).getResultCode());
            assertTrue(waiting.service.isWaiting(waiting.first.getPlayerId()));
        }

        try (RematchFixture missingCallback = new RematchFixture(true, true)) {
            missingCallback.callbacks.unregister(missingCallback.second.getPlayerId());
            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    missingCallback.service.createRematch(missingCallback.source.getGameId(),
                            missingCallback.first.getPlayerId(), missingCallback.first,
                            missingCallback.second.getPlayerId(), missingCallback.second)
                            .getResultCode());
            assertEquals(missingCallback.source.getGameId(),
                    missingCallback.games.findByPlayerId(missingCallback.first.getPlayerId())
                            .orElseThrow().getGameId());
        }
    }

    /** A different active game also makes the completed pair mapping stale and unavailable. */
    @Test
    void rematchCreationRejectsActiveAndStaleCompletedPair() {
        try (RematchFixture fixture = new RematchFixture(true, true)) {
            GameSession newer = fixture.games.createGame(fixture.first, fixture.second,
                    fixture.second.getPlayerId());

            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    fixture.service.createRematch(fixture.source.getGameId(),
                            fixture.first.getPlayerId(), fixture.first,
                            fixture.second.getPlayerId(), fixture.second).getResultCode());
            assertEquals(newer.getGameId(), fixture.games.findByPlayerId(
                    fixture.first.getPlayerId()).orElseThrow().getGameId());
        }
    }

    /** Guest ratings and deterministic starting-player selection use ordinary creation policy. */
    @Test
    void rematchCreationKeepsGuestRatingAndStartingPlayerPolicy() {
        try (RematchFixture fixture = new RematchFixture(false, false)) {
            assertTrue(fixture.service.createRematch(fixture.source.getGameId(),
                    fixture.first.getPlayerId(), fixture.first,
                    fixture.second.getPlayerId(), fixture.second).isSuccess());
            GameSession game = fixture.games.findByPlayerId(
                    fixture.first.getPlayerId()).orElseThrow();
            Instant firstSubmission = Instant.parse("2026-08-24T15:00:01Z");
            assertTrue(game.submitFleet(fixture.first.getPlayerId(), validFleet(),
                    firstSubmission, firstSubmission.plusSeconds(45)).isAccepted());
            Instant secondSubmission = firstSubmission.plusSeconds(1);
            assertTrue(game.submitFleet(fixture.second.getPlayerId(), validFleet(),
                    secondSubmission, secondSubmission.plusSeconds(45)).isAccepted());
            GameView firstView = game.getCurrentGame(fixture.first.getPlayerId()).getGameView();
            GameView secondView = game.getCurrentGame(fixture.second.getPlayerId()).getGameView();
            assertEquals(1000, firstView.getPlayer().getRating());
            assertEquals(1000, secondView.getPlayer().getRating());
            assertFalse(firstView.isYourTurn());
            assertTrue(secondView.isYourTurn());
            assertEquals(1, fixture.startSelectionCount.get());
        }
    }

    /** Owns one finalized source and common creation boundary for focused rematch checks. */
    private static final class RematchFixture implements AutoCloseable {
        private final GameSessionManager games = new GameSessionManager();
        private final ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        private final RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        private final GameCoordinator coordinator = new GameCoordinator(games, callbacks, ratings);
        private final AtomicInteger startSelectionCount = new AtomicInteger();
        private final PlayerView first;
        private final PlayerView second;
        private final MatchmakingService service;
        private final GameSession source;

        /**
         * Creates a finalized guest source with deterministic starting-player selection.
         *
         * @param firstStarts selected ordinary starting-player policy result
         * @param registered true for registered players, false for guests
         */
        private RematchFixture(boolean firstStarts, boolean registered) {
            first = registered
                    ? player("First", 1000)
                    : new PlayerView(UUID.randomUUID(), "First", 1600, true);
            second = registered
                    ? player("Second", 1000)
                    : new PlayerView(UUID.randomUUID(), "Second", 400, true);
            if (registered) {
                ratings.seedIfAbsent(first.getPlayerId(), first.getRating(), 0L);
                ratings.seedIfAbsent(second.getPlayerId(), second.getRating(), 0L);
            }
            service = new MatchmakingService(games, callbacks, coordinator, ratings,
                    (sessionId, playerId) -> sessionId.equals(playerId), () -> {
                        startSelectionCount.incrementAndGet();
                        return firstStarts;
                    }, 200);
            callbacks.register(first, new RecordingCallback(false));
            callbacks.register(second, new RecordingCallback(false));
            source = games.createGame(first, second, first.getPlayerId());
            source.activatePlacement(Instant.parse("2026-08-24T12:00:00Z"),
                    Instant.parse("2026-08-24T12:02:00Z"));
            source.leave(second.getPlayerId());
            games.markTerminalFinalizationComplete(source.getGameId());
        }

        /** Stops coordinator execution resources. */
        @Override
        public void close() {
            coordinator.close();
        }
    }

    /**
     * Seeds a process-live registry with registered test participants.
     *
     * @param players registered participant views
     * @return seeded rating authority
     */
    private static RegisteredRatingRegistry ratings(PlayerView... players) {
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        for (PlayerView player : players) {
            ratings.seedIfAbsent(player.getPlayerId(), player.getRating(), 0L);
        }
        return ratings;
    }

    /**
     * Records callback counts and can simulate one RMI delivery failure.
     */
    private static final class RecordingCallback implements FleetLinkClientCallback {
        private final boolean failMatchFound;
        private int matchFoundCount;

        /**
         * Creates one recording callback.
         *
         * @param failMatchFound whether match-found delivery should throw RemoteException
         */
        private RecordingCallback(boolean failMatchFound) {
            this.failMatchFound = failMatchFound;
        }

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) throws RemoteException {
            if (failMatchFound) {
                throw new RemoteException("simulated callback failure");
            }
            matchFoundCount++;
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
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

    /** Blocks one match-found callback so later sequencer work can be observed waiting. */
    private static final class BlockingMatchFoundCallback implements FleetLinkClientCallback {
        private final CountDownLatch matchFound = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        /**
         * Waits until normal activation reaches callback delivery.
         *
         * @return true when match-found delivery arrived before the timeout
         * @throws InterruptedException if the test thread is interrupted
         */
        private boolean awaitMatchFound() throws InterruptedException {
            return matchFound.await(2, TimeUnit.SECONDS);
        }

        /** Releases the deliberately delayed match-found callback. */
        private void releaseMatchFound() {
            release.countDown();
        }

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) throws RemoteException {
            matchFound.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new RemoteException("timed out waiting to release match-found callback");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RemoteException("match-found callback interrupted", exception);
            }
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
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
