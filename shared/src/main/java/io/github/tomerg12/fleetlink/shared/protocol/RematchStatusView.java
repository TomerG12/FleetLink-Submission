package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Carries one player's authoritative view of a rematch negotiation.
 */
public final class RematchStatusView implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID completedGameId;
    private final PlayerView opponent;
    private final RematchState state;

    /**
     * Creates a player-specific rematch status for a completed game.
     *
     * @param completedGameId the game whose players may start a new game
     * @param opponent the other participant in the completed game
     * @param state the authoritative rematch state visible to the receiver
     * @throws NullPointerException if any argument is null
     */
    public RematchStatusView(UUID completedGameId, PlayerView opponent, RematchState state) {
        this.completedGameId = Objects.requireNonNull(completedGameId, "completedGameId");
        this.opponent = Objects.requireNonNull(opponent, "opponent");
        this.state = Objects.requireNonNull(state, "state");
    }

    /**
     * Returns the completed game associated with this negotiation.
     *
     * @return the completed game identifier
     */
    public UUID getCompletedGameId() {
        return completedGameId;
    }

    /**
     * Returns the safe opponent information.
     *
     * @return the opponent view
     */
    public PlayerView getOpponent() {
        return opponent;
    }

    /**
     * Returns the authoritative state from the receiving player's perspective.
     *
     * @return the rematch state
     */
    public RematchState getState() {
        return state;
    }
}
