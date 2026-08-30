package io.github.tomerg12.fleetlink.shared.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies statistics DTO invariants, derived values, immutable copies, and serialization.
 */
class StatisticsProtocolTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-23T12:00:00Z");

    /**
     * Derives every personal ratio and page count from authoritative counts.
     */
    @Test
    void derivesPersonalRatiosAndPageMetadata() {
        PlayerStatisticsView view = new PlayerStatisticsView(1234, 4, 3, 1,
                5, 20, 8, List.of(history()), 2, true);

        assertEquals(1234, view.getCurrentRating());
        assertEquals(4, view.getTotalGames());
        assertEquals(3, view.getWins());
        assertEquals(1, view.getLosses());
        assertEquals(0.75, view.getWinRate());
        assertEquals(5, view.getShipsSunk());
        assertEquals(20, view.getTotalShots());
        assertEquals(8, view.getHits());
        assertEquals(0.4, view.getAccuracy());
        assertEquals(2.0, view.getAverageHitsPerGame());
        assertEquals(2, view.getHistoryOffset());
        assertEquals(1, view.getReturnedCount());
        assertTrue(view.hasMore());
    }

    /**
     * Defines all zero-denominator statistics as exactly zero.
     */
    @Test
    void derivesZeroRatiosForEmptyStatistics() {
        PlayerStatisticsView view = new PlayerStatisticsView(
                1000, 0, 0, 0, 0, 0, 0, List.of(), 50, false);

        assertEquals(0.0, view.getWinRate());
        assertEquals(0.0, view.getAccuracy());
        assertEquals(0.0, view.getAverageHitsPerGame());
        assertEquals(0, view.getReturnedCount());
        assertFalse(view.hasMore());
    }

    /**
     * Rejects negative and mutually inconsistent personal aggregate values.
     */
    @Test
    void rejectsInvalidPersonalStatistics() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerStatisticsView(
                -1, 0, 0, 0, 0, 0, 0, List.of(), 0, false));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStatisticsView(
                1000, 2, 2, 1, 0, 0, 0, List.of(), 0, false));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStatisticsView(
                1000, 1, 1, 0, 0, 1, 2, List.of(), 0, false));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStatisticsView(
                1000, 0, 0, 0, 0, 0, 0, List.of(), -1, false));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStatisticsView(
                1000, 0, 0, 0, 0, 0, 0, List.of(), 0, true));
        assertThrows(NullPointerException.class, () -> new PlayerStatisticsView(
                1000, 0, 0, 0, 0, 0, 0, null, 0, false));
    }

    /**
     * Protects personal history from caller mutation and rejects null entries.
     */
    @Test
    void defensivelyCopiesHistory() {
        List<MatchHistoryEntryView> mutable = new ArrayList<>();
        mutable.add(history());
        PlayerStatisticsView view = new PlayerStatisticsView(
                1000, 1, 1, 0, 1, 2, 1, mutable, 0, false);

        mutable.clear();
        assertEquals(1, view.getHistory().size());
        assertThrows(UnsupportedOperationException.class, () -> view.getHistory().clear());
        assertThrows(NullPointerException.class, () -> new PlayerStatisticsView(
                1000, 1, 1, 0, 1, 2, 1,
                java.util.Arrays.asList((MatchHistoryEntryView) null), 0, false));
    }

    /**
     * Derives history accuracy and validates history telemetry and required values.
     */
    @Test
    void validatesHistoryEntries() {
        MatchHistoryEntryView noShots = new MatchHistoryEntryView(
                "Guest", true, MatchOutcome.LOSS, GameEndReason.TIMEOUT,
                1, Duration.ZERO, 0, 0, 0, 0, COMPLETED_AT);

        assertEquals(0.0, noShots.getAccuracy());
        assertThrows(IllegalArgumentException.class, () -> new MatchHistoryEntryView(
                " ", false, MatchOutcome.WIN, GameEndReason.RESIGNATION,
                1, Duration.ZERO, 1, 1, 1, 16, COMPLETED_AT));
        assertThrows(IllegalArgumentException.class, () -> new MatchHistoryEntryView(
                "Opponent", false, MatchOutcome.WIN, GameEndReason.RESIGNATION,
                1, Duration.ofSeconds(-1), 1, 1, 1, 16, COMPLETED_AT));
        assertThrows(IllegalArgumentException.class, () -> new MatchHistoryEntryView(
                "Opponent", false, MatchOutcome.WIN, GameEndReason.RESIGNATION,
                1, Duration.ZERO, 1, 2, 1, 16, COMPLETED_AT));
        assertThrows(NullPointerException.class, () -> new MatchHistoryEntryView(
                "Opponent", false, null, GameEndReason.RESIGNATION,
                1, Duration.ZERO, 1, 1, 1, 16, COMPLETED_AT));
    }

    /**
     * Enforces leaderboard rank and aggregate invariants.
     */
    @Test
    void validatesLeaderboardEntries() {
        LeaderboardEntryView entry = new LeaderboardEntryView(1, "Ada", 1400, 10, 7);

        assertEquals(1, entry.getRank());
        assertEquals("Ada", entry.getUsername());
        assertEquals(1400, entry.getRating());
        assertEquals(10, entry.getGamesPlayed());
        assertEquals(7, entry.getWins());
        assertThrows(IllegalArgumentException.class,
                () -> new LeaderboardEntryView(0, "Ada", 1000, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new LeaderboardEntryView(1, "Ada", -1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new LeaderboardEntryView(1, "Ada", 1000, 1, 2));
        assertThrows(NullPointerException.class,
                () -> new LeaderboardEntryView(1, null, 1000, 0, 0));
    }

    /**
     * Preserves nested statistics and leaderboard payloads through Java serialization.
     *
     * @throws IOException if serialization cannot complete
     * @throws ClassNotFoundException if a nested protocol class cannot be loaded
     */
    @Test
    void nestedResultsSurviveSerialization() throws IOException, ClassNotFoundException {
        PlayerStatisticsResult statistics = SerializationTestSupport.roundTrip(
                PlayerStatisticsResult.success(new PlayerStatisticsView(
                        1016, 1, 1, 0, 1, 2, 1, List.of(history()), 0, false)),
                PlayerStatisticsResult.class);
        LeaderboardResult leaderboard = SerializationTestSupport.roundTrip(
                LeaderboardResult.success(List.of(
                        new LeaderboardEntryView(1, "Ada", 1016, 1, 1))),
                LeaderboardResult.class);
        PlayerStatisticsResult statsFailure = SerializationTestSupport.roundTrip(
                PlayerStatisticsResult.failure(ResultCode.REGISTERED_ACCOUNT_REQUIRED,
                        "Registered account required"), PlayerStatisticsResult.class);

        assertEquals(MatchOutcome.WIN,
                statistics.getStatistics().getHistory().get(0).getOutcome());
        assertEquals(1016, statistics.getStatistics().getCurrentRating());
        assertEquals("Ada", leaderboard.getEntries().get(0).getUsername());
        assertEquals(ResultCode.REGISTERED_ACCOUNT_REQUIRED, statsFailure.getResultCode());
        assertNull(statsFailure.getStatistics());
    }

    /**
     * Validates statistics and leaderboard success/failure result factories.
     */
    @Test
    void validatesStatisticsResultFactories() {
        PlayerStatisticsResult statsFailure = PlayerStatisticsResult.failure(
                ResultCode.INVALID_SESSION, "Invalid session");
        LeaderboardResult leaderboardFailure = LeaderboardResult.failure(
                ResultCode.INVALID_REQUEST, "Invalid limit");

        assertFalse(statsFailure.isSuccess());
        assertNull(statsFailure.getStatistics());
        assertFalse(leaderboardFailure.isSuccess());
        assertNull(leaderboardFailure.getEntries());
        assertThrows(NullPointerException.class, () -> PlayerStatisticsResult.success(null));
        assertThrows(NullPointerException.class, () -> LeaderboardResult.success(null));
        assertThrows(IllegalArgumentException.class, () -> PlayerStatisticsResult.failure(
                ResultCode.SUCCESS, "Failure"));
        assertThrows(IllegalArgumentException.class, () -> LeaderboardResult.failure(
                ResultCode.INVALID_REQUEST, " "));
    }

    /**
     * Creates one representative committed history row.
     *
     * @return validated history entry
     */
    private static MatchHistoryEntryView history() {
        return new MatchHistoryEntryView("Grace", false, MatchOutcome.WIN,
                GameEndReason.ALL_SHIPS_SUNK, 5, Duration.ofMinutes(3),
                4, 2, 1, 16, COMPLETED_AT);
    }
}
