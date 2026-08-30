package io.github.tomerg12.fleetlink.client.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchHistoryEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchOutcome;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentCellView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies two production client adapters against one real FleetLink server process over RMI.
 * The `IT` suffix keeps this process-level scenario explicit instead of adding it to every unit run.
 */
class RealTwoClientE2EIT {
    private static final long TIMEOUT_SECONDS = 10;
    private static final long EVENTUAL_POLL_MILLIS = 50;
    private static final int INITIAL_RATING = 1000;
    private static final int ELO_K_FACTOR = 32;
    private static final double ELO_SCALE = 400.0;

    /**
     * Plays one complete sunk-fleet match, one resignation match, and both logout lifecycles.
     *
     * @throws Exception if server startup, RMI transport, or an asynchronous operation fails
     */
    @Test
    void twoProductionClientsCompletePlayableGuestFlowAndLifecycle() throws Exception {
        Path repository = repositoryRoot();
        int port = availablePort();
        Process server = startServer(repository, port);
        String previousHostname = System.getProperty("java.rmi.server.hostname");
        System.setProperty("java.rmi.server.hostname", "127.0.0.1");
        try {
            awaitServerReady(server);
            RmiClientConfig config = new RmiClientConfig(
                    "127.0.0.1", port, "FleetLinkServer");
            ClientStateCoordinator firstState = new ClientStateCoordinator(Runnable::run);
            ClientStateCoordinator secondState = new ClientStateCoordinator(Runnable::run);

            try (ClientOperationService first = ClientOperationService.forRmi(firstState, config);
                 ClientOperationService second = ClientOperationService.forRmi(secondState, config)) {
                connectAndMatch(first, firstState, "E2E Alpha",
                        second, secondState, "E2E Bravo");
                submitBothFleets(first, firstState, second, secondState);
                playUntilFirstClientWins(first, firstState, second, secondState);

                assertFinishedForBoth(firstState, secondState);
                assertEquals(ClientPhase.LOBBY,
                        first.returnToLobby().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());
                assertEquals(ClientPhase.LOBBY,
                        second.returnToLobby().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());

                joinExistingSessions(first, firstState, second, secondState);
                submitBothFleets(first, firstState, second, secondState);
                assertEquals(ClientPhase.LOBBY,
                        first.leaveGame().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());
                assertEquals(ClientPhase.GAME_OVER,
                        awaitPhase(secondState, ClientPhase.GAME_OVER).getPhase());
                assertEquals(GameEndReason.RESIGNATION,
                        secondState.getState().getGameView().getEndReason());
                assertEquals(ClientPhase.LOBBY,
                        second.returnToLobby().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());

                assertEquals(ClientPhase.LOGIN,
                        first.logout().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());
                assertEquals(ClientPhase.LOGIN,
                        second.logout().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());

                ClientState registered = first.register("E2EAccount", " exact password ")
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                UUID persistentPlayerId = registered.getSessionInfo().getPlayer().getPlayerId();
                assertFalse(registered.getSessionInfo().getPlayer().isGuest());
                assertEquals(ClientPhase.LOBBY,
                        second.connectAsGuest("E2E Mixed Guest")
                                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());
                joinExistingSessions(first, firstState, second, secondState);
                submitBothFleets(first, firstState, second, secondState);
                assertEquals(ClientPhase.LOBBY,
                        second.leaveGame().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());
                assertEquals(ClientPhase.GAME_OVER,
                        awaitPhase(firstState, ClientPhase.GAME_OVER).getPhase());
                assertEquals(GameEndReason.RESIGNATION,
                        firstState.getState().getGameView().getEndReason());
                first.returnToLobby().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                first.logout().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                second.logout().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                ClientState loggedIn = first.login("e2eaccount", " exact password ")
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertEquals(persistentPlayerId,
                        loggedIn.getSessionInfo().getPlayer().getPlayerId());
                first.logout().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            restoreHostname(previousHostname);
            stopServer(server);
        }
    }

    /**
     * Completes one rated registered match, creates exactly one rematch, and proves committed
     * account, statistics, history, and leaderboard state survives a production server restart.
     *
     * @param temporaryDirectory isolated directory owned by this test execution
     * @param reporter structured evidence sink for the exact identities used by this execution
     * @throws Exception if server startup, RMI transport, persistence, or a callback fails
     */
    @Test
    void twoRegisteredClientsRetainRatedCompletionAcrossFileBackedServerRestart(
            @TempDir Path temporaryDirectory, TestReporter reporter) throws Exception {
        Path repository = repositoryRoot();
        Path databasePath = temporaryDirectory.resolve("fleetlink-registered-restart")
                .toAbsolutePath().normalize();
        String jdbcUrl = "jdbc:h2:file:"
                + databasePath.toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE";
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String firstUsername = "E2EA_" + suffix;
        String secondUsername = "E2EB_" + suffix;
        String password = " exact restart password ";
        int expectedWinnerRating = expectedEloRating(INITIAL_RATING, INITIAL_RATING, true);
        int expectedLoserRating = expectedEloRating(INITIAL_RATING, INITIAL_RATING, false);
        String previousHostname = System.getProperty("java.rmi.server.hostname");
        System.setProperty("java.rmi.server.hostname", "127.0.0.1");
        reporter.publishEntry("databasePath", databasePath.toString());
        reporter.publishEntry("usernames", firstUsername + "/" + secondUsername);
        reporter.publishEntry("expectedRatings",
                expectedWinnerRating + "/" + expectedLoserRating);

        Process server = null;
        UUID firstPlayerId;
        UUID secondPlayerId;
        UUID sourceGameId;
        UUID rematchGameId;
        try {
            int firstPort = availablePort();
            server = startServer(repository, firstPort, jdbcUrl);
            awaitServerReady(server);
            RmiClientConfig firstConfig = new RmiClientConfig(
                    "127.0.0.1", firstPort, "FleetLinkServer");
            ClientStateCoordinator firstState = new ClientStateCoordinator(Runnable::run);
            ClientStateCoordinator secondState = new ClientStateCoordinator(Runnable::run);

            try (ClientOperationService first = ClientOperationService.forRmi(
                    firstState, firstConfig);
                 ClientOperationService second = ClientOperationService.forRmi(
                         secondState, firstConfig)) {
                ClientState firstRegistered = first.register(firstUsername, password)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ClientState secondRegistered = second.register(secondUsername, password)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                firstPlayerId = firstRegistered.getSessionInfo().getPlayer().getPlayerId();
                secondPlayerId = secondRegistered.getSessionInfo().getPlayer().getPlayerId();
                reporter.publishEntry("firstPlayerId", firstPlayerId.toString());
                reporter.publishEntry("secondPlayerId", secondPlayerId.toString());
                assertFalse(firstRegistered.getSessionInfo().getPlayer().isGuest());
                assertFalse(secondRegistered.getSessionInfo().getPlayer().isGuest());

                joinExistingSessions(first, firstState, second, secondState);
                sourceGameId = firstState.getState().getGameView().getGameId();
                reporter.publishEntry("sourceGameId", sourceGameId.toString());
                assertEquals(sourceGameId, secondState.getState().getGameView().getGameId());
                submitBothFleets(first, firstState, second, secondState);
                playUntilFirstClientWins(first, firstState, second, secondState);
                assertFinishedForBoth(firstState, secondState);

                try (ClientStateCoordinator.DashboardSubscription firstDashboard =
                             firstState.activateStatisticsDashboard(state -> { });
                     ClientStateCoordinator.DashboardSubscription secondDashboard =
                             secondState.activateStatisticsDashboard(state -> { })) {
                    awaitCommittedEvidence(first, firstUsername, secondUsername,
                            expectedWinnerRating, true);
                    awaitCommittedEvidence(second, secondUsername, firstUsername,
                            expectedLoserRating, false);
                }

                List<UUID> firstRematchCallbacks = new CopyOnWriteArrayList<>();
                List<UUID> secondRematchCallbacks = new CopyOnWriteArrayList<>();
                CompletableFuture<ClientState> incomingRequest = new CompletableFuture<>();
                CompletableFuture<ClientState> firstRematch = new CompletableFuture<>();
                CompletableFuture<ClientState> secondRematch = new CompletableFuture<>();
                firstState.setStateListener(state -> recordRematchCallback(
                        state, sourceGameId, firstRematchCallbacks, firstRematch));
                secondState.setStateListener(state -> {
                    if (state.getPhase() == ClientPhase.GAME_OVER
                            && state.getRematchState() != null
                            && state.getRematchState().canAccept()) {
                        incomingRequest.complete(state);
                    }
                    recordRematchCallback(
                            state, sourceGameId, secondRematchCallbacks, secondRematch);
                });

                first.requestRematch().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                incomingRequest.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                second.respondToRematch(true).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ClientState firstNewGame = firstRematch.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ClientState secondNewGame = secondRematch.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                rematchGameId = firstNewGame.getGameView().getGameId();
                assertNotEquals(sourceGameId, rematchGameId);
                assertEquals(rematchGameId, secondNewGame.getGameView().getGameId());
                assertEquals(List.of(rematchGameId), firstRematchCallbacks,
                        "First client must observe exactly one rematch game callback");
                assertEquals(List.of(rematchGameId), secondRematchCallbacks,
                        "Second client must observe exactly one rematch game callback");
                assertEquals(GamePhase.FLEET_PLACEMENT, firstNewGame.getGameView().getPhase());
                assertEquals(GamePhase.FLEET_PLACEMENT, secondNewGame.getGameView().getPhase());
                assertTrue(firstNewGame.getGameView().getDeadlineEpochMillis()
                        > System.currentTimeMillis(),
                        "First rematch placement deadline must be active");
                assertEquals(firstNewGame.getGameView().getDeadlineEpochMillis(),
                        secondNewGame.getGameView().getDeadlineEpochMillis());

                first.shutdownGracefully().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                second.shutdownGracefully().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            stopServer(server);
            server = null;
            Path databaseFile = Path.of(databasePath + ".mv.db");
            assertTrue(Files.isRegularFile(databaseFile),
                    "Expected file-backed H2 database at " + databaseFile);

            int restartPort = availablePort();
            server = startServer(repository, restartPort, jdbcUrl);
            awaitServerReady(server);
            RmiClientConfig restartConfig = new RmiClientConfig(
                    "127.0.0.1", restartPort, "FleetLinkServer");
            ClientStateCoordinator restartedFirstState =
                    new ClientStateCoordinator(Runnable::run);
            ClientStateCoordinator restartedSecondState =
                    new ClientStateCoordinator(Runnable::run);
            try (ClientOperationService restartedFirst = ClientOperationService.forRmi(
                    restartedFirstState, restartConfig);
                 ClientOperationService restartedSecond = ClientOperationService.forRmi(
                         restartedSecondState, restartConfig)) {
                ClientState firstLogin = restartedFirst.login(firstUsername, password)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                ClientState secondLogin = restartedSecond.login(secondUsername, password)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertEquals(firstPlayerId,
                        firstLogin.getSessionInfo().getPlayer().getPlayerId());
                assertEquals(secondPlayerId,
                        secondLogin.getSessionInfo().getPlayer().getPlayerId());
                assertEquals(expectedWinnerRating,
                        firstLogin.getSessionInfo().getPlayer().getRating());
                assertEquals(expectedLoserRating,
                        secondLogin.getSessionInfo().getPlayer().getRating());
                try (ClientStateCoordinator.DashboardSubscription firstDashboard =
                             restartedFirstState.activateStatisticsDashboard(state -> { });
                     ClientStateCoordinator.DashboardSubscription secondDashboard =
                             restartedSecondState.activateStatisticsDashboard(state -> { })) {
                    awaitCommittedEvidence(restartedFirst, firstUsername, secondUsername,
                            expectedWinnerRating, true);
                    awaitCommittedEvidence(restartedSecond, secondUsername, firstUsername,
                            expectedLoserRating, false);
                }
                restartedFirst.shutdownGracefully().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                restartedSecond.shutdownGracefully().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            reporter.publishEntry("databaseFile", databaseFile.toString());
            reporter.publishEntry("rematchGameId", rematchGameId.toString());
        } finally {
            restoreHostname(previousHostname);
            if (server != null) {
                stopServer(server);
            }
        }
    }

    /**
     * Connects two guests and waits for callback-driven Ship Placement on both clients.
     *
     * @param first first client operation boundary
     * @param firstState first client state coordinator
     * @param firstName first guest display name
     * @param second second client operation boundary
     * @param secondState second client state coordinator
     * @param secondName second guest display name
     * @throws Exception if connection or matchmaking does not complete
     */
    private static void connectAndMatch(ClientOperationService first,
                                        ClientStateCoordinator firstState,
                                        String firstName,
                                        ClientOperationService second,
                                        ClientStateCoordinator secondState,
                                        String secondName) throws Exception {
        assertEquals(ClientPhase.LOBBY,
                first.connectAsGuest(firstName).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());
        assertEquals(ClientPhase.LOBBY,
                second.connectAsGuest(secondName).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());
        joinExistingSessions(first, firstState, second, secondState);
    }

    /**
     * Joins both established sessions to callback-driven matchmaking without polling.
     *
     * @param first first client operation boundary
     * @param firstState first client state coordinator
     * @param second second client operation boundary
     * @param secondState second client state coordinator
     * @throws Exception if matchmaking or callbacks do not complete
     */
    private static void joinExistingSessions(ClientOperationService first,
                                             ClientStateCoordinator firstState,
                                             ClientOperationService second,
                                             ClientStateCoordinator secondState) throws Exception {
        CompletableFuture<ClientState> firstMatched = phaseFuture(
                firstState, ClientPhase.SHIP_PLACEMENT);
        CompletableFuture<ClientState> secondMatched = phaseFuture(
                secondState, ClientPhase.SHIP_PLACEMENT);
        assertEquals(ClientPhase.MATCHMAKING,
                first.joinMatchmaking().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());
        second.joinMatchmaking().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(ClientPhase.SHIP_PLACEMENT,
                firstMatched.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());
        assertEquals(ClientPhase.SHIP_PLACEMENT,
                secondMatched.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());
    }

    /**
     * Submits complete fleets and waits for the authoritative Battle callback on both clients.
     *
     * @param first first client operation boundary
     * @param firstState first client state coordinator
     * @param second second client operation boundary
     * @param secondState second client state coordinator
     * @throws Exception if fleet submission or Battle callbacks do not complete
     */
    private static void submitBothFleets(ClientOperationService first,
                                         ClientStateCoordinator firstState,
                                         ClientOperationService second,
                                         ClientStateCoordinator secondState) throws Exception {
        CompletableFuture<ClientState> firstBattle = phaseFuture(firstState, ClientPhase.BATTLE);
        CompletableFuture<ClientState> secondBattle = phaseFuture(secondState, ClientPhase.BATTLE);
        first.submitFleet(ClientTestFixtures.validFleet())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        second.submitFleet(ClientTestFixtures.validFleet())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(ClientPhase.BATTLE,
                firstBattle.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());
        assertEquals(ClientPhase.BATTLE,
                secondBattle.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getPhase());
    }

    /**
     * Alternates authoritative shots until the first client sinks the complete second fleet.
     *
     * @param first first client operation boundary
     * @param firstState first client state coordinator
     * @param second second client operation boundary
     * @param secondState second client state coordinator
     * @throws Exception if any shot or callback does not complete
     */
    private static void playUntilFirstClientWins(ClientOperationService first,
                                                 ClientStateCoordinator firstState,
                                                 ClientOperationService second,
                                                 ClientStateCoordinator secondState) throws Exception {
        List<Coordinate> shipTargets = shipTargets();
        List<Coordinate> safeMisses = safeMisses();
        int hitIndex = 0;
        int missIndex = 0;
        while (firstState.getState().getPhase() != ClientPhase.GAME_OVER) {
            ClientState firstCurrent = firstState.getState();
            ClientState secondCurrent = secondState.getState();
            assertEquals(ClientPhase.BATTLE, firstCurrent.getPhase());
            assertEquals(ClientPhase.BATTLE, secondCurrent.getPhase());
            assertTrue(firstCurrent.getGameView().isYourTurn()
                    ^ secondCurrent.getGameView().isYourTurn());
            if (firstCurrent.getGameView().isYourTurn()) {
                first.fire(shipTargets.get(hitIndex++))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } else {
                second.fire(safeMisses.get(missIndex++))
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }
        assertEquals(shipTargets.size(), hitIndex);
        assertTrue(missIndex == shipTargets.size() || missIndex == shipTargets.size() - 1);
    }

    /**
     * Verifies both final snapshots identify the first guest and preserve discovered board state.
     *
     * @param firstState first client state coordinator
     * @param secondState second client state coordinator
     */
    private static void assertFinishedForBoth(ClientStateCoordinator firstState,
                                              ClientStateCoordinator secondState) {
        assertEquals(ClientPhase.GAME_OVER, firstState.getState().getPhase());
        assertEquals(ClientPhase.GAME_OVER, secondState.getState().getPhase());
        assertEquals(GameEndReason.ALL_SHIPS_SUNK,
                firstState.getState().getGameView().getEndReason());
        assertEquals(firstState.getState().getSessionInfo().getPlayer().getPlayerId(),
                firstState.getState().getGameView().getWinner().getPlayerId());
        for (Coordinate target : shipTargets()) {
            assertEquals(OpponentCellView.HIT,
                    firstState.getState().getGameView().getOpponentBoard().getCell(target));
        }
        assertFalse(secondState.getState().getGameView().isYourTurn());
    }

    /**
     * Records only callback-published fresh placement games that differ from the completed source.
     *
     * @param state callback-published client state
     * @param sourceGameId completed game that owns the rematch negotiation
     * @param observedGameIds thread-safe collection of observed fresh game identities
     * @param firstFreshGame future completed by the first fresh placement callback
     */
    private static void recordRematchCallback(ClientState state, UUID sourceGameId,
                                              List<UUID> observedGameIds,
                                              CompletableFuture<ClientState> firstFreshGame) {
        if (state.getPhase() != ClientPhase.SHIP_PLACEMENT || state.getGameView() == null
                || sourceGameId.equals(state.getGameView().getGameId())) {
            return;
        }
        observedGameIds.add(state.getGameView().getGameId());
        firstFreshGame.complete(state);
    }

    /**
     * Polls real RMI statistics and leaderboard reads until asynchronous completion persistence is
     * fully visible or the bounded integration deadline expires.
     *
     * @param operations registered client operation boundary
     * @param username current registered username
     * @param opponentUsername completed rated opponent username
     * @param expectedRating expected live and committed rating
     * @param winner true for the expected winner and false for the expected loser
     * @return converged dashboard state containing both committed slices
     * @throws Exception if a remote read fails or committed data does not converge in time
     */
    private static StatisticsDashboardState awaitCommittedEvidence(
            ClientOperationService operations, String username, String opponentUsername,
            int expectedRating, boolean winner) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        StatisticsDashboardState dashboard = null;
        while (System.nanoTime() < deadline) {
            operations.loadPlayerStatistics(0, 10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            dashboard = operations.loadLeaderboard(10).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (hasCommittedEvidence(dashboard, username, opponentUsername,
                    expectedRating, winner)) {
                return dashboard;
            }
            Thread.sleep(EVENTUAL_POLL_MILLIS);
        }
        assertNotNull(dashboard, "Statistics dashboard was never loaded");
        assertTrue(hasCommittedEvidence(dashboard, username, opponentUsername,
                        expectedRating, winner),
                "Committed statistics, history, and leaderboard did not converge for " + username);
        return dashboard;
    }

    /**
     * Checks the complete committed personal and leaderboard evidence for one rated participant.
     *
     * @param dashboard latest merged dashboard response
     * @param username current registered username
     * @param opponentUsername completed rated opponent username
     * @param expectedRating expected live and committed rating
     * @param winner true for the expected winner and false for the expected loser
     * @return true only when every required committed field is visible
     */
    private static boolean hasCommittedEvidence(StatisticsDashboardState dashboard,
                                                String username, String opponentUsername,
                                                int expectedRating, boolean winner) {
        PlayerStatisticsView statistics = dashboard.getPersonalStatistics();
        if (statistics == null || statistics.getCurrentRating() != expectedRating
                || statistics.getTotalGames() != 1L
                || statistics.getWins() != (winner ? 1L : 0L)
                || statistics.getLosses() != (winner ? 0L : 1L)
                || statistics.getHistory().size() != 1) {
            return false;
        }
        MatchHistoryEntryView history = statistics.getHistory().get(0);
        if (!history.getOpponentDisplayName().equals(opponentUsername)
                || history.isOpponentGuest()
                || history.getOutcome() != (winner ? MatchOutcome.WIN : MatchOutcome.LOSS)
                || history.getEndReason() != GameEndReason.ALL_SHIPS_SUNK
                || history.getRatingDelta() != expectedRating - INITIAL_RATING) {
            return false;
        }
        LeaderboardEntryView current = dashboard.getLeaderboardEntries().stream()
                .filter(entry -> entry.getUsername().equals(username))
                .findFirst().orElse(null);
        LeaderboardEntryView opponent = dashboard.getLeaderboardEntries().stream()
                .filter(entry -> entry.getUsername().equals(opponentUsername))
                .findFirst().orElse(null);
        return current != null && current.getRating() == expectedRating
                && current.getGamesPlayed() == 1L && current.getWins() == (winner ? 1L : 0L)
                && opponent != null && opponent.getRating()
                == expectedEloRating(INITIAL_RATING, INITIAL_RATING, !winner)
                && opponent.getGamesPlayed() == 1L
                && opponent.getWins() == (winner ? 0L : 1L);
    }

    /**
     * Derives the expected result from the frozen FleetLink Elo policy without importing server
     * implementation classes into the client module.
     *
     * @param rating participant rating captured at match creation
     * @param opponentRating opponent rating captured at match creation
     * @param winner true when the participant won
     * @return nearest-integer Elo rating under the approved K-factor and scale
     */
    private static int expectedEloRating(int rating, int opponentRating, boolean winner) {
        double expected = 1.0 / (1.0
                + Math.pow(10.0, ((double) opponentRating - rating) / ELO_SCALE));
        return Math.toIntExact(Math.round(
                rating + ELO_K_FACTOR * ((winner ? 1.0 : 0.0) - expected)));
    }

    /**
     * Creates a future completed when the coordinator publishes the requested phase.
     * This waits on callbacks and does not poll matchmaking or game state.
     *
     * @param coordinator state coordinator to observe
     * @param phase requested reconciled phase
     * @return callback-completed state future
     */
    private static CompletableFuture<ClientState> phaseFuture(
            ClientStateCoordinator coordinator, ClientPhase phase) {
        CompletableFuture<ClientState> reached = new CompletableFuture<>();
        coordinator.setStateListener(state -> {
            if (state.getPhase() == phase) {
                reached.complete(state);
            }
        });
        ClientState current = coordinator.getState();
        if (current.getPhase() == phase) {
            reached.complete(current);
        }
        return reached;
    }

    /**
     * Waits for one phase through the callback-completed future helper.
     *
     * @param coordinator state coordinator to observe
     * @param phase requested phase
     * @return state that reached the phase
     * @throws Exception if the phase is not reached within the timeout
     */
    private static ClientState awaitPhase(ClientStateCoordinator coordinator,
                                          ClientPhase phase) throws Exception {
        return phaseFuture(coordinator, phase).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Returns every occupied coordinate in the deterministic valid fleet.
     *
     * @return ordered unique ship targets
     */
    private static List<Coordinate> shipTargets() {
        List<Coordinate> targets = new ArrayList<>();
        int[] lengths = {5, 4, 3, 3, 2};
        for (int row = 0; row < lengths.length; row++) {
            for (int column = 0; column < lengths[row]; column++) {
                targets.add(new Coordinate(row, column));
            }
        }
        return List.copyOf(targets);
    }

    /**
     * Returns enough known-water coordinates for every possible opponent turn.
     *
     * @return ordered miss targets outside the deterministic fleet
     */
    private static List<Coordinate> safeMisses() {
        List<Coordinate> misses = new ArrayList<>();
        for (int column = 0; column < 10; column++) {
            misses.add(new Coordinate(9, column));
        }
        for (int column = 0; column < 7; column++) {
            misses.add(new Coordinate(8, column));
        }
        return List.copyOf(misses);
    }

    /**
     * Starts the production server main class in a dedicated process.
     *
     * @param repository repository root containing compiled server and shared classes
     * @param port available registry port
     * @return running server process
     * @throws IOException if the process cannot start
     */
    private static Process startServer(Path repository, int port) throws IOException {
        return startServer(repository, port,
                "jdbc:h2:mem:e2e-" + port + ";DB_CLOSE_DELAY=-1");
    }

    /**
     * Starts the production server main class with an explicit isolated persistence URL.
     *
     * @param repository repository root containing compiled server and shared classes
     * @param port available registry port
     * @param jdbcUrl isolated H2 URL supplied only to the child server process
     * @return running server process
     * @throws IOException if the process cannot start
     */
    private static Process startServer(Path repository, int port, String jdbcUrl)
            throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Path runtimeClasspath = repository.resolve("server/target/runtime-classpath.txt");
        if (!Files.isRegularFile(runtimeClasspath)) {
            throw new IllegalStateException("Server runtime classpath file was not generated");
        }
        String classpath = repository.resolve("server/target/classes")
                + System.getProperty("path.separator")
                + repository.resolve("shared/target/classes")
                + System.getProperty("path.separator")
                + Files.readString(runtimeClasspath, StandardCharsets.UTF_8).trim();
        return new ProcessBuilder(java.toString(),
                "-Djava.rmi.server.hostname=127.0.0.1",
                "-Dfleetlink.persistence.jdbc.url=" + jdbcUrl, "-cp", classpath,
                "io.github.tomerg12.fleetlink.server.rmi.FleetLinkServerMain",
                Integer.toString(port))
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start();
    }

    /**
     * Waits for the production server's registry-bound startup message.
     *
     * @param server running server process
     * @throws Exception if startup fails or does not finish within the timeout
     */
    private static void awaitServerReady(Process server) throws Exception {
        CompletableFuture<String> readyLine = CompletableFuture.supplyAsync(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        server.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                    if (line.contains("FleetLink RMI server bound")) {
                        return line;
                    }
                }
                return output.toString();
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read FleetLink server startup", exception);
            }
        });
        String output = readyLine.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(output != null && output.contains("FleetLink RMI server bound"), output);
    }

    /**
     * Resolves the repository root from either the root or client Maven working directory.
     *
     * @return repository root
     */
    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("server"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("server"))) {
            return parent;
        }
        throw new IllegalStateException("Could not resolve FleetLink repository root");
    }

    /**
     * Reserves and releases one local port for the server process registry.
     *
     * @return currently available local port
     * @throws IOException if a local port cannot be allocated
     */
    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Restores the process-local RMI hostname after callback transport cleanup.
     *
     * @param previous previous property value, or null if it was unset
     */
    private static void restoreHostname(String previous) {
        if (previous == null) {
            System.clearProperty("java.rmi.server.hostname");
        } else {
            System.setProperty("java.rmi.server.hostname", previous);
        }
    }

    /**
     * Stops only the dedicated server process created by this test.
     *
     * @param server test-owned server process
     * @throws InterruptedException if process shutdown wait is interrupted
     */
    private static void stopServer(Process server) throws InterruptedException {
        server.destroy();
        if (!server.waitFor(5, TimeUnit.SECONDS)) {
            server.destroyForcibly();
            server.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
