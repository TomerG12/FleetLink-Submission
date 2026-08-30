package io.github.tomerg12.fleetlink.server.rmi;

import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.validFleet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingState;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkServerRemote;
import java.io.IOException;
import java.net.ServerSocket;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies the core guest slice through real local RMI export, registry lookup, and callbacks.
 */
class FleetLinkRmiSmokeTest {

    /**
     * Exports the server and two client callbacks, resolves the server from a registry, and matches
     * two guest sessions through the remote stubs.
     *
     * @throws Exception if local RMI setup or invocation fails
     */
    @Test
    void connectsAndMatchesGuestsThroughExportedRmiStubs() throws Exception {
        int port = findAvailablePort();
        FleetLinkServerRemoteImpl implementation = FleetLinkServerMain.createCoreServer();
        FleetLinkServerRemote serverStub = (FleetLinkServerRemote)
                UnicastRemoteObject.exportObject(implementation, 0);
        Registry registry = LocateRegistry.createRegistry(port);
        RecordingCallback firstCallback = new RecordingCallback();
        RecordingCallback secondCallback = new RecordingCallback();
        FleetLinkClientCallback firstCallbackStub = (FleetLinkClientCallback)
                UnicastRemoteObject.exportObject(firstCallback, 0);
        FleetLinkClientCallback secondCallbackStub = (FleetLinkClientCallback)
                UnicastRemoteObject.exportObject(secondCallback, 0);
        boolean bound = false;
        try {
            registry.rebind(FleetLinkServerMain.BINDING_NAME, serverStub);
            bound = true;
            Registry clientRegistry = LocateRegistry.getRegistry("127.0.0.1", port);
            FleetLinkServerRemote remote = (FleetLinkServerRemote)
                    clientRegistry.lookup(FleetLinkServerMain.BINDING_NAME);

            SessionResult firstSession = remote.connectAsGuest("RmiFirst", firstCallbackStub);
            SessionResult secondSession = remote.connectAsGuest("RmiSecond", secondCallbackStub);
            assertTrue(firstSession.isSuccess());
            assertTrue(secondSession.isSuccess());
            assertEquals(MatchmakingState.WAITING,
                    remote.joinMatchmaking(firstSession.getSessionInfo().getSessionId()).getState());
            assertEquals(MatchmakingState.MATCHED,
                    remote.joinMatchmaking(secondSession.getSessionInfo().getSessionId()).getState());
            assertTrue(firstCallback.awaitMatchFound());
            assertTrue(secondCallback.awaitMatchFound());

            GameView source = firstCallback.games.getFirst();
            finishBySinkingSecondFleet(remote,
                    firstSession.getSessionInfo().getSessionId(),
                    secondSession.getSessionInfo().getSessionId());
            assertTrue(remote.requestRematch(
                    firstSession.getSessionInfo().getSessionId()).isSuccess());
            assertTrue(firstCallback.awaitRematchStatus());
            assertTrue(secondCallback.awaitRematchRequest());
            long beforeActivation = System.currentTimeMillis();
            assertTrue(remote.respondToRematch(
                    secondSession.getSessionInfo().getSessionId(), true).isSuccess());
            assertTrue(firstCallback.awaitSecondMatchFound());
            assertTrue(secondCallback.awaitSecondMatchFound());
            long afterActivation = System.currentTimeMillis();
            GameView rematch = firstCallback.games.getLast();
            assertNotEquals(source.getGameId(), rematch.getGameId());
            assertEquals(GamePhase.FLEET_PLACEMENT, rematch.getPhase());
            assertTrue(rematch.getDeadlineEpochMillis() >= beforeActivation + 120_000L);
            assertTrue(rematch.getDeadlineEpochMillis() <= afterActivation + 120_000L);
        } finally {
            if (bound) {
                registry.unbind(FleetLinkServerMain.BINDING_NAME);
            }
            UnicastRemoteObject.unexportObject(firstCallback, true);
            UnicastRemoteObject.unexportObject(secondCallback, true);
            UnicastRemoteObject.unexportObject(implementation, true);
            UnicastRemoteObject.unexportObject(registry, true);
        }
    }

    /**
     * Completes one ordinary eligible game through exported authoritative fleet and fire methods.
     * The first player targets every second-player ship cell while the second player targets safe
     * water, so both sessions remain available for the rematch portion of this smoke test.
     *
     * @param remote exported server stub
     * @param firstSessionId first participant session
     * @param secondSessionId second participant session
     * @throws RemoteException if an exported operation cannot complete
     */
    private static void finishBySinkingSecondFleet(FleetLinkServerRemote remote,
                                                    UUID firstSessionId,
                                                    UUID secondSessionId)
            throws RemoteException {
        assertTrue(remote.submitFleet(firstSessionId, validFleet()).isAccepted());
        assertTrue(remote.submitFleet(secondSessionId, validFleet()).isAccepted());
        List<Coordinate> shipTargets = List.of(
                new Coordinate(0, 0), new Coordinate(0, 1), new Coordinate(0, 2),
                new Coordinate(0, 3), new Coordinate(0, 4), new Coordinate(1, 0),
                new Coordinate(1, 1), new Coordinate(1, 2), new Coordinate(1, 3),
                new Coordinate(2, 0), new Coordinate(2, 1), new Coordinate(2, 2),
                new Coordinate(3, 0), new Coordinate(3, 1), new Coordinate(3, 2),
                new Coordinate(4, 0), new Coordinate(4, 1));
        List<Coordinate> waterTargets = List.of(
                new Coordinate(9, 0), new Coordinate(9, 1), new Coordinate(9, 2),
                new Coordinate(9, 3), new Coordinate(9, 4), new Coordinate(9, 5),
                new Coordinate(9, 6), new Coordinate(9, 7), new Coordinate(9, 8),
                new Coordinate(9, 9), new Coordinate(8, 0), new Coordinate(8, 1),
                new Coordinate(8, 2), new Coordinate(8, 3), new Coordinate(8, 4),
                new Coordinate(8, 5), new Coordinate(8, 6));
        int shipIndex = 0;
        int waterIndex = 0;
        while (remote.getCurrentGame(firstSessionId).getGameView().getPhase()
                != GamePhase.FINISHED) {
            GameView firstView = remote.getCurrentGame(firstSessionId).getGameView();
            if (firstView.isYourTurn()) {
                assertTrue(remote.fire(firstSessionId, shipTargets.get(shipIndex++)).isAccepted());
            } else {
                assertTrue(remote.fire(secondSessionId, waterTargets.get(waterIndex++)).isAccepted());
            }
        }
    }

    /**
     * Reserves an ephemeral local TCP port and releases it for immediate RMI registry creation.
     *
     * @return an available local port
     * @throws IOException if the temporary socket cannot be created
     */
    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Records receipt of the server-to-client match-found callback over an exported RMI stub.
     */
    private static final class RecordingCallback implements FleetLinkClientCallback {
        private final CountDownLatch matchFound = new CountDownLatch(1);
        private final CountDownLatch secondMatchFound = new CountDownLatch(1);
        private final CountDownLatch rematchRequest = new CountDownLatch(1);
        private final CountDownLatch rematchStatus = new CountDownLatch(1);
        private final List<GameView> games = new CopyOnWriteArrayList<>();

        /**
         * Waits for a match-found callback from the server.
         *
         * @return true when the callback arrives within the timeout
         * @throws InterruptedException if the test thread is interrupted
         */
        private boolean awaitMatchFound() throws InterruptedException {
            return matchFound.await(2, TimeUnit.SECONDS);
        }

        /**
         * Waits for the second match-found callback belonging to the rematch game.
         *
         * @return true when the callback arrives
         * @throws InterruptedException if interrupted while waiting
         */
        private boolean awaitSecondMatchFound() throws InterruptedException {
            return secondMatchFound.await(2, TimeUnit.SECONDS);
        }

        /**
         * Waits for an opponent rematch request callback.
         *
         * @return true when the callback arrives
         * @throws InterruptedException if interrupted while waiting
         */
        private boolean awaitRematchRequest() throws InterruptedException {
            return rematchRequest.await(2, TimeUnit.SECONDS);
        }

        /**
         * Waits for a rematch status callback.
         *
         * @return true when the callback arrives
         * @throws InterruptedException if interrupted while waiting
         */
        private boolean awaitRematchStatus() throws InterruptedException {
            return rematchStatus.await(2, TimeUnit.SECONDS);
        }

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) throws RemoteException {
            games.add(initialGame);
            if (games.size() == 1) {
                matchFound.countDown();
            } else {
                secondMatchFound.countDown();
            }
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) throws RemoteException {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchRequested(RematchStatusView rematchStatus) throws RemoteException {
            rematchRequest.countDown();
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus) throws RemoteException {
            this.rematchStatus.countDown();
        }
    }
}
