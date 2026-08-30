package io.github.tomerg12.fleetlink.client.integration;

import java.rmi.RemoteException;
import java.util.Objects;
import java.util.UUID;

import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;

/**
 * Implements the exported callback without holding JavaFX controls or screen references.
 */
final class ClientCallbackEndpoint implements FleetLinkClientCallback {
    private final ClientStateCoordinator coordinator;
    private UUID boundSessionId;

    /**
     * Creates a callback endpoint that delegates authoritative state to the client coordinator.
     *
     * @param coordinator client state reconciliation boundary
     */
    ClientCallbackEndpoint(ClientStateCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /**
     * Binds this exported endpoint to the exact successful session before its result is returned.
     * A later callback captures this value at method entry, so account identity reuse cannot make
     * an old session callback authoritative for a replacement session.
     *
     * @param sessionId exact successfully established session
     */
    synchronized void bindSession(UUID sessionId) {
        boundSessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    /** Clears local callback ownership before or during endpoint unexport. */
    synchronized void unbindSession() {
        boundSessionId = null;
    }

    /**
     * Records the new game before the coordinator schedules JavaFX presentation work.
     * The server invokes this method synchronously on an RMI-managed callback thread.
     *
     * @param initialGame authoritative initial game snapshot
     * @throws RemoteException if callback processing cannot complete through RMI
     */
    @Override
    public void onMatchFound(GameView initialGame) throws RemoteException {
        UUID callbackSessionId = requireBoundSessionId();
        coordinator.acceptMatchFound(callbackSessionId, initialGame);
    }

    /**
     * Records changed game state before the coordinator schedules JavaFX presentation work.
     * The server invokes this method synchronously on an RMI-managed callback thread.
     *
     * @param gameView authoritative changed game snapshot
     * @throws RemoteException if callback processing cannot complete through RMI
     */
    @Override
    public void onGameStateChanged(GameView gameView) throws RemoteException {
        UUID callbackSessionId = requireBoundSessionId();
        coordinator.acceptGameStateChanged(callbackSessionId, gameView);
    }

    /**
     * Reconciles an incoming rematch request against the exact locally bound session.
     *
     * @param rematchStatus authoritative rematch status
     * @throws RemoteException if callback processing cannot complete through RMI
     */
    @Override
    public void onRematchRequested(RematchStatusView rematchStatus) throws RemoteException {
        UUID callbackSessionId = requireBoundSessionId();
        coordinator.acceptRematchStatus(callbackSessionId, rematchStatus);
    }

    /**
     * Reconciles a changed rematch status against the exact locally bound session.
     *
     * @param rematchStatus authoritative rematch status
     * @throws RemoteException if callback processing cannot complete through RMI
     */
    @Override
    public void onRematchStatusChanged(RematchStatusView rematchStatus) throws RemoteException {
        UUID callbackSessionId = requireBoundSessionId();
        coordinator.acceptRematchStatus(callbackSessionId, rematchStatus);
    }

    /**
     * Captures the exact local session at callback entry.
     *
     * @return bound session identifier
     * @throws RemoteException if the endpoint has not completed successful session establishment
     */
    private synchronized UUID requireBoundSessionId() throws RemoteException {
        if (boundSessionId == null) {
            throw new RemoteException("FleetLink callback endpoint is not bound to a session");
        }
        return boundSessionId;
    }
}
