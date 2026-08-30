package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Carries one immutable player-oriented completed-match history entry.
 */
public final class MatchHistoryEntryView implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Opponent display name captured in the persistent participant snapshot. */
    private final String opponentDisplayName;
    /** Whether the opponent used a temporary guest identity. */
    private final boolean opponentGuest;
    /** Registered player's WIN or LOSS outcome. */
    private final MatchOutcome outcome;
    /** Authoritative reason the completed game ended. */
    private final GameEndReason endReason;
    /** Accepted shots plus expired Battle turns consumed by the player. */
    private final long turnsTaken;
    /** Derived elapsed time between authoritative start and completion. */
    private final Duration duration;
    /** Derived hits divided by accepted shots, with zero for no shots. */
    private final double accuracy;
    /** Number of opponent ships sunk by the player. */
    private final long shipsSunk;
    /** Signed persistent rating change for this match. */
    private final int ratingDelta;
    /** Authoritative persistent completion timestamp. */
    private final Instant completedAt;

    /**
     * Creates a history entry and derives accuracy from authoritative shot counts.
     *
     * @param opponentDisplayName opponent name captured when the game completed
     * @param opponentGuest whether the opponent used a temporary guest identity
     * @param outcome registered player's WIN or LOSS result
     * @param endReason authoritative terminal reason
     * @param turnsTaken accepted shots plus expired Battle turns for the player
     * @param duration elapsed time from placement activation through completion
     * @param shotsFired accepted shots fired by the player
     * @param hits accepted hits made by the player
     * @param shipsSunk opponent ships sunk by the player
     * @param ratingDelta signed persistent rating change for this match
     * @param completedAt authoritative completion timestamp
     * @throws NullPointerException if an object value is null
     * @throws IllegalArgumentException if text, time, or count invariants are invalid
     */
    public MatchHistoryEntryView(String opponentDisplayName, boolean opponentGuest,
                                 MatchOutcome outcome, GameEndReason endReason,
                                 long turnsTaken, Duration duration, long shotsFired,
                                 long hits, long shipsSunk, int ratingDelta,
                                 Instant completedAt) {
        this.opponentDisplayName = requireDisplayName(opponentDisplayName);
        this.opponentGuest = opponentGuest;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.endReason = Objects.requireNonNull(endReason, "endReason");
        this.duration = Objects.requireNonNull(duration, "duration");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (turnsTaken < 0 || shotsFired < 0 || hits < 0 || shipsSunk < 0
                || hits > shotsFired || shipsSunk > hits) {
            throw new IllegalArgumentException("history telemetry is inconsistent");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        this.turnsTaken = turnsTaken;
        this.accuracy = ratio(hits, shotsFired);
        this.shipsSunk = shipsSunk;
        this.ratingDelta = ratingDelta;
    }

    /**
     * Returns the opponent name captured at completion.
     *
     * @return immutable opponent display name
     */
    public String getOpponentDisplayName() {
        return opponentDisplayName;
    }

    /**
     * Reports whether the opponent was a guest.
     *
     * @return true for a temporary guest opponent
     */
    public boolean isOpponentGuest() {
        return opponentGuest;
    }

    /**
     * Returns the registered player's match outcome.
     *
     * @return WIN or LOSS
     */
    public MatchOutcome getOutcome() {
        return outcome;
    }

    /**
     * Returns the authoritative reason the game ended.
     *
     * @return terminal game reason
     */
    public GameEndReason getEndReason() {
        return endReason;
    }

    /**
     * Returns accepted shots plus expired Battle turns for the player.
     *
     * @return nonnegative turn count
     */
    public long getTurnsTaken() {
        return turnsTaken;
    }

    /**
     * Returns the elapsed authoritative match duration.
     *
     * @return nonnegative duration
     */
    public Duration getDuration() {
        return duration;
    }

    /**
     * Returns hits divided by accepted shots, or zero when no shots were fired.
     *
     * @return accuracy from 0.0 through 1.0
     */
    public double getAccuracy() {
        return accuracy;
    }

    /**
     * Returns the number of opponent ships sunk by the player.
     *
     * @return nonnegative sunk ship count
     */
    public long getShipsSunk() {
        return shipsSunk;
    }

    /**
     * Returns the signed rating change assigned to this match.
     *
     * @return rating delta, or zero for an unrated match
     */
    public int getRatingDelta() {
        return ratingDelta;
    }

    /**
     * Returns the authoritative completion timestamp.
     *
     * @return completion time
     */
    public Instant getCompletedAt() {
        return completedAt;
    }

    /**
     * Rejects a missing or blank opponent snapshot name.
     *
     * @param displayName opponent snapshot name
     * @return validated display name
     */
    private static String requireDisplayName(String displayName) {
        Objects.requireNonNull(displayName, "opponentDisplayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("opponentDisplayName must not be blank");
        }
        return displayName;
    }

    /**
     * Divides one nonnegative count while defining an empty denominator as zero.
     *
     * @param numerator nonnegative numerator
     * @param denominator nonnegative denominator
     * @return ratio or 0.0 when the denominator is zero
     */
    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
