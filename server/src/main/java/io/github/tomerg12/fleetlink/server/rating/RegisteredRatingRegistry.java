package io.github.tomerg12.fleetlink.server.rating;

import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns process-live registered ratings and revisions between terminal mutation and durable commit.
 * All state is guarded by one short in-memory critical section. No database or callback operation
 * occurs while that section is held.
 */
public final class RegisteredRatingRegistry {
    private final Object lock = new Object();
    private final Map<UUID, RatingState> ratings = new HashMap<>();
    private final Map<UUID, RatedGameAdjustment> adjustmentsByGame = new HashMap<>();

    /**
     * Creates an empty process-live registry. Durable state seeds it after startup.
     */
    public RegisteredRatingRegistry() {
    }

    /**
     * Seeds one registered player only when the process has not already loaded that identity.
     * A later login cannot overwrite a newer live value with stale durable state.
     *
     * @param playerId registered player identifier
     * @param rating durable rating observed during registration or first login
     * @param ratingRevision durable revision observed with the rating
     * @return existing or newly seeded immutable live state
     */
    public RatingState seedIfAbsent(UUID playerId, int rating, long ratingRevision) {
        Objects.requireNonNull(playerId, "playerId");
        RatingState requested = new RatingState(rating, ratingRevision);
        synchronized (lock) {
            return ratings.computeIfAbsent(playerId, ignored -> requested);
        }
    }

    /**
     * Resolves the current process-live state for a registered player.
     *
     * @param playerId registered player identifier
     * @return current immutable live state
     * @throws RatingIntegrityException if registration or login did not seed the identity
     */
    public RatingState current(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            RatingState state = ratings.get(playerId);
            if (state == null) {
                throw new RatingIntegrityException(
                        "Registered rating is not loaded for player " + playerId);
            }
            return state;
        }
    }

    /**
     * Rebuilds a safe player view with the authoritative current matchmaking rating.
     * Guests always use the fixed temporary rating and never enter the registry.
     *
     * @param sessionPlayer server-owned player view from the active session
     * @return refreshed registered view or fixed-rating guest view
     */
    public PlayerView authoritativeView(PlayerView sessionPlayer) {
        Objects.requireNonNull(sessionPlayer, "sessionPlayer");
        int rating = sessionPlayer.isGuest()
                ? EloRatingCalculator.GUEST_RATING
                : current(sessionPlayer.getPlayerId()).getRating();
        return new PlayerView(sessionPlayer.getPlayerId(), sessionPlayer.getDisplayName(),
                rating, sessionPlayer.isGuest());
    }

    /**
     * Atomically applies both registered transitions for one decisive rated game.
     * Equivalent repeats return the original immutable adjustment. Conflicting repeats and stale
     * game rating bases fail without changing either live player.
     *
     * @param gameId authoritative game identifier
     * @param first first registered participant captured by the game
     * @param second second registered participant captured by the game
     * @param winnerPlayerId authoritative winner identifier
     * @return immutable two-player adjustment
     * @throws RatingIntegrityException for missing, stale, or conflicting live state
     */
    public RatedGameAdjustment applyRatedGame(UUID gameId, PlayerView first, PlayerView second,
                                              UUID winnerPlayerId) {
        Objects.requireNonNull(gameId, "gameId");
        validateParticipant(first, "first");
        validateParticipant(second, "second");
        Objects.requireNonNull(winnerPlayerId, "winnerPlayerId");
        if (first.getPlayerId().equals(second.getPlayerId())) {
            throw new RatingIntegrityException("Rated participants must be distinct");
        }
        if (!winnerPlayerId.equals(first.getPlayerId())
                && !winnerPlayerId.equals(second.getPlayerId())) {
            throw new RatingIntegrityException("Rated winner must be a game participant");
        }
        synchronized (lock) {
            RatedGameAdjustment existing = adjustmentsByGame.get(gameId);
            if (existing != null) {
                if (!existing.matchesRequest(first, second, winnerPlayerId)) {
                    throw new RatingIntegrityException(
                            "Conflicting live rating application for game " + gameId);
                }
                return existing;
            }
            RatingState firstState = requireMatchingState(first);
            RatingState secondState = requireMatchingState(second);
            int firstAfter = EloRatingCalculator.ratingAfter(firstState.rating,
                    secondState.rating, winnerPlayerId.equals(first.getPlayerId()));
            int secondAfter = EloRatingCalculator.ratingAfter(secondState.rating,
                    firstState.rating, winnerPlayerId.equals(second.getPlayerId()));
            PlayerRatingAdjustment firstAdjustment = new PlayerRatingAdjustment(
                    first.getPlayerId(), firstState.rating, firstAfter, firstState.revision);
            PlayerRatingAdjustment secondAdjustment = new PlayerRatingAdjustment(
                    second.getPlayerId(), secondState.rating, secondAfter, secondState.revision);
            RatedGameAdjustment adjustment = new RatedGameAdjustment(gameId, winnerPlayerId,
                    List.of(firstAdjustment, secondAdjustment));
            RatingState firstNext = firstState.advance(firstAfter);
            RatingState secondNext = secondState.advance(secondAfter);
            ratings.put(first.getPlayerId(), firstNext);
            ratings.put(second.getPlayerId(), secondNext);
            adjustmentsByGame.put(gameId, adjustment);
            return adjustment;
        }
    }

    /**
     * Validates one required registered participant before entering the critical section.
     *
     * @param player participant to validate
     * @param name argument name used in failures
     */
    private static void validateParticipant(PlayerView player, String name) {
        Objects.requireNonNull(player, name);
        if (player.isGuest()) {
            throw new RatingIntegrityException("Rated game cannot contain a guest");
        }
    }

    /**
     * Loads one live state and verifies the game captured that exact rating base.
     * This method is called only while holding the registry lock.
     *
     * @param player registered participant captured by the game
     * @return matching current live state
     */
    private RatingState requireMatchingState(PlayerView player) {
        RatingState state = ratings.get(player.getPlayerId());
        if (state == null) {
            throw new RatingIntegrityException(
                    "Registered rating is not loaded for player " + player.getPlayerId());
        }
        if (state.rating != player.getRating()) {
            throw new RatingIntegrityException(
                    "Game rating base does not match live rating for player "
                            + player.getPlayerId());
        }
        return state;
    }

    /**
     * Represents one immutable process-live registered rating and revision pair.
     */
    public static final class RatingState {
        private final int rating;
        private final long revision;

        /**
         * Creates a validated immutable live state.
         *
         * @param rating current registered rating
         * @param revision current monotonic rating revision
         */
        private RatingState(int rating, long revision) {
            if (rating < 0) {
                throw new IllegalArgumentException("rating must not be negative");
            }
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            this.rating = rating;
            this.revision = revision;
        }

        /**
         * Returns the current process-live registered rating.
         *
         * @return current rating
         */
        public int getRating() {
            return rating;
        }

        /**
         * Returns the current process-live rating revision.
         *
         * @return current revision
         */
        public long getRevision() {
            return revision;
        }

        /**
         * Creates the next immutable live state after one rated transition.
         *
         * @param nextRating calculated next rating
         * @return state with the supplied rating and revision incremented once
         */
        private RatingState advance(int nextRating) {
            if (revision == Long.MAX_VALUE) {
                throw new RatingIntegrityException("Rating revision overflow");
            }
            return new RatingState(nextRating, revision + 1);
        }
    }
}
