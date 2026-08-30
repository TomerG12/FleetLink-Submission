package io.github.tomerg12.fleetlink.server.rmi;

import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.server.matchmaking.MatchmakingService;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.server.rematch.RematchCoordinator;
import io.github.tomerg12.fleetlink.server.service.ClientCallbackRegistry;
import io.github.tomerg12.fleetlink.server.service.GameCoordinator;
import io.github.tomerg12.fleetlink.server.session.SessionRegistry;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkServerRemote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

/** Starts the FleetLink RMI server and binds the shared remote contract. */
public final class FleetLinkServerMain {
    /** Default local RMI registry port. */
    public static final int DEFAULT_REGISTRY_PORT = Registry.REGISTRY_PORT;
    /** Stable registry binding name. */
    public static final String BINDING_NAME = "FleetLinkServer";

    /** Prevents construction of the process entry-point utility class. */
    private FleetLinkServerMain() {
    }

    /**
     * Starts production server wiring and RMI binding.
     *
     * @param args optional registry port
     * @throws RemoteException if export or registry operations fail
     * @throws IllegalArgumentException if the supplied registry port is invalid
     */
    public static void main(String[] args) throws RemoteException {
        int port = parseRegistryPort(args);
        FleetLinkServerRuntime runtime = FleetLinkServerRuntime.production();
        FleetLinkServerRemoteImpl implementation = runtime.getRemoteAdapter();
        try {
            FleetLinkServerRemote stub = (FleetLinkServerRemote)
                    UnicastRemoteObject.exportObject(implementation, 0);
            Registry registry = locateOrCreateRegistry(port);
            registry.rebind(BINDING_NAME, stub);
            installShutdownHook(runtime, implementation);
            System.out.println("FleetLink RMI server bound as " + BINDING_NAME + " on port " + port);
        } catch (RemoteException | RuntimeException exception) {
            runtime.close();
            throw exception;
        }
    }

    /**
     * Wires in-memory services without exporting a remote object.
     *
     * @return fully wired core remote adapter
     */
    static FleetLinkServerRemoteImpl createCoreServer() {
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        SessionRegistry sessions = new SessionRegistry();
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ratings);
        MatchmakingService matchmaking = new MatchmakingService(
                games, callbacks, coordinator, ratings,
                (sessionId, playerId) -> sessions.findSession(sessionId)
                        .map(session -> session.getPlayer().getPlayerId().equals(playerId))
                        .orElse(false));
        RematchCoordinator rematches = new RematchCoordinator(
                sessions, games, matchmaking, callbacks);
        return new FleetLinkServerRemoteImpl(
                sessions, callbacks, matchmaking, coordinator, rematches);
    }

    /**
     * Installs process shutdown cleanup that unexports RMI ingress before closing gameplay resources.
     *
     * @param runtime production runtime whose ordered resources must close
     * @param implementation exported remote implementation to unexport first
     */
    private static void installShutdownHook(FleetLinkServerRuntime runtime,
                                            FleetLinkServerRemoteImpl implementation) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                UnicastRemoteObject.unexportObject(implementation, true);
            } catch (java.rmi.NoSuchObjectException ignored) {
                // RMI cleanup is already complete.
            } finally {
                runtime.close();
            }
        }, "fleetlink-server-shutdown"));
    }

    /**
     * Creates the requested local registry or reuses a reachable registry already on that port.
     *
     * @param port registry TCP port
     * @return reachable registry instance
     * @throws RemoteException if a registry cannot be created or reached
     */
    private static Registry locateOrCreateRegistry(int port) throws RemoteException {
        try {
            return LocateRegistry.createRegistry(port);
        } catch (RemoteException creationFailure) {
            Registry existing = LocateRegistry.getRegistry(port);
            existing.list();
            return existing;
        }
    }

    /**
     * Parses the optional command-line registry port while preserving the RMI default when omitted.
     *
     * @param args process arguments, optionally containing one registry port
     * @return validated registry port
     * @throws IllegalArgumentException if the first argument is not an integer from 1 through 65535
     */
    private static int parseRegistryPort(String[] args) {
        if (args == null || args.length == 0) {
            return DEFAULT_REGISTRY_PORT;
        }
        final int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("registry port must be an integer", exception);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("registry port must be between 1 and 65535");
        }
        return port;
    }
}
