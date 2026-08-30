package io.github.tomerg12.fleetlink.client.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.OperationToken;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingState;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardResult;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchState;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.protocol.ShotOutcome;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkServerRemote;
import org.junit.jupiter.api.Test;

/**
 * Verifies real registry lookup, callback export, remote invocation, and callback delivery.
 */
class RmiClientGatewayTest {

    /**
     * Exercises the real RMI path without depending on server implementation classes.
     *
     * @throws Exception if local registry, export, lookup, or callback transport fails
     */
    @Test
    void registryLookupAndExportedCallbackUseRealRmiTransport() throws Exception {
        int port = availablePort();
        Registry registry = LocateRegistry.createRegistry(port);
        TestServerHandler handler = new TestServerHandler();
        FleetLinkServerRemote implementation = (FleetLinkServerRemote) Proxy.newProxyInstance(
                FleetLinkServerRemote.class.getClassLoader(),
                new Class<?>[] {FleetLinkServerRemote.class}, handler);
        Remote serverStub = UnicastRemoteObject.exportObject(implementation, 0);
        registry.rebind("FleetLinkServer", serverStub);

        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        RmiClientGateway gateway = null;
        try {
            gateway = RmiClientGateway.open(
                    new RmiClientConfig("localhost", port, "FleetLinkServer"), coordinator);
            OperationToken connection = coordinator.beginGuestConnection();
            SessionResult result = gateway.connectAsGuest("Guest Alpha");
            coordinator.completeGuestConnection(connection, result);

            assertTrue(gateway.register("AccountUser", "password").isSuccess());
            assertTrue(gateway.login("AccountUser", "password").isSuccess());

            MatchmakingResult matchmaking = gateway.joinMatchmaking(
                    ClientTestFixtures.session().getSessionId());
            OperationResult cancellation = gateway.cancelMatchmaking(
                    ClientTestFixtures.session().getSessionId());
            FleetSubmissionResult fleet = gateway.submitFleet(
                    ClientTestFixtures.session().getSessionId(), List.of());
            ShotResult shot = gateway.fire(
                    ClientTestFixtures.session().getSessionId(), new Coordinate(0, 0));
            PlayerStatisticsResult statistics = gateway.getPlayerStatistics(
                    ClientTestFixtures.session().getSessionId(), 0, 10);
            LeaderboardResult leaderboard = gateway.getLeaderboard(
                    ClientTestFixtures.session().getSessionId(), 100);
            OperationResult rematchRequest = gateway.requestRematch(
                    ClientTestFixtures.session().getSessionId());
            OperationResult rematchAccept = gateway.respondToRematch(
                    ClientTestFixtures.session().getSessionId(), true);
            OperationResult rematchDecline = gateway.respondToRematch(
                    ClientTestFixtures.session().getSessionId(), false);
            OperationResult leave = gateway.leaveGame(ClientTestFixtures.session().getSessionId());
            OperationResult logout = gateway.logout(ClientTestFixtures.session().getSessionId());

            assertEquals(MatchmakingState.WAITING, matchmaking.getState());
            assertTrue(cancellation.isSuccess());
            assertTrue(fleet.isAccepted());
            assertEquals(ShotOutcome.HIT, shot.getOutcome());
            assertTrue(statistics.isSuccess());
            assertTrue(leaderboard.isSuccess());
            assertTrue(rematchRequest.isSuccess());
            assertTrue(rematchAccept.isSuccess());
            assertTrue(rematchDecline.isSuccess());
            assertTrue(leave.isSuccess());
            assertTrue(logout.isSuccess());
            assertNotNull(handler.callback);
            handler.callback.onMatchFound(ClientTestFixtures.fleetPlacementGame());
            handler.callback.onGameStateChanged(ClientTestFixtures.finishedGame(true));
            handler.callback.onRematchRequested(
                    ClientTestFixtures.rematchStatus(RematchState.REQUESTED_BY_OPPONENT));
            handler.callback.onRematchStatusChanged(
                    ClientTestFixtures.rematchStatus(RematchState.DECLINED));

            assertEquals(ClientPhase.GAME_OVER, coordinator.getState().getPhase());
            assertEquals(RematchState.DECLINED, coordinator.getState().getRematchState()
                    .getAuthoritativeStatus().getState());
            assertEquals(List.of(true, false), handler.rematchResponses);
            assertEquals(List.of("connectAsGuest", "register", "login", "joinMatchmaking",
                    "cancelMatchmaking", "submitFleet", "fire", "getPlayerStatistics",
                    "getLeaderboard", "requestRematch", "respondToRematch",
                    "respondToRematch", "leaveGame", "logout"),
                    handler.invocations);
            gateway.close();
            RmiClientGateway closedGateway = gateway;
            assertThrows(RemoteException.class,
                    () -> closedGateway.connectAsGuest("Closed Gateway"));
        } finally {
            if (gateway != null) {
                gateway.close();
            }
            UnicastRemoteObject.unexportObject(implementation, true);
            UnicastRemoteObject.unexportObject(registry, true);
        }
    }

    /**
     * Reserves and releases an available local port for the test registry.
     *
     * @return currently available local TCP port
     * @throws IOException if a local port cannot be allocated
     */
    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Handles only the guest operation needed to prove the real client-side RMI path.
     */
    private static final class TestServerHandler implements InvocationHandler {
        private FleetLinkClientCallback callback;
        private final List<String> invocations = new ArrayList<>();
        private final List<Boolean> rematchResponses = new ArrayList<>();

        /**
         * Records the exported callback and returns an authoritative guest session.
         *
         * @param proxy exported dynamic remote implementation
         * @param method invoked remote contract method
         * @param arguments serialized remote arguments
         * @return result for the invoked method
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if ("connectAsGuest".equals(method.getName())) {
                invocations.add(method.getName());
                callback = (FleetLinkClientCallback) arguments[1];
                return SessionResult.success(ClientTestFixtures.session());
            }
            if ("register".equals(method.getName()) || "login".equals(method.getName())) {
                invocations.add(method.getName());
                callback = (FleetLinkClientCallback) arguments[2];
                return SessionResult.success(ClientTestFixtures.registeredSession());
            }
            if ("joinMatchmaking".equals(method.getName())) {
                invocations.add(method.getName());
                return MatchmakingResult.success(MatchmakingState.WAITING);
            }
            if ("cancelMatchmaking".equals(method.getName())) {
                invocations.add(method.getName());
                return OperationResult.success();
            }
            if ("submitFleet".equals(method.getName())) {
                invocations.add(method.getName());
                return FleetSubmissionResult.accepted(ClientTestFixtures.fleetPlacementGame());
            }
            if ("fire".equals(method.getName())) {
                invocations.add(method.getName());
                return ShotResult.accepted(ShotOutcome.HIT, ClientTestFixtures.battleGame(false));
            }
            if ("getPlayerStatistics".equals(method.getName())) {
                invocations.add(method.getName());
                return PlayerStatisticsResult.success(new PlayerStatisticsView(
                        1000, 0, 0, 0, 0, 0, 0, List.of(), 0, false));
            }
            if ("getLeaderboard".equals(method.getName())) {
                invocations.add(method.getName());
                return LeaderboardResult.success(List.of());
            }
            if ("requestRematch".equals(method.getName())) {
                invocations.add(method.getName());
                return OperationResult.success();
            }
            if ("respondToRematch".equals(method.getName())) {
                invocations.add(method.getName());
                rematchResponses.add((Boolean) arguments[1]);
                return OperationResult.success();
            }
            if ("leaveGame".equals(method.getName()) || "logout".equals(method.getName())) {
                invocations.add(method.getName());
                return OperationResult.success();
            }
            if ("toString".equals(method.getName())) {
                return "TestFleetLinkServer";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return proxy == arguments[0];
            }
            throw new UnsupportedOperationException("Unexpected remote method: " + method.getName());
        }
    }
}
