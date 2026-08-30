package io.github.tomerg12.fleetlink.server.rating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Verifies registry seeding, atomic transitions, idempotency, and concurrent integrity.
 */
class RegisteredRatingRegistryTest {

    /**
     * Preserves a newer process-live state when a later login observes stale durable data.
     */
    @Test
    void seedIfAbsentNeverOverwritesLiveState() {
        RegisteredRatingRegistry registry = new RegisteredRatingRegistry();
        UUID playerId = UUID.randomUUID();

        registry.seedIfAbsent(playerId, 1000, 0);
        registry.seedIfAbsent(playerId, 900, 7);

        assertEquals(1000, registry.current(playerId).getRating());
        assertEquals(0, registry.current(playerId).getRevision());
    }

    /**
     * Applies both equal-rating transitions atomically and returns one idempotent adjustment.
     */
    @Test
    void ratedGameUpdatesBothPlayersExactlyOnce() {
        RegisteredRatingRegistry registry = new RegisteredRatingRegistry();
        PlayerView first = registered("First", 1000);
        PlayerView second = registered("Second", 1000);
        registry.seedIfAbsent(first.getPlayerId(), 1000, 3);
        registry.seedIfAbsent(second.getPlayerId(), 1000, 8);
        UUID gameId = UUID.randomUUID();

        RatedGameAdjustment applied = registry.applyRatedGame(
                gameId, first, second, first.getPlayerId());
        RatedGameAdjustment repeated = registry.applyRatedGame(
                gameId, second, first, first.getPlayerId());

        assertSame(applied, repeated);
        assertEquals(1016, registry.current(first.getPlayerId()).getRating());
        assertEquals(984, registry.current(second.getPlayerId()).getRating());
        assertEquals(4, registry.current(first.getPlayerId()).getRevision());
        assertEquals(9, registry.current(second.getPlayerId()).getRevision());
        assertEquals(16, applied.adjustmentFor(first.getPlayerId()).getRatingDelta());
        assertEquals(-16, applied.adjustmentFor(second.getPlayerId()).getRatingDelta());
    }

    /**
     * Rejects stale rating bases and conflicting game-id reuse without partial mutation.
     */
    @Test
    void staleOrConflictingRequestCannotMutateEitherPlayer() {
        RegisteredRatingRegistry registry = new RegisteredRatingRegistry();
        PlayerView first = registered("First", 1000);
        PlayerView second = registered("Second", 1000);
        registry.seedIfAbsent(first.getPlayerId(), 1000, 0);
        registry.seedIfAbsent(second.getPlayerId(), 1000, 0);
        UUID gameId = UUID.randomUUID();
        registry.applyRatedGame(gameId, first, second, first.getPlayerId());

        assertThrows(RatingIntegrityException.class, () -> registry.applyRatedGame(
                gameId, first, second, second.getPlayerId()));
        assertThrows(RatingIntegrityException.class, () -> registry.applyRatedGame(
                UUID.randomUUID(), first, second, first.getPlayerId()));
        assertEquals(1016, registry.current(first.getPlayerId()).getRating());
        assertEquals(984, registry.current(second.getPlayerId()).getRating());
    }

    /**
     * Rejects revision overflow before either participant is changed.
     */
    @Test
    void revisionOverflowCannotPartiallyApplyPair() {
        RegisteredRatingRegistry registry = new RegisteredRatingRegistry();
        PlayerView first = registered("First", 1000);
        PlayerView second = registered("Second", 1000);
        registry.seedIfAbsent(first.getPlayerId(), 1000, 0L);
        registry.seedIfAbsent(second.getPlayerId(), 1000, Long.MAX_VALUE);

        assertThrows(RatingIntegrityException.class, () -> registry.applyRatedGame(
                UUID.randomUUID(), first, second, first.getPlayerId()));

        assertEquals(1000, registry.current(first.getPlayerId()).getRating());
        assertEquals(0L, registry.current(first.getPlayerId()).getRevision());
        assertEquals(1000, registry.current(second.getPlayerId()).getRating());
        assertEquals(Long.MAX_VALUE, registry.current(second.getPlayerId()).getRevision());
    }

    /**
     * Lets only one of two concurrent conflicting applications own a game identifier.
     *
     * @throws Exception if deterministic concurrent execution cannot complete
     */
    @Test
    void concurrentConflictingApplicationsCommitOneAtomicPair() throws Exception {
        RegisteredRatingRegistry registry = new RegisteredRatingRegistry();
        PlayerView first = registered("First", 1000);
        PlayerView second = registered("Second", 1000);
        registry.seedIfAbsent(first.getPlayerId(), 1000, 0);
        registry.seedIfAbsent(second.getPlayerId(), 1000, 0);
        UUID gameId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> firstWins = executor.submit(() -> apply(
                    registry, gameId, first, second, first.getPlayerId(), start));
            Future<Boolean> secondWins = executor.submit(() -> apply(
                    registry, gameId, first, second, second.getPlayerId(), start));
            start.countDown();

            assertEquals(1, (firstWins.get() ? 1 : 0) + (secondWins.get() ? 1 : 0));
            int firstRating = registry.current(first.getPlayerId()).getRating();
            int secondRating = registry.current(second.getPlayerId()).getRating();
            assertEquals(2000, firstRating + secondRating);
            assertEquals(1, registry.current(first.getPlayerId()).getRevision());
            assertEquals(1, registry.current(second.getPlayerId()).getRevision());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Runs one competing registry application after a common release point.
     *
     * @param registry registry under test
     * @param gameId shared game identifier
     * @param first first participant
     * @param second second participant
     * @param winner requested winner
     * @param start common release latch
     * @return true when this request committed or matched the winning request
     * @throws InterruptedException if the test thread is interrupted while awaiting release
     */
    private static boolean apply(RegisteredRatingRegistry registry, UUID gameId,
                                 PlayerView first, PlayerView second, UUID winner,
                                 CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            registry.applyRatedGame(gameId, first, second, winner);
            return true;
        } catch (RatingIntegrityException exception) {
            return false;
        }
    }

    /**
     * Creates a safe registered player view for registry tests.
     *
     * @param name display name
     * @param rating captured rating
     * @return registered player view
     */
    private static PlayerView registered(String name, int rating) {
        return new PlayerView(UUID.randomUUID(), name, rating, false);
    }
}
