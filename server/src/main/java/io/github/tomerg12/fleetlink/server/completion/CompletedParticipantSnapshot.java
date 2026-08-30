package io.github.tomerg12.fleetlink.server.completion;

import io.github.tomerg12.fleetlink.server.persistence.ParticipantResult;
import java.util.Objects;
import java.util.UUID;

/**
 * Captures one participant's immutable identity and result at a terminal game transition.
 */
public final class CompletedParticipantSnapshot {

    private final UUID playerId;
    private final String displayName;
    private final boolean guest;
    private final int ratingAtMatch;
    private final ParticipantResult result;
    private final int shotsFired;
    private final int hits;
    private final int shipsSunk;
    private final int turnsTaken;
    private final int ratingDelta;
    private final Long ratingRevisionBefore;

    /**
     * Creates a validated participant completion snapshot.
     *
     * @param playerId stable registered or temporary guest identifier
     * @param displayName display name captured from the game participant
     * @param guest whether the participant is a guest
     * @param ratingAtMatch authoritative rating captured at match creation
     * @param result terminal WIN or LOSS assignment
     * @param shotsFired accepted shots fired during the match
     * @param hits accepted hits during the match
     * @param shipsSunk ships sunk during the match
     * @param turnsTaken accepted shots plus expired Battle turns
     * @param ratingDelta signed rating change, zero for unrated participants
     * @param ratingRevisionBefore live revision before a rated transition, otherwise null
     */
    public CompletedParticipantSnapshot(UUID playerId, String displayName, boolean guest,
                                        int ratingAtMatch, ParticipantResult result,
                                        int shotsFired, int hits, int shipsSunk, int turnsTaken,
                                        int ratingDelta, Long ratingRevisionBefore) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (guest && ratingAtMatch != 1000) {
            throw new IllegalArgumentException("guest completion rating must be 1000");
        }
        this.guest = guest;
        if (ratingAtMatch < 0) {
            throw new IllegalArgumentException("ratingAtMatch must be nonnegative");
        }
        this.ratingAtMatch = ratingAtMatch;
        this.result = Objects.requireNonNull(result, "result");
        if (shotsFired < 0 || hits < 0 || shipsSunk < 0 || turnsTaken < 0) {
            throw new IllegalArgumentException("telemetry counters must be nonnegative");
        }
        if (hits > shotsFired || shipsSunk > hits || turnsTaken < shotsFired) {
            throw new IllegalArgumentException("telemetry counters are inconsistent");
        }
        if (ratingRevisionBefore != null && ratingRevisionBefore < 0) {
            throw new IllegalArgumentException("ratingRevisionBefore must be nonnegative");
        }
        if (guest && (ratingDelta != 0 || ratingRevisionBefore != null)) {
            throw new IllegalArgumentException("guest completion cannot contain a rating change");
        }
        long ratingAfter = (long) ratingAtMatch + ratingDelta;
        if (ratingAfter < 0 || ratingAfter > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("rating transition exceeds the supported range");
        }
        this.shotsFired = shotsFired;
        this.hits = hits;
        this.shipsSunk = shipsSunk;
        this.turnsTaken = turnsTaken;
        this.ratingDelta = ratingDelta;
        this.ratingRevisionBefore = ratingRevisionBefore;
    }

    /**
     * Returns the participant identifier captured by the game.
     *
     * @return player identifier
     */
    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * Returns the display name captured at match time.
     *
     * @return display name snapshot
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Reports whether this participant used a temporary guest identity.
     *
     * @return true for a guest
     */
    public boolean isGuest() {
        return guest;
    }

    /**
     * Returns the rating captured when the game was created.
     *
     * @return rating at match time
     */
    public int getRatingAtMatch() {
        return ratingAtMatch;
    }

    /**
     * Returns the terminal result assigned by the authoritative game.
     *
     * @return WIN or LOSS
     */
    public ParticipantResult getResult() {
        return result;
    }

    /**
     * Returns accepted shots fired during the match.
     *
     * @return shot count
     */
    public int getShotsFired() {
        return shotsFired;
    }

    /**
     * Returns accepted hits during the match.
     *
     * @return hit count
     */
    public int getHits() {
        return hits;
    }

    /**
     * Returns ships sunk during the match.
     *
     * @return sunk ship count
     */
    public int getShipsSunk() {
        return shipsSunk;
    }

    /**
     * Returns accepted shots plus expired Battle turns.
     *
     * @return semantic turn count
     */
    public int getTurnsTaken() {
        return turnsTaken;
    }

    /**
     * Returns the signed rating adjustment assigned at terminal transition.
     *
     * @return signed rating delta
     */
    public int getRatingDelta() {
        return ratingDelta;
    }

    /**
     * Returns the registered player's revision before a rated transition.
     *
     * @return prior revision, or null for unrated participants
     */
    public Long getRatingRevisionBefore() {
        return ratingRevisionBefore;
    }

    /**
     * Compares every authoritative persisted participant field.
     *
     * @param other participant snapshot to compare
     * @return true when all persisted invariants are equivalent
     */
    public boolean equivalentTo(CompletedParticipantSnapshot other) {
        return other != null
                && playerId.equals(other.playerId)
                && displayName.equals(other.displayName)
                && guest == other.guest
                && ratingAtMatch == other.ratingAtMatch
                && result == other.result
                && shotsFired == other.shotsFired
                && hits == other.hits
                && shipsSunk == other.shipsSunk
                && turnsTaken == other.turnsTaken
                && ratingDelta == other.ratingDelta
                && Objects.equals(ratingRevisionBefore, other.ratingRevisionBefore);
    }
}
