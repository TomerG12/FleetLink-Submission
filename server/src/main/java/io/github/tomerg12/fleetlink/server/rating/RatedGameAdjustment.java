package io.github.tomerg12.fleetlink.server.rating;

import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Captures both atomic registered-player transitions and the request identity of one rated game.
 */
public final class RatedGameAdjustment {
    private final UUID gameId;
    private final UUID winnerPlayerId;
    private final List<PlayerRatingAdjustment> participants;

    /**
     * Creates a complete two-player rated adjustment.
     *
     * @param gameId authoritative game identifier used for idempotency
     * @param winnerPlayerId authoritative registered winner
     * @param participants exactly two distinct registered rating transitions
     */
    public RatedGameAdjustment(UUID gameId, UUID winnerPlayerId,
                               List<PlayerRatingAdjustment> participants) {
        this.gameId = Objects.requireNonNull(gameId, "gameId");
        this.winnerPlayerId = Objects.requireNonNull(winnerPlayerId, "winnerPlayerId");
        this.participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        if (this.participants.size() != 2
                || this.participants.get(0).getPlayerId()
                        .equals(this.participants.get(1).getPlayerId())) {
            throw new IllegalArgumentException(
                    "rated adjustment requires two distinct participants");
        }
        if (this.participants.stream().noneMatch(
                participant -> participant.getPlayerId().equals(winnerPlayerId))) {
            throw new IllegalArgumentException("rated winner must be a participant");
        }
    }

    /**
     * Returns the game idempotency key.
     *
     * @return game identifier
     */
    public UUID getGameId() {
        return gameId;
    }

    /**
     * Returns the authoritative winner identifier used to calculate the transitions.
     *
     * @return winner player identifier
     */
    public UUID getWinnerPlayerId() {
        return winnerPlayerId;
    }

    /**
     * Returns both immutable participant transitions.
     *
     * @return two adjustments in stable game order
     */
    public List<PlayerRatingAdjustment> getParticipants() {
        return participants;
    }

    /**
     * Finds one participant transition by registered player identifier.
     *
     * @param playerId registered player identifier
     * @return matching immutable adjustment
     * @throws RatingIntegrityException if the player is not part of this adjustment
     */
    public PlayerRatingAdjustment adjustmentFor(UUID playerId) {
        return participants.stream()
                .filter(participant -> participant.getPlayerId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> new RatingIntegrityException(
                        "Rated adjustment does not contain player " + playerId));
    }

    /**
     * Checks whether a repeated application request is equivalent to this immutable adjustment.
     * Participant order does not affect equivalence.
     *
     * @param first first registered game participant
     * @param second second registered game participant
     * @param requestedWinnerId winner identifier supplied by the repeated terminal state
     * @return true when game participants, rating bases, and winner are equivalent
     */
    public boolean matchesRequest(PlayerView first, PlayerView second, UUID requestedWinnerId) {
        if (!winnerPlayerId.equals(requestedWinnerId)) {
            return false;
        }
        return matchesPlayer(first) && matchesPlayer(second)
                && !first.getPlayerId().equals(second.getPlayerId());
    }

    /**
     * Checks whether one requested participant matches its stored identity and rating base.
     *
     * @param player requested registered participant
     * @return true when the stored adjustment has the same identity and rating base
     */
    private boolean matchesPlayer(PlayerView player) {
        if (player == null || player.isGuest()) {
            return false;
        }
        return participants.stream().anyMatch(participant ->
                participant.getPlayerId().equals(player.getPlayerId())
                        && participant.getRatingBefore() == player.getRating());
    }
}
