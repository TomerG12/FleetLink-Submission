package io.github.tomerg12.fleetlink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState;
import io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardTestFixtures;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchHistoryEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchOutcome;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsView;
import org.junit.jupiter.api.Test;

/**
 * Verifies the deterministic lobby presentation states.
 */
class LobbyPresentationModelTest {

    /**
     * Confirms searching and cancellation are reversible local presentation changes.
     */
    @Test
    void searchingPresentationCanBeEnteredAndCancelled() {
        LobbyPresentationModel model = new LobbyPresentationModel();

        assertEquals(LobbyPresentationModel.MatchmakingState.IDLE, model.getMatchmakingState());
        model.startSearching();
        assertEquals(LobbyPresentationModel.MatchmakingState.SEARCHING, model.getMatchmakingState());
        model.cancelSearching();
        assertEquals(LobbyPresentationModel.MatchmakingState.IDLE, model.getMatchmakingState());
        assertEquals("Find an opponent and start a match.", model.getDetailMessage());
    }

    /**
     * Confirms join failure becomes idle while cancellation failure keeps active waiting state.
     */
    @Test
    void failureMessagesPreserveAuthoritativeWaitingMeaning() {
        LobbyPresentationModel model = new LobbyPresentationModel();
        model.showFailure("Join failed.");
        assertEquals(LobbyPresentationModel.MatchmakingState.IDLE, model.getMatchmakingState());
        assertEquals("Join failed.", model.getDetailMessage());

        model.showSearchingMessage("Cancellation failed.");
        assertEquals(LobbyPresentationModel.MatchmakingState.SEARCHING,
                model.getMatchmakingState());
        assertEquals("Cancellation failed.", model.getDetailMessage());
    }

    /** Renders live summary values and caps personal history without changing server order. */
    @Test
    void registeredPreviewUsesLiveStatisticsAndFirstThreeHistoryRows() {
        StatisticsDashboardState state = StatisticsDashboardTestFixtures.successful(
                statistics(List.of(
                        history("Alice", MatchOutcome.WIN, 16),
                        history("Bob", MatchOutcome.LOSS, -12),
                        history("Carol", MatchOutcome.WIN, 14),
                        history("Dan", MatchOutcome.WIN, 10))), List.of());

        assertEquals("1516", LobbyPresentationModel.ratingText(true, state));
        assertEquals("4", LobbyPresentationModel.gamesText(true, state));
        assertEquals("3", LobbyPresentationModel.winsText(true, state));
        assertEquals("75.0%", LobbyPresentationModel.winRateText(true, state));
        assertEquals(List.of("WIN   Alice   +16", "LOSS   Bob   -12",
                        "WIN   Carol   +14"),
                LobbyPresentationModel.recentMatchRows(true, state));
        assertEquals("", LobbyPresentationModel.personalStatusText(true, state));
    }

    /** Preserves server rank and order while displaying at most five leaderboard rows. */
    @Test
    void leaderboardPreviewUsesFirstFiveServerOrderedRows() {
        List<LeaderboardEntryView> entries = List.of(
                leader(7, "Zulu", 1700), leader(2, "Beta", 1600),
                leader(9, "Echo", 1500), leader(1, "Alpha", 1400),
                leader(4, "Delta", 1300), leader(3, "Charlie", 1200));
        StatisticsDashboardState state = StatisticsDashboardTestFixtures.successful(
                statistics(List.of()), entries);

        assertEquals(List.of("#7   Zulu   1700", "#2   Beta   1600",
                        "#9   Echo   1500", "#1   Alpha   1400", "#4   Delta   1300"),
                LobbyPresentationModel.leaderboardRows(state));
        assertEquals("", LobbyPresentationModel.leaderboardStatusText(state));
    }

    /** Displays neutral empty and failure copy without inventing rating or rows. */
    @Test
    void emptyAndFailedPreviewRemainNeutral() {
        StatisticsDashboardState empty = StatisticsDashboardTestFixtures.successful(
                statistics(List.of()), List.of());
        StatisticsDashboardState failed = StatisticsDashboardTestFixtures.failed();

        assertEquals("No completed matches yet.",
                LobbyPresentationModel.personalStatusText(true, empty));
        assertEquals("No ranked players yet.",
                LobbyPresentationModel.leaderboardStatusText(empty));
        assertEquals("--", LobbyPresentationModel.ratingText(true, failed));
        assertEquals(List.of(), LobbyPresentationModel.recentMatchRows(true, failed));
        assertEquals("Personal data unavailable",
                LobbyPresentationModel.personalStatusText(true, failed));
        assertEquals("Leaderboard unavailable",
                LobbyPresentationModel.leaderboardStatusText(failed));
    }

    /** Keeps registered-only personal copy local for guests while allowing leaderboard rendering. */
    @Test
    void guestPreviewSkipsPersonalPresentationButRetainsLeaderboard() {
        StatisticsDashboardState state = StatisticsDashboardTestFixtures.successful(
                statistics(List.of(history("Alice", MatchOutcome.WIN, 16))),
                List.of(leader(1, "Alpha", 1500)));

        assertEquals("--", LobbyPresentationModel.ratingText(false, state));
        assertEquals(List.of(), LobbyPresentationModel.recentMatchRows(false, state));
        assertEquals("Personal statistics and match history require a registered account.",
                LobbyPresentationModel.personalStatusText(false, state));
        assertEquals(List.of("#1   Alpha   1500"),
                LobbyPresentationModel.leaderboardRows(state));
    }

    /** Creates valid personal statistics for compact-preview tests. */
    private static PlayerStatisticsView statistics(List<MatchHistoryEntryView> history) {
        return new PlayerStatisticsView(1516, 4, 3, 1, 4, 10, 5,
                history, 0, false);
    }

    /** Creates one valid personal match-history row. */
    private static MatchHistoryEntryView history(String opponent, MatchOutcome outcome,
                                                  int ratingDelta) {
        return new MatchHistoryEntryView(opponent, false, outcome,
                GameEndReason.RESIGNATION, 5, Duration.ofMinutes(2),
                5, 3, 2, ratingDelta, Instant.parse("2026-08-25T10:00:00Z"));
    }

    /** Creates one server-ranked leaderboard row. */
    private static LeaderboardEntryView leader(int rank, String username, int rating) {
        return new LeaderboardEntryView(rank, username, rating, 4, 3);
    }
}
