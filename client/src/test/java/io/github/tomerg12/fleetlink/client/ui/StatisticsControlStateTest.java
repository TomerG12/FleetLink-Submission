package io.github.tomerg12.fleetlink.client.ui;

import static io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState.LoadStatus.EXPECTED_FAILURE;
import static io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState.LoadStatus.IDLE;
import static io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState.LoadStatus.LOADING;
import static io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState.LoadStatus.SUCCESS;
import static io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState.LoadStatus.TRANSPORT_FAILURE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState.LoadStatus;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.MatchHistoryEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchOutcome;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsView;
import org.junit.jupiter.api.Test;

/** Verifies statistics control enablement without starting the JavaFX toolkit. */
class StatisticsControlStateTest {

    /** Confirms the stable shell cannot refresh before either request starts. */
    @Test
    void initialIdleSlicesDisableRefresh() {
        StatisticsControlState controls = controls(IDLE, null, IDLE);

        assertFalse(controls.isRefreshEnabled());
    }

    /** Confirms personal loading blocks refresh and only the personal retry. */
    @Test
    void personalLoadingDisablesRefreshAndPersonalRetryOnly() {
        StatisticsControlState controls = controls(LOADING, null, SUCCESS);

        assertAll(
                () -> assertFalse(controls.isRefreshEnabled()),
                () -> assertFalse(controls.isPersonalRetryEnabled()),
                () -> assertFalse(controls.isLeaderboardRetryEnabled()));
    }

    /** Confirms leaderboard loading blocks refresh and only the leaderboard retry. */
    @Test
    void leaderboardLoadingDisablesRefreshAndLeaderboardRetryOnly() {
        StatisticsControlState controls = controls(SUCCESS, emptyStatistics(), LOADING);

        assertAll(
                () -> assertFalse(controls.isRefreshEnabled()),
                () -> assertFalse(controls.isPersonalRetryEnabled()),
                () -> assertFalse(controls.isLeaderboardRetryEnabled()));
    }

    /** Confirms two successful slices allow the combined refresh operation. */
    @Test
    void successfulSlicesEnableRefresh() {
        StatisticsControlState controls = controls(SUCCESS, emptyStatistics(), SUCCESS);

        assertTrue(controls.isRefreshEnabled());
    }

    /** Confirms recoverable request outcomes count as settled for refresh. */
    @Test
    void recoverableFailuresEnableRefreshWhenBothSlicesAreSettled() {
        StatisticsControlState transportFailure = controls(
                TRANSPORT_FAILURE, null, SUCCESS);
        StatisticsControlState expectedFailures = controls(
                EXPECTED_FAILURE, null, TRANSPORT_FAILURE);

        assertAll(
                () -> assertTrue(transportFailure.isRefreshEnabled()),
                () -> assertTrue(expectedFailures.isRefreshEnabled()),
                () -> assertTrue(transportFailure.isPersonalRetryEnabled()),
                () -> assertTrue(expectedFailures.isLeaderboardRetryEnabled()));
    }

    /** Confirms leaderboard loading cannot disable authoritative personal pagination. */
    @Test
    void leaderboardLoadingDoesNotDisablePersonalPagination() {
        StatisticsControlState controls = controls(SUCCESS, pagedStatistics(), LOADING);

        assertAll(
                () -> assertTrue(controls.isPreviousEnabled()),
                () -> assertTrue(controls.isNextEnabled()),
                () -> assertFalse(controls.isRefreshEnabled()));
    }

    /** Confirms personal loading disables pagination even when retained metadata permits it. */
    @Test
    void personalLoadingDisablesPagination() {
        StatisticsControlState controls = controls(LOADING, pagedStatistics(), SUCCESS);

        assertAll(
                () -> assertFalse(controls.isPreviousEnabled()),
                () -> assertFalse(controls.isNextEnabled()));
    }

    /** Confirms each retry follows only its own slice across both loading arrangements. */
    @Test
    void retriesRemainIndependentAcrossLoadingArrangements() {
        StatisticsControlState leaderboardLoading = controls(
                SUCCESS, emptyStatistics(), LOADING);
        StatisticsControlState personalLoading = controls(LOADING, null, SUCCESS);

        assertAll(
                () -> assertFalse(leaderboardLoading.isPersonalRetryEnabled()),
                () -> assertFalse(leaderboardLoading.isLeaderboardRetryEnabled()),
                () -> assertFalse(personalLoading.isPersonalRetryEnabled()),
                () -> assertFalse(personalLoading.isLeaderboardRetryEnabled()));
    }

    /** Confirms a guest never receives a personal retry while public leaderboard retry remains. */
    @Test
    void guestHidesPersonalRetryButCanRetryLeaderboardFailure() {
        StatisticsControlState controls = StatisticsControlState.evaluate(
                EXPECTED_FAILURE, null, TRANSPORT_FAILURE, true);

        assertAll(
                () -> assertFalse(controls.isPersonalRetryEnabled()),
                () -> assertTrue(controls.isLeaderboardRetryEnabled()));
    }

    /**
     * Evaluates one pure control state from explicit test inputs.
     *
     * @param personalStatus personal slice status
     * @param statistics current or retained personal payload
     * @param leaderboardStatus leaderboard slice status
     * @return evaluated controls
     */
    private static StatisticsControlState controls(LoadStatus personalStatus,
                                                    PlayerStatisticsView statistics,
                                                    LoadStatus leaderboardStatus) {
        return StatisticsControlState.evaluate(
                personalStatus, statistics, leaderboardStatus, false);
    }

    /**
     * Creates successful personal data without history pagination.
     *
     * @return empty authoritative personal snapshot
     */
    private static PlayerStatisticsView emptyStatistics() {
        return new PlayerStatisticsView(1000, 0, 0, 0, 0, 0, 0,
                List.of(), 0, false);
    }

    /**
     * Creates retained personal data that permits both pagination directions.
     *
     * @return authoritative personal snapshot with a later page available
     */
    private static PlayerStatisticsView pagedStatistics() {
        MatchHistoryEntryView history = new MatchHistoryEntryView(
                "Opponent", false, MatchOutcome.WIN, GameEndReason.ALL_SHIPS_SUNK,
                1, Duration.ofSeconds(30), 1, 1, 1, 16, Instant.EPOCH);
        return new PlayerStatisticsView(1016, 1, 1, 0, 1, 1, 1,
                List.of(history), 10, true);
    }
}
