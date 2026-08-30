package io.github.tomerg12.fleetlink.server.rating;

import java.util.Objects;
import java.util.UUID;

/**
 * Captures one registered participant's immutable live transition for a rated game.
 */
public final class PlayerRatingAdjustment {
    private final UUID playerId;
    private final int ratingBefore;
    private final int ratingAfter;
    private final long ratingRevisionBefore;

    /**
     * Creates one validated player rating transition.
     *
     * @param playerId registered player identifier
     * @param ratingBefore rating captured by the game
     * @param ratingAfter calculated and clamped rating after the game
     * @param ratingRevisionBefore live revision before this transition
     */
    public PlayerRatingAdjustment(UUID playerId, int ratingBefore, int ratingAfter,
                                  long ratingRevisionBefore) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        if (ratingBefore < 0 || ratingAfter < 0) {
            throw new IllegalArgumentException("ratings must not be negative");
        }
        if (ratingRevisionBefore < 0) {
            throw new IllegalArgumentException("ratingRevisionBefore must not be negative");
        }
        this.ratingBefore = ratingBefore;
        this.ratingAfter = ratingAfter;
        this.ratingRevisionBefore = ratingRevisionBefore;
    }

    /**
     * Returns the registered player identifier.
     *
     * @return player identifier
     */
    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * Returns the exact rating base captured by the game.
     *
     * @return rating before the transition
     */
    public int getRatingBefore() {
        return ratingBefore;
    }

    /**
     * Returns the rounded and clamped live rating after the transition.
     *
     * @return rating after the transition
     */
    public int getRatingAfter() {
        return ratingAfter;
    }

    /**
     * Returns the actual transition delta after minimum-rating clamping.
     *
     * @return stored rating delta
     */
    public int getRatingDelta() {
        return ratingAfter - ratingBefore;
    }

    /**
     * Returns the durable revision expected before this transition is persisted.
     *
     * @return rating revision before the game
     */
    public long getRatingRevisionBefore() {
        return ratingRevisionBefore;
    }

    /**
     * Compares every immutable transition field.
     *
     * @param other adjustment to compare
     * @return true when both adjustments represent the same transition
     */
    public boolean equivalentTo(PlayerRatingAdjustment other) {
        return other != null && playerId.equals(other.playerId)
                && ratingBefore == other.ratingBefore && ratingAfter == other.ratingAfter
                && ratingRevisionBefore == other.ratingRevisionBefore;
    }
}
