package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Carries one ranked registered-player row from the committed leaderboard.
 */
public final class LeaderboardEntryView implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** One-based sequential position in the returned leaderboard. */
    private final int rank;
    /** Case-preserving registered username intended for display. */
    private final String username;
    /** Committed persistent rating used for leaderboard ordering. */
    private final int rating;
    /** Number of committed completed games for this account. */
    private final long gamesPlayed;
    /** Number of committed wins for this account. */
    private final long wins;

    /**
     * Creates one validated sequential leaderboard entry.
     *
     * @param rank one-based sequential position
     * @param username case-preserving registered username
     * @param rating committed persistent rating
     * @param gamesPlayed committed completed-game count
     * @param wins committed win count
     * @throws NullPointerException if username is null
     * @throws IllegalArgumentException if rank, text, rating, or counts are invalid
     */
    public LeaderboardEntryView(int rank, String username, int rating,
                                long gamesPlayed, long wins) {
        this.username = Objects.requireNonNull(username, "username");
        if (rank <= 0 || username.isBlank() || rating < 0 || gamesPlayed < 0
                || wins < 0 || wins > gamesPlayed) {
            throw new IllegalArgumentException("leaderboard entry is inconsistent");
        }
        this.rank = rank;
        this.rating = rating;
        this.gamesPlayed = gamesPlayed;
        this.wins = wins;
    }

    /**
     * Returns the one-based sequential rank.
     *
     * @return positive rank
     */
    public int getRank() {
        return rank;
    }

    /**
     * Returns the case-preserving registered username.
     *
     * @return username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Returns the committed rating used for ordering.
     *
     * @return nonnegative rating
     */
    public int getRating() {
        return rating;
    }

    /**
     * Returns committed completed games.
     *
     * @return game count
     */
    public long getGamesPlayed() {
        return gamesPlayed;
    }

    /**
     * Returns committed wins.
     *
     * @return win count
     */
    public long getWins() {
        return wins;
    }
}
