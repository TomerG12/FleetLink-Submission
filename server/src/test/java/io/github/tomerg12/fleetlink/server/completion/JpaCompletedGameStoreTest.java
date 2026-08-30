package io.github.tomerg12.fleetlink.server.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.persistence.FleetLinkPersistence;
import io.github.tomerg12.fleetlink.server.persistence.ParticipantResult;
import io.github.tomerg12.fleetlink.server.persistence.PersistenceTestSupport;
import io.github.tomerg12.fleetlink.server.persistence.PlayerEntity;
import io.github.tomerg12.fleetlink.server.persistence.PlayerRepository;
import io.github.tomerg12.fleetlink.server.rating.EloRatingCalculator;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies atomic aggregate persistence, eligibility, idempotency, and conflict detection.
 */
class JpaCompletedGameStoreTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-08-22T10:00:00Z");

    /**
     * Persists registered and mixed games with exactly two correctly linked participant rows.
     */
    @Test
    void persistsRegisteredAndMixedGamesInEitherParticipantOrder() {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            PlayerRepository players = new PlayerRepository(persistence.getEntityManagerFactory());
            PlayerEntity registered = player("Registered", 1250);
            PlayerEntity secondRegistered = player("Second", 980);
            players.create(registered);
            players.create(secondRegistered);
            JpaCompletedGameStore store = new JpaCompletedGameStore(
                    persistence.getEntityManagerFactory());

            CompletedGameSnapshot registeredGame = snapshot(UUID.randomUUID(),
                    ratedParticipant(registered, secondRegistered, ParticipantResult.WIN),
                    ratedParticipant(secondRegistered, registered, ParticipantResult.LOSS),
                    GameEndReason.ALL_SHIPS_SUNK);
            CompletedGameSnapshot registeredFirst = snapshot(UUID.randomUUID(),
                    participant(registered, ParticipantResult.WIN),
                    guest("Guest A", ParticipantResult.LOSS), GameEndReason.RESIGNATION);
            CompletedGameSnapshot guestFirst = snapshot(UUID.randomUUID(),
                    guest("Guest B", ParticipantResult.LOSS),
                    participant(registered, ParticipantResult.WIN), GameEndReason.DISCONNECT);

            assertEquals(CompletionRecordOutcome.RECORDED, store.record(registeredGame));
            assertEquals(CompletionRecordOutcome.RECORDED, store.record(registeredFirst));
            assertEquals(CompletionRecordOutcome.RECORDED, store.record(guestFirst));
            assertEquals(3, store.countGames());
            assertEquals(6, store.countParticipants());
            assertEquals(registered.getRating() + EloRatingCalculator.ratingDelta(
                    registered.getRating(), secondRegistered.getRating(), true),
                    players.findById(registered.getId()).orElseThrow().getRating());
            assertEquals(1L, players.findById(registered.getId()).orElseThrow()
                    .getRatingRevision());

            CompletedGameSnapshot storedMixed = store.find(guestFirst.getGameId()).orElseThrow();
            assertEquals(GameEndReason.DISCONNECT, storedMixed.getEndReason());
            assertEquals(1, storedMixed.getParticipants().stream()
                    .filter(CompletedParticipantSnapshot::isGuest).count());
            assertEquals(1000, storedMixed.getParticipants().stream()
                    .filter(CompletedParticipantSnapshot::isGuest).findFirst().orElseThrow()
                    .getRatingAtMatch());
            assertEquals(1, storedMixed.getParticipants().stream()
                    .filter(p -> p.getResult() == ParticipantResult.WIN).count());
            assertEquals(1, storedMixed.getParticipants().stream()
                    .filter(p -> p.getResult() == ParticipantResult.LOSS).count());
        }
    }

    /**
     * Skips guest-only games without creating Game or GameParticipant rows.
     */
    @Test
    void guestOnlyGameIsNotPersisted() {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            JpaCompletedGameStore store = new JpaCompletedGameStore(
                    persistence.getEntityManagerFactory());
            CompletedGameSnapshot guests = snapshot(UUID.randomUUID(),
                    guest("Guest One", ParticipantResult.WIN),
                    guest("Guest Two", ParticipantResult.LOSS), GameEndReason.RESIGNATION);

            assertEquals(CompletionRecordOutcome.NOT_ELIGIBLE, store.record(guests));
            assertEquals(0, store.countGames());
            assertEquals(0, store.countParticipants());
        }
    }

    /**
     * Treats equivalent data as an idempotent no-op and conflicting data as integrity failure.
     */
    @Test
    void duplicateRequiresEquivalentAuthoritativeData() {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            PlayerRepository players = new PlayerRepository(persistence.getEntityManagerFactory());
            PlayerEntity registered = player("Account", 1100);
            players.create(registered);
            JpaCompletedGameStore store = new JpaCompletedGameStore(
                    persistence.getEntityManagerFactory());
            UUID gameId = UUID.randomUUID();
            CompletedGameSnapshot original = snapshot(gameId,
                    participant(registered, ParticipantResult.WIN),
                    guest("Guest", ParticipantResult.LOSS), GameEndReason.ALL_SHIPS_SUNK);
            CompletedGameSnapshot conflict = snapshot(gameId,
                    participant(registered, ParticipantResult.LOSS),
                    guest("Guest", ParticipantResult.WIN), GameEndReason.RESIGNATION);

            assertEquals(CompletionRecordOutcome.RECORDED, store.record(original));
            assertEquals(CompletionRecordOutcome.ALREADY_RECORDED, store.record(original));
            assertThrows(CompletionIntegrityException.class, () -> store.record(conflict));
            assertEquals(1, store.countGames());
            assertEquals(2, store.countParticipants());
        }
    }

    /**
     * Normalizes completion timestamps to database precision so a retry remains equivalent.
     */
    @Test
    void nanosecondTimestampRoundTripRemainsIdempotent() {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            PlayerRepository players = new PlayerRepository(persistence.getEntityManagerFactory());
            PlayerEntity registered = player("Precision", 1000);
            players.create(registered);
            JpaCompletedGameStore store = new JpaCompletedGameStore(
                    persistence.getEntityManagerFactory());
            CompletedParticipantSnapshot winner = participant(
                    registered, ParticipantResult.WIN);
            CompletedParticipantSnapshot loser = guest("Guest", ParticipantResult.LOSS);
            CompletedGameSnapshot snapshot = new CompletedGameSnapshot(UUID.randomUUID(),
                    Instant.parse("2026-08-22T09:59:00.987654321Z"),
                    Instant.parse("2026-08-22T10:00:00.123456789Z"),
                    GameEndReason.ALL_SHIPS_SUNK, winner.getPlayerId(), List.of(winner, loser));

            assertEquals(CompletionRecordOutcome.RECORDED, store.record(snapshot));
            assertEquals(CompletionRecordOutcome.ALREADY_RECORDED, store.record(snapshot));
        }
    }

    /**
     * Allows concurrent equivalent requests to produce one durable aggregate.
     *
     * @throws Exception if concurrent execution fails
     */
    @Test
    void concurrentDuplicateRecordingProducesOneAggregate() throws Exception {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            PlayerRepository players = new PlayerRepository(persistence.getEntityManagerFactory());
            PlayerEntity registered = player("Concurrent", 1000);
            PlayerEntity opponent = player("ConcurrentOpponent", 1000);
            players.create(registered);
            players.create(opponent);
            JpaCompletedGameStore store = new JpaCompletedGameStore(
                    persistence.getEntityManagerFactory());
            CompletedGameSnapshot snapshot = snapshot(UUID.randomUUID(),
                    ratedParticipant(registered, opponent, ParticipantResult.WIN),
                    ratedParticipant(opponent, registered, ParticipantResult.LOSS),
                    GameEndReason.DISCONNECT);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<CompletionRecordOutcome> first = executor.submit(() -> {
                    start.await();
                    return store.record(snapshot);
                });
                Future<CompletionRecordOutcome> second = executor.submit(() -> {
                    start.await();
                    return store.record(snapshot);
                });
                start.countDown();
                assertTrue(List.of(first.get(), second.get()).contains(
                        CompletionRecordOutcome.RECORDED));
                assertEquals(1, store.countGames());
                assertEquals(2, store.countParticipants());
                assertEquals(1016, players.findById(registered.getId()).orElseThrow().getRating());
                assertEquals(1L, players.findById(registered.getId()).orElseThrow()
                        .getRatingRevision());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Rolls back a later revision until its predecessor commits, then applies both transitions.
     *
     * @throws Exception if the controlled worker cannot complete
     */
    @Test
    void laterCompletionWaitsForPredecessorWithoutLeavingRows() throws Exception {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            PlayerRepository players = new PlayerRepository(persistence.getEntityManagerFactory());
            PlayerEntity first = player("OrderedFirst", 1000);
            PlayerEntity second = player("OrderedSecond", 1000);
            players.create(first);
            players.create(second);
            JpaCompletedGameStore store = new JpaCompletedGameStore(
                    persistence.getEntityManagerFactory());
            CompletedGameSnapshot gameA = ratedSnapshot(UUID.randomUUID(), first.getId(),
                    first.getUsername(), 1000, 0L, second.getId(), second.getUsername(),
                    1000, 0L, true);
            CompletedGameSnapshot gameB = ratedSnapshot(UUID.randomUUID(), first.getId(),
                    first.getUsername(), 1016, 1L, second.getId(), second.getUsername(),
                    984, 1L, true);
            CountDownLatch laterReady = new CountDownLatch(1);
            CountDownLatch allowLater = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Class<?>> firstAttempt = executor.submit(() -> {
                    laterReady.countDown();
                    allowLater.await();
                    return assertThrows(CompletionPredecessorPendingException.class,
                            () -> store.record(gameB)).getClass();
                });
                laterReady.await();
                allowLater.countDown();
                assertEquals(CompletionPredecessorPendingException.class, firstAttempt.get());
                assertEquals(0, store.countGames());
                assertEquals(0, store.countParticipants());

                assertEquals(CompletionRecordOutcome.RECORDED, store.record(gameA));
                assertEquals(CompletionRecordOutcome.RECORDED, store.record(gameB));
                assertEquals(2, store.countGames());
                assertEquals(4, store.countParticipants());
                PlayerEntity storedFirst = players.findById(first.getId()).orElseThrow();
                PlayerEntity storedSecond = players.findById(second.getId()).orElseThrow();
                assertEquals(1031, storedFirst.getRating());
                assertEquals(969, storedSecond.getRating());
                assertEquals(2L, storedFirst.getRatingRevision());
                assertEquals(2L, storedSecond.getRatingRevision());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Treats equal-revision base mismatch and higher durable revisions as permanent integrity
     * failures while preserving pair atomicity and duplicate idempotency.
     */
    @Test
    void durableRatingGuardRejectsWrongBaseAndStaleTransition() {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory()) {
            PlayerRepository players = new PlayerRepository(persistence.getEntityManagerFactory());
            PlayerEntity first = player("GuardFirst", 1000);
            PlayerEntity second = player("GuardSecond", 1000);
            players.create(first);
            players.create(second);
            JpaCompletedGameStore store = new JpaCompletedGameStore(
                    persistence.getEntityManagerFactory());
            CompletedGameSnapshot wrongBase = ratedSnapshot(UUID.randomUUID(), first.getId(),
                    first.getUsername(), 1000, 0L, second.getId(), second.getUsername(),
                    999, 0L, true);

            assertThrows(CompletionIntegrityException.class, () -> store.record(wrongBase));
            assertEquals(0, store.countGames());
            assertEquals(1000, players.findById(first.getId()).orElseThrow().getRating());
            assertEquals(0L, players.findById(first.getId()).orElseThrow().getRatingRevision());

            CompletedGameSnapshot valid = ratedSnapshot(UUID.randomUUID(), first.getId(),
                    first.getUsername(), 1000, 0L, second.getId(), second.getUsername(),
                    1000, 0L, true);
            assertEquals(CompletionRecordOutcome.RECORDED, store.record(valid));
            assertEquals(CompletionRecordOutcome.ALREADY_RECORDED, store.record(valid));
            assertEquals(1016, players.findById(first.getId()).orElseThrow().getRating());
            assertEquals(1L, players.findById(first.getId()).orElseThrow().getRatingRevision());

            CompletedGameSnapshot stale = ratedSnapshot(UUID.randomUUID(), first.getId(),
                    first.getUsername(), 1000, 0L, second.getId(), second.getUsername(),
                    1000, 0L, true);
            assertThrows(CompletionIntegrityException.class, () -> store.record(stale));
            assertEquals(1, store.countGames());
            assertEquals(2, store.countParticipants());
            assertEquals(1016, players.findById(first.getId()).orElseThrow().getRating());
        }
    }

    /**
     * Persists rating revisions and telemetry across an isolated file-backed database restart.
     *
     * @param directory temporary database directory
     */
    @Test
    void ratedCompletionAndTelemetrySurviveRestart(@TempDir Path directory) {
        String url = "jdbc:h2:file:" + directory.resolve("fleetlink").toAbsolutePath();
        UUID firstId;
        UUID gameId = UUID.randomUUID();
        CompletedGameSnapshot completion;
        try (FleetLinkPersistence firstPersistence = PersistenceTestSupport.open(url, "create")) {
            PlayerRepository players = new PlayerRepository(
                    firstPersistence.getEntityManagerFactory());
            PlayerEntity first = player("RestartFirst", 1000);
            PlayerEntity second = player("RestartSecond", 1000);
            firstId = first.getId();
            players.create(first);
            players.create(second);
            completion = ratedSnapshot(gameId, first.getId(), first.getUsername(), 1000, 0L,
                    second.getId(), second.getUsername(), 1000, 0L, true);
            JpaCompletedGameStore store = new JpaCompletedGameStore(
                    firstPersistence.getEntityManagerFactory());
            assertEquals(CompletionRecordOutcome.RECORDED, store.record(completion));
        }

        try (FleetLinkPersistence secondPersistence = PersistenceTestSupport.open(url, "update")) {
            PlayerRepository players = new PlayerRepository(
                    secondPersistence.getEntityManagerFactory());
            JpaCompletedGameStore store = new JpaCompletedGameStore(
                    secondPersistence.getEntityManagerFactory());
            PlayerEntity first = players.findById(firstId).orElseThrow();
            CompletedGameSnapshot restored = store.find(gameId).orElseThrow();

            assertEquals(1016, first.getRating());
            assertEquals(1L, first.getRatingRevision());
            assertEquals(completion.getStartedAt(), restored.getStartedAt());
            assertEquals(completion.getCompletedAt(), restored.getCompletedAt());
            assertTrue(completion.equivalentTo(restored));
            assertEquals(7, restored.getParticipants().stream()
                    .filter(participant -> participant.getPlayerId().equals(firstId))
                    .findFirst().orElseThrow().getTurnsTaken());
        }
    }

    /**
     * Creates a detached registered player row for persistence tests.
     *
     * @param username unique username
     * @param rating stored rating
     * @return transient player entity
     */
    private static PlayerEntity player(String username, int rating) {
        return new PlayerEntity(UUID.randomUUID(), username, username.toLowerCase(),
                new byte[32], new byte[16], 31, rating, 0L,
                COMPLETED_AT.minusSeconds(100));
    }

    /**
     * Converts a registered entity to a completion participant snapshot.
     *
     * @param player persistent player entity
     * @param result terminal result
     * @return registered completion snapshot
     */
    private static CompletedParticipantSnapshot participant(PlayerEntity player,
                                                            ParticipantResult result) {
        return new CompletedParticipantSnapshot(player.getId(), player.getUsername(), false,
                player.getRating(), result, 4, 2, 1, 5, 0, null);
    }

    /**
     * Converts a registered entity to one rated completion transition.
     *
     * @param player transition participant
     * @param opponent opposing registered participant
     * @param result terminal result
     * @return rated participant snapshot at revision zero
     */
    private static CompletedParticipantSnapshot ratedParticipant(
            PlayerEntity player, PlayerEntity opponent, ParticipantResult result) {
        int delta = EloRatingCalculator.ratingDelta(player.getRating(), opponent.getRating(),
                result == ParticipantResult.WIN);
        return new CompletedParticipantSnapshot(player.getId(), player.getUsername(), false,
                player.getRating(), result, 4, 2, 1, 5, delta, 0L);
    }

    /**
     * Creates a temporary guest completion participant with fixed rating 1000.
     *
     * @param name guest display name
     * @param result terminal result
     * @return guest completion snapshot
     */
    private static CompletedParticipantSnapshot guest(String name, ParticipantResult result) {
        return new CompletedParticipantSnapshot(UUID.randomUUID(), name, true, 1000, result,
                3, 1, 0, 3, 0, null);
    }

    /**
     * Creates a validated terminal aggregate and derives its winner from participant results.
     *
     * @param gameId game identifier
     * @param first first participant
     * @param second second participant
     * @param reason end reason
     * @return completed game snapshot
     */
    private static CompletedGameSnapshot snapshot(UUID gameId,
                                                  CompletedParticipantSnapshot first,
                                                  CompletedParticipantSnapshot second,
                                                  GameEndReason reason) {
        UUID winner = first.getResult() == ParticipantResult.WIN
                ? first.getPlayerId() : second.getPlayerId();
        return new CompletedGameSnapshot(
                gameId, COMPLETED_AT.minusSeconds(60), COMPLETED_AT,
                reason, winner, List.of(first, second));
    }

    /**
     * Creates a deterministic two-registered-player rated aggregate.
     *
     * @param gameId game identifier
     * @param firstId first registered identifier
     * @param firstName first display name
     * @param firstRating first rating base
     * @param firstRevision first prior revision
     * @param secondId second registered identifier
     * @param secondName second display name
     * @param secondRating second rating base
     * @param secondRevision second prior revision
     * @param firstWins whether the first participant wins
     * @return rated completion aggregate
     */
    private static CompletedGameSnapshot ratedSnapshot(
            UUID gameId, UUID firstId, String firstName, int firstRating, long firstRevision,
            UUID secondId, String secondName, int secondRating, long secondRevision,
            boolean firstWins) {
        ParticipantResult firstResult = firstWins
                ? ParticipantResult.WIN : ParticipantResult.LOSS;
        ParticipantResult secondResult = firstWins
                ? ParticipantResult.LOSS : ParticipantResult.WIN;
        CompletedParticipantSnapshot first = new CompletedParticipantSnapshot(
                firstId, firstName, false, firstRating, firstResult,
                6, 3, 1, 7, EloRatingCalculator.ratingDelta(
                        firstRating, secondRating, firstWins), firstRevision);
        CompletedParticipantSnapshot second = new CompletedParticipantSnapshot(
                secondId, secondName, false, secondRating, secondResult,
                5, 2, 1, 6, EloRatingCalculator.ratingDelta(
                        secondRating, firstRating, !firstWins), secondRevision);
        UUID winnerId = firstWins ? firstId : secondId;
        return new CompletedGameSnapshot(gameId, COMPLETED_AT.minusSeconds(60), COMPLETED_AT,
                GameEndReason.ALL_SHIPS_SUNK, winnerId, List.of(first, second));
    }
}
