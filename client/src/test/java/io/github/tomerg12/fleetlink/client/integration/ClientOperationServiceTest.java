package io.github.tomerg12.fleetlink.client.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingState;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardResult;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentCellView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShotOutcome;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import org.junit.jupiter.api.Test;

/**
 * Verifies the non-blocking remote executor and the first playable client flow.
 */
class ClientOperationServiceTest {

    /**
     * Confirms guest, matchmaking, and fleet calls run off the caller and preserve callback freshness.
     *
     * @throws Exception if an asynchronous test operation does not complete
     */
    @Test
    void coreFlowRunsRemotelyAndReachesBattleFromCallbacks() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        List<String> remoteThreads = new ArrayList<>();
        CallbackFirstGateway gateway = new CallbackFirstGateway(coordinator, remoteThreads);
        ExecutorService executor = namedExecutor("test-rmi-worker");
        String callerThread = Thread.currentThread().getName();

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            assertEquals(ClientPhase.LOBBY,
                    service.connectAsGuest("Guest Alpha").get(5, TimeUnit.SECONDS).getPhase());
            assertEquals(ClientPhase.SHIP_PLACEMENT,
                    service.joinMatchmaking().get(5, TimeUnit.SECONDS).getPhase());
            assertEquals(ClientPhase.BATTLE,
                    service.submitFleet(List.of()).get(5, TimeUnit.SECONDS).getPhase());
        }

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(4, remoteThreads.size());
        for (String remoteThread : remoteThreads) {
            assertEquals("test-rmi-worker", remoteThread);
            assertNotEquals(callerThread, remoteThread);
        }
        assertTrue(gateway.closed);
    }

    /**
     * Confirms blank guest input fails before gateway creation or remote execution.
     */
    @Test
    void blankGuestNameFailsLocallyWithoutOpeningGateway() {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        AtomicInteger opens = new AtomicInteger();
        ExecutorService executor = namedExecutor("unused-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(coordinator, () -> {
            opens.incrementAndGet();
            return new CallbackFirstGateway(coordinator, new ArrayList<>());
        }, executor)) {
            assertThrows(CompletionException.class,
                    () -> service.connectAsGuest("   ").join());
        }

        assertEquals(0, opens.get());
        assertEquals(ClientPhase.LOGIN, coordinator.getState().getPhase());
    }

    /**
     * Runs Sign In and Create Account transport calls on dedicated workers with exact passwords.
     *
     * @throws Exception if either asynchronous account operation does not complete
     */
    @Test
    void registeredSessionOperationsRunOnRemoteExecutorWithoutChangingPassword() throws Exception {
        ClientStateCoordinator loginCoordinator = new ClientStateCoordinator(Runnable::run);
        List<String> loginThreads = new ArrayList<>();
        CallbackFirstGateway loginGateway = new CallbackFirstGateway(
                loginCoordinator, loginThreads, false);
        ExecutorService loginExecutor = namedExecutor("login-rmi-worker");
        try (ClientOperationService service = new ClientOperationService(
                loginCoordinator, () -> loginGateway, loginExecutor)) {
            ClientState state = service.login("AccountUser", " exact password ")
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ClientPhase.LOBBY, state.getPhase());
            assertFalse(state.getSessionInfo().getPlayer().isGuest());
            assertEquals(" exact password ", loginGateway.lastPassword);
        }

        ClientStateCoordinator registerCoordinator = new ClientStateCoordinator(Runnable::run);
        List<String> registerThreads = new ArrayList<>();
        CallbackFirstGateway registerGateway = new CallbackFirstGateway(
                registerCoordinator, registerThreads, false);
        ExecutorService registerExecutor = namedExecutor("register-rmi-worker");
        try (ClientOperationService service = new ClientOperationService(
                registerCoordinator, () -> registerGateway, registerExecutor)) {
            assertEquals(ClientPhase.LOBBY,
                    service.register("NewAccount", "password")
                            .get(5, TimeUnit.SECONDS).getPhase());
        }

        assertTrue(loginExecutor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(registerExecutor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(loginThreads.stream().allMatch("login-rmi-worker"::equals));
        assertTrue(registerThreads.stream().allMatch("register-rmi-worker"::equals));
    }

    /**
     * Confirms remote setup failure is reconciled into a recoverable login state.
     *
     * @throws Exception if the asynchronous failure is not processed
     */
    @Test
    void transportFailureReturnsToSafeSourcePhase() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        ExecutorService executor = namedExecutor("failing-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(coordinator,
                () -> {
                    throw new RemoteException("registry unavailable");
                }, executor)) {
            ClientState result = service.connectAsGuest("Guest Alpha").get(5, TimeUnit.SECONDS);
            assertEquals(ClientPhase.LOGIN, result.getPhase());
            assertTrue(result.getStatusMessage().contains("registry unavailable"));
        }
    }

    /**
     * Confirms cancellation remains remote and returns to Lobby only after server success.
     *
     * @throws Exception if an asynchronous test operation does not complete
     */
    @Test
    void matchmakingCancellationUsesAuthoritativeResult() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        List<String> remoteThreads = new ArrayList<>();
        CallbackFirstGateway gateway = new CallbackFirstGateway(
                coordinator, remoteThreads, false);
        ExecutorService executor = namedExecutor("cancel-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            service.connectAsGuest("Guest Alpha").get(5, TimeUnit.SECONDS);
            assertEquals(ClientPhase.MATCHMAKING,
                    service.joinMatchmaking().get(5, TimeUnit.SECONDS).getPhase());
            assertEquals(ClientPhase.LOBBY,
                    service.cancelMatchmaking().get(5, TimeUnit.SECONDS).getPhase());
        }

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(List.of("cancel-rmi-worker", "cancel-rmi-worker", "cancel-rmi-worker",
                "cancel-rmi-worker"),
                remoteThreads);
    }

    /**
     * Confirms a stale Lobby action becomes an exceptional future without cancelling a found match.
     *
     * @throws Exception if an asynchronous setup operation does not complete
     */
    @Test
    void staleMatchmakingCancellationCannotEscapeOrRegressMatchedState() throws Exception {
        Queue<Runnable> presentationTasks = new ConcurrentLinkedQueue<>();
        List<ClientPhase> presentedPhases = new ArrayList<>();
        ClientStateCoordinator coordinator = new ClientStateCoordinator(presentationTasks::add);
        coordinator.setStateListener(state -> presentedPhases.add(state.getPhase()));
        List<String> remoteThreads = new ArrayList<>();
        CallbackFirstGateway gateway = new CallbackFirstGateway(
                coordinator, remoteThreads, false);
        ExecutorService executor = namedExecutor("stale-action-rmi-worker");
        AtomicReference<CompletableFuture<ClientState>> cancellation = new AtomicReference<>();

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            service.connectAsGuest("Guest Alpha").get(5, TimeUnit.SECONDS);
            presentationTasks.remove().run();
            service.joinMatchmaking().get(5, TimeUnit.SECONDS);
            while (!presentationTasks.isEmpty()) {
                presentationTasks.remove().run();
            }
            assertEquals(ClientPhase.MATCHMAKING,
                    presentedPhases.get(presentedPhases.size() - 1));

            coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
            long matchedRevision = coordinator.getState().getRevision();

            assertEquals(ClientPhase.SHIP_PLACEMENT, coordinator.getState().getPhase());
            assertEquals(ClientPhase.MATCHMAKING,
                    presentedPhases.get(presentedPhases.size() - 1));
            assertDoesNotThrow(() -> cancellation.set(service.cancelMatchmaking()));
            assertThrows(CompletionException.class, () -> cancellation.get().join());
            assertEquals(ClientPhase.SHIP_PLACEMENT, coordinator.getState().getPhase());
            assertEquals(matchedRevision, coordinator.getState().getRevision());
            assertEquals(0, gateway.cancellationCalls.get());

            while (!presentationTasks.isEmpty()) {
                presentationTasks.remove().run();
            }
            assertEquals(ClientPhase.SHIP_PLACEMENT,
                    presentedPhases.get(presentedPhases.size() - 1));
            assertEquals(ClientPhase.SHIP_PLACEMENT, coordinator.getState().getPhase());
            assertEquals(matchedRevision, coordinator.getState().getRevision());
        }

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(List.of("stale-action-rmi-worker", "stale-action-rmi-worker",
                "stale-action-rmi-worker"),
                remoteThreads);
    }

    /**
     * Confirms fire runs on the remote executor and a callback wins over its older result.
     *
     * @throws Exception if an asynchronous setup or fire operation does not complete
     */
    @Test
    void authoritativeFireRunsRemotelyAndPreservesCallbackGameOver() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        List<String> remoteThreads = new ArrayList<>();
        CallbackFirstGateway gateway = new CallbackFirstGateway(coordinator, remoteThreads);
        ExecutorService executor = namedExecutor("fire-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            service.connectAsGuest("Guest Alpha").get(5, TimeUnit.SECONDS);
            service.joinMatchmaking().get(5, TimeUnit.SECONDS);
            service.submitFleet(List.of()).get(5, TimeUnit.SECONDS);

            ClientState result = service.fire(new Coordinate(0, 0)).get(5, TimeUnit.SECONDS);

            assertEquals(ClientPhase.GAME_OVER, result.getPhase());
            assertEquals(ClientPhase.GAME_OVER, coordinator.getState().getPhase());
            assertEquals(ClientTestFixtures.finishedGame(true).getWinner().getPlayerId(),
                    result.getGameView().getWinner().getPlayerId());
        }

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(List.of("fire-rmi-worker", "fire-rmi-worker",
                "fire-rmi-worker", "fire-rmi-worker", "fire-rmi-worker"), remoteThreads);
        assertEquals(1, gateway.fireCalls.get());
    }

    /**
     * Confirms a stale Battle action cannot synchronously escape or queue an invalid shot.
     *
     * @throws Exception if asynchronous setup operations do not complete
     */
    @Test
    void staleBattleFireReturnsFailedFutureWithoutRemoteCall() throws Exception {
        Queue<Runnable> presentationTasks = new ConcurrentLinkedQueue<>();
        List<Boolean> presentedTurns = new ArrayList<>();
        ClientStateCoordinator coordinator = new ClientStateCoordinator(presentationTasks::add);
        coordinator.setStateListener(state -> {
            if (state.getGameView() != null) {
                presentedTurns.add(state.getGameView().isYourTurn());
            }
        });
        CallbackFirstGateway gateway = new CallbackFirstGateway(
                coordinator, new ArrayList<>(), true);
        ExecutorService executor = namedExecutor("stale-fire-rmi-worker");
        AtomicReference<CompletableFuture<ClientState>> staleFire = new AtomicReference<>();

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            service.connectAsGuest("Guest Alpha").get(5, TimeUnit.SECONDS);
            service.joinMatchmaking().get(5, TimeUnit.SECONDS);
            service.submitFleet(List.of()).get(5, TimeUnit.SECONDS);
            while (!presentationTasks.isEmpty()) {
                presentationTasks.remove().run();
            }
            assertTrue(presentedTurns.get(presentedTurns.size() - 1));

            coordinator.acceptGameStateChanged(ClientTestFixtures.battleGame(false));
            long callbackRevision = coordinator.getState().getRevision();

            assertEquals(ClientPhase.BATTLE, coordinator.getState().getPhase());
            assertTrue(presentedTurns.get(presentedTurns.size() - 1));
            assertDoesNotThrow(() -> staleFire.set(service.fire(new Coordinate(0, 0))));
            assertThrows(CompletionException.class, () -> staleFire.get().join());
            assertEquals(0, gateway.fireCalls.get());
            assertEquals(callbackRevision, coordinator.getState().getRevision());

            while (!presentationTasks.isEmpty()) {
                presentationTasks.remove().run();
            }
            assertEquals(false, presentedTurns.get(presentedTurns.size() - 1));
            assertEquals(ClientPhase.BATTLE, coordinator.getState().getPhase());
            assertEquals(callbackRevision, coordinator.getState().getRevision());
        }

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    /**
     * Confirms a resolved target stays local, preserves the turn, and does not prevent a following
     * unknown target from reaching the remote authority.
     *
     * @throws Exception if asynchronous setup or the valid follow-up shot does not complete
     */
    @Test
    void resolvedTargetRejectsLocallyBeforeValidSameTurnShot() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        CallbackFirstGateway gateway = new CallbackFirstGateway(
                coordinator, new ArrayList<>(), false);
        ExecutorService executor = namedExecutor("resolved-target-rmi-worker");
        Coordinate resolved = new Coordinate(0, 0);
        Coordinate unknown = new Coordinate(0, 1);

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            service.connectAsGuest("Guest Alpha").get(5, TimeUnit.SECONDS);
            coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
            coordinator.acceptGameStateChanged(ClientTestFixtures.battleGameAfterShot(
                    true, resolved, OpponentCellView.MISS));
            UUID gameId = coordinator.getState().getGameView().getGameId();

            assertThrows(CompletionException.class, () -> service.fire(resolved).join());
            assertEquals(0, gateway.fireCalls.get());
            assertEquals(ClientPhase.BATTLE, coordinator.getState().getPhase());
            assertTrue(coordinator.getState().getGameView().isYourTurn());
            assertEquals(gameId, coordinator.getState().getGameView().getGameId());

            ClientState validResult = service.fire(unknown).get(5, TimeUnit.SECONDS);
            assertEquals(1, gateway.fireCalls.get());
            assertEquals(ClientPhase.BATTLE, validResult.getPhase());
            assertFalse(validResult.getGameView().isYourTurn());
        }

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    /**
     * Confirms leave, completed-game return, and explicit logout follow authoritative boundaries.
     *
     * @throws Exception if an asynchronous lifecycle operation does not complete
     */
    @Test
    void playableLifecycleSupportsLeaveReturnAndLogout() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        CallbackFirstGateway gateway = new CallbackFirstGateway(
                coordinator, new ArrayList<>(), true);
        ExecutorService executor = namedExecutor("lifecycle-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            service.connectAsGuest("Guest Alpha").get(5, TimeUnit.SECONDS);
            service.joinMatchmaking().get(5, TimeUnit.SECONDS);
            service.submitFleet(List.of()).get(5, TimeUnit.SECONDS);

            assertEquals(ClientPhase.LOBBY,
                    service.leaveGame().get(5, TimeUnit.SECONDS).getPhase());
            assertEquals(1, gateway.leaveCalls.get());

            coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
            coordinator.acceptGameStateChanged(ClientTestFixtures.finishedGame(true));
            assertEquals(ClientPhase.LOBBY,
                    service.returnToLobby().get(5, TimeUnit.SECONDS).getPhase());

            assertEquals(ClientPhase.LOGIN,
                    service.logout().get(5, TimeUnit.SECONDS).getPhase());
            assertEquals(1, gateway.logoutCalls.get());
            assertTrue(gateway.closed);
        }

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    /**
     * Confirms leave transport failure uses callback-aware dedicated reconciliation.
     *
     * @throws Exception if asynchronous setup or leave does not complete
     */
    @Test
    void leaveTransportFailurePreservesNewerBattleCallback() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        CallbackFirstGateway gateway = new CallbackFirstGateway(
                coordinator, new ArrayList<>(), true);
        gateway.battleCallbackDuringLeave = true;
        gateway.leaveFailure = new RemoteException("leave unavailable");
        ExecutorService executor = namedExecutor("leave-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            service.connectAsGuest("Guest Alpha").get(5, TimeUnit.SECONDS);
            service.joinMatchmaking().get(5, TimeUnit.SECONDS);

            ClientState state = service.leaveGame().get(5, TimeUnit.SECONDS);

            assertEquals(ClientPhase.BATTLE, state.getPhase());
            assertEquals("FleetLink server connection failed: leave unavailable",
                    state.getStatusMessage());
            assertEquals(1, gateway.leaveCalls.get());
        }
    }

    /**
     * Confirms successful logout overrides a callback race and closes the reconciled gateway.
     *
     * @throws Exception if an asynchronous lifecycle operation does not complete
     */
    @Test
    void successfulLogoutAfterMatchCallbackEndsSessionAndClosesGateway() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        CallbackFirstGateway gateway = new CallbackFirstGateway(
                coordinator, new ArrayList<>(), false);
        gateway.returnLogoutAfterMatchCallback(OperationResult.success());
        ExecutorService executor = namedExecutor("logout-success-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            enterWaitingMatchmaking(service);

            ClientState result = service.logout().get(5, TimeUnit.SECONDS);

            assertLoggedOut(result);
            assertLoggedOut(coordinator.getState());
            assertTrue(gateway.closed);
        }

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    /**
     * Confirms invalid session is terminal after a callback race and closes the gateway.
     *
     * @throws Exception if an asynchronous lifecycle operation does not complete
     */
    @Test
    void invalidSessionLogoutAfterMatchCallbackEndsSessionAndClosesGateway() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        CallbackFirstGateway gateway = new CallbackFirstGateway(
                coordinator, new ArrayList<>(), false);
        gateway.returnLogoutAfterMatchCallback(OperationResult.failure(
                ResultCode.INVALID_SESSION, "Session expired"));
        ExecutorService executor = namedExecutor("logout-invalid-session-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            enterWaitingMatchmaking(service);

            ClientState result = service.logout().get(5, TimeUnit.SECONDS);

            assertLoggedOut(result);
            assertLoggedOut(coordinator.getState());
            assertTrue(gateway.closed);
        }

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    /**
     * Confirms transport uncertainty preserves callback state and leaves the gateway available.
     *
     * @throws Exception if an asynchronous lifecycle operation does not complete
     */
    @Test
    void logoutTransportFailureAfterMatchCallbackPreservesSessionAndGateway() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        CallbackFirstGateway gateway = new CallbackFirstGateway(
                coordinator, new ArrayList<>(), false);
        gateway.failLogoutAfterMatchCallback(new RemoteException("logout transport failed"));
        ExecutorService executor = namedExecutor("logout-transport-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            enterWaitingMatchmaking(service);

            ClientState result = service.logout().get(5, TimeUnit.SECONDS);

            assertEquals(ClientPhase.SHIP_PLACEMENT, result.getPhase());
            assertEquals(ClientTestFixtures.session().getSessionId(),
                    result.getSessionInfo().getSessionId());
            assertEquals(ClientTestFixtures.fleetPlacementGame().getGameId(),
                    result.getGameView().getGameId());
            assertFalse(gateway.closed);
        }

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(gateway.closed);
    }

    /**
     * Confirms a recoverable logout result preserves callback state without gateway cleanup.
     *
     * @throws Exception if an asynchronous lifecycle operation does not complete
     */
    @Test
    void recoverableLogoutFailureAfterMatchCallbackPreservesSessionAndGateway() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        CallbackFirstGateway gateway = new CallbackFirstGateway(
                coordinator, new ArrayList<>(), false);
        gateway.returnLogoutAfterMatchCallback(OperationResult.failure(
                ResultCode.INVALID_REQUEST, "Logout not completed"));
        ExecutorService executor = namedExecutor("logout-recoverable-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            enterWaitingMatchmaking(service);

            ClientState result = service.logout().get(5, TimeUnit.SECONDS);

            assertEquals(ClientPhase.SHIP_PLACEMENT, result.getPhase());
            assertEquals(ClientTestFixtures.session().getSessionId(),
                    result.getSessionInfo().getSessionId());
            assertEquals(ClientTestFixtures.fleetPlacementGame().getGameId(),
                    result.getGameView().getGameId());
            assertFalse(gateway.closed);
        }

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(gateway.closed);
    }

    /**
     * Confirms graceful shutdown is non-blocking, rejects new commands, and always cleans callbacks.
     *
     * @throws Exception if asynchronous shutdown does not complete within the test timeout
     */
    @Test
    void gracefulShutdownLogsOutOffCallerAndRejectsNewCommands() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        CountDownLatch logoutStarted = new CountDownLatch(1);
        CountDownLatch allowLogout = new CountDownLatch(1);
        List<String> remoteThreads = new ArrayList<>();
        CallbackFirstGateway gateway = new CallbackFirstGateway(
                coordinator, remoteThreads, false, logoutStarted, allowLogout);
        ExecutorService executor = namedExecutor("shutdown-rmi-worker");
        ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor);
        service.connectAsGuest("Guest Alpha").get(5, TimeUnit.SECONDS);

        CompletableFuture<Void> shutdown = service.shutdownGracefully();

        assertTrue(logoutStarted.await(5, TimeUnit.SECONDS));
        assertFalse(shutdown.isDone());
        assertThrows(CompletionException.class, () -> service.joinMatchmaking().join());
        allowLogout.countDown();
        shutdown.get(5, TimeUnit.SECONDS);

        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(gateway.closed);
        assertEquals(1, gateway.logoutCalls.get());
        assertEquals(List.of("shutdown-rmi-worker", "shutdown-rmi-worker"), remoteThreads);
    }

    /**
     * Confirms statistics reads run only on the dedicated executor and preserve top-level phase.
     *
     * @throws Exception if asynchronous setup or statistics work does not complete
     */
    @Test
    void statisticsReadsRunOnRemoteExecutorAndPreserveClientPhase() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        List<String> remoteThreads = new ArrayList<>();
        CallbackFirstGateway gateway = new CallbackFirstGateway(coordinator, remoteThreads, false);
        ExecutorService executor = namedExecutor("statistics-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            service.login("AccountUser", "password").get(5, TimeUnit.SECONDS);
            remoteThreads.clear();
            try (ClientStateCoordinator.DashboardSubscription ignored =
                         coordinator.activateStatisticsDashboard(state -> { })) {
                StatisticsDashboardState personal = service.loadPlayerStatistics(20, 10)
                        .get(5, TimeUnit.SECONDS);
                StatisticsDashboardState leaderboard = service.loadLeaderboard(100)
                        .get(5, TimeUnit.SECONDS);

                assertEquals(1000, personal.getPersonalStatistics().getCurrentRating());
                assertEquals(20, personal.getPersonalStatistics().getHistoryOffset());
                assertEquals(20, gateway.lastHistoryOffset);
                assertEquals(10, gateway.lastHistoryLimit);
                assertEquals(100, gateway.lastLeaderboardLimit);
                assertTrue(leaderboard.getLeaderboardEntries().isEmpty());
                assertEquals(ClientPhase.LOBBY, coordinator.getState().getPhase());
                assertEquals(List.of("statistics-rmi-worker", "statistics-rmi-worker"),
                        remoteThreads);
            }
        }
    }

    /**
     * Confirms an inactive statistics-screen action returns an exceptional future instead of
     * synchronously leaking a lifecycle race through a JavaFX event handler.
     *
     * @throws Exception if asynchronous session setup does not complete
     */
    @Test
    void staleStatisticsActionFailsAsynchronouslyWithoutRemoteCall() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        List<String> remoteThreads = new ArrayList<>();
        CallbackFirstGateway gateway = new CallbackFirstGateway(coordinator, remoteThreads, false);
        ExecutorService executor = namedExecutor("stale-statistics-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            service.login("AccountUser", "password").get(5, TimeUnit.SECONDS);
            ClientStateCoordinator.DashboardSubscription subscription =
                    coordinator.activateStatisticsDashboard(state -> { });
            subscription.close();
            remoteThreads.clear();

            CompletableFuture<StatisticsDashboardState> future = assertDoesNotThrow(
                    () -> service.loadPlayerStatistics(0, 10));
            assertThrows(CompletionException.class, future::join);
            assertTrue(remoteThreads.isEmpty());
            assertEquals(ClientPhase.LOBBY, coordinator.getState().getPhase());
        }
    }

    /**
     * Confirms invalid page requests fail locally before a gateway invocation or generation starts.
     *
     * @throws Exception if asynchronous session setup does not complete
     */
    @Test
    void invalidStatisticsBoundsFailLocallyWithoutRemoteRead() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        List<String> remoteThreads = new ArrayList<>();
        CallbackFirstGateway gateway = new CallbackFirstGateway(coordinator, remoteThreads, false);
        ExecutorService executor = namedExecutor("invalid-statistics-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            service.login("AccountUser", "password").get(5, TimeUnit.SECONDS);
            remoteThreads.clear();
            try (ClientStateCoordinator.DashboardSubscription ignored =
                         coordinator.activateStatisticsDashboard(state -> { })) {
                assertThrows(CompletionException.class,
                        () -> service.loadPlayerStatistics(-1, 10).join());
                assertThrows(CompletionException.class,
                        () -> service.loadPlayerStatistics(0, 0).join());
                assertThrows(CompletionException.class,
                        () -> service.loadPlayerStatistics(0, 51).join());
                assertThrows(CompletionException.class,
                        () -> service.loadLeaderboard(0).join());
                assertThrows(CompletionException.class,
                        () -> service.loadLeaderboard(101).join());
                assertTrue(remoteThreads.isEmpty());
                assertEquals(StatisticsDashboardState.LoadStatus.IDLE,
                        coordinator.getStatisticsDashboardState().getPersonalStatus());
                assertEquals(StatisticsDashboardState.LoadStatus.IDLE,
                        coordinator.getStatisticsDashboardState().getLeaderboardStatus());
            }
        }
    }

    /**
     * Confirms bounded preview reads retain FIFO remote order while matchmaking admission remains
     * immediate and an earlier preview failure cannot prevent the queued matchmaking call.
     *
     * @throws Exception if controlled remote work does not complete
     */
    @Test
    void blockedPreviewDoesNotDelayLocalMatchmakingAdmissionOrBreakRemoteFifo() throws Exception {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        CallbackFirstGateway gateway = new CallbackFirstGateway(
                coordinator, new ArrayList<>(), false);
        gateway.personalStarted = new CountDownLatch(1);
        gateway.allowPersonal = new CountDownLatch(1);
        gateway.personalFailure = new RemoteException("preview unavailable");
        ExecutorService executor = namedExecutor("preview-fifo-rmi-worker");

        try (ClientOperationService service = new ClientOperationService(
                coordinator, () -> gateway, executor)) {
            service.login("AccountUser", "password").get(5, TimeUnit.SECONDS);
            gateway.operationOrder.clear();
            try (ClientStateCoordinator.DashboardSubscription ignored =
                         coordinator.activateStatisticsDashboard(state -> { })) {
                CompletableFuture<StatisticsDashboardState> personal =
                        service.loadPlayerStatistics(0, 3);
                assertTrue(gateway.personalStarted.await(5, TimeUnit.SECONDS));
                CompletableFuture<StatisticsDashboardState> leaderboard =
                        service.loadLeaderboard(5);
                CompletableFuture<ClientState> matchmaking = service.joinMatchmaking();

                assertEquals(ClientPhase.MATCHMAKING, coordinator.getState().getPhase());
                assertFalse(matchmaking.isDone());
                gateway.allowPersonal.countDown();

                assertEquals(StatisticsDashboardState.LoadStatus.TRANSPORT_FAILURE,
                        personal.get(5, TimeUnit.SECONDS).getPersonalStatus());
                leaderboard.get(5, TimeUnit.SECONDS);
                assertEquals(ClientPhase.MATCHMAKING,
                        matchmaking.get(5, TimeUnit.SECONDS).getPhase());
                assertEquals(List.of("personal", "leaderboard", "matchmaking"),
                        gateway.operationOrder);
            }
        }
    }

    /**
     * Creates one named single-thread executor for observable thread-boundary assertions.
     *
     * @param name worker thread name
     * @return single-threaded executor
     */
    private static ExecutorService namedExecutor(String name) {
        return Executors.newSingleThreadExecutor(action -> new Thread(action, name));
    }

    /**
     * Establishes a guest session waiting in matchmaking without an automatic match callback.
     *
     * @param service operation service under test
     * @throws Exception if an asynchronous setup operation does not complete
     */
    private static void enterWaitingMatchmaking(ClientOperationService service) throws Exception {
        service.connectAsGuest("Guest Alpha").get(5, TimeUnit.SECONDS);
        assertEquals(ClientPhase.MATCHMAKING,
                service.joinMatchmaking().get(5, TimeUnit.SECONDS).getPhase());
    }

    /**
     * Verifies the local terminal session invariant.
     *
     * @param state state expected to represent completed logout
     */
    private static void assertLoggedOut(ClientState state) {
        assertEquals(ClientPhase.LOGIN, state.getPhase());
        assertEquals(null, state.getSessionInfo());
        assertEquals(null, state.getGameView());
    }

    /**
     * Simulates legal callback-before-result ordering without replacing server authority.
     */
    private static final class CallbackFirstGateway implements ClientRemoteGateway {
        private final ClientStateCoordinator coordinator;
        private final List<String> remoteThreads;
        private final boolean callbackFirst;
        private final AtomicInteger cancellationCalls = new AtomicInteger();
        private final AtomicInteger fireCalls = new AtomicInteger();
        private final AtomicInteger leaveCalls = new AtomicInteger();
        private final AtomicInteger logoutCalls = new AtomicInteger();
        private final CountDownLatch logoutStarted;
        private final CountDownLatch allowLogout;
        private OperationResult logoutResult = OperationResult.success();
        private RemoteException logoutFailure;
        private RemoteException leaveFailure;
        private RemoteException personalFailure;
        private boolean battleCallbackDuringLeave;
        private boolean callbackDuringLogout;
        private volatile boolean closed;
        private String lastPassword;
        private int lastHistoryOffset;
        private int lastHistoryLimit;
        private int lastLeaderboardLimit;
        private CountDownLatch personalStarted;
        private CountDownLatch allowPersonal;
        private final List<String> operationOrder = new ArrayList<>();

        /**
         * Creates a gateway that emits authoritative fixtures before matching synchronous results.
         *
         * @param coordinator callback destination
         * @param remoteThreads observed remote call thread names
         */
        private CallbackFirstGateway(ClientStateCoordinator coordinator,
                                     List<String> remoteThreads) {
            this(coordinator, remoteThreads, true);
        }

        /**
         * Creates a gateway with optional callback-before-result behavior.
         *
         * @param coordinator callback destination
         * @param remoteThreads observed remote call thread names
         * @param callbackFirst true to emit callbacks before successful results
         */
        private CallbackFirstGateway(ClientStateCoordinator coordinator,
                                     List<String> remoteThreads, boolean callbackFirst) {
            this(coordinator, remoteThreads, callbackFirst, null, null);
        }

        /**
         * Creates a gateway with optional synchronization around graceful logout.
         *
         * @param coordinator callback destination
         * @param remoteThreads observed remote call thread names
         * @param callbackFirst true to emit callbacks before successful results
         * @param logoutStarted optional signal raised when logout starts
         * @param allowLogout optional signal that permits logout to finish
         */
        private CallbackFirstGateway(ClientStateCoordinator coordinator,
                                     List<String> remoteThreads, boolean callbackFirst,
                                     CountDownLatch logoutStarted, CountDownLatch allowLogout) {
            this.coordinator = coordinator;
            this.remoteThreads = remoteThreads;
            this.callbackFirst = callbackFirst;
            this.logoutStarted = logoutStarted;
            this.allowLogout = allowLogout;
        }

        /**
         * Returns the deterministic registered session for asynchronous login tests.
         *
         * @param username submitted username
         * @param password exact submitted password
         * @return successful session result
         */
        @Override
        public SessionResult login(String username, String password) {
            recordThread();
            lastPassword = password;
            return SessionResult.success(ClientTestFixtures.registeredSession());
        }

        /**
         * Returns the deterministic registered session for asynchronous registration tests.
         *
         * @param username submitted username
         * @param password exact submitted password
         * @return successful session result
         */
        @Override
        public SessionResult register(String username, String password) {
            recordThread();
            lastPassword = password;
            return SessionResult.success(ClientTestFixtures.registeredSession());
        }

        /**
         * Returns the deterministic guest session.
         *
         * @param displayName requested guest display name
         * @return successful session result
         */
        @Override
        public SessionResult connectAsGuest(String displayName) {
            recordThread();
            return SessionResult.success(ClientTestFixtures.session());
        }

        /**
         * Emits onMatchFound before returning MATCHED.
         *
         * @param sessionId established session identifier
         * @return successful matched result
         */
        @Override
        public MatchmakingResult joinMatchmaking(UUID sessionId) {
            operationOrder.add("matchmaking");
            recordThread();
            if (callbackFirst) {
                coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
                return MatchmakingResult.success(MatchmakingState.MATCHED);
            }
            return MatchmakingResult.success(MatchmakingState.WAITING);
        }

        /**
         * Returns successful cancellation for interface completeness.
         *
         * @param sessionId established session identifier
         * @return successful cancellation result
         */
        @Override
        public OperationResult cancelMatchmaking(UUID sessionId) {
            cancellationCalls.incrementAndGet();
            recordThread();
            return OperationResult.success();
        }

        /**
         * Emits BATTLE before returning an older accepted placement snapshot.
         *
         * @param sessionId established session identifier
         * @param placements complete fleet request
         * @return older accepted placement snapshot
         */
        @Override
        public FleetSubmissionResult submitFleet(UUID sessionId,
                                                 List<ShipPlacement> placements) {
            recordThread();
            if (callbackFirst) {
                coordinator.acceptGameStateChanged(ClientTestFixtures.battleGame());
            }
            return FleetSubmissionResult.accepted(ClientTestFixtures.fleetPlacementGame());
        }

        /**
         * Emits a terminal callback before returning an older battle shot snapshot.
         *
         * @param sessionId established session identifier
         * @param coordinate requested target coordinate
         * @return older authoritative accepted-shot result
         */
        @Override
        public ShotResult fire(UUID sessionId, Coordinate coordinate) {
            fireCalls.incrementAndGet();
            recordThread();
            if (callbackFirst) {
                coordinator.acceptGameStateChanged(ClientTestFixtures.finishedGame(true));
            }
            return ShotResult.accepted(ShotOutcome.HIT, ClientTestFixtures.battleGame(false));
        }

        /** Returns empty personal statistics for interface completeness. */
        @Override
        public PlayerStatisticsResult getPlayerStatistics(UUID sessionId, int historyOffset,
                                                          int historyLimit)
                throws RemoteException {
            operationOrder.add("personal");
            recordThread();
            lastHistoryOffset = historyOffset;
            lastHistoryLimit = historyLimit;
            if (personalStarted != null) {
                personalStarted.countDown();
            }
            awaitLatch(allowPersonal, "personal preview");
            if (personalFailure != null) {
                throw personalFailure;
            }
            return PlayerStatisticsResult.success(new PlayerStatisticsView(
                    1000, 0, 0, 0, 0, 0, 0, List.of(), historyOffset, false));
        }

        /** Returns an empty leaderboard for interface completeness. */
        @Override
        public LeaderboardResult getLeaderboard(UUID sessionId, int limit) {
            operationOrder.add("leaderboard");
            recordThread();
            lastLeaderboardLimit = limit;
            return LeaderboardResult.success(List.of());
        }

        /**
         * Awaits optional deterministic test permission for one controlled remote call.
         *
         * @param latch optional permission latch
         * @param operation operation name used in failure text
         * @throws RemoteException if waiting is interrupted or times out
         */
        private static void awaitLatch(CountDownLatch latch, String operation)
                throws RemoteException {
            if (latch == null) {
                return;
            }
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new RemoteException("timed out waiting for " + operation);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RemoteException(operation + " test interrupted", exception);
            }
        }

        /** Returns a successful rematch request for interface completeness. */
        @Override
        public OperationResult requestRematch(UUID sessionId) {
            recordThread();
            return OperationResult.success();
        }

        /** Returns a successful rematch response for interface completeness. */
        @Override
        public OperationResult respondToRematch(UUID sessionId, boolean accept) {
            recordThread();
            return OperationResult.success();
        }

        /**
         * Returns successful authoritative game departure.
         *
         * @param sessionId established session identifier
         * @return successful leave result
         */
        @Override
        public OperationResult leaveGame(UUID sessionId) throws RemoteException {
            leaveCalls.incrementAndGet();
            recordThread();
            if (battleCallbackDuringLeave) {
                coordinator.acceptGameStateChanged(ClientTestFixtures.battleGame());
            }
            if (leaveFailure != null) {
                throw leaveFailure;
            }
            return OperationResult.success();
        }

        /**
         * Returns successful logout after optional test synchronization.
         *
         * @param sessionId established session identifier
         * @return successful logout result
         * @throws RemoteException if the test thread is interrupted
         */
        @Override
        public OperationResult logout(UUID sessionId) throws RemoteException {
            logoutCalls.incrementAndGet();
            recordThread();
            if (logoutStarted != null) {
                logoutStarted.countDown();
            }
            if (allowLogout != null) {
                try {
                    if (!allowLogout.await(5, TimeUnit.SECONDS)) {
                        throw new RemoteException("timed out waiting to complete logout");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RemoteException("logout test interrupted", exception);
                }
            }
            if (callbackDuringLogout) {
                coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
            }
            if (logoutFailure != null) {
                throw logoutFailure;
            }
            return logoutResult;
        }

        /**
         * Records callback cleanup by the service.
         */
        @Override
        public void close() {
            closed = true;
        }

        /**
         * Captures the current worker name for thread-boundary assertions.
         */
        private void recordThread() {
            remoteThreads.add(Thread.currentThread().getName());
        }

        /**
         * Configures logout to emit a match callback before returning an operation result.
         *
         * @param result authoritative result returned after the callback
         */
        private void returnLogoutAfterMatchCallback(OperationResult result) {
            callbackDuringLogout = true;
            logoutResult = result;
            logoutFailure = null;
        }

        /**
         * Configures logout to emit a match callback before a transport failure.
         *
         * @param failure transport failure thrown after the callback
         */
        private void failLogoutAfterMatchCallback(RemoteException failure) {
            callbackDuringLogout = true;
            logoutFailure = failure;
        }
    }
}
