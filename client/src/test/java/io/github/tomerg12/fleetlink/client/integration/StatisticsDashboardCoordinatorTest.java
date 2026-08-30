package io.github.tomerg12.fleetlink.client.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.DashboardSubscription;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.OperationToken;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.StatisticsOperationToken;
import io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState.LoadStatus;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardResult;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionInfo;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import org.junit.jupiter.api.Test;

/** Verifies session-bound, independent, monotonic statistics-dashboard reconciliation. */
class StatisticsDashboardCoordinatorTest {

    /** Confirms invalid activation input cannot partially install dashboard lifecycle state. */
    @Test
    void nullDashboardListenerIsRejectedBeforeActivationStateChanges() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run, session(11));

        assertThrows(NullPointerException.class,
                () -> coordinator.activateStatisticsDashboard(null));
        assertNull(coordinator.getStatisticsDashboardState());
    }

    /** Confirms an older personal result cannot overwrite a newer successful generation. */
    @Test
    void olderPersonalResultCannotOverwriteNewerPersonalResult() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run, session(1));
        try (DashboardSubscription ignored = coordinator.activateStatisticsDashboard(state -> { })) {
            StatisticsOperationToken older = coordinator.beginPlayerStatisticsLoad();
            StatisticsOperationToken newer = coordinator.beginPlayerStatisticsLoad();
            coordinator.completePlayerStatistics(newer, statistics(1400));
            coordinator.completePlayerStatistics(older, statistics(900));

            assertEquals(1400, coordinator.getStatisticsDashboardState()
                    .getPersonalStatistics().getCurrentRating());
        }
    }

    /** Confirms an older personal failure cannot replace a newer successful generation. */
    @Test
    void olderPersonalFailureCannotOverwriteNewerSuccess() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run, session(2));
        try (DashboardSubscription ignored = coordinator.activateStatisticsDashboard(state -> { })) {
            StatisticsOperationToken older = coordinator.beginPlayerStatisticsLoad();
            StatisticsOperationToken newer = coordinator.beginPlayerStatisticsLoad();
            coordinator.completePlayerStatistics(newer, statistics(1500));
            coordinator.completePlayerStatistics(older, PlayerStatisticsResult.failure(
                    ResultCode.INVALID_REQUEST, "Old request failed"));

            StatisticsDashboardState state = coordinator.getStatisticsDashboardState();
            assertEquals(LoadStatus.SUCCESS, state.getPersonalStatus());
            assertEquals(1500, state.getPersonalStatistics().getCurrentRating());
        }
    }

    /** Confirms an older leaderboard result cannot overwrite newer server-ranked rows. */
    @Test
    void olderLeaderboardResultCannotOverwriteNewerRows() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run, session(3));
        try (DashboardSubscription ignored = coordinator.activateStatisticsDashboard(state -> { })) {
            StatisticsOperationToken older = coordinator.beginLeaderboardLoad();
            StatisticsOperationToken newer = coordinator.beginLeaderboardLoad();
            coordinator.completeLeaderboard(newer, leaderboard("Newest", 1600));
            coordinator.completeLeaderboard(older, leaderboard("Older", 800));

            assertEquals("Newest", coordinator.getStatisticsDashboardState()
                    .getLeaderboardEntries().get(0).getUsername());
        }
    }

    /** Confirms personal and leaderboard completions merge either order without erasing a slice. */
    @Test
    void independentSlicesMergeInEitherCompletionOrder() {
        for (boolean personalFirst : List.of(true, false)) {
            ClientStateCoordinator coordinator = connectedCoordinator(
                    Runnable::run, session(personalFirst ? 4 : 5));
            try (DashboardSubscription ignored =
                         coordinator.activateStatisticsDashboard(state -> { })) {
                StatisticsOperationToken personal = coordinator.beginPlayerStatisticsLoad();
                StatisticsOperationToken leaderboard = coordinator.beginLeaderboardLoad();
                assertEquals(LoadStatus.LOADING,
                        coordinator.getStatisticsDashboardState().getPersonalStatus());
                assertEquals(LoadStatus.LOADING,
                        coordinator.getStatisticsDashboardState().getLeaderboardStatus());
                if (personalFirst) {
                    coordinator.completePlayerStatistics(personal, statistics(1700));
                    coordinator.completeLeaderboard(leaderboard, leaderboard("Captain", 1700));
                } else {
                    coordinator.completeLeaderboard(leaderboard, leaderboard("Captain", 1700));
                    coordinator.completePlayerStatistics(personal, statistics(1700));
                }

                StatisticsDashboardState state = coordinator.getStatisticsDashboardState();
                assertEquals(1700, state.getPersonalStatistics().getCurrentRating());
                assertEquals("Captain", state.getLeaderboardEntries().get(0).getUsername());
            }
        }
    }

    /** Confirms successful empty personal and leaderboard payloads remain success, not failure. */
    @Test
    void successfulEmptyPayloadsProduceExplicitEmptySuccessState() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run, session(10));
        try (DashboardSubscription ignored = coordinator.activateStatisticsDashboard(state -> { })) {
            StatisticsOperationToken personal = coordinator.beginPlayerStatisticsLoad();
            StatisticsOperationToken leaderboard = coordinator.beginLeaderboardLoad();
            coordinator.completePlayerStatistics(personal, statistics(1000));
            coordinator.completeLeaderboard(leaderboard, LeaderboardResult.success(List.of()));

            StatisticsDashboardState state = coordinator.getStatisticsDashboardState();
            assertEquals(LoadStatus.SUCCESS, state.getPersonalStatus());
            assertTrue(state.getPersonalStatistics().getHistory().isEmpty());
            assertEquals(LoadStatus.SUCCESS, state.getLeaderboardStatus());
            assertTrue(state.getLeaderboardEntries().isEmpty());
        }
    }

    /** Confirms results from a logged-out session cannot enter a later session dashboard. */
    @Test
    void oldSessionDashboardResultIsRejected() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run, session(6));
        DashboardSubscription first = coordinator.activateStatisticsDashboard(state -> { });
        StatisticsOperationToken oldRequest = coordinator.beginPlayerStatisticsLoad();

        OperationToken logout = coordinator.beginLogout();
        coordinator.completeLogout(logout, OperationResult.success());
        first.close();
        connect(coordinator, session(7));
        try (DashboardSubscription ignored = coordinator.activateStatisticsDashboard(state -> { })) {
            coordinator.completePlayerStatistics(oldRequest, statistics(700));

            assertEquals(session(7).getSessionId(),
                    coordinator.getStatisticsDashboardState().getSessionId());
            assertNull(coordinator.getStatisticsDashboardState().getPersonalStatistics());
        }
    }

    /** Confirms guest personal rejection and guest leaderboard success remain independent. */
    @Test
    void guestPersonalFailureDoesNotPreventLeaderboard() {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        OperationToken connection = coordinator.beginGuestConnection();
        coordinator.completeGuestConnection(connection,
                SessionResult.success(ClientTestFixtures.session()));
        try (DashboardSubscription ignored = coordinator.activateStatisticsDashboard(state -> { })) {
            StatisticsOperationToken personal = coordinator.beginPlayerStatisticsLoad();
            StatisticsOperationToken leaderboard = coordinator.beginLeaderboardLoad();
            coordinator.completePlayerStatistics(personal, PlayerStatisticsResult.failure(
                    ResultCode.REGISTERED_ACCOUNT_REQUIRED,
                    "Registered account required for personal statistics"));
            coordinator.completeLeaderboard(leaderboard, leaderboard("Registered", 1100));

            StatisticsDashboardState state = coordinator.getStatisticsDashboardState();
            assertEquals(LoadStatus.EXPECTED_FAILURE, state.getPersonalStatus());
            assertEquals(ResultCode.REGISTERED_ACCOUNT_REQUIRED,
                    state.getPersonalResultCode());
            assertEquals(LoadStatus.SUCCESS, state.getLeaderboardStatus());
        }
    }

    /** Confirms loading and failure never mutate gameplay phase or publish top-level navigation. */
    @Test
    void dashboardLoadingAndFailureDoNotMutateClientPhaseOrNavigationListener() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run, session(8));
        AtomicInteger navigationPublications = new AtomicInteger();
        coordinator.setStateListener(state -> navigationPublications.incrementAndGet());
        try (DashboardSubscription ignored = coordinator.activateStatisticsDashboard(state -> { })) {
            StatisticsOperationToken personal = coordinator.beginPlayerStatisticsLoad();
            coordinator.failStatisticsOperation(personal, "Transport unavailable");

            assertEquals(ClientPhase.LOBBY, coordinator.getState().getPhase());
            assertEquals(0, navigationPublications.get());
            assertEquals(LoadStatus.TRANSPORT_FAILURE,
                    coordinator.getStatisticsDashboardState().getPersonalStatus());
        }
    }

    /** Confirms queued presentation is suppressed after deactivation and re-entry starts fresh. */
    @Test
    void inactiveScreenReceivesNoLatePresentationAndReentryUsesFreshGeneration() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ClientStateCoordinator coordinator = connectedCoordinator(dispatcher, session(9));
        dispatcher.runAll();
        List<StatisticsDashboardState> firstDelivery = new ArrayList<>();
        DashboardSubscription first = coordinator.activateStatisticsDashboard(firstDelivery::add);
        StatisticsOperationToken request = coordinator.beginLeaderboardLoad();
        coordinator.completeLeaderboard(request, leaderboard("Late", 1000));
        first.close();
        dispatcher.runAll();
        assertTrue(firstDelivery.isEmpty());

        List<StatisticsDashboardState> secondDelivery = new ArrayList<>();
        try (DashboardSubscription ignored =
                     coordinator.activateStatisticsDashboard(secondDelivery::add)) {
            dispatcher.runAll();
            assertEquals(LoadStatus.IDLE, secondDelivery.get(0).getLeaderboardStatus());
            assertTrue(secondDelivery.get(0).getLeaderboardEntries().isEmpty());
        }
    }

    /** Creates a successful statistics result with one observable rating. */
    private static PlayerStatisticsResult statistics(int rating) {
        return PlayerStatisticsResult.success(new PlayerStatisticsView(
                rating, 0, 0, 0, 0, 0, 0, List.of(), 0, false));
    }

    /** Creates one successful server-ranked leaderboard row. */
    private static LeaderboardResult leaderboard(String username, int rating) {
        return LeaderboardResult.success(List.of(
                new LeaderboardEntryView(1, username, rating, 0, 0)));
    }

    /** Creates a deterministic registered session with distinct identifiers. */
    private static SessionInfo session(int suffix) {
        UUID sessionId = UUID.fromString(String.format(
                "00000000-0000-0000-0000-%012d", suffix));
        UUID playerId = UUID.fromString(String.format(
                "10000000-0000-0000-0000-%012d", suffix));
        return new SessionInfo(sessionId,
                new PlayerView(playerId, "Player" + suffix, 1000, false));
    }

    /** Creates a Lobby coordinator for one registered session. */
    private static ClientStateCoordinator connectedCoordinator(ClientUiDispatcher dispatcher,
                                                               SessionInfo session) {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(dispatcher);
        connect(coordinator, session);
        return coordinator;
    }

    /** Establishes one registered session through the production reconciliation path. */
    private static void connect(ClientStateCoordinator coordinator, SessionInfo session) {
        OperationToken connection = coordinator.beginRegisteredConnection(false);
        coordinator.completeRegisteredConnection(connection, SessionResult.success(session), false);
    }

    /** Stores UI work until the test explicitly releases it. */
    private static final class RecordingDispatcher implements ClientUiDispatcher {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        /** Adds one presentation task without executing it. */
        @Override
        public void dispatch(Runnable action) {
            tasks.add(action);
        }

        /** Runs every queued presentation task deterministically. */
        private void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }
}
