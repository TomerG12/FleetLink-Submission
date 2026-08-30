package io.github.tomerg12.fleetlink.server.service;

import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.player;
import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.validFleet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.completion.CompletedGameSnapshot;
import io.github.tomerg12.fleetlink.server.deadline.DeadlineScheduler;
import io.github.tomerg12.fleetlink.server.game.GameSession;
import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Verifies deadline admission ordering, generation invalidation, and winnerless placement expiry. */
class GameCoordinatorDeadlineTest {
    private static final Instant START = Instant.parse("2026-08-22T12:00:00Z");

    /**
     * Reproduces the overtaking race: an on-time fire admitted behind a blocked callback must execute
     * before a later expiry command even though server time passes the deadline while it waits.
     * Admission is observed directly through the injected clock rather than inferred from a sleep.
     *
     * @throws Exception if deterministic executor or latch coordination fails
     */
    @Test
    void admittedOnTimeCommandCannotBeOvertakenByExpiry() throws Exception {
        MutableClock clock = new MutableClock(START);
        ControlledDeadlineScheduler scheduler = new ControlledDeadlineScheduler();
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        BlockingCallback firstCallback = new BlockingCallback();
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        callbacks.register(first, firstCallback);
        callbacks.register(second, new NoOpCallback());
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ignored -> { },
                clock, scheduler);
        ExecutorService requests = Executors.newFixedThreadPool(2);
        try {
            GameSession game = games.createGame(first, second, first.getPlayerId());
            coordinator.activateMatchedGame(game);
            assertTrue(coordinator.submitFleet(first.getPlayerId(), validFleet()).isAccepted());
            assertTrue(coordinator.submitFleet(second.getPlayerId(), validFleet()).isAccepted());

            clock.set(START.plusSeconds(1));
            firstCallback.blockNextState();
            Future<ShotResult> firstShot = requests.submit(
                    () -> coordinator.fire(first.getPlayerId(), new Coordinate(9, 9)));
            assertTrue(firstCallback.awaitBlockedState());

            clock.set(START.plusSeconds(45));
            CountDownLatch secondAdmissionCaptured = new CountDownLatch(1);
            clock.signalNextRead(secondAdmissionCaptured);
            Future<ShotResult> onTimeSecondShot = requests.submit(
                    () -> coordinator.fire(second.getPlayerId(), new Coordinate(9, 8)));
            assertTrue(secondAdmissionCaptured.await(2, TimeUnit.SECONDS));

            clock.set(START.plusSeconds(46));
            scheduler.runDue(clock.instant());
            firstCallback.releaseBlockedState();

            assertTrue(firstShot.get(2, TimeUnit.SECONDS).isAccepted());
            ShotResult secondResult = onTimeSecondShot.get(2, TimeUnit.SECONDS);
            assertTrue(secondResult.isAccepted(), () -> "second result code="
                    + secondResult.getResultCode() + ", message=" + secondResult.getMessage()
                    + ", phase=" + secondResult.getGameView().getPhase());
            GameView firstView = coordinator.getCurrentGame(first.getPlayerId()).getGameView();
            assertTrue(firstView.isYourTurn());
            assertEquals(0, firstView.getOpponentTimeoutStrikes());
        } finally {
            firstCallback.releaseBlockedState();
            requests.shutdownNow();
            coordinator.close();
        }
    }

    /**
     * Proves normal shutdown keeps the scheduler available until an admitted turn-changing command
     * finishes its state mutation, replacement deadline registration, and callback delivery.
     *
     * @throws Exception if deterministic executor or latch coordination fails
     */
    @Test
    void shutdownPreservesAdmittedCommandBeforeClosingDeadlineScheduler() throws Exception {
        MutableClock clock = new MutableClock(START);
        ControlledDeadlineScheduler scheduler = new ControlledDeadlineScheduler();
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        BlockingCallback firstCallback = new BlockingCallback();
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        callbacks.register(first, firstCallback);
        callbacks.register(second, new NoOpCallback());
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ignored -> { },
                clock, scheduler);
        ExecutorService requests = Executors.newFixedThreadPool(3);
        try {
            GameSession game = games.createGame(first, second, first.getPlayerId());
            coordinator.activateMatchedGame(game);
            assertTrue(coordinator.submitFleet(first.getPlayerId(), validFleet()).isAccepted());
            assertTrue(coordinator.submitFleet(second.getPlayerId(), validFleet()).isAccepted());

            clock.set(START.plusSeconds(1));
            firstCallback.blockNextState();
            Future<ShotResult> firstShot = requests.submit(
                    () -> coordinator.fire(first.getPlayerId(), new Coordinate(9, 9)));
            assertTrue(firstCallback.awaitBlockedState());

            clock.set(START.plusSeconds(2));
            CountDownLatch secondAdmissionCaptured = new CountDownLatch(1);
            clock.signalNextRead(secondAdmissionCaptured);
            Future<ShotResult> admittedSecondShot = requests.submit(
                    () -> coordinator.fire(second.getPlayerId(), new Coordinate(9, 8)));
            assertTrue(secondAdmissionCaptured.await(2, TimeUnit.SECONDS));

            CountDownLatch shutdownCancellationObserved = new CountDownLatch(1);
            scheduler.signalNextCancellation(shutdownCancellationObserved);
            Future<?> shutdown = requests.submit(coordinator::close);
            assertTrue(shutdownCancellationObserved.await(2, TimeUnit.SECONDS));
            boolean schedulerClosedBeforeRelease = scheduler.awaitClosed(250, TimeUnit.MILLISECONDS);

            firstCallback.releaseBlockedState();
            assertTrue(firstShot.get(2, TimeUnit.SECONDS).isAccepted());
            assertTrue(admittedSecondShot.get(2, TimeUnit.SECONDS).isAccepted());
            assertTrue(firstCallback.awaitSubsequentState());
            shutdown.get(2, TimeUnit.SECONDS);

            assertFalse(schedulerClosedBeforeRelease);
            assertTrue(scheduler.isClosed());
            assertEquals(0, scheduler.runDue(START.plusSeconds(1_000)));
            assertThrows(RejectedExecutionException.class,
                    () -> scheduler.schedule(START.plusSeconds(1_001), () -> { }));
            assertTrue(coordinator.getCurrentGame(first.getPlayerId()).getGameView().isYourTurn());
        } finally {
            firstCallback.releaseBlockedState();
            requests.shutdownNow();
            coordinator.close();
        }
    }

    /**
     * Proves double placement AFK becomes NO_CONTEST and never creates a completion handoff.
     *
     * @throws Exception if deterministic callback coordination fails
     */
    @Test
    void doubleAfkNoContestSkipsCompletionPipeline() throws Exception {
        MutableClock clock = new MutableClock(START);
        ControlledDeadlineScheduler scheduler = new ControlledDeadlineScheduler();
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        List<CompletedGameSnapshot> completed = new ArrayList<>();
        TerminalCallback terminalCallback = new TerminalCallback();
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        callbacks.register(first, terminalCallback);
        callbacks.register(second, new NoOpCallback());
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, completed::add,
                clock, scheduler);
        try {
            GameSession game = games.createGame(first, second, first.getPlayerId());
            coordinator.activateMatchedGame(game);
            long expectedDeadline = START.plusSeconds(120).toEpochMilli();
            assertEquals(expectedDeadline,
                    coordinator.getCurrentGame(first.getPlayerId()).getGameView()
                            .getDeadlineEpochMillis());

            clock.set(START.plusSeconds(120));
            scheduler.runDue(clock.instant());
            assertTrue(terminalCallback.awaitNoContest());

            GameView finalView = coordinator.getCurrentGame(first.getPlayerId()).getGameView();
            assertEquals(GameEndReason.NO_CONTEST, finalView.getEndReason());
            assertNull(finalView.getWinner());
            assertTrue(completed.isEmpty());
        } finally {
            coordinator.close();
        }
    }

    /**
     * Proves both initial callbacks are invoked independently rather than sequentially.
     *
     * @throws Exception if deterministic callback or executor coordination fails
     */
    @Test
    void matchFoundCallbacksFanOutConcurrently() throws Exception {
        MutableClock clock = new MutableClock(START);
        ControlledDeadlineScheduler scheduler = new ControlledDeadlineScheduler();
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        PlayerView first = player("First", 1200);
        PlayerView second = player("Second", 1200);
        callbacks.register(first, new BlockingMatchCallback(bothEntered, release));
        callbacks.register(second, new BlockingMatchCallback(bothEntered, release));
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ignored -> { },
                clock, scheduler);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            GameSession game = games.createGame(first, second, first.getPlayerId());
            Future<?> activation = executor.submit(() -> coordinator.activateMatchedGame(game));
            assertTrue(bothEntered.await(2, TimeUnit.SECONDS));
            release.countDown();
            activation.get(2, TimeUnit.SECONDS);
            GameView firstView = coordinator.getCurrentGame(first.getPlayerId()).getGameView();
            GameView secondView = coordinator.getCurrentGame(second.getPlayerId()).getGameView();
            assertEquals(firstView.getDeadlineEpochMillis(), secondView.getDeadlineEpochMillis());
        } finally {
            release.countDown();
            executor.shutdownNow();
            coordinator.close();
        }
    }

    /** Mutable deterministic Clock used without wall-clock sleeps. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final AtomicReference<CountDownLatch> nextReadSignal = new AtomicReference<>();

        /**
         * Creates a mutable UTC test clock.
         *
         * @param initial initial authoritative instant
         */
        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        /**
         * Sets the current authoritative test instant.
         *
         * @param value new current instant
         */
        private void set(Instant value) {
            instant.set(value);
        }

        /**
         * Arms a one-shot signal that fires after the next caller captures the clock value.
         *
         * @param signal latch identifying the next observed clock read
         */
        private void signalNextRead(CountDownLatch signal) {
            nextReadSignal.set(signal);
        }

        /** {@inheritDoc} */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /** {@inheritDoc} */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public Instant instant() {
            Instant captured = instant.get();
            CountDownLatch signal = nextReadSignal.getAndSet(null);
            if (signal != null) {
                signal.countDown();
            }
            return captured;
        }
    }

    /** Controlled scheduler whose tasks execute only when the test explicitly releases due work. */
    private static final class ControlledDeadlineScheduler implements DeadlineScheduler {
        private final List<Registration> registrations = new ArrayList<>();
        private final AtomicReference<CountDownLatch> nextCancellationSignal =
                new AtomicReference<>();
        private final CountDownLatch closedSignal = new CountDownLatch(1);
        private boolean closed;

        /**
         * Registers controlled work unless test shutdown has closed the scheduler.
         *
         * @param deadline absolute deadline associated with the registration
         * @param task deferred expiry notification
         * @return cancellation handle for the controlled registration
         * @throws RejectedExecutionException if the scheduler is already closed
         */
        @Override
        public synchronized Handle schedule(Instant deadline, Runnable task) {
            if (closed) {
                throw new RejectedExecutionException("controlled deadline scheduler is closed");
            }
            Registration registration = new Registration(deadline, task);
            registrations.add(registration);
            return () -> {
                synchronized (ControlledDeadlineScheduler.this) {
                    registration.cancelled = true;
                    CountDownLatch signal = nextCancellationSignal.getAndSet(null);
                    if (signal != null) {
                        signal.countDown();
                    }
                    return true;
                }
            };
        }

        /**
         * Arms a one-shot signal for the next deadline-handle cancellation.
         *
         * @param signal latch identifying the observed cancellation
         */
        private void signalNextCancellation(CountDownLatch signal) {
            nextCancellationSignal.set(signal);
        }

        /**
         * Waits for scheduler closure without polling.
         *
         * @param timeout maximum wait length
         * @param unit timeout unit
         * @return true when close entered before the timeout
         * @throws InterruptedException if the waiting test thread is interrupted
         */
        private boolean awaitClosed(long timeout, TimeUnit unit) throws InterruptedException {
            return closedSignal.await(timeout, unit);
        }

        /**
         * Reports whether scheduler closure has completed.
         *
         * @return true after close has cancelled all registrations
         */
        private synchronized boolean isClosed() {
            return closed;
        }

        /**
         * Runs each non-cancelled registration whose deadline is at or before the supplied instant.
         *
         * @param now deterministic current server time
         * @return number of expiry notifications invoked
         */
        private int runDue(Instant now) {
            List<Runnable> due = new ArrayList<>();
            synchronized (this) {
                for (Registration registration : registrations) {
                    if (!registration.cancelled && !registration.ran
                            && !registration.deadline.isAfter(now)) {
                        registration.ran = true;
                        due.add(registration.task);
                    }
                }
            }
            due.forEach(Runnable::run);
            return due.size();
        }

        /** {@inheritDoc} */
        @Override
        public synchronized void close() {
            closed = true;
            registrations.forEach(registration -> registration.cancelled = true);
            closedSignal.countDown();
        }

        /** Stores one controlled deadline registration and its deterministic lifecycle flags. */
        private static final class Registration {
            private final Instant deadline;
            private final Runnable task;
            private boolean cancelled;
            private boolean ran;

            /**
             * Creates one controlled registration.
             *
             * @param deadline absolute test deadline
             * @param task deferred expiry notification
             */
            private Registration(Instant deadline, Runnable task) {
                this.deadline = deadline;
                this.task = task;
            }
        }
    }

    /** Blocks one state callback so later commands can be admitted while D-013 remains occupied. */
    private static final class BlockingCallback implements FleetLinkClientCallback {
        private final AtomicBoolean blockNext = new AtomicBoolean();
        private final AtomicBoolean blockedStateReleased = new AtomicBoolean();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch subsequentState = new CountDownLatch(1);

        /** Arms the next game-state callback for deterministic blocking. */
        private void blockNextState() {
            blockNext.set(true);
        }

        /**
         * Waits for the armed callback to enter.
         *
         * @return true when the callback entered before the test timeout
         * @throws InterruptedException if the waiting test thread is interrupted
         */
        private boolean awaitBlockedState() throws InterruptedException {
            return blocked.await(2, TimeUnit.SECONDS);
        }

        /** Releases the blocked callback. */
        private void releaseBlockedState() {
            release.countDown();
        }

        /**
         * Waits for the first state callback delivered after the blocked callback completes.
         *
         * @return true when the subsequent callback arrived before the test timeout
         * @throws InterruptedException if the waiting test thread is interrupted
         */
        private boolean awaitSubsequentState() throws InterruptedException {
            return subsequentState.await(2, TimeUnit.SECONDS);
        }

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) {
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
            if (!blockNext.compareAndSet(true, false)) {
                if (blockedStateReleased.get()) {
                    subsequentState.countDown();
                }
                return;
            }
            blocked.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                blockedStateReleased.set(true);
            }
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchRequested(RematchStatusView rematchStatus) {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus) {
        }
    }

    /** Records when an authoritative NO_CONTEST callback is delivered. */
    private static final class TerminalCallback implements FleetLinkClientCallback {
        private final CountDownLatch noContest = new CountDownLatch(1);

        /**
         * Waits for a NO_CONTEST game-state callback.
         *
         * @return true when the callback arrived before the test timeout
         * @throws InterruptedException if the waiting test thread is interrupted
         */
        private boolean awaitNoContest() throws InterruptedException {
            return noContest.await(2, TimeUnit.SECONDS);
        }

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) {
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
            if (gameView.getEndReason() == GameEndReason.NO_CONTEST) {
                noContest.countDown();
            }
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchRequested(RematchStatusView rematchStatus) {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus) {
        }
    }

    /** Blocks match-found delivery until both participant callbacks have entered. */
    private static final class BlockingMatchCallback implements FleetLinkClientCallback {
        private final CountDownLatch bothEntered;
        private final CountDownLatch release;

        /**
         * Creates one side of the same-state fan-out barrier.
         *
         * @param bothEntered shared latch decremented by both participant callbacks
         * @param release shared latch releasing both callbacks
         */
        private BlockingMatchCallback(CountDownLatch bothEntered, CountDownLatch release) {
            this.bothEntered = bothEntered;
            this.release = release;
        }

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) {
            bothEntered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchRequested(RematchStatusView rematchStatus) {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus) {
        }
    }

    /** Callback implementation used when a participant's delivery behavior is irrelevant to a test. */
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
        public void onRematchRequested(RematchStatusView rematchStatus) {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus) {
        }
    }
}
