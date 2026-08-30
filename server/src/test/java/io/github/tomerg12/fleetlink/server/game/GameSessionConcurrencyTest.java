package io.github.tomerg12.fleetlink.server.game;

import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.player;
import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.validFleet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Verifies the GameSession monitor protects a complete game action from competing server threads.
 */
class GameSessionConcurrencyTest {
    /**
     * Allows exactly one of two simultaneous same-turn shots to commit.
     *
     * @throws Exception if the test executor cannot complete the competing actions
     */
    @Test
    void simultaneousShotsConsumeTurnOnlyOnce() throws Exception {
        Instant now = Instant.parse("2026-08-22T12:00:00Z");
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        GameSession game = new GameSession(UUID.randomUUID(), first, second, first.getPlayerId());
        game.activatePlacement(now, now.plusSeconds(120));
        game.submitFleet(first.getPlayerId(), validFleet(), now, now.plusSeconds(45));
        game.submitFleet(second.getPlayerId(), validFleet(), now, now.plusSeconds(45));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ShotResult> firstShot = executor.submit(() -> {
                ready.countDown();
                start.await();
                return game.fire(first.getPlayerId(), new Coordinate(9, 8),
                        now, now.plusSeconds(45));
            });
            Future<ShotResult> secondShot = executor.submit(() -> {
                ready.countDown();
                start.await();
                return game.fire(first.getPlayerId(), new Coordinate(9, 7),
                        now, now.plusSeconds(45));
            });
            ready.await();
            start.countDown();
            ShotResult firstResult = firstShot.get();
            ShotResult secondResult = secondShot.get();
            int acceptedCount = (firstResult.isAccepted() ? 1 : 0)
                    + (secondResult.isAccepted() ? 1 : 0);
            ShotResult rejected = firstResult.isAccepted() ? secondResult : firstResult;
            assertEquals(1, acceptedCount);
            assertEquals(ResultCode.NOT_YOUR_TURN, rejected.getResultCode());
        } finally {
            executor.shutdownNow();
        }
    }
}
