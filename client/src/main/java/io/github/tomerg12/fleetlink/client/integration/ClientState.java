package io.github.tomerg12.fleetlink.client.integration;

import java.util.Objects;

import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.SessionInfo;

/**
 * Provides one immutable view of the client state accepted by the reconciliation boundary.
 */
public final class ClientState {
    private final ClientPhase phase;
    private final SessionInfo sessionInfo;
    private final GameView gameView;
    private final RematchClientState rematchState;
    private final String statusMessage;
    private final long revision;

    /**
     * Creates an immutable state snapshot for publication to presentation listeners.
     *
     * @param phase reconciled client phase
     * @param sessionInfo current session, or null before connection
     * @param gameView current authoritative game snapshot, or null outside a game
     * @param rematchState current Game Over rematch slice, or null outside Game Over
     * @param statusMessage current player-facing status text
     * @param revision monotonically increasing local state revision
     */
    ClientState(ClientPhase phase, SessionInfo sessionInfo, GameView gameView,
                RematchClientState rematchState, String statusMessage, long revision) {
        this.phase = Objects.requireNonNull(phase, "phase");
        this.sessionInfo = sessionInfo;
        this.gameView = gameView;
        this.rematchState = rematchState;
        this.statusMessage = Objects.requireNonNull(statusMessage, "statusMessage");
        this.revision = revision;
    }

    /**
     * Returns the reconciled lifecycle phase.
     *
     * @return current client phase
     */
    public ClientPhase getPhase() {
        return phase;
    }

    /**
     * Returns the established server session when one exists.
     *
     * @return current session information, or null before connection
     */
    public SessionInfo getSessionInfo() {
        return sessionInfo;
    }

    /**
     * Returns the newest authoritative game snapshot accepted by the coordinator.
     *
     * @return current game snapshot, or null outside a game
     */
    public GameView getGameView() {
        return gameView;
    }

    /**
     * Returns the immutable rematch interaction slice for the active completed game.
     *
     * @return rematch state, or null outside the active Game Over lifecycle
     */
    public RematchClientState getRematchState() {
        return rematchState;
    }

    /**
     * Returns the current player-facing operation or failure message.
     *
     * @return non-null status message
     */
    public String getStatusMessage() {
        return statusMessage;
    }

    /**
     * Returns the local revision used to identify accepted state changes.
     *
     * @return monotonically increasing revision
     */
    public long getRevision() {
        return revision;
    }
}
