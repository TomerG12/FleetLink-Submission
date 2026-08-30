package io.github.tomerg12.fleetlink.server.rmi;

import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.validFleet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.server.matchmaking.MatchmakingService;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.server.rematch.RematchCoordinator;
import io.github.tomerg12.fleetlink.server.service.ClientCallbackRegistry;
import io.github.tomerg12.fleetlink.server.service.GameCoordinator;
import io.github.tomerg12.fleetlink.server.session.SessionRegistry;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingState;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchState;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.protocol.ShotOutcome;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import java.rmi.RemoteException;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies the guest-only RMI vertical slice from session creation through matchmaking and game play.
 */
class FleetLinkServerRemoteImplTest {

    /**
     * Connects two guests, matches them, starts battle, processes a shot, and ends by resignation.
     *
     * @throws RemoteException if the local remote adapter unexpectedly reports transport failure
     */
    @Test
    void guestVerticalSliceRunsThroughCoreGameFlow() throws RemoteException {
        Fixture fixture = new Fixture();
        RecordingCallback firstCallback = new RecordingCallback();
        RecordingCallback secondCallback = new RecordingCallback();
        SessionResult firstSession = fixture.server.connectAsGuest("First", firstCallback);
        SessionResult secondSession = fixture.server.connectAsGuest("Second", secondCallback);
        UUID firstSessionId = firstSession.getSessionInfo().getSessionId();
        UUID secondSessionId = secondSession.getSessionInfo().getSessionId();

        assertTrue(firstSession.isSuccess());
        assertTrue(secondSession.isSuccess());
        assertTrue(firstSession.getSessionInfo().getPlayer().isGuest());
        assertEquals(1000, firstSession.getSessionInfo().getPlayer().getRating());
        assertEquals(MatchmakingState.WAITING,
                fixture.server.joinMatchmaking(firstSessionId).getState());
        assertEquals(MatchmakingState.MATCHED,
                fixture.server.joinMatchmaking(secondSessionId).getState());
        assertEquals(1, firstCallback.matchFoundCount);
        assertEquals(1, secondCallback.matchFoundCount);

        assertTrue(fixture.server.submitFleet(firstSessionId, validFleet()).isAccepted());
        assertTrue(fixture.server.submitFleet(secondSessionId, validFleet()).isAccepted());
        GameView firstBattle = fixture.server.getCurrentGame(firstSessionId).getGameView();
        GameView secondBattle = fixture.server.getCurrentGame(secondSessionId).getGameView();
        assertEquals(GamePhase.BATTLE, firstBattle.getPhase());
        assertEquals(GamePhase.BATTLE, secondBattle.getPhase());
        assertEquals(1, firstCallback.gameChangedCount);
        assertEquals(1, secondCallback.gameChangedCount);

        UUID shooterSessionId = firstBattle.isYourTurn() ? firstSessionId : secondSessionId;
        assertEquals(ShotOutcome.MISS,
                fixture.server.fire(shooterSessionId, new Coordinate(9, 9)).getOutcome());
        assertEquals(2, firstCallback.gameChangedCount);
        assertEquals(2, secondCallback.gameChangedCount);

        assertTrue(fixture.server.leaveGame(firstSessionId).isSuccess());
        GameView finalView = fixture.server.getCurrentGame(secondSessionId).getGameView();
        assertEquals(GamePhase.FINISHED, finalView.getPhase());
        assertEquals(GameEndReason.RESIGNATION, finalView.getEndReason());
        assertEquals(secondSession.getSessionInfo().getPlayer().getPlayerId(),
                finalView.getWinner().getPlayerId());

        assertEquals(RematchState.EXPIRED, secondCallback.rematchStatusStates.getLast());
        assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                fixture.server.requestRematch(secondSessionId).getResultCode());
    }

    /**
     * Removes waiting and callback state on logout and rejects later use of the old session.
     *
     * @throws RemoteException if the local remote adapter unexpectedly reports transport failure
     */
    @Test
    void logoutInvalidatesGuestSessionAndWaitingState() throws RemoteException {
        Fixture fixture = new Fixture();
        RecordingCallback callback = new RecordingCallback();
        SessionResult session = fixture.server.connectAsGuest("Waiting", callback);
        UUID sessionId = session.getSessionInfo().getSessionId();
        UUID playerId = session.getSessionInfo().getPlayer().getPlayerId();
        assertEquals(MatchmakingState.WAITING,
                fixture.server.joinMatchmaking(sessionId).getState());
        assertTrue(fixture.matchmaking.isWaiting(playerId));

        assertTrue(fixture.server.logout(sessionId).isSuccess());

        assertFalse(fixture.matchmaking.isWaiting(playerId));
        assertTrue(fixture.callbacks.find(playerId).isEmpty());
        assertEquals(ResultCode.INVALID_SESSION,
                fixture.server.joinMatchmaking(sessionId).getResultCode());
        assertEquals(ResultCode.INVALID_SESSION,
                fixture.server.getCurrentGame(sessionId).getResultCode());
    }

    /** Pending rematch expires for the connected opponent when the requester logs out. */
    @Test
    void logoutExpiresPendingRematch() throws RemoteException {
        Fixture fixture = new Fixture();
        RecordingCallback firstCallback = new RecordingCallback();
        RecordingCallback secondCallback = new RecordingCallback();
        SessionResult first = fixture.server.connectAsGuest("First", firstCallback);
        SessionResult second = fixture.server.connectAsGuest("Second", secondCallback);
        UUID firstSessionId = first.getSessionInfo().getSessionId();
        UUID secondSessionId = second.getSessionInfo().getSessionId();
        fixture.server.joinMatchmaking(firstSessionId);
        fixture.server.joinMatchmaking(secondSessionId);
        finishRematchSource(fixture, first);
        assertTrue(fixture.server.requestRematch(firstSessionId).isSuccess());

        assertTrue(fixture.server.logout(firstSessionId).isSuccess());

        assertEquals(RematchState.EXPIRED, secondCallback.rematchStatusStates.getLast());
        assertEquals(ResultCode.INVALID_SESSION,
                fixture.server.requestRematch(firstSessionId).getResultCode());
    }

    /** Disconnect before any request expires the newly completed opportunity for the opponent. */
    @Test
    void disconnectExpiresUnrequestedRematchOpportunity() throws RemoteException {
        Fixture fixture = new Fixture();
        RecordingCallback firstCallback = new RecordingCallback();
        RecordingCallback secondCallback = new RecordingCallback();
        SessionResult first = fixture.server.connectAsGuest("First", firstCallback);
        SessionResult second = fixture.server.connectAsGuest("Second", secondCallback);
        UUID firstSessionId = first.getSessionInfo().getSessionId();
        UUID secondSessionId = second.getSessionInfo().getSessionId();
        fixture.server.joinMatchmaking(firstSessionId);
        fixture.server.joinMatchmaking(secondSessionId);

        assertTrue(fixture.server.logout(firstSessionId).isSuccess());

        assertEquals(GameEndReason.DISCONNECT, secondCallback.lastGameView.getEndReason());
        assertEquals(RematchState.EXPIRED, secondCallback.rematchStatusStates.getLast());
        assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                fixture.server.requestRematch(secondSessionId).getResultCode());
    }

    /** A false response before any request represents authoritative Return to Lobby expiration. */
    @Test
    void returnToLobbyExpiresUnrequestedRematchOpportunity() throws RemoteException {
        Fixture fixture = new Fixture();
        RecordingCallback firstCallback = new RecordingCallback();
        RecordingCallback secondCallback = new RecordingCallback();
        SessionResult first = fixture.server.connectAsGuest("First", firstCallback);
        SessionResult second = fixture.server.connectAsGuest("Second", secondCallback);
        UUID firstSessionId = first.getSessionInfo().getSessionId();
        UUID secondSessionId = second.getSessionInfo().getSessionId();
        fixture.server.joinMatchmaking(firstSessionId);
        fixture.server.joinMatchmaking(secondSessionId);
        finishRematchSource(fixture, first);

        assertTrue(fixture.server.respondToRematch(firstSessionId, false).isSuccess());

        assertEquals(RematchState.EXPIRED, secondCallback.rematchStatusStates.getLast());
        assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                fixture.server.requestRematch(secondSessionId).getResultCode());
    }

    /** Ordinary waiting admission expires an older pending rematch opportunity. */
    @Test
    void ordinaryMatchmakingExpiresPendingRematch() throws RemoteException {
        Fixture fixture = new Fixture();
        RecordingCallback firstCallback = new RecordingCallback();
        RecordingCallback secondCallback = new RecordingCallback();
        SessionResult first = fixture.server.connectAsGuest("First", firstCallback);
        SessionResult second = fixture.server.connectAsGuest("Second", secondCallback);
        UUID firstSessionId = first.getSessionInfo().getSessionId();
        UUID secondSessionId = second.getSessionInfo().getSessionId();
        fixture.server.joinMatchmaking(firstSessionId);
        fixture.server.joinMatchmaking(secondSessionId);
        finishRematchSource(fixture, first);
        assertTrue(fixture.server.requestRematch(firstSessionId).isSuccess());

        assertEquals(MatchmakingState.WAITING,
                fixture.server.joinMatchmaking(firstSessionId).getState());

        assertEquals(RematchState.EXPIRED, firstCallback.rematchStatusStates.getLast());
        assertEquals(RematchState.EXPIRED, secondCallback.rematchStatusStates.getLast());
        assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                fixture.server.respondToRematch(secondSessionId, true).getResultCode());
    }

    /** Public facade preserves distinct decline and requester-withdrawal terminal states. */
    @Test
    void rematchFacadeSupportsDeclineAndWithdrawal() throws RemoteException {
        Fixture declineFixture = new Fixture();
        RecordingCallback declineFirstCallback = new RecordingCallback();
        RecordingCallback declineSecondCallback = new RecordingCallback();
        SessionResult declineFirst = declineFixture.server.connectAsGuest(
                "First", declineFirstCallback);
        SessionResult declineSecond = declineFixture.server.connectAsGuest(
                "Second", declineSecondCallback);
        UUID declineFirstId = declineFirst.getSessionInfo().getSessionId();
        UUID declineSecondId = declineSecond.getSessionInfo().getSessionId();
        declineFixture.server.joinMatchmaking(declineFirstId);
        declineFixture.server.joinMatchmaking(declineSecondId);
        finishRematchSource(declineFixture, declineFirst);
        assertTrue(declineFixture.server.requestRematch(declineFirstId).isSuccess());
        assertTrue(declineFixture.server.respondToRematch(declineSecondId, false).isSuccess());
        assertEquals(RematchState.DECLINED,
                declineFirstCallback.rematchStatusStates.getLast());

        Fixture withdrawalFixture = new Fixture();
        RecordingCallback withdrawalFirstCallback = new RecordingCallback();
        RecordingCallback withdrawalSecondCallback = new RecordingCallback();
        SessionResult withdrawalFirst = withdrawalFixture.server.connectAsGuest(
                "First", withdrawalFirstCallback);
        SessionResult withdrawalSecond = withdrawalFixture.server.connectAsGuest(
                "Second", withdrawalSecondCallback);
        UUID withdrawalFirstId = withdrawalFirst.getSessionInfo().getSessionId();
        UUID withdrawalSecondId = withdrawalSecond.getSessionInfo().getSessionId();
        withdrawalFixture.server.joinMatchmaking(withdrawalFirstId);
        withdrawalFixture.server.joinMatchmaking(withdrawalSecondId);
        finishRematchSource(withdrawalFixture, withdrawalFirst);
        assertTrue(withdrawalFixture.server.requestRematch(withdrawalFirstId).isSuccess());
        assertTrue(withdrawalFixture.server.respondToRematch(
                withdrawalFirstId, false).isSuccess());
        assertEquals(RematchState.EXPIRED,
                withdrawalSecondCallback.rematchStatusStates.getLast());
    }

    /** Simultaneous public requests create one rematch and one callback per participant. */
    @Test
    void simultaneousFacadeRequestsCreateOneRematch() throws Exception {
        Fixture fixture = new Fixture();
        RecordingCallback firstCallback = new RecordingCallback();
        RecordingCallback secondCallback = new RecordingCallback();
        SessionResult first = fixture.server.connectAsGuest("First", firstCallback);
        SessionResult second = fixture.server.connectAsGuest("Second", secondCallback);
        UUID firstSessionId = first.getSessionInfo().getSessionId();
        UUID secondSessionId = second.getSessionInfo().getSessionId();
        fixture.server.joinMatchmaking(firstSessionId);
        fixture.server.joinMatchmaking(secondSessionId);
        finishRematchSource(fixture, first);
        UUID completedGameId = fixture.server.getCurrentGame(firstSessionId)
                .getGameView().getGameId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<OperationResult> firstRequest = executor.submit(() -> {
                ready.countDown();
                start.await();
                return fixture.server.requestRematch(firstSessionId);
            });
            Future<OperationResult> secondRequest = executor.submit(() -> {
                ready.countDown();
                start.await();
                return fixture.server.requestRematch(secondSessionId);
            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(firstRequest.get(3, TimeUnit.SECONDS).isSuccess());
            assertTrue(secondRequest.get(3, TimeUnit.SECONDS).isSuccess());
        } finally {
            executor.shutdownNow();
        }
        GameView rematch = fixture.server.getCurrentGame(firstSessionId).getGameView();
        assertFalse(completedGameId.equals(rematch.getGameId()));
        assertEquals(GamePhase.FLEET_PLACEMENT, rematch.getPhase());
        assertEquals(2, firstCallback.matchFoundCount);
        assertEquals(2, secondCallback.matchFoundCount);
    }

    /** Final callback loss rejects creation and a different active game blocks the old source. */
    @Test
    void rematchFacadeRejectsFinalCallbackLossAndNewerActiveGame() throws RemoteException {
        Fixture rejectionFixture = new Fixture();
        RecordingCallback firstCallback = new RecordingCallback();
        RecordingCallback secondCallback = new RecordingCallback();
        SessionResult first = rejectionFixture.server.connectAsGuest("First", firstCallback);
        SessionResult second = rejectionFixture.server.connectAsGuest("Second", secondCallback);
        UUID firstSessionId = first.getSessionInfo().getSessionId();
        UUID secondSessionId = second.getSessionInfo().getSessionId();
        rejectionFixture.server.joinMatchmaking(firstSessionId);
        rejectionFixture.server.joinMatchmaking(secondSessionId);
        finishRematchSource(rejectionFixture, first);
        assertTrue(rejectionFixture.server.requestRematch(firstSessionId).isSuccess());
        rejectionFixture.callbacks.unregister(second.getSessionInfo().getPlayer().getPlayerId());
        assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                rejectionFixture.server.respondToRematch(secondSessionId, true).getResultCode());
        assertEquals(RematchState.EXPIRED, firstCallback.rematchStatusStates.getLast());
        assertEquals(1, firstCallback.matchFoundCount);

        Fixture activeFixture = new Fixture();
        RecordingCallback activeFirstCallback = new RecordingCallback();
        RecordingCallback activeSecondCallback = new RecordingCallback();
        RecordingCallback thirdCallback = new RecordingCallback();
        SessionResult activeFirst = activeFixture.server.connectAsGuest(
                "First", activeFirstCallback);
        SessionResult activeSecond = activeFixture.server.connectAsGuest(
                "Second", activeSecondCallback);
        SessionResult third = activeFixture.server.connectAsGuest("Third", thirdCallback);
        UUID activeFirstId = activeFirst.getSessionInfo().getSessionId();
        UUID activeSecondId = activeSecond.getSessionInfo().getSessionId();
        activeFixture.server.joinMatchmaking(activeFirstId);
        activeFixture.server.joinMatchmaking(activeSecondId);
        finishRematchSource(activeFixture, activeFirst);
        activeFixture.games.createGame(activeFirst.getSessionInfo().getPlayer(),
                third.getSessionInfo().getPlayer(),
                activeFirst.getSessionInfo().getPlayer().getPlayerId());
        assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                activeFixture.server.requestRematch(activeSecondId).getResultCode());
    }

    /**
     * Keeps deferred registered authentication and rematch operations explicit and contract-safe.
     *
     * @throws RemoteException if the local remote adapter unexpectedly reports transport failure
     */
    @Test
    void deferredOperationsReturnExplicitFailures() throws RemoteException {
        Fixture fixture = new Fixture();
        RecordingCallback callback = new RecordingCallback();

        assertEquals(ResultCode.INVALID_REQUEST,
                fixture.server.login("user", "pass", callback).getResultCode());
        assertEquals(ResultCode.INVALID_REQUEST,
                fixture.server.register("user", "pass", callback).getResultCode());

        SessionResult guest = fixture.server.connectAsGuest("Guest", callback);
        assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                fixture.server.requestRematch(guest.getSessionInfo().getSessionId()).getResultCode());
        assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                fixture.server.respondToRematch(
                        guest.getSessionInfo().getSessionId(), true).getResultCode());
    }

    /**
     * Finalizes an eligible source through the domain coordinator while leaving both clients in
     * the completed-game lifecycle for rematch facade tests.
     *
     * @param fixture isolated server fixture
     * @param departing session whose player ends the source
     */
    private static void finishRematchSource(Fixture fixture, SessionResult departing) {
        assertTrue(fixture.coordinator.leaveGame(
                departing.getSessionInfo().getPlayer().getPlayerId()).isSuccess());
    }

    /**
     * Wires one isolated in-memory server core for remote-adapter tests.
     */
    private static final class Fixture {
        private final GameSessionManager games = new GameSessionManager();
        private final ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        private final SessionRegistry sessions = new SessionRegistry();
        private final RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        private final GameCoordinator coordinator = new GameCoordinator(games, callbacks, ratings);
        private final MatchmakingService matchmaking = new MatchmakingService(
                games, callbacks, coordinator, ratings,
                (sessionId, playerId) -> sessions.findSession(sessionId)
                        .map(session -> session.getPlayer().getPlayerId().equals(playerId))
                        .orElse(false));
        private final RematchCoordinator rematches = new RematchCoordinator(
                sessions, games, matchmaking, callbacks);
        private final FleetLinkServerRemoteImpl server = new FleetLinkServerRemoteImpl(
                sessions, callbacks, matchmaking, coordinator, rematches);
    }

    /**
     * Records synchronous callback delivery for integration assertions.
     */
    private static final class RecordingCallback implements FleetLinkClientCallback {
        private int matchFoundCount;
        private int gameChangedCount;
        private GameView lastGameView;
        private final List<RematchState> rematchRequestStates = new ArrayList<>();
        private final List<RematchState> rematchStatusStates = new ArrayList<>();

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) {
            matchFoundCount++;
            lastGameView = initialGame;
            assertNotNull(lastGameView);
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
            gameChangedCount++;
            lastGameView = gameView;
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchRequested(RematchStatusView rematchStatus) {
            rematchRequestStates.add(rematchStatus.getState());
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus) {
            rematchStatusStates.add(rematchStatus.getState());
        }
    }
}
