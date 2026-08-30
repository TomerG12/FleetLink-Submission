package io.github.tomerg12.fleetlink.client.integration;

import java.util.List;

import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.DashboardSubscription;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.OperationToken;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.StatisticsOperationToken;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;

/** Creates dashboard snapshots for presentation tests through production reconciliation methods. */
public final class StatisticsDashboardTestFixtures {

    /** Prevents construction because this test support type exposes only static factories. */
    private StatisticsDashboardTestFixtures() {
    }

    /**
     * Creates a dashboard with successful personal and leaderboard slices.
     *
     * @param statistics authoritative personal payload
     * @param leaderboard authoritative server-ordered leaderboard rows
     * @return immutable successful dashboard state
     */
    public static StatisticsDashboardState successful(
            PlayerStatisticsView statistics, List<LeaderboardEntryView> leaderboard) {
        ClientStateCoordinator coordinator = registeredCoordinator();
        try (DashboardSubscription ignored = coordinator.activateStatisticsDashboard(state -> { })) {
            StatisticsOperationToken personal = coordinator.beginPlayerStatisticsLoad();
            coordinator.completePlayerStatistics(personal,
                    PlayerStatisticsResult.success(statistics));
            StatisticsOperationToken leaders = coordinator.beginLeaderboardLoad();
            coordinator.completeLeaderboard(leaders, LeaderboardResult.success(leaderboard));
            return coordinator.getStatisticsDashboardState();
        }
    }

    /**
     * Creates a dashboard with independently failed slices for resilient preview tests.
     *
     * @return immutable expected-failure dashboard state
     */
    public static StatisticsDashboardState failed() {
        ClientStateCoordinator coordinator = registeredCoordinator();
        try (DashboardSubscription ignored = coordinator.activateStatisticsDashboard(state -> { })) {
            StatisticsOperationToken personal = coordinator.beginPlayerStatisticsLoad();
            coordinator.completePlayerStatistics(personal, PlayerStatisticsResult.failure(
                    ResultCode.INVALID_REQUEST, "Personal data unavailable"));
            StatisticsOperationToken leaders = coordinator.beginLeaderboardLoad();
            coordinator.completeLeaderboard(leaders, LeaderboardResult.failure(
                    ResultCode.INVALID_REQUEST, "Leaderboard unavailable"));
            return coordinator.getStatisticsDashboardState();
        }
    }

    /**
     * Creates an established registered coordinator for lifecycle tests outside this package.
     *
     * @return registered Lobby coordinator
     */
    public static ClientStateCoordinator registeredLobbyCoordinator() {
        return registeredCoordinator();
    }

    /**
     * Creates an established guest coordinator for lifecycle tests outside this package.
     *
     * @return guest Lobby coordinator
     */
    public static ClientStateCoordinator guestLobbyCoordinator() {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        OperationToken connection = coordinator.beginGuestConnection();
        coordinator.completeGuestConnection(connection,
                SessionResult.success(ClientTestFixtures.session()));
        return coordinator;
    }

    /**
     * Creates an established registered coordinator for dashboard activation.
     *
     * @return registered Lobby coordinator
     */
    private static ClientStateCoordinator registeredCoordinator() {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        OperationToken connection = coordinator.beginRegisteredConnection(false);
        coordinator.completeRegisteredConnection(connection,
                SessionResult.success(ClientTestFixtures.registeredSession()), false);
        return coordinator;
    }
}
