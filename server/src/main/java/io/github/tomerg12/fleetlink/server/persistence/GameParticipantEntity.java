package io.github.tomerg12.fleetlink.server.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;

/**
 * Stores one immutable participant snapshot inside a completed-game aggregate.
 */
@Entity
@Table(name = "game_participants", uniqueConstraints = {
        @UniqueConstraint(name = "uk_game_participant_identity",
                columnNames = {"game_id", "player_id_snapshot"}),
        @UniqueConstraint(name = "uk_game_participant_result",
                columnNames = {"game_id", "result"})
})
public class GameParticipantEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false, updatable = false)
    private CompletedGameEntity game;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", updatable = false)
    private PlayerEntity player;

    @Column(name = "player_id_snapshot", nullable = false, updatable = false)
    private UUID playerIdSnapshot;

    @Lob
    @Column(name = "display_name_snapshot", nullable = false, updatable = false)
    private String displayNameSnapshot;

    @Column(name = "guest", nullable = false, updatable = false)
    private boolean guest;

    @Column(name = "rating_at_match", nullable = false, updatable = false)
    private int ratingAtMatch;

    @Column(name = "shots_fired", nullable = false, updatable = false)
    private int shotsFired;

    @Column(name = "hits", nullable = false, updatable = false)
    private int hits;

    @Column(name = "ships_sunk", nullable = false, updatable = false)
    private int shipsSunk;

    @Column(name = "turns_taken", nullable = false, updatable = false)
    private int turnsTaken;

    @Column(name = "rating_delta", nullable = false, updatable = false)
    private int ratingDelta;

    @Column(name = "rating_revision_before", updatable = false)
    private Long ratingRevisionBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, updatable = false, length = 8)
    private ParticipantResult result;

    /**
     * Creates an empty instance for JPA materialization.
     */
    protected GameParticipantEntity() {
    }

    /**
     * Creates one participant row from a validated terminal snapshot.
     *
     * @param id independent participant row identifier
     * @param player persistent player link, or null for a guest
     * @param playerIdSnapshot stable or temporary player identifier at match time
     * @param displayNameSnapshot display name at match time
     * @param guest whether the participant was a guest
     * @param ratingAtMatch authoritative rating at match time
     * @param result terminal participant result
     * @param shotsFired accepted shots fired during the match
     * @param hits accepted hits during the match
     * @param shipsSunk ships sunk during the match
     * @param turnsTaken accepted shots plus expired Battle turns
     * @param ratingDelta signed rating transition, zero for unrated games
     * @param ratingRevisionBefore revision before a rated transition, otherwise null
     * @throws IllegalArgumentException if identity, link, telemetry, or rating data is inconsistent
     */
    public GameParticipantEntity(UUID id, PlayerEntity player, UUID playerIdSnapshot,
                                 String displayNameSnapshot, boolean guest, int ratingAtMatch,
                                 ParticipantResult result, int shotsFired, int hits,
                                 int shipsSunk, int turnsTaken, int ratingDelta,
                                 Long ratingRevisionBefore) {
        this.id = Objects.requireNonNull(id, "id");
        this.player = player;
        this.playerIdSnapshot = Objects.requireNonNull(playerIdSnapshot, "playerIdSnapshot");
        this.displayNameSnapshot = Objects.requireNonNull(
                displayNameSnapshot, "displayNameSnapshot");
        this.guest = guest;
        this.ratingAtMatch = ratingAtMatch;
        this.result = Objects.requireNonNull(result, "result");
        this.shotsFired = shotsFired;
        this.hits = hits;
        this.shipsSunk = shipsSunk;
        this.turnsTaken = turnsTaken;
        this.ratingDelta = ratingDelta;
        this.ratingRevisionBefore = ratingRevisionBefore;
        if (guest == (player != null)) {
            throw new IllegalArgumentException(
                    "guest participants must not link a player and registered participants must");
        }
        if (!guest && !player.getId().equals(playerIdSnapshot)) {
            throw new IllegalArgumentException("registered player link must match snapshot identity");
        }
        if (ratingAtMatch < 0 || shotsFired < 0 || hits < 0 || shipsSunk < 0 || turnsTaken < 0
                || hits > shotsFired || shipsSunk > hits || turnsTaken < shotsFired) {
            throw new IllegalArgumentException("participant rating or telemetry is inconsistent");
        }
        if (ratingRevisionBefore != null && ratingRevisionBefore < 0) {
            throw new IllegalArgumentException("rating revision must be nonnegative");
        }
        if (guest && (ratingDelta != 0 || ratingRevisionBefore != null)) {
            throw new IllegalArgumentException("guest participant cannot contain a rating change");
        }
    }

    /**
     * Establishes the owning game relationship before aggregate persistence.
     *
     * @param owner completed game that owns this row
     */
    void attachTo(CompletedGameEntity owner) {
        if (game != null && game != owner) {
            throw new IllegalStateException("participant already belongs to another game");
        }
        game = Objects.requireNonNull(owner, "owner");
    }

    /**
     * Returns the participant row identifier.
     *
     * @return row identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the linked persistent player or null for a guest.
     *
     * @return persistent player link or null
     */
    public PlayerEntity getPlayer() {
        return player;
    }

    /**
     * Returns the participant identity captured at match time.
     *
     * @return snapshotted player identifier
     */
    public UUID getPlayerIdSnapshot() {
        return playerIdSnapshot;
    }

    /**
     * Returns the display name captured at match time.
     *
     * @return snapshotted display name
     */
    public String getDisplayNameSnapshot() {
        return displayNameSnapshot;
    }

    /**
     * Reports whether this row describes a guest participant.
     *
     * @return true for a guest participant
     */
    public boolean isGuest() {
        return guest;
    }

    /**
     * Returns the rating captured when matchmaking created the game.
     *
     * @return rating at match time
     */
    public int getRatingAtMatch() {
        return ratingAtMatch;
    }

    /**
     * Returns the terminal participant result.
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
     * Returns the signed rating transition persisted with the match.
     *
     * @return rating delta
     */
    public int getRatingDelta() {
        return ratingDelta;
    }

    /**
     * Returns the prior durable rating revision for a rated transition.
     *
     * @return prior revision, or null for an unrated game
     */
    public Long getRatingRevisionBefore() {
        return ratingRevisionBefore;
    }
}
