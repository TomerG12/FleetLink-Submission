package io.github.tomerg12.fleetlink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator;
import io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState;
import io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardTestFixtures;
import org.junit.jupiter.api.Test;

/** Verifies bounded registered and guest Lobby preview activation without JavaFX toolkit startup. */
class LobbyPreviewLifecycleTest {

    /** Requests exactly offset 0, history limit 3, and leaderboard limit 5 when registered. */
    @Test
    void registeredActivationRequestsBothBoundedPreviewSlices() {
        ClientStateCoordinator coordinator =
                StatisticsDashboardTestFixtures.registeredLobbyCoordinator();
        List<String> calls = new ArrayList<>();

        try (ClientStateCoordinator.DashboardSubscription ignored = LobbyScreen.activatePreview(
                coordinator, true, state -> { },
                (offset, limit) -> {
                    calls.add("personal:" + offset + ":" + limit);
                    return completed(coordinator);
                },
                limit -> {
                    calls.add("leaderboard:" + limit);
                    return completed(coordinator);
                })) {
            assertEquals(List.of("personal:0:3", "leaderboard:5"), calls);
        }
    }

    /** Skips personal loading for guests while requesting the same public Top 5 once. */
    @Test
    void guestActivationSkipsPersonalAndLoadsLeaderboardOnce() {
        ClientStateCoordinator coordinator =
                StatisticsDashboardTestFixtures.guestLobbyCoordinator();
        AtomicInteger personalCalls = new AtomicInteger();
        List<Integer> leaderboardLimits = new ArrayList<>();

        try (ClientStateCoordinator.DashboardSubscription ignored = LobbyScreen.activatePreview(
                coordinator, false, state -> { },
                (offset, limit) -> {
                    personalCalls.incrementAndGet();
                    return completed(coordinator);
                },
                limit -> {
                    leaderboardLimits.add(limit);
                    return completed(coordinator);
                })) {
            assertEquals(0, personalCalls.get());
            assertEquals(List.of(5), leaderboardLimits);
        }
    }

    /** Uses the established Statistics screen for the compact preview's secondary action. */
    @Test
    void fullStatisticsActionReusesExistingDestination() {
        assertEquals(ScreenId.PLAYER_STATISTICS, LobbyScreen.fullStatisticsDestination());
    }

    /**
     * Returns an already completed future containing current dashboard state.
     *
     * @param coordinator current dashboard owner
     * @return completed dashboard future
     */
    private static CompletableFuture<StatisticsDashboardState> completed(
            ClientStateCoordinator coordinator) {
        return CompletableFuture.completedFuture(coordinator.getStatisticsDashboardState());
    }
}
