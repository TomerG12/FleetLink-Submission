package io.github.tomerg12.fleetlink.client.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.rmi.RemoteException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsResult;
import io.github.tomerg12.fleetlink.shared.protocol.RematchState;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import org.junit.jupiter.api.Test;

/** Verifies asynchronous rematch operations and atomic Lobby cleanup on the remote executor. */
class ClientRematchOperationServiceTest {

    /** Confirms Request is non-blocking and coordinator revalidation prevents a duplicate call. */
    @Test
    void requestRunsAsynchronouslyAndDuplicateDoesNotReachGateway() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        RecordingRematchGateway gateway = new RecordingRematchGateway();
        gateway.blockRequest();
        ExecutorService executor = namedExecutor("rematch-request-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            establishGameOver(service, coordinator);
            String caller = Thread.currentThread().getName();

            CompletableFuture<ClientState> request = service.requestRematch();

            assertTrue(gateway.requestStarted.await(5, TimeUnit.SECONDS));
            assertFalse(request.isDone());
            assertEquals(RematchClientState.InFlightAction.REQUEST,
                    coordinator.getState().getRematchState().getInFlightAction());
            assertThrows(CompletionException.class, () -> service.requestRematch().join());
            assertEquals(1, gateway.requestCalls.get());
            assertNotEquals(caller, gateway.rematchThread);

            gateway.allowRequest.countDown();
            assertTrue(request.get(5, TimeUnit.SECONDS).getRematchState()
                    .isRequestAcknowledged());
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    /** Confirms Accept and Decline use the one response method with exact boolean values. */
    @Test
    void acceptAndDeclineRunAsynchronouslyWithExactResponseValues() throws Exception {
        ClientStateCoordinator acceptCoordinator = new ClientStateCoordinator(Runnable::run);
        RecordingRematchGateway acceptGateway = new RecordingRematchGateway();
        ExecutorService acceptExecutor = namedExecutor("rematch-accept-worker");
        try (ClientOperationService service = new ClientOperationService(
                acceptCoordinator, () -> acceptGateway, acceptExecutor)) {
            establishIncomingRequest(service, acceptCoordinator);
            ClientState accepted = service.respondToRematch(true).get(5, TimeUnit.SECONDS);
            assertEquals(List.of(true), acceptGateway.responseValues);
            assertTrue(accepted.getRematchState().isCreationCommitted());
            assertEquals(ClientPhase.GAME_OVER, accepted.getPhase());
        }

        ClientStateCoordinator declineCoordinator = new ClientStateCoordinator(Runnable::run);
        RecordingRematchGateway declineGateway = new RecordingRematchGateway();
        ExecutorService declineExecutor = namedExecutor("rematch-decline-worker");
        try (ClientOperationService service = new ClientOperationService(
                declineCoordinator, () -> declineGateway, declineExecutor)) {
            establishIncomingRequest(service, declineCoordinator);
            ClientState declined = service.respondToRematch(false).get(5, TimeUnit.SECONDS);
            assertEquals(List.of(false), declineGateway.responseValues);
            assertEquals(RematchClientState.Presentation.DECLINED,
                    declined.getRematchState().getPresentation());
            assertTrue(declined.getRematchState().canReturnToLobby());
        }

        assertTrue(acceptExecutor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(declineExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }

    /** Confirms request, accept, and decline transport failures retire busy state safely. */
    @Test
    void rematchTransportFailuresRetireOnlyTheirCurrentAction() throws Exception {
        ClientStateCoordinator requestCoordinator = new ClientStateCoordinator(Runnable::run);
        RecordingRematchGateway requestGateway = new RecordingRematchGateway();
        requestGateway.requestFailure = new RemoteException("request uncertain");
        ExecutorService requestExecutor = namedExecutor("request-failure-worker");
        try (ClientOperationService service = new ClientOperationService(
                requestCoordinator, () -> requestGateway, requestExecutor)) {
            establishGameOver(service, requestCoordinator);
            RematchClientState state = service.requestRematch().get(5, TimeUnit.SECONDS)
                    .getRematchState();
            assertEquals(RematchClientState.InFlightAction.NONE, state.getInFlightAction());
            assertTrue(state.isTransportFailure());
            assertTrue(state.canRequest());
        }

        for (boolean accept : List.of(true, false)) {
            ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
            RecordingRematchGateway gateway = new RecordingRematchGateway();
            gateway.responseFailure = new RemoteException("response uncertain");
            ExecutorService executor = namedExecutor(accept
                    ? "accept-failure-worker" : "decline-failure-worker");
            try (ClientOperationService service = new ClientOperationService(
                    coordinator, () -> gateway, executor)) {
                establishIncomingRequest(service, coordinator);
                RematchClientState state = service.respondToRematch(accept)
                        .get(5, TimeUnit.SECONDS).getRematchState();
                assertEquals(RematchState.REQUESTED_BY_OPPONENT,
                        state.getAuthoritativeStatus().getState());
                assertEquals(RematchClientState.InFlightAction.NONE,
                        state.getInFlightAction());
                assertTrue(state.canAccept());
                assertTrue(state.canDecline());
            }
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(requestExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }

    /** Confirms SEND_FALSE commits Lobby before its best-effort remote result or transport failure. */
    @Test
    void sendFalseNeverRestoresDestroyedGameOverScope() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        RecordingRematchGateway gateway = new RecordingRematchGateway();
        gateway.blockResponse();
        gateway.responseFailure = new RemoteException("false uncertain");
        ExecutorService executor = namedExecutor("return-false-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            establishIncomingRequest(service, coordinator);

            CompletableFuture<ClientState> returned = service.returnToLobby();

            assertEquals(ClientPhase.LOBBY, coordinator.getState().getPhase());
            assertEquals(null, coordinator.getState().getGameView());
            assertEquals(null, coordinator.getState().getRematchState());
            assertTrue(gateway.responseStarted.await(5, TimeUnit.SECONDS));
            assertFalse(returned.isDone());
            assertEquals(List.of(false), gateway.responseValues);

            gateway.allowResponse.countDown();
            assertEquals(ClientPhase.LOBBY,
                    returned.get(5, TimeUnit.SECONDS).getPhase());
            assertEquals(ClientPhase.LOBBY, coordinator.getState().getPhase());
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    /** Confirms Lobby expiration follows an in-flight request and late state cannot restore Game Over. */
    @Test
    void returnDuringRequestQueuesExpirationAndLateResultCannotRestoreGameOver() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        RecordingRematchGateway gateway = new RecordingRematchGateway();
        gateway.blockRequest();
        ExecutorService executor = namedExecutor("return-abandon-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            establishGameOver(service, coordinator);
            CompletableFuture<ClientState> request = service.requestRematch();
            assertTrue(gateway.requestStarted.await(5, TimeUnit.SECONDS));

            CompletableFuture<ClientState> returned = service.returnToLobby();
            assertEquals(ClientPhase.LOBBY, coordinator.getState().getPhase());
            gateway.allowRequest.countDown();
            assertEquals(ClientPhase.LOBBY,
                    request.get(5, TimeUnit.SECONDS).getPhase());
            assertEquals(ClientPhase.LOBBY,
                    returned.get(5, TimeUnit.SECONDS).getPhase());
            assertEquals(1, gateway.responseCalls.get());
            assertEquals(List.of(false), gateway.responseValues);
            assertEquals(null, coordinator.getState().getRematchState());
        }
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    /** Establishes a guest session and exact completed game through production service boundaries. */
    private static void establishGameOver(ClientOperationService service,
                                          ClientStateCoordinator coordinator) throws Exception {
        service.connectAsGuest("Guest Alpha").get(5, TimeUnit.SECONDS);
        UUID sessionId = ClientTestFixtures.session().getSessionId();
        coordinator.acceptMatchFound(sessionId, ClientTestFixtures.fleetPlacementGame());
        coordinator.acceptGameStateChanged(sessionId, ClientTestFixtures.finishedGame(true));
    }

    /** Establishes Game Over and accepts one current incoming rematch callback. */
    private static void establishIncomingRequest(ClientOperationService service,
                                                 ClientStateCoordinator coordinator)
            throws Exception {
        establishGameOver(service, coordinator);
        coordinator.acceptRematchStatus(ClientTestFixtures.session().getSessionId(),
                ClientTestFixtures.rematchStatus(RematchState.REQUESTED_BY_OPPONENT));
    }

    /** Creates one named single-thread executor for observable remote-thread assertions. */
    private static ExecutorService namedExecutor(String name) {
        return Executors.newSingleThreadExecutor(action -> new Thread(action, name));
    }

    /** Provides controllable synchronous gateway calls for deterministic operation ordering. */
    private static final class RecordingRematchGateway implements ClientRemoteGateway {
        private final AtomicInteger requestCalls = new AtomicInteger();
        private final AtomicInteger responseCalls = new AtomicInteger();
        private final List<Boolean> responseValues = new java.util.concurrent.CopyOnWriteArrayList<>();
        private CountDownLatch requestStarted;
        private CountDownLatch allowRequest;
        private CountDownLatch responseStarted;
        private CountDownLatch allowResponse;
        private RemoteException requestFailure;
        private RemoteException responseFailure;
        private String rematchThread;

        /** Configures the next request to wait on a deterministic test latch. */
        private void blockRequest() {
            requestStarted = new CountDownLatch(1);
            allowRequest = new CountDownLatch(1);
        }

        /** Configures the next response to wait on a deterministic test latch. */
        private void blockResponse() {
            responseStarted = new CountDownLatch(1);
            allowResponse = new CountDownLatch(1);
        }

        /** Returns a deterministic guest session. */
        @Override
        public SessionResult connectAsGuest(String displayName) {
            return SessionResult.success(ClientTestFixtures.session());
        }

        /** Returns a deterministic registered session for interface completeness. */
        @Override
        public SessionResult login(String username, String password) {
            return SessionResult.success(ClientTestFixtures.registeredSession());
        }

        /** Returns a deterministic registered session for interface completeness. */
        @Override
        public SessionResult register(String username, String password) {
            return SessionResult.success(ClientTestFixtures.registeredSession());
        }

        /** Rejects unused matchmaking in this focused gateway. */
        @Override
        public MatchmakingResult joinMatchmaking(UUID sessionId) {
            throw new UnsupportedOperationException();
        }

        /** Rejects unused cancellation in this focused gateway. */
        @Override
        public OperationResult cancelMatchmaking(UUID sessionId) {
            throw new UnsupportedOperationException();
        }

        /** Rejects unused fleet submission in this focused gateway. */
        @Override
        public FleetSubmissionResult submitFleet(UUID sessionId, List<ShipPlacement> placements) {
            throw new UnsupportedOperationException();
        }

        /** Rejects unused firing in this focused gateway. */
        @Override
        public ShotResult fire(UUID sessionId, Coordinate coordinate) {
            throw new UnsupportedOperationException();
        }

        /** Rejects unused statistics in this focused gateway. */
        @Override
        public PlayerStatisticsResult getPlayerStatistics(UUID sessionId, int historyOffset,
                                                          int historyLimit) {
            throw new UnsupportedOperationException();
        }

        /** Rejects unused leaderboard in this focused gateway. */
        @Override
        public LeaderboardResult getLeaderboard(UUID sessionId, int limit) {
            throw new UnsupportedOperationException();
        }

        /** Runs one controllable request with optional transport failure. */
        @Override
        public OperationResult requestRematch(UUID sessionId) throws RemoteException {
            requestCalls.incrementAndGet();
            rematchThread = Thread.currentThread().getName();
            await(requestStarted, allowRequest);
            if (requestFailure != null) {
                throw requestFailure;
            }
            return OperationResult.success();
        }

        /** Runs one controllable response with its exact boolean value. */
        @Override
        public OperationResult respondToRematch(UUID sessionId, boolean accept)
                throws RemoteException {
            responseCalls.incrementAndGet();
            responseValues.add(accept);
            rematchThread = Thread.currentThread().getName();
            await(responseStarted, allowResponse);
            if (responseFailure != null) {
                throw responseFailure;
            }
            return OperationResult.success();
        }

        /** Returns successful unused game departure. */
        @Override
        public OperationResult leaveGame(UUID sessionId) {
            return OperationResult.success();
        }

        /** Returns successful shutdown logout. */
        @Override
        public OperationResult logout(UUID sessionId) {
            return OperationResult.success();
        }

        /** Records no resources beyond test-owned latches. */
        @Override
        public void close() {
        }

        /** Waits only when a test configured both deterministic synchronization latches. */
        private static void await(CountDownLatch started, CountDownLatch allowed)
                throws RemoteException {
            if (started == null || allowed == null) {
                return;
            }
            started.countDown();
            try {
                if (!allowed.await(5, TimeUnit.SECONDS)) {
                    throw new RemoteException("test operation timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RemoteException("test operation interrupted", exception);
            }
        }
    }
}
