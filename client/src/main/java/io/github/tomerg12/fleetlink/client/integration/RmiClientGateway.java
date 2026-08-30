package io.github.tomerg12.fleetlink.client.integration;

import java.rmi.NotBoundException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardResult;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsResult;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkServerRemote;

/**
 * Performs real registry lookup and synchronous FleetLink RMI calls behind the client gateway.
 */
public final class RmiClientGateway implements ClientRemoteGateway {
    private final FleetLinkServerRemote server;
    private final ClientCallbackEndpoint callbackEndpoint;
    private final FleetLinkClientCallback callbackStub;
    private boolean closed;

    /**
     * Stores a looked-up server stub and the exported callback pair.
     *
     * @param server looked-up server remote reference
     * @param callbackEndpoint local callback implementation
     * @param callbackStub exported callback reference sent to the server
     */
    private RmiClientGateway(FleetLinkServerRemote server,
                             ClientCallbackEndpoint callbackEndpoint,
                             FleetLinkClientCallback callbackStub) {
        this.server = server;
        this.callbackEndpoint = callbackEndpoint;
        this.callbackStub = callbackStub;
    }

    /**
     * Exports the callback and looks up the configured server binding.
     * This operation can block and must run off the JavaFX Application Thread.
     *
     * @param config validated registry configuration
     * @param coordinator state boundary receiving callback data
     * @return open RMI gateway
     * @throws RemoteException if callback export or registry lookup fails
     */
    public static RmiClientGateway open(RmiClientConfig config,
                                        ClientStateCoordinator coordinator) throws RemoteException {
        Objects.requireNonNull(config, "config");
        ClientCallbackEndpoint endpoint = new ClientCallbackEndpoint(coordinator);
        FleetLinkClientCallback callback = (FleetLinkClientCallback)
                UnicastRemoteObject.exportObject(endpoint, 0);
        try {
            Registry registry = LocateRegistry.getRegistry(config.getHost(), config.getPort());
            Remote lookedUp = registry.lookup(config.getBindingName());
            if (!(lookedUp instanceof FleetLinkServerRemote remoteServer)) {
                throw new RemoteException("Registry binding does not implement FleetLinkServerRemote");
            }
            return new RmiClientGateway(remoteServer, endpoint, callback);
        } catch (NotBoundException | RemoteException exception) {
            unexport(endpoint);
            if (exception instanceof RemoteException remoteException) {
                throw remoteException;
            }
            throw new RemoteException("FleetLink server binding was not found", exception);
        } catch (RuntimeException exception) {
            unexport(endpoint);
            throw exception;
        }
    }

    /**
     * Invokes the synchronous guest connection operation with the exported callback stub.
     *
     * @param displayName requested guest display name
     * @return authoritative session result
     * @throws RemoteException if RMI cannot complete the call
     */
    @Override
    public SessionResult connectAsGuest(String displayName) throws RemoteException {
        requireOpen();
        return bindSuccessfulSession(server.connectAsGuest(displayName, callbackStub));
    }

    /**
     * Invokes synchronous registered login with the already-exported callback stub.
     *
     * @param username submitted account username
     * @param password exact submitted password sequence
     * @return authoritative session result
     * @throws RemoteException if RMI cannot complete the call
     */
    @Override
    public SessionResult login(String username, String password) throws RemoteException {
        requireOpen();
        return bindSuccessfulSession(server.login(username, password, callbackStub));
    }

    /**
     * Invokes synchronous registered account creation with the exported callback stub.
     *
     * @param username submitted account username
     * @param password exact submitted password sequence
     * @return authoritative session result
     * @throws RemoteException if RMI cannot complete the call
     */
    @Override
    public SessionResult register(String username, String password) throws RemoteException {
        requireOpen();
        return bindSuccessfulSession(server.register(username, password, callbackStub));
    }

    /**
     * Invokes synchronous server matchmaking for the established session.
     *
     * @param sessionId established session identifier
     * @return authoritative matchmaking result
     * @throws RemoteException if RMI cannot complete the call
     */
    @Override
    public MatchmakingResult joinMatchmaking(UUID sessionId) throws RemoteException {
        requireOpen();
        return server.joinMatchmaking(sessionId);
    }

    /**
     * Invokes synchronous server matchmaking cancellation.
     *
     * @param sessionId established session identifier
     * @return authoritative cancellation result
     * @throws RemoteException if RMI cannot complete the call
     */
    @Override
    public OperationResult cancelMatchmaking(UUID sessionId) throws RemoteException {
        requireOpen();
        return server.cancelMatchmaking(sessionId);
    }

    /**
     * Invokes one synchronous complete-fleet submission.
     *
     * @param sessionId established session identifier
     * @param placements complete fleet request
     * @return authoritative fleet result
     * @throws RemoteException if RMI cannot complete the call
     */
    @Override
    public FleetSubmissionResult submitFleet(UUID sessionId, List<ShipPlacement> placements)
            throws RemoteException {
        requireOpen();
        return server.submitFleet(sessionId, placements);
    }

    /**
     * Invokes one synchronous server-authoritative shot operation.
     *
     * @param sessionId established session identifier
     * @param coordinate requested target coordinate
     * @return authoritative shot result
     * @throws RemoteException if RMI cannot complete the call
     */
    @Override
    public ShotResult fire(UUID sessionId, Coordinate coordinate) throws RemoteException {
        requireOpen();
        return server.fire(sessionId, coordinate);
    }

    /**
     * Invokes the synchronous personal-statistics read for one database-paginated history page.
     *
     * @param sessionId established session identifier
     * @param historyOffset zero-based database history offset
     * @param historyLimit bounded history page size
     * @return authoritative personal statistics result
     * @throws RemoteException if RMI cannot complete the call
     */
    @Override
    public PlayerStatisticsResult getPlayerStatistics(UUID sessionId, int historyOffset,
                                                      int historyLimit) throws RemoteException {
        requireOpen();
        return server.getPlayerStatistics(sessionId, historyOffset, historyLimit);
    }

    /**
     * Invokes the synchronous server-ranked leaderboard read.
     *
     * @param sessionId established session identifier, including a guest session
     * @param limit bounded maximum row count
     * @return authoritative leaderboard result
     * @throws RemoteException if RMI cannot complete the call
     */
    @Override
    public LeaderboardResult getLeaderboard(UUID sessionId, int limit) throws RemoteException {
        requireOpen();
        return server.getLeaderboard(sessionId, limit);
    }

    /**
     * Delegates a positive rematch request to the existing server operation.
     *
     * @param sessionId established session identifier
     * @return authoritative rematch operation result
     * @throws RemoteException if RMI cannot complete the call
     */
    @Override
    public OperationResult requestRematch(UUID sessionId) throws RemoteException {
        requireOpen();
        return server.requestRematch(sessionId);
    }

    /**
     * Delegates acceptance, decline, or withdrawal to the existing server response operation.
     *
     * @param sessionId established session identifier
     * @param accept true for acceptance, false for decline or withdrawal
     * @return authoritative rematch operation result
     * @throws RemoteException if RMI cannot complete the call
     */
    @Override
    public OperationResult respondToRematch(UUID sessionId, boolean accept) throws RemoteException {
        requireOpen();
        return server.respondToRematch(sessionId, accept);
    }

    /**
     * Invokes synchronous authoritative game departure for the established session.
     *
     * @param sessionId established session identifier
     * @return authoritative leave result
     * @throws RemoteException if RMI cannot complete the call
     */
    @Override
    public OperationResult leaveGame(UUID sessionId) throws RemoteException {
        requireOpen();
        return server.leaveGame(sessionId);
    }

    /**
     * Invokes synchronous server logout before local callback cleanup.
     *
     * @param sessionId established session identifier
     * @return authoritative logout result
     * @throws RemoteException if RMI cannot complete the call
     */
    @Override
    public OperationResult logout(UUID sessionId) throws RemoteException {
        requireOpen();
        return server.logout(sessionId);
    }

    /**
     * Unexports the local callback so it no longer accepts server invocations.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        callbackEndpoint.unbindSession();
        unexport(callbackEndpoint);
    }

    /**
     * Binds the endpoint to the exact returned session before exposing successful establishment.
     *
     * @param result authoritative session result
     * @return the unchanged result after successful local binding
     */
    private SessionResult bindSuccessfulSession(SessionResult result) {
        if (result.isSuccess()) {
            callbackEndpoint.bindSession(result.getSessionInfo().getSessionId());
        }
        return result;
    }

    /**
     * Rejects calls after callback cleanup has closed the gateway.
     *
     * @throws RemoteException if the gateway is closed
     */
    private synchronized void requireOpen() throws RemoteException {
        if (closed) {
            throw new RemoteException("RMI client gateway is closed");
        }
    }

    /**
     * Best-effort unexports one callback implementation during cleanup or failed lookup.
     *
     * @param endpoint exported callback implementation
     */
    private static void unexport(ClientCallbackEndpoint endpoint) {
        try {
            UnicastRemoteObject.unexportObject(endpoint, true);
        } catch (java.rmi.NoSuchObjectException ignored) {
            // The endpoint is already unexported, so cleanup is complete.
        }
    }
}
