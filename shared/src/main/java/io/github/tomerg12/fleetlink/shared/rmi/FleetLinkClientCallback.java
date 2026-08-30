package io.github.tomerg12.fleetlink.shared.rmi;

import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Defines synchronous server-to-client RMI notifications for authoritative state changes.
 * The server invokes these methods on RMI-managed threads, and client implementations must route
 * presentation changes onto the JavaFX Application Thread rather than touching controls directly.
 */
public interface FleetLinkClientCallback extends Remote {

    /**
     * Notifies the client that matchmaking created a game and supplies its safe initial snapshot.
     * The server invokes this synchronous callback after committing the match. Callback failure is
     * a network condition and does not undo the authoritative match already created by the server.
     *
     * @param initialGame the player-specific initial game snapshot
     * @throws RemoteException if RMI cannot deliver or complete the callback
     */
    void onMatchFound(GameView initialGame) throws RemoteException;

    /**
     * Pushes a complete safe snapshot after an authoritative game-state change.
     * The server invokes this synchronous callback after committing the change. The snapshot may
     * represent a normal update, resignation, disconnect, or final game state.
     *
     * @param gameView the receiving player's latest safe game snapshot
     * @throws RemoteException if RMI cannot deliver or complete the callback
     */
    void onGameStateChanged(GameView gameView) throws RemoteException;

    /**
     * Notifies the client that the opponent requested a rematch for a completed game.
     * The server invokes this synchronous callback after recording the request. A callback failure
     * does not remove the authoritative pending rematch state.
     *
     * @param rematchStatus the receiving player's authoritative rematch view
     * @throws RemoteException if RMI cannot deliver or complete the callback
     */
    void onRematchRequested(RematchStatusView rematchStatus) throws RemoteException;

    /**
     * Pushes a changed rematch negotiation state, including acceptance, decline, or expiration.
     * The server invokes this synchronous callback after committing the state transition. A fully
     * accepted rematch may be followed by {@link #onMatchFound(GameView)} for the new game.
     *
     * @param rematchStatus the receiving player's updated authoritative rematch view
     * @throws RemoteException if RMI cannot deliver or complete the callback
     */
    void onRematchStatusChanged(RematchStatusView rematchStatus) throws RemoteException;
}
