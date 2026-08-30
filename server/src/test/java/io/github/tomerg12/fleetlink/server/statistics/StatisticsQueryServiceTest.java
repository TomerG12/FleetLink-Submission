package io.github.tomerg12.fleetlink.server.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.completion.CompletionRecordOutcome;
import io.github.tomerg12.fleetlink.server.completion.CompletedGameSnapshot;
import io.github.tomerg12.fleetlink.server.completion.CompletedParticipantSnapshot;
import io.github.tomerg12.fleetlink.server.completion.JpaCompletedGameStore;
import io.github.tomerg12.fleetlink.server.persistence.FleetLinkPersistence;
import io.github.tomerg12.fleetlink.server.persistence.ParticipantResult;
import io.github.tomerg12.fleetlink.server.persistence.PersistenceTestSupport;
import io.github.tomerg12.fleetlink.server.persistence.PlayerEntity;
import io.github.tomerg12.fleetlink.server.persistence.PlayerRepository;
import io.github.tomerg12.fleetlink.server.rating.PlayerRatingAdjustment;
import io.github.tomerg12.fleetlink.server.rating.RatingIntegrityException;
import io.github.tomerg12.fleetlink.server.rating.RatedGameAdjustment;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchHistoryEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchOutcome;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies fresh-H2 statistics aggregation, history pagination, leaderboard ordering, and ratings.
 */
class StatisticsQueryServiceTest {

    private static final Instant BASE_TIME = Instant.parse("2026-08-23T12:00:00Z");

    /**
     * Returns zero aggregates and ratios for a registered player with no committed games.
     */
    @Test
    void returnsZeroStatisticsForRegisteredPlayerWithoutGames() {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            PlayerEntity player = player(UUID.randomUUID(), "Empty", "empty", 1000, 0);
            new PlayerRepository(persistence.getEntityManagerFactory()).create(player);
            RegisteredRatingRegistry ratings = ratings(player);
            StatisticsQueryService service = service(persistence, ratings);

            PlayerStatisticsView view = service.getPlayerStatistics(player.getId(), 0, 10);

            assertEquals(1000, view.getCurrentRating());
            assertEquals(0, view.getTotalGames());
            assertEquals(0, view.getWins());
            assertEquals(0, view.getLosses());
            assertEquals(0.0, view.getWinRate());
            assertEquals(0, view.getTotalShots());
            assertEquals(0, view.getHits());
            assertEquals(0, view.getShipsSunk());
            assertEquals(0.0, view.getAccuracy());
            assertEquals(0.0, view.getAverageHitsPerGame());
            assertTrue(view.getHistory().isEmpty());
            assertFalse(view.hasMore());
        }
    }

    /**
     * Aggregates registered and mixed games and maps every required newest-first history field.
     */
    @Test
    void aggregatesPersonalStatisticsAndMapsHistory() {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            PlayerRepository players = new PlayerRepository(persistence.getEntityManagerFactory());
            PlayerEntity ada = player(UUID.randomUUID(), "Ada", "ada", 1000, 0);
            PlayerEntity grace = player(UUID.randomUUID(), "Grace", "grace", 1000, 0);
            players.create(ada);
            players.create(grace);
            JpaCompletedGameStore store = new JpaCompletedGameStore(
                    persistence.getEntityManagerFactory());
            UUID ratedGameId = UUID.randomUUID();
            store.record(ratedGame(ratedGameId, ada, grace, ada.getId(), BASE_TIME,
                    10, 6, 2, 10, 8, 3, 1, 9));
            store.record(mixedGame(UUID.randomUUID(), ada, 1016, false, "Guest Winner",
                    GameEndReason.DISCONNECT, BASE_TIME.plusSeconds(300),
                    4, 1, 0, 5));
            RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
            ratings.seedIfAbsent(ada.getId(), 1016, 1);
            ratings.seedIfAbsent(grace.getId(), 984, 1);
            StatisticsQueryService service = service(persistence, ratings);

            PlayerStatisticsView view = service.getPlayerStatistics(ada.getId(), 0, 10);

            assertEquals(1016, view.getCurrentRating());
            assertEquals(2, view.getTotalGames());
            assertEquals(1, view.getWins());
            assertEquals(1, view.getLosses());
            assertEquals(0.5, view.getWinRate());
            assertEquals(14, view.getTotalShots());
            assertEquals(7, view.getHits());
            assertEquals(2, view.getShipsSunk());
            assertEquals(0.5, view.getAccuracy());
            assertEquals(3.5, view.getAverageHitsPerGame());
            assertEquals(2, view.getReturnedCount());
            assertFalse(view.hasMore());

            MatchHistoryEntryView mixed = view.getHistory().get(0);
            assertEquals("Guest Winner", mixed.getOpponentDisplayName());
            assertTrue(mixed.isOpponentGuest());
            assertEquals(MatchOutcome.LOSS, mixed.getOutcome());
            assertEquals(GameEndReason.DISCONNECT, mixed.getEndReason());
            assertEquals(5, mixed.getTurnsTaken());
            assertEquals(120, mixed.getDuration().toSeconds());
            assertEquals(0.25, mixed.getAccuracy());
            assertEquals(0, mixed.getShipsSunk());
            assertEquals(0, mixed.getRatingDelta());
            assertEquals(BASE_TIME.plusSeconds(300), mixed.getCompletedAt());

            MatchHistoryEntryView rated = view.getHistory().get(1);
            assertEquals("Grace", rated.getOpponentDisplayName());
            assertFalse(rated.isOpponentGuest());
            assertEquals(MatchOutcome.WIN, rated.getOutcome());
            assertEquals(GameEndReason.ALL_SHIPS_SUNK, rated.getEndReason());
            assertEquals(16, rated.getRatingDelta());
        }
    }

    /**
     * Uses completion descending and game-ID descending ordering with database offset and limit.
     */
    @Test
    void paginatesHistoryWithStableGameIdTieBreak() {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            PlayerRepository players = new PlayerRepository(persistence.getEntityManagerFactory());
            PlayerEntity player = player(UUID.randomUUID(), "History", "history", 1000, 0);
            players.create(player);
            JpaCompletedGameStore store = new JpaCompletedGameStore(
                    persistence.getEntityManagerFactory());
            UUID lowId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            UUID highId = UUID.fromString("00000000-0000-0000-0000-000000000002");
            UUID newestId = UUID.fromString("00000000-0000-0000-0000-000000000003");
            store.record(mixedGame(lowId, player, 1000, true, "Tie Low",
                    GameEndReason.RESIGNATION, BASE_TIME, 1, 0, 0, 1));
            store.record(mixedGame(highId, player, 1000, true, "Tie High",
                    GameEndReason.TIMEOUT, BASE_TIME, 2, 1, 0, 2));
            store.record(mixedGame(newestId, player, 1000, true, "Newest",
                    GameEndReason.ALL_SHIPS_SUNK, BASE_TIME.plusSeconds(60),
                    3, 2, 1, 3));
            StatisticsQueryService service = service(persistence, ratings(player));

            PlayerStatisticsView first = service.getPlayerStatistics(player.getId(), 0, 2);
            PlayerStatisticsView second = service.getPlayerStatistics(player.getId(), 2, 2);
            PlayerStatisticsView beyond = service.getPlayerStatistics(player.getId(), 99, 2);

            assertEquals(List.of("Newest", "Tie High"), first.getHistory().stream()
                    .map(MatchHistoryEntryView::getOpponentDisplayName).toList());
            assertTrue(first.hasMore());
            assertEquals(List.of("Tie Low"), second.getHistory().stream()
                    .map(MatchHistoryEntryView::getOpponentDisplayName).toList());
            assertEquals(0.0, second.getHistory().get(0).getAccuracy());
            assertFalse(second.hasMore());
            assertEquals(2, second.getHistoryOffset());
            assertTrue(beyond.getHistory().isEmpty());
            assertFalse(beyond.hasMore());
            assertEquals(99, beyond.getHistoryOffset());
        }
    }

    /**
     * Keeps zero-game accounts, applies every deterministic tie key, aggregates wins, and limits.
     */
    @Test
    void ordersAndLimitsCommittedLeaderboardWithSequentialRanks() {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            dropUsernameKeyConstraintForFinalTieFixture(persistence);
            PlayerRepository players = new PlayerRepository(persistence.getEntityManagerFactory());
            PlayerEntity top = player(uuid(9), "Top", "top", 1500, 0);
            PlayerEntity alpha = player(uuid(8), "Alpha", "alpha", 1400, 0);
            PlayerEntity zulu = player(uuid(7), "Zulu", "zulu", 1400, 0);
            PlayerEntity tieLow = player(uuid(1), "Tie Low", "tie", 1300, 0);
            PlayerEntity tieHigh = player(uuid(2), "Tie High", "tie", 1300, 0);
            PlayerEntity winner = player(uuid(3), "Winner", "winner", 1000, 0);
            PlayerEntity loser = player(uuid(4), "Loser", "loser", 1000, 0);
            for (PlayerEntity player : List.of(top, alpha, zulu, tieLow, tieHigh, winner, loser)) {
                players.create(player);
            }
            new JpaCompletedGameStore(persistence.getEntityManagerFactory()).record(
                    ratedGame(UUID.randomUUID(), winner, loser, winner.getId(), BASE_TIME,
                            5, 3, 1, 5, 4, 1, 0, 4));
            RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
            for (PlayerEntity player : List.of(top, alpha, zulu, tieLow, tieHigh)) {
                ratings.seedIfAbsent(player.getId(), player.getRating(), 0);
            }
            ratings.seedIfAbsent(winner.getId(), 1016, 1);
            ratings.seedIfAbsent(loser.getId(), 984, 1);
            StatisticsQueryService service = service(persistence, ratings);

            List<LeaderboardEntryView> entries = service.getLeaderboard(6);

            assertEquals(List.of("Top", "Alpha", "Zulu", "Tie Low", "Tie High", "Winner"),
                    entries.stream().map(LeaderboardEntryView::getUsername).toList());
            assertEquals(List.of(1, 2, 3, 4, 5, 6),
                    entries.stream().map(LeaderboardEntryView::getRank).toList());
            assertEquals(0, entries.get(0).getGamesPlayed());
            assertEquals(0, entries.get(0).getWins());
            assertEquals(1, entries.get(5).getGamesPlayed());
            assertEquals(1, entries.get(5).getWins());
            assertEquals(2, service.getLeaderboard(2).size());
        }
    }

    /**
     * Proves personal rating is live while leaderboard rating remains committed until recording.
     */
    @Test
    void separatesLivePersonalRatingFromCommittedLeaderboardRating() {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            PlayerRepository players = new PlayerRepository(persistence.getEntityManagerFactory());
            PlayerEntity first = player(uuid(11), "First", "first", 1000, 0);
            PlayerEntity second = player(uuid(12), "Second", "second", 1000, 0);
            players.create(first);
            players.create(second);
            RegisteredRatingRegistry ratings = ratings(first, second);
            UUID gameId = UUID.randomUUID();
            PlayerView firstView = view(first, 1000);
            PlayerView secondView = view(second, 1000);
            RatedGameAdjustment adjustment = ratings.applyRatedGame(
                    gameId, firstView, secondView, first.getId());
            StatisticsQueryService service = service(persistence, ratings);

            assertEquals(1016,
                    service.getPlayerStatistics(first.getId(), 0, 10).getCurrentRating());
            assertEquals(1000, leaderboardRating(service, "First"));

            new JpaCompletedGameStore(persistence.getEntityManagerFactory()).record(
                    ratedGame(gameId, first, second, first.getId(), BASE_TIME, adjustment,
                            5, 3, 1, 5, 4, 1, 0, 4));

            assertEquals(1016, leaderboardRating(service, "First"));
        }
    }

    /**
     * Holds a personal read between its paired queries and proves a concurrent durable completion
     * commits without entering the in-flight request's earlier committed snapshot.
     *
     * @throws Exception if a bounded deterministic future cannot complete
     */
    @Test
    void personalStatisticsUsesOneNonblockingCommittedSnapshot() throws Exception {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            PlayerEntity player = player(UUID.randomUUID(), "Snapshot", "snapshot", 1000, 0);
            new PlayerRepository(persistence.getEntityManagerFactory()).create(player);
            CountDownLatch aggregateRead = new CountDownLatch(1);
            CountDownLatch releaseRead = new CountDownLatch(1);
            StatisticsRepository repository = new StatisticsRepository(
                    persistence.getEntityManagerFactory(), new StatisticsRepository.QueryObserver() {
                        /** {@inheritDoc} */
                        @Override
                        public void afterPersonalAggregateRead() {
                            aggregateRead.countDown();
                            awaitLatch(releaseRead);
                        }
                    });
            StatisticsQueryService service = new StatisticsQueryService(
                    repository, ratings(player));
            JpaCompletedGameStore store = new JpaCompletedGameStore(
                    persistence.getEntityManagerFactory());

            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<PlayerStatisticsView> read = executor.submit(
                        () -> service.getPlayerStatistics(player.getId(), 0, 10));
                assertTrue(aggregateRead.await(10, TimeUnit.SECONDS));
                Future<CompletionRecordOutcome> completion = executor.submit(() -> store.record(
                        mixedGame(UUID.randomUUID(), player, 1000, true, "Concurrent Guest",
                                GameEndReason.RESIGNATION, BASE_TIME, 3, 2, 1, 3)));
                try {
                    assertEquals(CompletionRecordOutcome.RECORDED,
                            completion.get(10, TimeUnit.SECONDS));
                    assertFalse(read.isDone());
                } finally {
                    releaseRead.countDown();
                }

                PlayerStatisticsView inFlight = read.get(10, TimeUnit.SECONDS);
                assertEquals(0, inFlight.getTotalGames());
                assertEquals(0, inFlight.getWins());
                assertEquals(0, inFlight.getLosses());
                assertEquals(0, inFlight.getTotalShots());
                assertEquals(0, inFlight.getHits());
                assertEquals(0, inFlight.getShipsSunk());
                assertTrue(inFlight.getHistory().isEmpty());
                assertFalse(inFlight.hasMore());
            }

            PlayerStatisticsView later = service.getPlayerStatistics(player.getId(), 0, 10);
            assertEquals(1, later.getTotalGames());
            assertEquals(1, later.getWins());
            assertEquals(1, later.getHistory().size());
        }
    }

    /**
     * Holds a leaderboard read after ordered selection and proves a concurrent rated completion
     * commits while the in-flight request keeps its earlier ratings and zero-game aggregates.
     *
     * @throws Exception if a bounded deterministic future cannot complete
     */
    @Test
    void leaderboardUsesOneNonblockingCommittedSnapshot() throws Exception {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            PlayerRepository players = new PlayerRepository(persistence.getEntityManagerFactory());
            PlayerEntity alpha = player(uuid(13), "Alpha", "alpha", 1000, 0);
            PlayerEntity bravo = player(uuid(14), "Bravo", "bravo", 1000, 0);
            players.create(alpha);
            players.create(bravo);
            RegisteredRatingRegistry ratings = ratings(alpha, bravo);
            UUID gameId = UUID.randomUUID();
            RatedGameAdjustment adjustment = ratings.applyRatedGame(
                    gameId, view(alpha, 1000), view(bravo, 1000), alpha.getId());
            CountDownLatch selectionRead = new CountDownLatch(1);
            CountDownLatch releaseRead = new CountDownLatch(1);
            StatisticsRepository repository = new StatisticsRepository(
                    persistence.getEntityManagerFactory(), new StatisticsRepository.QueryObserver() {
                        /** {@inheritDoc} */
                        @Override
                        public void afterLeaderboardSelectionRead() {
                            selectionRead.countDown();
                            awaitLatch(releaseRead);
                        }
                    });
            StatisticsQueryService service = new StatisticsQueryService(repository, ratings);
            JpaCompletedGameStore store = new JpaCompletedGameStore(
                    persistence.getEntityManagerFactory());

            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<List<LeaderboardEntryView>> read = executor.submit(
                        () -> service.getLeaderboard(10));
                assertTrue(selectionRead.await(10, TimeUnit.SECONDS));
                Future<CompletionRecordOutcome> completion = executor.submit(() -> store.record(
                        ratedGame(gameId, alpha, bravo, alpha.getId(), BASE_TIME, adjustment,
                                5, 3, 1, 5, 4, 1, 0, 4)));
                try {
                    assertEquals(CompletionRecordOutcome.RECORDED,
                            completion.get(10, TimeUnit.SECONDS));
                    assertFalse(read.isDone());
                } finally {
                    releaseRead.countDown();
                }

                List<LeaderboardEntryView> inFlight = read.get(10, TimeUnit.SECONDS);
                assertEquals(List.of("Alpha", "Bravo"), inFlight.stream()
                        .map(LeaderboardEntryView::getUsername).toList());
                assertEquals(List.of(1000, 1000), inFlight.stream()
                        .map(LeaderboardEntryView::getRating).toList());
                assertEquals(List.of(0L, 0L), inFlight.stream()
                        .map(LeaderboardEntryView::getGamesPlayed).toList());
                assertEquals(List.of(0L, 0L), inFlight.stream()
                        .map(LeaderboardEntryView::getWins).toList());
            }

            List<LeaderboardEntryView> later = service.getLeaderboard(10);
            assertEquals(List.of("Alpha", "Bravo"), later.stream()
                    .map(LeaderboardEntryView::getUsername).toList());
            assertEquals(List.of(1016, 984), later.stream()
                    .map(LeaderboardEntryView::getRating).toList());
            assertEquals(List.of(1L, 1L), later.stream()
                    .map(LeaderboardEntryView::getGamesPlayed).toList());
            assertEquals(List.of(1L, 0L), later.stream()
                    .map(LeaderboardEntryView::getWins).toList());
        }
    }

    /**
     * Restores pooled connection isolation after both a committed read and a rolled-back read.
     */
    @Test
    void restoresPreviousConnectionIsolationAfterSuccessAndFailure() {
        try (FleetLinkPersistence persistence = FleetLinkPersistence.withProperties(Map.of(
                "jakarta.persistence.jdbc.url",
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.show_sql", "false",
                "hibernate.connection.pool_size", "1"))) {
            int previousIsolation = currentIsolation(persistence);
            new StatisticsRepository(persistence.getEntityManagerFactory()).loadLeaderboard(1);
            assertEquals(previousIsolation, currentIsolation(persistence));

            StatisticsRepository failingRepository = new StatisticsRepository(
                    persistence.getEntityManagerFactory(), new StatisticsRepository.QueryObserver() {
                        /** {@inheritDoc} */
                        @Override
                        public void afterLeaderboardSelectionRead() {
                            throw new IllegalStateException("controlled observer failure");
                        }
                    });
            assertThrows(IllegalStateException.class,
                    () -> failingRepository.loadLeaderboard(1));
            assertEquals(previousIsolation, currentIsolation(persistence));
        }
    }

    /**
     * Rejects invalid repository bounds before attempting a query.
     */
    @Test
    void repositoryRejectsInvalidDirectBounds() {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            StatisticsRepository repository = new StatisticsRepository(
                    persistence.getEntityManagerFactory());

            assertThrows(NullPointerException.class,
                    () -> repository.loadPersonalStatistics(null, 0, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> repository.loadPersonalStatistics(UUID.randomUUID(), -1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> repository.loadPersonalStatistics(UUID.randomUUID(), 0, 0));
            assertThrows(IllegalArgumentException.class, () -> repository.loadLeaderboard(0));
        }
    }

    /**
     * Treats an unexpectedly missing live registered rating as integrity failure without DB fallback.
     */
    @Test
    void missingLiveRegisteredRatingDoesNotFallBackToDatabase() {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            PlayerEntity player = player(UUID.randomUUID(), "MissingLive", "missinglive", 1300, 0);
            new PlayerRepository(persistence.getEntityManagerFactory()).create(player);
            StatisticsQueryService service = service(
                    persistence, new RegisteredRatingRegistry());

            assertThrows(RatingIntegrityException.class,
                    () -> service.getPlayerStatistics(player.getId(), 0, 10));
        }
    }

    /**
     * Reopens a file-backed H2 database and returns the same durable history and leaderboard data.
     *
     * @param temporaryDirectory isolated filesystem location
     */
    @Test
    void statisticsQueriesSurvivePersistenceRestart(@TempDir Path temporaryDirectory) {
        String databasePath = temporaryDirectory.resolve("statistics-restart")
                .toAbsolutePath().toString().replace('\\', '/');
        String jdbcUrl = "jdbc:h2:file:" + databasePath + ";DB_CLOSE_ON_EXIT=FALSE";
        UUID playerId = UUID.randomUUID();
        try (FleetLinkPersistence first = PersistenceTestSupport.open(jdbcUrl, "create")) {
            PlayerEntity player = player(playerId, "Restarted", "restarted", 1000, 0);
            new PlayerRepository(first.getEntityManagerFactory()).create(player);
            new JpaCompletedGameStore(first.getEntityManagerFactory()).record(
                    mixedGame(UUID.randomUUID(), player, 1000, true, "Durable Guest",
                            GameEndReason.RESIGNATION, BASE_TIME, 2, 1, 1, 2));
        }

        try (FleetLinkPersistence reopened = PersistenceTestSupport.open(jdbcUrl, "validate")) {
            PlayerEntity stored = new PlayerRepository(reopened.getEntityManagerFactory())
                    .findById(playerId).orElseThrow();
            StatisticsQueryService service = service(reopened, ratings(stored));

            PlayerStatisticsView statistics = service.getPlayerStatistics(playerId, 0, 10);
            assertEquals(1, statistics.getTotalGames());
            assertEquals("Durable Guest",
                    statistics.getHistory().get(0).getOpponentDisplayName());
            assertEquals("Restarted", service.getLeaderboard(10).get(0).getUsername());
        }
    }

    /**
     * Creates the service over one repository and the supplied existing registry.
     *
     * @param persistence isolated persistence owner
     * @param ratings live rating registry
     * @return query service
     */
    private static StatisticsQueryService service(FleetLinkPersistence persistence,
                                                  RegisteredRatingRegistry ratings) {
        return new StatisticsQueryService(
                new StatisticsRepository(persistence.getEntityManagerFactory()), ratings);
    }

    /**
     * Creates and seeds a live registry from detached persistent players.
     *
     * @param players players to seed
     * @return seeded registry
     */
    private static RegisteredRatingRegistry ratings(PlayerEntity... players) {
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        for (PlayerEntity player : players) {
            ratings.seedIfAbsent(player.getId(), player.getRating(), player.getRatingRevision());
        }
        return ratings;
    }

    /**
     * Creates one persistent player fixture with deterministic identity and committed rating.
     *
     * @param id persistent identifier
     * @param username case-preserving username
     * @param usernameKey deterministic normalized ordering key
     * @param rating committed rating
     * @param revision committed rating revision
     * @return transient player entity
     */
    private static PlayerEntity player(UUID id, String username, String usernameKey,
                                       int rating, long revision) {
        return new PlayerEntity(id, username, usernameKey, new byte[]{1}, new byte[]{2},
                1, rating, revision, BASE_TIME.minusSeconds(3600));
    }

    /**
     * Creates one registered protocol player at an explicit live rating.
     *
     * @param player persistent player
     * @param rating live rating
     * @return registered player view
     */
    private static PlayerView view(PlayerEntity player, int rating) {
        return new PlayerView(player.getId(), player.getUsername(), rating, false);
    }

    /**
     * Creates a rated game using Elo changes derived from both rating-1000 fixtures.
     *
     * @param gameId game identifier
     * @param first first registered participant
     * @param second second registered participant
     * @param winnerId winner identifier
     * @param completedAt completion time
     * @param firstShots first shots
     * @param firstHits first hits
     * @param firstSunk first sunk ships
     * @param firstTurns first turns
     * @param secondShots second shots
     * @param secondHits second hits
     * @param secondSunk second sunk ships
     * @param secondTurns second turns
     * @return rated completion snapshot
     */
    private static CompletedGameSnapshot ratedGame(
            UUID gameId, PlayerEntity first, PlayerEntity second, UUID winnerId,
            Instant completedAt, int firstShots, int firstHits, int firstSunk, int firstTurns,
            int secondShots, int secondHits, int secondSunk, int secondTurns) {
        RegisteredRatingRegistry registry = ratings(first, second);
        RatedGameAdjustment adjustment = registry.applyRatedGame(
                gameId, view(first, first.getRating()), view(second, second.getRating()), winnerId);
        return ratedGame(gameId, first, second, winnerId, completedAt, adjustment,
                firstShots, firstHits, firstSunk, firstTurns,
                secondShots, secondHits, secondSunk, secondTurns);
    }

    /**
     * Creates a rated persistent aggregate from an already applied live adjustment.
     *
     * @param gameId game identifier
     * @param first first registered participant
     * @param second second registered participant
     * @param winnerId winner identifier
     * @param completedAt completion time
     * @param adjustment immutable live adjustment
     * @param firstShots first shots
     * @param firstHits first hits
     * @param firstSunk first sunk ships
     * @param firstTurns first turns
     * @param secondShots second shots
     * @param secondHits second hits
     * @param secondSunk second sunk ships
     * @param secondTurns second turns
     * @return rated completion snapshot
     */
    private static CompletedGameSnapshot ratedGame(
            UUID gameId, PlayerEntity first, PlayerEntity second, UUID winnerId,
            Instant completedAt, RatedGameAdjustment adjustment,
            int firstShots, int firstHits, int firstSunk, int firstTurns,
            int secondShots, int secondHits, int secondSunk, int secondTurns) {
        CompletedParticipantSnapshot firstSnapshot = ratedParticipant(first,
                winnerId.equals(first.getId()) ? ParticipantResult.WIN : ParticipantResult.LOSS,
                adjustment.adjustmentFor(first.getId()),
                firstShots, firstHits, firstSunk, firstTurns);
        CompletedParticipantSnapshot secondSnapshot = ratedParticipant(second,
                winnerId.equals(second.getId()) ? ParticipantResult.WIN : ParticipantResult.LOSS,
                adjustment.adjustmentFor(second.getId()),
                secondShots, secondHits, secondSunk, secondTurns);
        return new CompletedGameSnapshot(gameId, completedAt.minusSeconds(180), completedAt,
                GameEndReason.ALL_SHIPS_SUNK, winnerId,
                List.of(firstSnapshot, secondSnapshot));
    }

    /**
     * Creates one rated participant from the registry's immutable adjustment.
     *
     * @param player persistent player fixture
     * @param result terminal participant result
     * @param adjustment live rating transition
     * @param shots accepted shots
     * @param hits accepted hits
     * @param sunk sunk ships
     * @param turns consumed turns
     * @return completion participant
     */
    private static CompletedParticipantSnapshot ratedParticipant(
            PlayerEntity player, ParticipantResult result, PlayerRatingAdjustment adjustment,
            int shots, int hits, int sunk, int turns) {
        return new CompletedParticipantSnapshot(player.getId(), player.getUsername(), false,
                adjustment.getRatingBefore(), result, shots, hits, sunk, turns,
                adjustment.getRatingDelta(), adjustment.getRatingRevisionBefore());
    }

    /**
     * Creates one unrated mixed game with the registered participant on either result side.
     *
     * @param gameId game identifier
     * @param registered registered participant
     * @param registeredRating registered rating captured by the game
     * @param registeredWins whether the registered participant won
     * @param guestName opponent snapshot name
     * @param endReason terminal reason
     * @param completedAt completion time
     * @param shots registered accepted shots
     * @param hits registered hits
     * @param sunk registered sunk ships
     * @param turns registered turns
     * @return mixed completion snapshot
     */
    private static CompletedGameSnapshot mixedGame(
            UUID gameId, PlayerEntity registered, int registeredRating, boolean registeredWins,
            String guestName, GameEndReason endReason, Instant completedAt,
            int shots, int hits, int sunk, int turns) {
        ParticipantResult registeredResult = registeredWins
                ? ParticipantResult.WIN : ParticipantResult.LOSS;
        ParticipantResult guestResult = registeredWins
                ? ParticipantResult.LOSS : ParticipantResult.WIN;
        CompletedParticipantSnapshot registeredSnapshot = new CompletedParticipantSnapshot(
                registered.getId(), registered.getUsername(), false, registeredRating,
                registeredResult, shots, hits, sunk, turns, 0, null);
        CompletedParticipantSnapshot guest = new CompletedParticipantSnapshot(
                UUID.randomUUID(), guestName, true, 1000, guestResult,
                2, 1, 0, 2, 0, null);
        UUID winnerId = registeredWins ? registered.getId() : guest.getPlayerId();
        return new CompletedGameSnapshot(gameId, completedAt.minusSeconds(120), completedAt,
                endReason, winnerId, List.of(registeredSnapshot, guest));
    }

    /**
     * Reads one username's committed leaderboard rating.
     *
     * @param service statistics service
     * @param username expected username
     * @return committed rating
     */
    private static int leaderboardRating(StatisticsQueryService service, String username) {
        return service.getLeaderboard(100).stream()
                .filter(entry -> entry.getUsername().equals(username))
                .findFirst().orElseThrow().getRating();
    }

    /**
     * Awaits a deterministic test release without using timing as correctness coordination.
     *
     * @param latch release latch
     * @throws AssertionError if the test cannot reach the expected interleaving
     */
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out awaiting deterministic test release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted awaiting deterministic test release", exception);
        }
    }

    /**
     * Reads the only pooled connection's current isolation outside a transaction.
     *
     * @param persistence isolated one-connection persistence owner
     * @return current JDBC transaction-isolation value
     */
    private static int currentIsolation(FleetLinkPersistence persistence) {
        SessionFactory sessionFactory = persistence.getEntityManagerFactory()
                .unwrap(SessionFactory.class);
        try (Session session = sessionFactory.openSession()) {
            return session.doReturningWork(connection -> connection.getTransactionIsolation());
        }
    }

    /**
     * Removes only the username-key uniqueness constraint in one isolated test database so two
     * rows can exercise the otherwise unreachable final UUID ordering tie.
     *
     * @param persistence isolated test persistence
     */
    private static void dropUsernameKeyConstraintForFinalTieFixture(
            FleetLinkPersistence persistence) {
        try (EntityManager entityManager = persistence.getEntityManagerFactory()
                .createEntityManager()) {
            entityManager.getTransaction().begin();
            entityManager.createNativeQuery(
                    "alter table players drop constraint uk_players_username_key").executeUpdate();
            entityManager.getTransaction().commit();
        }
    }

    /**
     * Creates a deterministic UUID whose last hexadecimal digit supplies ordering.
     *
     * @param suffix final hexadecimal digit from zero through fifteen
     * @return deterministic UUID
     */
    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-00000000000"
                + Integer.toHexString(suffix));
    }
}
