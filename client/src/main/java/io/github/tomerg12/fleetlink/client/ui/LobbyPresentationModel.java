package io.github.tomerg12.fleetlink.client.ui;

import java.util.List;

import io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState;
import io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState.LoadStatus;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchHistoryEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsView;

/**
 * Holds deterministic lobby presentation state for the client shell.
 * It contains no server, RMI, timer, or matchmaking behavior.
 */
public final class LobbyPresentationModel {
    private static final int RECENT_MATCH_LIMIT = 3;
    private static final int TOP_PLAYER_LIMIT = 5;
    private static final String REGISTERED_REQUIRED =
            "Personal statistics and match history require a registered account.";

    /**
     * Visual states supported by the initial lobby shell.
     */
    public enum MatchmakingState {
        /** Lobby is ready for a local matchmaking action. */
        IDLE,

        /** Lobby is displaying its pending/searching presentation. */
        SEARCHING
    }

    private MatchmakingState matchmakingState = MatchmakingState.IDLE;
    private String detailMessage = "Find an opponent and start a match.";

    /**
     * Creates a lobby presentation model in its default idle state.
     */
    public LobbyPresentationModel() {
    }

    /**
     * Moves the shell into its deterministic searching presentation.
     */
    public void startSearching() {
        matchmakingState = MatchmakingState.SEARCHING;
        detailMessage = "Searching for an available opponent.";
    }

    /**
     * Restores the shell to its deterministic idle presentation.
     */
    public void cancelSearching() {
        matchmakingState = MatchmakingState.IDLE;
        detailMessage = "Find an opponent and start a match.";
    }

    /**
     * Restores the idle presentation with an authoritative or transport failure message.
     *
     * @param message non-blank player-facing failure explanation
     */
    public void showFailure(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        matchmakingState = MatchmakingState.IDLE;
        detailMessage = message;
    }

    /**
     * Keeps matchmaking active while replacing its detail with a recoverable failure message.
     *
     * @param message non-blank player-facing failure explanation
     */
    public void showSearchingMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        matchmakingState = MatchmakingState.SEARCHING;
        detailMessage = message;
    }

    /**
     * Returns the currently selected lobby presentation state.
     *
     * @return current matchmaking presentation state
     */
    public MatchmakingState getMatchmakingState() {
        return matchmakingState;
    }

    /**
     * Returns the detail text for the current lobby presentation state.
     *
     * @return non-blank presentation detail
     */
    public String getDetailMessage() {
        return detailMessage;
    }

    /**
     * Formats the live rating only from a successful personal statistics response.
     *
     * @param registered whether the current session is registered
     * @param state current dashboard state, or null before activation
     * @return live rating or a neutral placeholder
     */
    static String ratingText(boolean registered, StatisticsDashboardState state) {
        PlayerStatisticsView statistics = successfulStatistics(registered, state);
        return statistics == null ? "--" : Integer.toString(statistics.getCurrentRating());
    }

    /**
     * Formats the committed completed-game count from the personal preview.
     *
     * @param registered whether the current session is registered
     * @param state current dashboard state, or null before activation
     * @return game count or a neutral placeholder
     */
    static String gamesText(boolean registered, StatisticsDashboardState state) {
        PlayerStatisticsView statistics = successfulStatistics(registered, state);
        return statistics == null ? "--" : Long.toString(statistics.getTotalGames());
    }

    /**
     * Formats the committed win count from the personal preview.
     *
     * @param registered whether the current session is registered
     * @param state current dashboard state, or null before activation
     * @return win count or a neutral placeholder
     */
    static String winsText(boolean registered, StatisticsDashboardState state) {
        PlayerStatisticsView statistics = successfulStatistics(registered, state);
        return statistics == null ? "--" : Long.toString(statistics.getWins());
    }

    /**
     * Formats the server-derived win rate from the personal preview.
     *
     * @param registered whether the current session is registered
     * @param state current dashboard state, or null before activation
     * @return percentage or a neutral placeholder
     */
    static String winRateText(boolean registered, StatisticsDashboardState state) {
        PlayerStatisticsView statistics = successfulStatistics(registered, state);
        return statistics == null ? "--" : StatisticsPresentation.percentage(
                statistics.getWinRate());
    }

    /**
     * Formats at most three current-user history rows in the server-provided order.
     *
     * @param registered whether the current session is registered
     * @param state current dashboard state, or null before activation
     * @return immutable compact history rows
     */
    static List<String> recentMatchRows(boolean registered, StatisticsDashboardState state) {
        PlayerStatisticsView statistics = successfulStatistics(registered, state);
        if (statistics == null) {
            return List.of();
        }
        return statistics.getHistory().stream().limit(RECENT_MATCH_LIMIT)
                .map(LobbyPresentationModel::historyRow)
                .toList();
    }

    /**
     * Selects local guest, loading, empty, or failure copy for the personal preview.
     *
     * @param registered whether the current session is registered
     * @param state current dashboard state, or null before activation
     * @return concise personal preview status text
     */
    static String personalStatusText(boolean registered, StatisticsDashboardState state) {
        if (!registered) {
            return REGISTERED_REQUIRED;
        }
        if (state == null || state.getPersonalStatus() == LoadStatus.IDLE) {
            return "Personal statistics have not loaded yet.";
        }
        if (state.getPersonalStatus() == LoadStatus.LOADING) {
            return "Loading personal statistics...";
        }
        if (state.getPersonalStatus() == LoadStatus.SUCCESS) {
            return state.getPersonalStatistics().getHistory().isEmpty()
                    ? "No completed matches yet." : "";
        }
        return state.getPersonalMessage();
    }

    /**
     * Formats at most five leaderboard entries in the exact server-provided order.
     *
     * @param state current dashboard state, or null before activation
     * @return immutable compact leaderboard rows
     */
    static List<String> leaderboardRows(StatisticsDashboardState state) {
        if (state == null || state.getLeaderboardStatus() != LoadStatus.SUCCESS) {
            return List.of();
        }
        return state.getLeaderboardEntries().stream().limit(TOP_PLAYER_LIMIT)
                .map(LobbyPresentationModel::leaderboardRow)
                .toList();
    }

    /**
     * Selects loading, empty, or failure copy for the leaderboard preview.
     *
     * @param state current dashboard state, or null before activation
     * @return concise leaderboard status text
     */
    static String leaderboardStatusText(StatisticsDashboardState state) {
        if (state == null || state.getLeaderboardStatus() == LoadStatus.IDLE) {
            return "Leaderboard has not loaded yet.";
        }
        if (state.getLeaderboardStatus() == LoadStatus.LOADING) {
            return "Loading top players...";
        }
        if (state.getLeaderboardStatus() == LoadStatus.SUCCESS) {
            return state.getLeaderboardEntries().isEmpty() ? "No ranked players yet." : "";
        }
        return state.getLeaderboardMessage();
    }

    /**
     * Returns personal statistics only for a successful registered preview.
     *
     * @param registered whether the current session is registered
     * @param state current dashboard state, or null
     * @return successful personal snapshot, or null when unavailable
     */
    private static PlayerStatisticsView successfulStatistics(
            boolean registered, StatisticsDashboardState state) {
        return registered && state != null && state.getPersonalStatus() == LoadStatus.SUCCESS
                ? state.getPersonalStatistics() : null;
    }

    /**
     * Formats one personal history row without reordering or recalculating authoritative fields.
     *
     * @param entry authoritative personal history entry
     * @return compact player-facing row
     */
    private static String historyRow(MatchHistoryEntryView entry) {
        return StatisticsPresentation.outcome(entry.getOutcome()) + "   "
                + entry.getOpponentDisplayName() + "   "
                + StatisticsPresentation.ratingDelta(entry.getRatingDelta());
    }

    /**
     * Formats one server-ranked leaderboard entry without inferring rank or order.
     *
     * @param entry authoritative leaderboard entry
     * @return compact player-facing row
     */
    private static String leaderboardRow(LeaderboardEntryView entry) {
        return "#" + entry.getRank() + "   " + entry.getUsername() + "   " + entry.getRating();
    }
}
