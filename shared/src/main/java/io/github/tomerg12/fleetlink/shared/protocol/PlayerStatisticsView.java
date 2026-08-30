package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Carries committed personal aggregates, a live rating, and one bounded history page.
 */
public final class PlayerStatisticsView implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Current process-live rating for the registered player. */
    private final int currentRating;
    /** Total committed completed games for the registered player. */
    private final long totalGames;
    /** Total committed wins for the registered player. */
    private final long wins;
    /** Total committed losses for the registered player. */
    private final long losses;
    /** Derived wins divided by total games, with zero for no games. */
    private final double winRate;
    /** Total committed opponent ships sunk by the registered player. */
    private final long shipsSunk;
    /** Total committed accepted shots fired by the registered player. */
    private final long totalShots;
    /** Total committed hits made by the registered player. */
    private final long hits;
    /** Derived hits divided by accepted shots, with zero for no shots. */
    private final double accuracy;
    /** Derived hits divided by completed games, with zero for no games. */
    private final double averageHitsPerGame;
    /** Immutable bounded match-history page in deterministic order. */
    private final List<MatchHistoryEntryView> history;
    /** Zero-based database offset used for this history page. */
    private final int historyOffset;
    /** Number of history entries included in this response. */
    private final int returnedCount;
    /** Whether another committed history row follows this page. */
    private final boolean hasMore;

    /**
     * Creates a statistics snapshot and derives every ratio and returned-page count.
     *
     * @param currentRating current process-live registered rating
     * @param totalGames committed completed-game count
     * @param wins committed wins
     * @param losses committed losses
     * @param shipsSunk committed ships sunk
     * @param totalShots committed accepted shots
     * @param hits committed hits
     * @param history requested committed history page
     * @param historyOffset zero-based database page offset
     * @param hasMore whether another committed history row follows this page
     * @throws NullPointerException if history or an entry is null
     * @throws IllegalArgumentException if rating, counts, or pagination are inconsistent
     */
    public PlayerStatisticsView(int currentRating, long totalGames, long wins, long losses,
                                long shipsSunk, long totalShots, long hits,
                                List<MatchHistoryEntryView> history, int historyOffset,
                                boolean hasMore) {
        if (currentRating < 0 || totalGames < 0 || wins < 0 || losses < 0
                || shipsSunk < 0 || totalShots < 0 || hits < 0 || historyOffset < 0
                || wins > totalGames || losses != totalGames - wins
                || hits > totalShots || shipsSunk > hits) {
            throw new IllegalArgumentException("statistics counts are inconsistent");
        }
        this.history = List.copyOf(Objects.requireNonNull(history, "history"));
        if (hasMore && this.history.isEmpty()) {
            throw new IllegalArgumentException("hasMore requires a returned history row");
        }
        this.currentRating = currentRating;
        this.totalGames = totalGames;
        this.wins = wins;
        this.losses = losses;
        this.winRate = ratio(wins, totalGames);
        this.shipsSunk = shipsSunk;
        this.totalShots = totalShots;
        this.hits = hits;
        this.accuracy = ratio(hits, totalShots);
        this.averageHitsPerGame = ratio(hits, totalGames);
        this.historyOffset = historyOffset;
        this.returnedCount = this.history.size();
        this.hasMore = hasMore;
    }

    /**
     * Returns the process-live registered rating observed for this request.
     *
     * @return nonnegative current rating
     */
    public int getCurrentRating() {
        return currentRating;
    }

    /**
     * Returns committed completed games.
     *
     * @return total game count
     */
    public long getTotalGames() {
        return totalGames;
    }

    /**
     * Returns committed wins.
     *
     * @return win count
     */
    public long getWins() {
        return wins;
    }

    /**
     * Returns committed losses.
     *
     * @return loss count
     */
    public long getLosses() {
        return losses;
    }

    /**
     * Returns wins divided by games, or zero when no games are committed.
     *
     * @return win rate from 0.0 through 1.0
     */
    public double getWinRate() {
        return winRate;
    }

    /**
     * Returns committed ships sunk.
     *
     * @return sunk ship count
     */
    public long getShipsSunk() {
        return shipsSunk;
    }

    /**
     * Returns committed accepted shots.
     *
     * @return accepted shot count
     */
    public long getTotalShots() {
        return totalShots;
    }

    /**
     * Returns committed hits.
     *
     * @return hit count
     */
    public long getHits() {
        return hits;
    }

    /**
     * Returns hits divided by accepted shots, or zero when no shots are committed.
     *
     * @return accuracy from 0.0 through 1.0
     */
    public double getAccuracy() {
        return accuracy;
    }

    /**
     * Returns hits divided by games, or zero when no games are committed.
     *
     * @return average hits per completed game
     */
    public double getAverageHitsPerGame() {
        return averageHitsPerGame;
    }

    /**
     * Returns an immutable defensive history page.
     *
     * @return ordered history entries
     */
    public List<MatchHistoryEntryView> getHistory() {
        return history;
    }

    /**
     * Returns the requested zero-based history offset.
     *
     * @return history offset
     */
    public int getHistoryOffset() {
        return historyOffset;
    }

    /**
     * Returns the number of entries in this page.
     *
     * @return returned history count
     */
    public int getReturnedCount() {
        return returnedCount;
    }

    /**
     * Reports whether another committed history row follows this page.
     *
     * @return true when another page is available
     */
    public boolean hasMore() {
        return hasMore;
    }

    /**
     * Divides one nonnegative count while defining an empty denominator as zero.
     *
     * @param numerator nonnegative numerator
     * @param denominator nonnegative denominator
     * @return ratio or 0.0 when denominator is zero
     */
    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
