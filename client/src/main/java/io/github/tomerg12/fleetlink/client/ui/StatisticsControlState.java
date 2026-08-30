package io.github.tomerg12.fleetlink.client.ui;

import java.util.Objects;

import io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState.LoadStatus;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsView;

/**
 * Derives dashboard button enablement from immutable statistics read state without depending on
 * JavaFX controls or toolkit lifecycle.
 */
final class StatisticsControlState {
    private final boolean refreshEnabled;
    private final boolean personalRetryEnabled;
    private final boolean leaderboardRetryEnabled;
    private final boolean previousEnabled;
    private final boolean nextEnabled;

    /**
     * Creates one immutable set of dashboard control decisions.
     *
     * @param refreshEnabled whether a combined refresh may be submitted
     * @param personalRetryEnabled whether personal data may be retried
     * @param leaderboardRetryEnabled whether leaderboard data may be retried
     * @param previousEnabled whether the previous history page may be requested
     * @param nextEnabled whether the next history page may be requested
     */
    private StatisticsControlState(boolean refreshEnabled, boolean personalRetryEnabled,
                                   boolean leaderboardRetryEnabled, boolean previousEnabled,
                                   boolean nextEnabled) {
        this.refreshEnabled = refreshEnabled;
        this.personalRetryEnabled = personalRetryEnabled;
        this.leaderboardRetryEnabled = leaderboardRetryEnabled;
        this.previousEnabled = previousEnabled;
        this.nextEnabled = nextEnabled;
    }

    /**
     * Evaluates independent slice and pagination inputs for deterministic toolkit-free tests.
     *
     * @param personalStatus personal statistics load status
     * @param personalStatistics current or retained authoritative personal snapshot
     * @param leaderboardStatus leaderboard load status
     * @param guest whether personal statistics are unavailable by account policy
     * @return control decisions for the supplied inputs
     */
    static StatisticsControlState evaluate(LoadStatus personalStatus,
                                           PlayerStatisticsView personalStatistics,
                                           LoadStatus leaderboardStatus,
                                           boolean guest) {
        Objects.requireNonNull(personalStatus, "personalStatus");
        Objects.requireNonNull(leaderboardStatus, "leaderboardStatus");
        boolean personalLoading = personalStatus == LoadStatus.LOADING;
        boolean previousEnabled = !personalLoading && personalStatistics != null
                && personalStatistics.getHistoryOffset() > 0;
        boolean nextEnabled = !personalLoading && personalStatistics != null
                && personalStatistics.hasMore();
        return new StatisticsControlState(
                isSettled(personalStatus) && isSettled(leaderboardStatus),
                !guest && isFailure(personalStatus),
                isFailure(leaderboardStatus),
                previousEnabled,
                nextEnabled);
    }

    /**
     * Reports whether one settled slice offers a meaningful retry.
     *
     * @param status dashboard slice status
     * @return true only for expected or transport failure
     */
    private static boolean isFailure(LoadStatus status) {
        return status == LoadStatus.EXPECTED_FAILURE || status == LoadStatus.TRANSPORT_FAILURE;
    }

    /**
     * Reports whether a load status represents a completed request attempt.
     *
     * @param status dashboard slice status
     * @return true for success, expected failure, or transport failure
     */
    private static boolean isSettled(LoadStatus status) {
        return switch (status) {
            case SUCCESS, EXPECTED_FAILURE, TRANSPORT_FAILURE -> true;
            case IDLE, LOADING -> false;
        };
    }

    /**
     * Reports whether both dashboard slices are settled and may be refreshed together.
     *
     * @return true when refresh is enabled
     */
    boolean isRefreshEnabled() {
        return refreshEnabled;
    }

    /**
     * Reports whether personal statistics are not currently loading.
     *
     * @return true when personal retry is enabled
     */
    boolean isPersonalRetryEnabled() {
        return personalRetryEnabled;
    }

    /**
     * Reports whether leaderboard data are not currently loading.
     *
     * @return true when leaderboard retry is enabled
     */
    boolean isLeaderboardRetryEnabled() {
        return leaderboardRetryEnabled;
    }

    /**
     * Reports whether authoritative personal pagination permits a previous page request.
     *
     * @return true when previous-page navigation is enabled
     */
    boolean isPreviousEnabled() {
        return previousEnabled;
    }

    /**
     * Reports whether authoritative personal pagination permits a next page request.
     *
     * @return true when next-page navigation is enabled
     */
    boolean isNextEnabled() {
        return nextEnabled;
    }
}
