package io.github.tomerg12.fleetlink.server.persistence;

import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stores one terminal game summary and owns its two participant rows.
 */
@Entity
@Table(name = "completed_games")
public class CompletedGameEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", nullable = false, updatable = false, length = 32)
    private GameEndReason endReason;

    @OneToMany(mappedBy = "game", cascade = CascadeType.PERSIST)
    private List<GameParticipantEntity> participants = new ArrayList<>();

    /**
     * Creates an empty instance for JPA materialization.
     */
    protected CompletedGameEntity() {
    }

    /**
     * Creates a completed-game row before its validated participants are attached.
     *
     * @param id authoritative GameSession identifier
     * @param startedAt authoritative placement activation timestamp
     * @param completedAt terminal transition timestamp
     * @param endReason authoritative end reason
     * @throws IllegalArgumentException if completion precedes activation
     */
    public CompletedGameEntity(UUID id, Instant startedAt, Instant completedAt,
                               GameEndReason endReason) {
        this.id = Objects.requireNonNull(id, "id");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not precede startedAt");
        }
        this.endReason = Objects.requireNonNull(endReason, "endReason");
    }

    /**
     * Attaches one participant to this aggregate and establishes the owning relationship.
     *
     * @param participant participant row to attach
     */
    public void addParticipant(GameParticipantEntity participant) {
        GameParticipantEntity required = Objects.requireNonNull(participant, "participant");
        required.attachTo(this);
        participants.add(required);
    }

    /**
     * Returns the authoritative game identifier.
     *
     * @return game identifier
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the terminal transition timestamp.
     *
     * @return completion time
     */
    public Instant getCompletedAt() {
        return completedAt;
    }

    /**
     * Returns the authoritative placement activation timestamp.
     *
     * @return match start time
     */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * Returns the authoritative terminal reason.
     *
     * @return game end reason
     */
    public GameEndReason getEndReason() {
        return endReason;
    }

    /**
     * Returns an unmodifiable view of the participant rows.
     *
     * @return participant rows
     */
    public List<GameParticipantEntity> getParticipants() {
        return Collections.unmodifiableList(participants);
    }
}
