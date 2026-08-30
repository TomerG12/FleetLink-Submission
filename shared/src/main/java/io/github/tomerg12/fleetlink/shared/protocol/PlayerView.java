package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Carries safe server-owned player information for display by a client.
 * Passwords and persistence entities are deliberately absent from this transport type.
 */
public final class PlayerView implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID playerId;
    private final String displayName;
    private final int rating;
    private final boolean guest;

    /**
     * Creates a safe player description produced by the server.
     *
     * @param playerId the stable player or temporary guest identifier
     * @param displayName the non-blank name shown to players
     * @param rating the authoritative rating supplied by the server
     * @param guest whether the player is using a temporary guest session
     * @throws NullPointerException if the identifier or display name is null
     * @throws IllegalArgumentException if the display name is blank
     */
    public PlayerView(UUID playerId, String displayName, int rating, boolean guest) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.displayName = requireDisplayName(displayName);
        this.rating = rating;
        this.guest = guest;
    }

    /**
     * Returns the safe player identifier.
     *
     * @return the player identifier
     */
    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * Returns the player-facing name.
     *
     * @return the non-blank display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns the rating calculated and supplied by the server.
     *
     * @return the authoritative rating
     */
    public int getRating() {
        return rating;
    }

    /**
     * Indicates whether this is a temporary guest identity.
     *
     * @return true for a guest player
     */
    public boolean isGuest() {
        return guest;
    }

    /**
     * Validates the player-facing name without imposing an undecided naming policy.
     *
     * @param displayName the name supplied by the server
     * @return the validated name
     * @throws NullPointerException if the name is null
     * @throws IllegalArgumentException if the name is blank
     */
    private static String requireDisplayName(String displayName) {
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        return displayName;
    }
}
