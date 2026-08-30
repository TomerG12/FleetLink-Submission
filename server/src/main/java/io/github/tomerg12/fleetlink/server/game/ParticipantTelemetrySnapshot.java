package io.github.tomerg12.fleetlink.server.game;

import java.util.Objects;
import java.util.UUID;

/**
 * Captures immutable authoritative match telemetry for one game participant.
 */
public final class ParticipantTelemetrySnapshot {
    private final UUID playerId;
    private final int shotsFired;
    private final int hits;
    private final int shipsSunk;
    private final int turnsTaken;

    /**
     * Creates a validated telemetry snapshot.
     *
     * @param playerId participant identifier
     * @param shotsFired accepted shots fired by the participant
     * @param hits accepted shots that hit a ship
     * @param shipsSunk accepted shots that sank a ship
     * @param turnsTaken accepted shots plus expired Battle turns
     */
    public ParticipantTelemetrySnapshot(UUID playerId, int shotsFired, int hits,
                                        int shipsSunk, int turnsTaken) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        if (shotsFired < 0 || hits < 0 || shipsSunk < 0 || turnsTaken < 0) {
            throw new IllegalArgumentException("telemetry counters must be nonnegative");
        }
        if (hits > shotsFired) {
            throw new IllegalArgumentException("hits cannot exceed shots fired");
        }
        if (shipsSunk > hits) {
            throw new IllegalArgumentException("ships sunk cannot exceed hits");
        }
        if (turnsTaken < shotsFired) {
            throw new IllegalArgumentException("turns taken cannot be less than shots fired");
        }
        this.shotsFired = shotsFired;
        this.hits = hits;
        this.shipsSunk = shipsSunk;
        this.turnsTaken = turnsTaken;
    }

    /**
     * Returns the participant identifier.
     *
     * @return participant identifier
     */
    public UUID getPlayerId() {
        return playerId;
    }

    /**
     * Returns the number of accepted shots.
     *
     * @return accepted shot count
     */
    public int getShotsFired() {
        return shotsFired;
    }

    /**
     * Returns the number of accepted hits, including sinking shots.
     *
     * @return hit count
     */
    public int getHits() {
        return hits;
    }

    /**
     * Returns the number of ships sunk by accepted shots.
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
}
