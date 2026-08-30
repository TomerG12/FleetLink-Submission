package io.github.tomerg12.fleetlink.server.service;

import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.player;
import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.validFleet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.game.GameSession;
import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import java.rmi.RemoteException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies that state-change callbacks preserve commit order for one game under concurrency.
 */
class GameCoordinatorCallbackOrderingTest {

    /**
     * Blocks the first shot callback while a second legal shot is attempted from another thread.
     * The second state callback must not begin until the first delivery completes, so the client
     * observes the same order in which the two game states were committed.
     *
     * @throws Exception if a concurrent test operation cannot complete normally
     */
    @Test
    void serializesSuccessiveStateCallbacksForSameGame() throws Exception {
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks);
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        BlockingOrderCallback firstCallback = new BlockingOrderCallback();
        callbacks.register(first, firstCallback);
        callbacks.register(second, new NoOpCallback());
        GameSession game = games.createGame(first, second, first.getPlayerId());
        coordinator.activateMatchedGame(game);
        assertTrue(coordinator.submitFleet(first.getPlayerId(), validFleet()).isAccepted());
        assertTrue(coordinator.submitFleet(second.getPlayerId(), validFleet()).isAccepted());
        firstCallback.arm();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ShotResult> firstShot = executor.submit(
                    () -> coordinator.fire(first.getPlayerId(), new Coordinate(9, 9)));
            assertTrue(firstCallback.awaitFirstCallback());

            CountDownLatch secondCallStarted = new CountDownLatch(1);
            Future<ShotResult> secondShot = executor.submit(() -> {
                secondCallStarted.countDown();
                return coordinator.fire(second.getPlayerId(), new Coordinate(9, 8));
            });
            assertTrue(secondCallStarted.await(2, TimeUnit.SECONDS));

            assertFalse(firstCallback.awaitSecondCallbackWhileFirstBlocked());
            firstCallback.releaseFirstCallback();

            assertTrue(firstShot.get(2, TimeUnit.SECONDS).isAccepted());
            assertTrue(secondShot.get(2, TimeUnit.SECONDS).isAccepted());
            assertTrue(firstCallback.awaitBothCallbacks());

            assertEquals(List.of(false, true), firstCallback.turnStates());
            assertEquals(1, firstCallback.maxConcurrentCallbacks());
        } finally {
            firstCallback.releaseFirstCallback();
            executor.shutdownNow();
            coordinator.close();
        }
    }

    /**
     * Blocks one game's post-shot callback while proving a different game can commit and deliver
     * its own post-shot state through the same coordinator.
     *
     * @throws Exception if a concurrent test operation cannot complete normally
     */
    @Test
    void blockedCallbackInOneGameDoesNotBlockAnotherGame() throws Exception {
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks);
        PlayerView gameAFirst = player("Game A First", 1200);
        PlayerView gameASecond = player("Game A Second", 1200);
        PlayerView gameBFirst = player("Game B First", 1200);
        PlayerView gameBSecond = player("Game B Second", 1200);
        BlockingOrderCallback gameABlockingCallback = new BlockingOrderCallback();
        CompletingCallback gameBCompletingCallback = new CompletingCallback();
        callbacks.register(gameAFirst, gameABlockingCallback);
        callbacks.register(gameASecond, new NoOpCallback());
        callbacks.register(gameBFirst, gameBCompletingCallback);
        callbacks.register(gameBSecond, new NoOpCallback());

        GameSession gameA = games.createGame(
                gameAFirst, gameASecond, gameAFirst.getPlayerId());
        coordinator.activateMatchedGame(gameA);
        assertTrue(coordinator.submitFleet(gameAFirst.getPlayerId(), validFleet()).isAccepted());
        assertTrue(coordinator.submitFleet(gameASecond.getPlayerId(), validFleet()).isAccepted());

        GameSession gameB = games.createGame(
                gameBFirst, gameBSecond, gameBFirst.getPlayerId());
        coordinator.activateMatchedGame(gameB);
        assertTrue(coordinator.submitFleet(gameBFirst.getPlayerId(), validFleet()).isAccepted());
        assertTrue(coordinator.submitFleet(gameBSecond.getPlayerId(), validFleet()).isAccepted());

        gameABlockingCallback.arm();
        gameBCompletingCallback.arm();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ShotResult> gameAShot = executor.submit(
                    () -> coordinator.fire(gameAFirst.getPlayerId(), new Coordinate(9, 9)));
            assertTrue(gameABlockingCallback.awaitFirstCallback());

            Future<ShotResult> gameBShot = executor.submit(
                    () -> coordinator.fire(gameBFirst.getPlayerId(), new Coordinate(9, 9)));
            assertTrue(gameBShot.get(2, TimeUnit.SECONDS).isAccepted());
            assertTrue(gameBCompletingCallback.awaitCallback());
            assertFalse(coordinator.getCurrentGame(gameBFirst.getPlayerId())
                    .getGameView().isYourTurn());
            assertEquals(List.of(false), gameBCompletingCallback.turnStates());

            assertFalse(gameAShot.isDone());
            assertTrue(gameABlockingCallback.isFirstCallbackBlocked());
            gameABlockingCallback.releaseFirstCallback();

            assertTrue(gameAShot.get(2, TimeUnit.SECONDS).isAccepted());
            assertEquals(List.of(false), gameABlockingCallback.turnStates());
        } finally {
            gameABlockingCallback.releaseFirstCallback();
            executor.shutdownNow();
            coordinator.close();
        }
    }

    /**
     * Records callback entry order while allowing the first delivery to be held open deliberately.
     */
    private static final class BlockingOrderCallback implements FleetLinkClientCallback {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicInteger callbackIndex = new AtomicInteger();
        private final AtomicInteger activeCallbacks = new AtomicInteger();
        private final AtomicInteger maxConcurrentCallbacks = new AtomicInteger();
        private final CountDownLatch firstCallbackEntered = new CountDownLatch(1);
        private final CountDownLatch secondCallbackEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstCallback = new CountDownLatch(1);
        private final CountDownLatch bothCallbacksCompleted = new CountDownLatch(2);
        private final CopyOnWriteArrayList<Boolean> turnStates = new CopyOnWriteArrayList<>();

        /**
         * Ignores setup callbacks and starts recording the two shot-driven state changes.
         */
        private void arm() {
            armed.set(true);
        }

        /**
         * Waits until the first post-arm callback has entered and is blocked.
         *
         * @return true when the callback entered within the timeout
         * @throws InterruptedException if the test thread is interrupted
         */
        private boolean awaitFirstCallback() throws InterruptedException {
            return firstCallbackEntered.await(2, TimeUnit.SECONDS);
        }

        /**
         * Reports whether the first callback has entered and still awaits its release signal.
         *
         * @return true when the first callback is deliberately blocked
         */
        private boolean isFirstCallbackBlocked() {
            return firstCallbackEntered.getCount() == 0 && releaseFirstCallback.getCount() == 1;
        }

        /**
         * Checks whether the second callback incorrectly entered before the first was released.
         *
         * @return true when the second callback overtook the first delivery
         * @throws InterruptedException if the test thread is interrupted
         */
        private boolean awaitSecondCallbackWhileFirstBlocked() throws InterruptedException {
            return secondCallbackEntered.await(250, TimeUnit.MILLISECONDS);
        }

        /**
         * Allows the deliberately blocked first callback to finish.
         */
        private void releaseFirstCallback() {
            releaseFirstCallback.countDown();
        }

        /**
         * Waits for both post-arm callback deliveries to complete.
         *
         * @return true when both callbacks completed within the timeout
         * @throws InterruptedException if the test thread is interrupted
         */
        private boolean awaitBothCallbacks() throws InterruptedException {
            return bothCallbacksCompleted.await(2, TimeUnit.SECONDS);
        }

        /**
         * Returns the observed turn flags in callback completion order.
         *
         * @return the ordered turn-state observations
         */
        private List<Boolean> turnStates() {
            return List.copyOf(turnStates);
        }

        /**
         * Returns the largest number of callbacks that were executing at the same time.
         *
         * @return the maximum concurrent callback count
         */
        private int maxConcurrentCallbacks() {
            return maxConcurrentCallbacks.get();
        }

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) {
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
            if (!armed.get()) {
                return;
            }
            int index = callbackIndex.getAndIncrement();
            int active = activeCallbacks.incrementAndGet();
            maxConcurrentCallbacks.accumulateAndGet(active, Math::max);
            try {
                if (index == 0) {
                    firstCallbackEntered.countDown();
                    if (!releaseFirstCallback.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release first callback");
                    }
                } else if (index == 1) {
                    secondCallbackEntered.countDown();
                }
                turnStates.add(gameView.isYourTurn());
                bothCallbacksCompleted.countDown();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Callback test was interrupted", exception);
            } finally {
                activeCallbacks.decrementAndGet();
            }
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchRequested(RematchStatusView rematchStatus) throws RemoteException {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus) throws RemoteException {
        }
    }

    /**
     * Records completion of post-arm state callbacks without delaying their delivery.
     */
    private static final class CompletingCallback implements FleetLinkClientCallback {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final CountDownLatch callbackCompleted = new CountDownLatch(1);
        private final CopyOnWriteArrayList<Boolean> turnStates = new CopyOnWriteArrayList<>();

        /**
         * Ignores setup callbacks and starts recording shot-driven state changes.
         */
        private void arm() {
            armed.set(true);
        }

        /**
         * Waits until the post-arm state callback has completed.
         *
         * @return true when the callback completed within the timeout
         * @throws InterruptedException if the test thread is interrupted
         */
        private boolean awaitCallback() throws InterruptedException {
            return callbackCompleted.await(2, TimeUnit.SECONDS);
        }

        /**
         * Returns the turn flags recorded by completed callbacks.
         *
         * @return the recorded turn-state observations
         */
        private List<Boolean> turnStates() {
            return List.copyOf(turnStates);
        }

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) {
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
            if (armed.get()) {
                turnStates.add(gameView.isYourTurn());
                callbackCompleted.countDown();
            }
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchRequested(RematchStatusView rematchStatus) throws RemoteException {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus) throws RemoteException {
        }
    }

    /**
     * Accepts callbacks without adding synchronization behavior to the ordering test.
     */
    private static final class NoOpCallback implements FleetLinkClientCallback {

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) {
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchRequested(RematchStatusView rematchStatus) throws RemoteException {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus) throws RemoteException {
        }
    }
}
