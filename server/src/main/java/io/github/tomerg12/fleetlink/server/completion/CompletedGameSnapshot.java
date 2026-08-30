package io.github.tomerg12.fleetlink.server.completion;

import io.github.tomerg12.fleetlink.server.persistence.ParticipantResult;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Captures the persistence-agnostic authoritative summary of one terminal game transition.
 */
public final class CompletedGameSnapshot {

    private final UUID gameId;
    private final Instant startedAt;
    private final Instant completedAt;
    private final GameEndReason endReason;
    private final List<CompletedParticipantSnapshot> participants;

    /**
     * Creates and validates a complete two-participant terminal aggregate.
     *
     * @param gameId authoritative GameSession identifier
     * @param startedAt placement activation timestamp, normalized to database microsecond precision
     * @param completedAt terminal transition timestamp, normalized to database microsecond precision
     * @param endReason authoritative end reason
     * @param winnerId authoritative winner identifier used to verify result consistency
     * @param participants exactly two distinct participant snapshots
     */
    public CompletedGameSnapshot(UUID gameId, Instant startedAt, Instant completedAt,
                                 GameEndReason endReason,
                                 UUID winnerId,
                                 List<CompletedParticipantSnapshot> participants) {
        this.gameId = Objects.requireNonNull(gameId, "gameId");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt")
                .truncatedTo(ChronoUnit.MICROS);
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt")
                .truncatedTo(ChronoUnit.MICROS);
        if (this.completedAt.isBefore(this.startedAt)) {
            throw new IllegalArgumentException("completedAt must not precede startedAt");
        }
        this.endReason = Objects.requireNonNull(endReason, "endReason");
        Objects.requireNonNull(winnerId, "winnerId");
        this.participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        validate(winnerId);
    }

    /**
     * Returns the database idempotency key shared with the GameSession.
     *
     * @return game identifier
     */
    public UUID getGameId() {
        return gameId;
    }

    /**
     * Returns the placement activation timestamp.
     *
     * @return match start time
     */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * Returns the captured terminal transition timestamp.
     *
     * @return completion time
     */
    public Instant getCompletedAt() {
        return completedAt;
    }

    /**
     * Returns the authoritative reason the game ended.
     *
     * @return end reason
     */
    public GameEndReason getEndReason() {
        return endReason;
    }

    /**
     * Returns the two immutable participant snapshots.
     *
     * @return participants in stable game order
     */
    public List<CompletedParticipantSnapshot> getParticipants() {
        return participants;
    }

    /**
     * Reports whether this game contains persistent history eligible for recording.
     *
     * @return true when at least one participant is registered
     */
    public boolean hasRegisteredParticipant() {
        return participants.stream().anyMatch(participant -> !participant.isGuest());
    }

    /**
     * Compares every persisted authoritative completion invariant without relying on row order.
     *
     * @param other completion snapshot to compare
     * @return true when both aggregates contain equivalent required data
     */
    public boolean equivalentTo(CompletedGameSnapshot other) {
        if (other == null || !gameId.equals(other.gameId)
                || !startedAt.equals(other.startedAt)
                || !completedAt.equals(other.completedAt) || endReason != other.endReason) {
            return false;
        }
        return participants.stream().allMatch(participant -> other.participants.stream()
                .anyMatch(participant::equivalentTo));
    }

    /**
     * Enforces cardinality, identity, result, and winner consistency before persistence.
     *
     * @param winnerId authoritative winner identifier
     */
    private void validate(UUID winnerId) {
        if (participants.size() != 2) {
            throw new IllegalArgumentException("completed game must contain exactly two participants");
        }
        Set<UUID> identities = participants.stream()
                .map(CompletedParticipantSnapshot::getPlayerId)
                .collect(Collectors.toUnmodifiableSet());
        if (identities.size() != 2) {
            throw new IllegalArgumentException("completed participants must have distinct identities");
        }
        long wins = participants.stream()
                .filter(participant -> participant.getResult() == ParticipantResult.WIN).count();
        long losses = participants.stream()
                .filter(participant -> participant.getResult() == ParticipantResult.LOSS).count();
        if (wins != 1 || losses != 1) {
            throw new IllegalArgumentException("completed game requires exactly one WIN and one LOSS");
        }
        CompletedParticipantSnapshot winner = participants.stream()
                .filter(participant -> participant.getResult() == ParticipantResult.WIN)
                .findFirst().orElseThrow();
        if (!winner.getPlayerId().equals(winnerId)) {
            throw new IllegalArgumentException("winner identity must match the WIN participant");
        }
        boolean rated = participants.stream().allMatch(participant -> !participant.isGuest())
                && endReason != GameEndReason.NO_CONTEST;
        for (CompletedParticipantSnapshot participant : participants) {
            if (rated != (participant.getRatingRevisionBefore() != null)) {
                throw new IllegalArgumentException(
                        "rating revision presence must match rated-game eligibility");
            }
            if (!rated && participant.getRatingDelta() != 0) {
                throw new IllegalArgumentException("unrated completion must have zero rating delta");
            }
        }
    }
}
