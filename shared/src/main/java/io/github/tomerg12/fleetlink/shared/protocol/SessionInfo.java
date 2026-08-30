package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Carries the session identifier and safe player information returned after session establishment.
 */
public final class SessionInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID sessionId;
    private final PlayerView player;

    /**
     * Creates the client-facing information for an established session.
     *
     * @param sessionId the opaque identifier used for later authenticated operations
     * @param player the safe server-owned player description
     * @throws NullPointerException if either argument is null
     */
    public SessionInfo(UUID sessionId, PlayerView player) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.player = Objects.requireNonNull(player, "player");
    }

    /**
     * Returns the opaque identifier used by session-based remote operations.
     *
     * @return the session identifier
     */
    public UUID getSessionId() {
        return sessionId;
    }

    /**
     * Returns the safe player information associated with the session.
     *
     * @return the player view
     */
    public PlayerView getPlayer() {
        return player;
    }
}
