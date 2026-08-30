package io.github.tomerg12.fleetlink.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Verifies sequencer shutdown preserves already admitted work without accepting new commands. */
class GameCommandSequencerTest {
    /**
     * Proves a gated mandatory admission owns FIFO position zero before later user work and does not
     * execute until its outer-lock gate is released.
     *
     * @throws Exception if deterministic coordination fails
     */
    @Test
    void preparedRequiredAdmissionExecutesFirstAfterGateRelease() throws Exception {
        GameCommandSequencer sequencer = new GameCommandSequencer(
                Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"), ZoneOffset.UTC));
        UUID gameId = UUID.randomUUID();
        List<String> order = new ArrayList<>();
        CountDownLatch laterStarted = new CountDownLatch(1);
        GameCommandSequencer.PreparedAdmission reservation =
                sequencer.reserveRequiredAdmission();
        GameCommandSequencer.AdmittedCommand activation = reservation.admitFirst(
                gameId, () -> order.add("activation"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> later = executor.submit(() -> {
                laterStarted.countDown();
                return sequencer.submit(gameId, receivedAt -> {
                    order.add("later");
                    return "done";
                });
            });
            assertTrue(laterStarted.await(2, TimeUnit.SECONDS));
            assertFalse(later.isDone());

            activation.releaseAndAwait();

            assertEquals("done", later.get(2, TimeUnit.SECONDS));
            assertEquals(List.of("activation", "later"), order);
        } finally {
            executor.shutdownNow();
            sequencer.close();
        }
    }

    /**
     * Blocks one admitted command, starts two close callers, and proves every normal shutdown waits
     * without interrupting that command before rejecting later synchronous admissions.
     *
     * @throws Exception if deterministic test coordination fails
     */
    @Test
    void concurrentCloseCallsLetAlreadyAdmittedCommandFinish() throws Exception {
        GameCommandSequencer sequencer = new GameCommandSequencer(
                Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC));
        UUID gameId = UUID.randomUUID();
        CountDownLatch commandEntered = new CountDownLatch(1);
        CountDownLatch releaseCommand = new CountDownLatch(1);
        CountDownLatch closesStarted = new CountDownLatch(2);
        AtomicBoolean interrupted = new AtomicBoolean();
        ExecutorService callers = Executors.newFixedThreadPool(3);
        try {
            Future<Integer> command = callers.submit(() -> sequencer.submit(gameId, receivedAt -> {
                commandEntered.countDown();
                try {
                    releaseCommand.await();
                } catch (InterruptedException exception) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
                return 42;
            }));
            assertTrue(commandEntered.await(2, TimeUnit.SECONDS));

            Future<?> firstClose = callers.submit(() -> {
                closesStarted.countDown();
                sequencer.close();
            });
            Future<?> secondClose = callers.submit(() -> {
                closesStarted.countDown();
                sequencer.close();
            });
            assertTrue(closesStarted.await(2, TimeUnit.SECONDS));
            assertFalse(firstClose.isDone());
            assertFalse(secondClose.isDone());

            releaseCommand.countDown();
            assertEquals(42, command.get(2, TimeUnit.SECONDS));
            firstClose.get(2, TimeUnit.SECONDS);
            secondClose.get(2, TimeUnit.SECONDS);
            assertFalse(interrupted.get());
            assertThrows(IllegalStateException.class,
                    () -> sequencer.submit(gameId, receivedAt -> 7));
        } finally {
            releaseCommand.countDown();
            sequencer.close();
            callers.shutdownNow();
        }
    }
}
