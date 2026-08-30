package io.github.tomerg12.fleetlink.server.rematch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.game.GameSession;
import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.server.matchmaking.MatchmakingService;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.server.service.ClientCallbackRegistry;
import io.github.tomerg12.fleetlink.server.service.GameCoordinator;
import io.github.tomerg12.fleetlink.server.session.SessionRegistry;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingState;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchState;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionInfo;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import java.rmi.RemoteException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Verifies authoritative rematch state, callbacks, creation, failures, and concurrency. */
class RematchCoordinatorTest {

    /** First request records perspective-specific state and an equal duplicate is callback-free. */
    @Test
    void firstRequestAndDuplicateAreIdempotent() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.rematches.requestRematch(fixture.first.getSessionId()).isSuccess());
            assertEquals(List.of(RematchState.REQUESTED_BY_YOU),
                    fixture.firstCallback.statusStates);
            assertEquals(List.of(RematchState.REQUESTED_BY_OPPONENT),
                    fixture.secondCallback.requestStates);

            assertTrue(fixture.rematches.requestRematch(fixture.first.getSessionId()).isSuccess());
            assertEquals(1, fixture.firstCallback.statusStates.size());
            assertEquals(1, fixture.secondCallback.requestStates.size());
            assertEquals(0, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
        }
    }

    /** A created rematch is terminal and later operations cannot create or notify again. */
    @Test
    void requestsFromBothSidesCreateOneNewGame() {
        try (Fixture fixture = new Fixture()) {
            fixture.rematches.requestRematch(fixture.first.getSessionId());

            OperationResult result = fixture.rematches.requestRematch(
                    fixture.second.getSessionId());

            assertTrue(result.isSuccess());
            assertEquals(1, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals("CREATED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            GameSession newGame = fixture.games.findByPlayerId(
                    fixture.first.getPlayer().getPlayerId()).orElseThrow();
            assertNotEquals(fixture.source.getGameId(), newGame.getGameId());
            assertEquals(GamePhase.FLEET_PLACEMENT,
                    newGame.getCurrentGame(fixture.first.getPlayer().getPlayerId())
                            .getGameView().getPhase());
            assertEquals(1, fixture.firstCallback.matchFoundIds.size());
            assertEquals(1, fixture.secondCallback.matchFoundIds.size());
            assertEquals(RematchState.ACCEPTED,
                    fixture.firstCallback.statusStates.getLast());
            assertEquals(RematchState.ACCEPTED,
                    fixture.secondCallback.statusStates.getLast());

            UUID createdGameId = newGame.getGameId();
            int firstStatusCount = fixture.firstCallback.statusStates.size();
            int secondStatusCount = fixture.secondCallback.statusStates.size();
            int firstRequestCount = fixture.firstCallback.requestStates.size();
            int secondRequestCount = fixture.secondCallback.requestStates.size();
            int firstMatchFoundCount = fixture.firstCallback.matchFoundIds.size();
            int secondMatchFoundCount = fixture.secondCallback.matchFoundIds.size();

            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    fixture.rematches.requestRematch(
                            fixture.first.getSessionId()).getResultCode());
            assertEquals(1, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals("CREATED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(createdGameId, fixture.games.findByPlayerId(
                    fixture.first.getPlayer().getPlayerId()).orElseThrow().getGameId());
            assertEquals(firstStatusCount, fixture.firstCallback.statusStates.size());
            assertEquals(secondStatusCount, fixture.secondCallback.statusStates.size());
            assertEquals(firstRequestCount, fixture.firstCallback.requestStates.size());
            assertEquals(secondRequestCount, fixture.secondCallback.requestStates.size());
            assertEquals(firstMatchFoundCount, fixture.firstCallback.matchFoundIds.size());
            assertEquals(secondMatchFoundCount, fixture.secondCallback.matchFoundIds.size());

            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    fixture.rematches.respondToRematch(
                            fixture.second.getSessionId(), true).getResultCode());
            assertEquals(1, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals("CREATED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(createdGameId, fixture.games.findByPlayerId(
                    fixture.second.getPlayer().getPlayerId()).orElseThrow().getGameId());
            assertEquals(firstStatusCount, fixture.firstCallback.statusStates.size());
            assertEquals(secondStatusCount, fixture.secondCallback.statusStates.size());
            assertEquals(firstRequestCount, fixture.firstCallback.requestStates.size());
            assertEquals(secondRequestCount, fixture.secondCallback.requestStates.size());
            assertEquals(firstMatchFoundCount, fixture.firstCallback.matchFoundIds.size());
            assertEquals(secondMatchFoundCount, fixture.secondCallback.matchFoundIds.size());
        }
    }

    /** Cleanup after a created rematch binds to the newly completed current game. */
    @Test
    void falseAfterCreatedHistoricalNegotiationExpiresCurrentGame() {
        try (Fixture fixture = new Fixture()) {
            UUID firstGameId = fixture.source.getGameId();
            assertTrue(fixture.rematches.requestRematch(fixture.first.getSessionId()).isSuccess());
            assertTrue(fixture.rematches.respondToRematch(
                    fixture.second.getSessionId(), true).isSuccess());
            GameSession secondGame = fixture.games.findByPlayerId(
                    fixture.first.getPlayer().getPlayerId()).orElseThrow();
            finishGame(fixture, secondGame, fixture.second.getPlayer().getPlayerId());
            int firstMatchFoundCount = fixture.firstCallback.matchFoundIds.size();
            int secondMatchFoundCount = fixture.secondCallback.matchFoundIds.size();

            assertTrue(fixture.rematches.respondToRematch(
                    fixture.first.getSessionId(), false).isSuccess());

            assertEquals("CREATED", fixture.rematches.lifecycleName(firstGameId));
            assertEquals(1, fixture.rematches.creationClaimCount(firstGameId));
            assertEquals("EXPIRED", fixture.rematches.lifecycleName(secondGame.getGameId()));
            assertEquals(0, fixture.rematches.creationClaimCount(secondGame.getGameId()));
            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    fixture.rematches.requestRematch(
                            fixture.second.getSessionId()).getResultCode());
            assertEquals(secondGame.getGameId(), fixture.games.findByPlayerId(
                    fixture.first.getPlayer().getPlayerId()).orElseThrow().getGameId());
            assertEquals(firstMatchFoundCount, fixture.firstCallback.matchFoundIds.size());
            assertEquals(secondMatchFoundCount, fixture.secondCallback.matchFoundIds.size());
        }
    }

    /** A caller already in ordinary matchmaking cannot open a rematch opportunity. */
    @Test
    void callerWaitingCannotOpenRematch() {
        try (Fixture fixture = new Fixture()) {
            MatchmakingResult waiting = fixture.matchmaking.join(
                    fixture.first.getSessionId(), fixture.first.getPlayer());
            assertTrue(waiting.isSuccess());
            assertEquals(MatchmakingState.WAITING, waiting.getState());

            OperationResult result = fixture.rematches.requestRematch(
                    fixture.first.getSessionId());

            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE, result.getResultCode());
            assertEquals("", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(0, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals(0, fixture.firstCallback.requestStates.size());
            assertEquals(0, fixture.firstCallback.statusStates.size());
            assertEquals(0, fixture.secondCallback.requestStates.size());
            assertEquals(0, fixture.secondCallback.statusStates.size());
            assertTrue(fixture.matchmaking.isWaiting(fixture.first.getPlayer().getPlayerId()));
            assertEquals(fixture.source.getGameId(), fixture.games.findByPlayerId(
                    fixture.first.getPlayer().getPlayerId()).orElseThrow().getGameId());
            assertEquals(0, fixture.firstCallback.matchFoundIds.size());
            assertEquals(0, fixture.secondCallback.matchFoundIds.size());
        }
    }

    /** An opponent already in ordinary matchmaking prevents a new rematch opportunity. */
    @Test
    void opponentWaitingPreventsRematchRequest() {
        try (Fixture fixture = new Fixture()) {
            MatchmakingResult waiting = fixture.matchmaking.join(
                    fixture.second.getSessionId(), fixture.second.getPlayer());
            assertTrue(waiting.isSuccess());
            assertEquals(MatchmakingState.WAITING, waiting.getState());

            OperationResult result = fixture.rematches.requestRematch(
                    fixture.first.getSessionId());

            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE, result.getResultCode());
            assertEquals("", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(0, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals(0, fixture.firstCallback.requestStates.size());
            assertEquals(0, fixture.firstCallback.statusStates.size());
            assertEquals(0, fixture.secondCallback.requestStates.size());
            assertEquals(0, fixture.secondCallback.statusStates.size());
            assertTrue(fixture.matchmaking.isWaiting(fixture.second.getPlayer().getPlayerId()));
            assertEquals(fixture.source.getGameId(), fixture.games.findByPlayerId(
                    fixture.second.getPlayer().getPlayerId()).orElseThrow().getGameId());
            assertEquals(0, fixture.firstCallback.matchFoundIds.size());
            assertEquals(0, fixture.secondCallback.matchFoundIds.size());
        }
    }

    /** Waiting membership makes an open negotiation stale before positive response processing. */
    @Test
    void openNegotiationExpiresWhenParticipantStartsWaiting() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.rematches.requestRematch(
                    fixture.first.getSessionId()).isSuccess());
            assertEquals("OPEN", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            MatchmakingResult waiting = fixture.matchmaking.join(
                    fixture.first.getSessionId(), fixture.first.getPlayer());
            assertTrue(waiting.isSuccess());
            assertEquals(MatchmakingState.WAITING, waiting.getState());
            int firstStatusCount = fixture.firstCallback.statusStates.size();
            int secondStatusCount = fixture.secondCallback.statusStates.size();
            int firstRequestCount = fixture.firstCallback.requestStates.size();
            int secondRequestCount = fixture.secondCallback.requestStates.size();

            OperationResult result = fixture.rematches.respondToRematch(
                    fixture.second.getSessionId(), true);

            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE, result.getResultCode());
            assertEquals("EXPIRED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(0, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals(firstStatusCount + 1, fixture.firstCallback.statusStates.size());
            assertEquals(secondStatusCount + 1, fixture.secondCallback.statusStates.size());
            assertEquals(RematchState.EXPIRED, fixture.firstCallback.statusStates.getLast());
            assertEquals(RematchState.EXPIRED, fixture.secondCallback.statusStates.getLast());
            assertEquals(firstRequestCount, fixture.firstCallback.requestStates.size());
            assertEquals(secondRequestCount, fixture.secondCallback.requestStates.size());
            assertTrue(fixture.matchmaking.isWaiting(fixture.first.getPlayer().getPlayerId()));
            assertEquals(fixture.source.getGameId(), fixture.games.findByPlayerId(
                    fixture.first.getPlayer().getPlayerId()).orElseThrow().getGameId());
            assertEquals(0, fixture.firstCallback.matchFoundIds.size());
            assertEquals(0, fixture.secondCallback.matchFoundIds.size());
        }
    }

    /** A duplicate positive request revalidates waiting membership and expires stale state. */
    @Test
    void duplicateRequestCannotBypassWaitingEligibility() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.rematches.requestRematch(
                    fixture.first.getSessionId()).isSuccess());
            MatchmakingResult waiting = fixture.matchmaking.join(
                    fixture.first.getSessionId(), fixture.first.getPlayer());
            assertTrue(waiting.isSuccess());
            assertEquals(MatchmakingState.WAITING, waiting.getState());
            int firstStatusCount = fixture.firstCallback.statusStates.size();
            int secondStatusCount = fixture.secondCallback.statusStates.size();
            int firstRequestCount = fixture.firstCallback.requestStates.size();
            int secondRequestCount = fixture.secondCallback.requestStates.size();

            OperationResult result = fixture.rematches.requestRematch(
                    fixture.first.getSessionId());

            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE, result.getResultCode());
            assertEquals("EXPIRED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(0, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals(firstStatusCount + 1, fixture.firstCallback.statusStates.size());
            assertEquals(secondStatusCount + 1, fixture.secondCallback.statusStates.size());
            assertEquals(RematchState.EXPIRED, fixture.firstCallback.statusStates.getLast());
            assertEquals(RematchState.EXPIRED, fixture.secondCallback.statusStates.getLast());
            assertEquals(firstRequestCount, fixture.firstCallback.requestStates.size());
            assertEquals(secondRequestCount, fixture.secondCallback.requestStates.size());
            assertTrue(fixture.matchmaking.isWaiting(fixture.first.getPlayer().getPlayerId()));
            assertEquals(fixture.source.getGameId(), fixture.games.findByPlayerId(
                    fixture.first.getPlayer().getPlayerId()).orElseThrow().getGameId());
            assertEquals(0, fixture.firstCallback.matchFoundIds.size());
            assertEquals(0, fixture.secondCallback.matchFoundIds.size());
        }
    }

    /** Explicit positive response shares the same one-shot creation path. */
    @Test
    void requestAndPositiveResponseCreateOneGame() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.rematches.requestRematch(fixture.first.getSessionId()).isSuccess());
            assertTrue(fixture.rematches.respondToRematch(
                    fixture.second.getSessionId(), true).isSuccess());
            assertEquals(1, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals("CREATED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
        }
    }

    /** Opponent false declines permanently and the same false response is idempotent. */
    @Test
    void declineIsTerminalAndDuplicateFalseIsIdempotent() {
        try (Fixture fixture = new Fixture()) {
            fixture.rematches.requestRematch(fixture.first.getSessionId());
            assertTrue(fixture.rematches.respondToRematch(
                    fixture.second.getSessionId(), false).isSuccess());
            assertEquals("DECLINED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            int callbacks = fixture.firstCallback.statusStates.size();
            assertTrue(fixture.rematches.respondToRematch(
                    fixture.second.getSessionId(), false).isSuccess());
            assertEquals(callbacks, fixture.firstCallback.statusStates.size());
            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    fixture.rematches.respondToRematch(
                            fixture.second.getSessionId(), true).getResultCode());
            assertEquals(RematchState.DECLINED, fixture.firstCallback.statusStates.getLast());
        }
    }

    /** Requester false withdraws permanently as EXPIRED. */
    @Test
    void requesterFalseWithdrawsAndCannotReopen() {
        try (Fixture fixture = new Fixture()) {
            fixture.rematches.requestRematch(fixture.first.getSessionId());
            assertTrue(fixture.rematches.respondToRematch(
                    fixture.first.getSessionId(), false).isSuccess());
            assertEquals("EXPIRED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertTrue(fixture.rematches.respondToRematch(
                    fixture.first.getSessionId(), false).isSuccess());
            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    fixture.rematches.requestRematch(
                            fixture.first.getSessionId()).getResultCode());
            assertEquals(RematchState.EXPIRED, fixture.secondCallback.statusStates.getLast());
        }
    }

    /** A historical same-responder expiration cannot absorb cleanup for the current game. */
    @Test
    void falseAfterExpiredHistoricalNegotiationExpiresCurrentGame() {
        try (Fixture fixture = new Fixture()) {
            UUID firstGameId = fixture.source.getGameId();
            assertTrue(fixture.rematches.requestRematch(fixture.first.getSessionId()).isSuccess());
            assertTrue(fixture.rematches.respondToRematch(
                    fixture.first.getSessionId(), false).isSuccess());
            int firstHistoricalCallbacks = fixture.firstCallback.statusGameIds.size();
            int secondHistoricalCallbacks = fixture.secondCallback.statusGameIds.size();
            GameSession secondGame = fixture.games.createGame(fixture.first.getPlayer(),
                    fixture.second.getPlayer(), fixture.first.getPlayer().getPlayerId());
            secondGame.activatePlacement(Instant.parse("2026-08-24T13:00:00Z"),
                    Instant.parse("2026-08-24T13:02:00Z"));
            finishGame(fixture, secondGame, fixture.second.getPlayer().getPlayerId());

            assertTrue(fixture.rematches.respondToRematch(
                    fixture.first.getSessionId(), false).isSuccess());

            assertEquals("EXPIRED", fixture.rematches.lifecycleName(firstGameId));
            assertEquals(0, fixture.rematches.creationClaimCount(firstGameId));
            assertEquals(firstHistoricalCallbacks + 1,
                    fixture.firstCallback.statusGameIds.size());
            assertEquals(secondHistoricalCallbacks + 1,
                    fixture.secondCallback.statusGameIds.size());
            assertEquals(secondGame.getGameId(), fixture.firstCallback.statusGameIds.getLast());
            assertEquals(secondGame.getGameId(), fixture.secondCallback.statusGameIds.getLast());
            assertEquals("EXPIRED", fixture.rematches.lifecycleName(secondGame.getGameId()));
            assertEquals(0, fixture.rematches.creationClaimCount(secondGame.getGameId()));
            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    fixture.rematches.requestRematch(
                            fixture.second.getSessionId()).getResultCode());
            assertEquals(secondGame.getGameId(), fixture.games.findByPlayerId(
                    fixture.first.getPlayer().getPlayerId()).orElseThrow().getGameId());
            assertEquals(0, fixture.firstCallback.matchFoundIds.size());
            assertEquals(0, fixture.secondCallback.matchFoundIds.size());
        }
    }

    /** Returning to Lobby before either request creates a terminal expired opportunity. */
    @Test
    void falseWithoutPriorRequestExpiresOpportunity() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.rematches.respondToRematch(
                    fixture.first.getSessionId(), false).isSuccess());

            assertEquals("EXPIRED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(RematchState.EXPIRED, fixture.secondCallback.statusStates.getLast());
            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    fixture.rematches.requestRematch(
                            fixture.second.getSessionId()).getResultCode());
        }
    }

    /** Session termination can expire an unrequested source after request authority is removed. */
    @Test
    void departureAfterSessionRemovalExpiresUnrequestedOpportunity() {
        try (Fixture fixture = new Fixture()) {
            fixture.sessions.beginTermination(fixture.first.getSessionId())
                    .ifPresent(termination -> fixture.rematches.abandonCompletedGame(
                            termination.getSession().getSessionId(),
                            termination.getSession().getPlayer().getPlayerId()));

            assertEquals("EXPIRED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(RematchState.EXPIRED, fixture.secondCallback.statusStates.getLast());
            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    fixture.rematches.requestRematch(
                            fixture.second.getSessionId()).getResultCode());
        }
    }

    /** Invalid, unfinished, and terminal-finalizing sources are unavailable. */
    @Test
    void invalidAndIneligibleSourcesAreRejected() {
        try (Fixture fixture = new Fixture(false)) {
            assertEquals(ResultCode.INVALID_SESSION,
                    fixture.rematches.requestRematch(UUID.randomUUID()).getResultCode());
            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    fixture.rematches.requestRematch(
                            fixture.first.getSessionId()).getResultCode());

            fixture.source.leave(fixture.second.getPlayer().getPlayerId());
            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    fixture.rematches.requestRematch(
                            fixture.first.getSessionId()).getResultCode());
        }
    }

    /** Missing callback at final creation rejects synchronously and expires the claim. */
    @Test
    void missingCallbackRejectsClaimedCreation() {
        try (Fixture fixture = new Fixture()) {
            fixture.rematches.requestRematch(fixture.first.getSessionId());
            fixture.firstCallback.failStatus = true;
            fixture.callbacks.unregister(fixture.second.getPlayer().getPlayerId());

            OperationResult result = fixture.rematches.respondToRematch(
                    fixture.second.getSessionId(), true);

            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE, result.getResultCode());
            assertEquals(1, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals("EXPIRED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(fixture.source.getGameId(), fixture.games.findByPlayerId(
                    fixture.first.getPlayer().getPlayerId()).orElseThrow().getGameId());
            assertEquals(RematchState.EXPIRED, fixture.firstCallback.statusStates.getLast());
        }
    }

    /** Callback RemoteException cannot roll back successful creation or create a second game. */
    @Test
    void callbackFailureDoesNotRollbackCreatedGame() {
        try (Fixture fixture = new Fixture()) {
            fixture.secondCallback.failStatus = true;
            fixture.rematches.requestRematch(fixture.first.getSessionId());

            assertTrue(fixture.rematches.respondToRematch(
                    fixture.second.getSessionId(), true).isSuccess());
            assertEquals("CREATED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(1, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertNotEquals(fixture.source.getGameId(), fixture.games.findByPlayerId(
                    fixture.first.getPlayer().getPlayerId()).orElseThrow().getGameId());
        }
    }

    /** Concurrent equal requests commit one pending transition and no creation claim. */
    @Test
    void duplicateRequestRaceCommitsOnePendingTransition() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<OperationResult> first = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fixture.rematches.requestRematch(fixture.first.getSessionId());
                });
                Future<OperationResult> duplicate = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fixture.rematches.requestRematch(fixture.first.getSessionId());
                });
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                start.countDown();
                assertTrue(first.get(3, TimeUnit.SECONDS).isSuccess());
                assertTrue(duplicate.get(3, TimeUnit.SECONDS).isSuccess());
            } finally {
                executor.shutdownNow();
            }
            assertEquals(List.of(RematchState.REQUESTED_BY_YOU),
                    fixture.firstCallback.statusStates);
            assertEquals(List.of(RematchState.REQUESTED_BY_OPPONENT),
                    fixture.secondCallback.requestStates);
            assertEquals(0, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
        }
    }

    /** Two positive confirmations overlap one blocked final check and create only one game. */
    @Test
    void simultaneousPositiveConfirmationsShareOneCreationClaim() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.rematches.requestRematch(fixture.first.getSessionId());
            fixture.sessionGuard.blockNextValidation();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<OperationResult> first = executor.submit(() ->
                        fixture.rematches.respondToRematch(
                                fixture.second.getSessionId(), true));
                assertTrue(fixture.sessionGuard.awaitBlocked());
                Future<OperationResult> duplicate = executor.submit(() ->
                        fixture.rematches.respondToRematch(
                                fixture.second.getSessionId(), true));
                assertTrue(duplicate.get(3, TimeUnit.SECONDS).isSuccess());
                fixture.sessionGuard.releaseValidation();
                assertTrue(first.get(3, TimeUnit.SECONDS).isSuccess());
            } finally {
                fixture.sessionGuard.releaseValidation();
                executor.shutdownNow();
            }
            assertEquals(1, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals("CREATED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(1, fixture.firstCallback.matchFoundIds.size());
        }
    }

    /** False after the one-shot claim cannot cancel its blocked final creation attempt. */
    @Test
    void falseAfterCreationClaimCannotRollbackCreation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.rematches.requestRematch(fixture.first.getSessionId());
            fixture.sessionGuard.blockNextValidation();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<OperationResult> acceptance = executor.submit(() ->
                        fixture.rematches.respondToRematch(
                                fixture.second.getSessionId(), true));
                assertTrue(fixture.sessionGuard.awaitBlocked());
                assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                        fixture.rematches.respondToRematch(
                                fixture.first.getSessionId(), false).getResultCode());
                fixture.sessionGuard.releaseValidation();
                assertTrue(acceptance.get(3, TimeUnit.SECONDS).isSuccess());
            } finally {
                fixture.sessionGuard.releaseValidation();
                executor.shutdownNow();
            }
            assertEquals(1, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals("CREATED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
        }
    }

    /** Opponent acceptance racing opponent decline follows the actual rematch-lock winner. */
    @Test
    void acceptVersusDeclineHasOneLinearizedOutcome() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.rematches.requestRematch(fixture.first.getSessionId());
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<OperationResult> accept = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fixture.rematches.respondToRematch(
                            fixture.second.getSessionId(), true);
                });
                Future<OperationResult> decline = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fixture.rematches.respondToRematch(
                            fixture.second.getSessionId(), false);
                });
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                start.countDown();
                assertTrue(accept.get(3, TimeUnit.SECONDS).isSuccess()
                        ^ decline.get(3, TimeUnit.SECONDS).isSuccess());
            } finally {
                executor.shutdownNow();
            }
            String lifecycle = fixture.rematches.lifecycleName(fixture.source.getGameId());
            assertTrue(lifecycle.equals("CREATED") || lifecycle.equals("DECLINED"));
            assertTrue(fixture.rematches.creationClaimCount(fixture.source.getGameId()) <= 1);
        }
    }

    /** A newer source mapping expires an older open negotiation without claiming creation. */
    @Test
    void newerGameMappingExpiresOldNegotiation() {
        try (Fixture fixture = new Fixture()) {
            fixture.rematches.requestRematch(fixture.first.getSessionId());
            GameSession newer = fixture.games.createGame(fixture.first.getPlayer(),
                    fixture.second.getPlayer(), fixture.second.getPlayer().getPlayerId());

            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    fixture.rematches.respondToRematch(
                            fixture.second.getSessionId(), true).getResultCode());
            assertEquals("EXPIRED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(0, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals(newer.getGameId(), fixture.games.findByPlayerId(
                    fixture.first.getPlayer().getPlayerId()).orElseThrow().getGameId());
        }
    }

    /** Source replacement after a claim makes the final check expire without another game. */
    @Test
    void staleClaimAfterSourceMappingChangeCreatesNoRematch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.rematches.requestRematch(fixture.first.getSessionId());
            fixture.sessionGuard.blockNextValidation();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<OperationResult> acceptance = executor.submit(() ->
                        fixture.rematches.respondToRematch(
                                fixture.second.getSessionId(), true));
                assertTrue(fixture.sessionGuard.awaitBlocked());
                GameSession replacement = fixture.games.createGame(fixture.first.getPlayer(),
                        fixture.second.getPlayer(), fixture.first.getPlayer().getPlayerId());
                fixture.sessionGuard.releaseValidation();
                assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                        acceptance.get(3, TimeUnit.SECONDS).getResultCode());
                assertEquals(replacement.getGameId(), fixture.games.findByPlayerId(
                        fixture.first.getPlayer().getPlayerId()).orElseThrow().getGameId());
            } finally {
                fixture.sessionGuard.releaseValidation();
                executor.shutdownNow();
            }
            assertEquals(1, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals("EXPIRED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(0, fixture.firstCallback.matchFoundIds.size());
        }
    }

    /** A replacement session for the same player cannot revive an old exact-session negotiation. */
    @Test
    void replacementSessionForSamePlayerCannotReviveNegotiation() {
        UUID firstPlayerId = UUID.randomUUID();
        UUID secondPlayerId = UUID.randomUUID();
        SessionRegistry sessions = new SessionRegistry();
        GameSessionManager games = new GameSessionManager();
        ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        GameCoordinator coordinator = new GameCoordinator(games, callbacks, ratings);
        MatchmakingService matchmaking = new MatchmakingService(
                games, callbacks, coordinator, ratings,
                (sessionId, playerId) -> sessions.findSession(sessionId)
                        .map(session -> session.getPlayer().getPlayerId().equals(playerId))
                        .orElse(false));
        RematchCoordinator rematches = new RematchCoordinator(
                sessions, games, matchmaking, callbacks);
        PlayerView firstPlayer = new PlayerView(firstPlayerId, "First", 1000, false);
        PlayerView secondPlayer = new PlayerView(secondPlayerId, "Second", 1000, false);
        SessionInfo first = sessions.claimRegistered(firstPlayer).orElseThrow();
        SessionInfo second = sessions.claimRegistered(secondPlayer).orElseThrow();
        callbacks.register(firstPlayer, new RecordingCallback());
        callbacks.register(secondPlayer, new RecordingCallback());
        GameSession source = games.createGame(firstPlayer, secondPlayer, firstPlayerId);
        source.activatePlacement(Instant.parse("2026-08-24T12:00:00Z"),
                Instant.parse("2026-08-24T12:02:00Z"));
        source.leave(secondPlayerId);
        games.markTerminalFinalizationComplete(source.getGameId());
        try {
            assertTrue(rematches.requestRematch(first.getSessionId()).isSuccess());
            SessionRegistry.Termination termination = sessions.beginTermination(
                    first.getSessionId()).orElseThrow();
            assertTrue(sessions.completeTermination(termination));
            SessionInfo replacement = sessions.claimRegistered(firstPlayer).orElseThrow();
            callbacks.register(firstPlayer, new RecordingCallback());

            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    rematches.respondToRematch(second.getSessionId(), true).getResultCode());
            assertEquals("EXPIRED", rematches.lifecycleName(source.getGameId()));
            assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                    rematches.requestRematch(replacement.getSessionId()).getResultCode());
            assertEquals(source.getGameId(), games.findByPlayerId(firstPlayerId)
                    .orElseThrow().getGameId());
        } finally {
            coordinator.close();
        }
    }

    /** Simultaneous requests linearize to one claim and one new game without sleeps. */
    @Test
    void simultaneousRequestsCreateAtMostOneGame() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<OperationResult> first = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fixture.rematches.requestRematch(fixture.first.getSessionId());
                });
                Future<OperationResult> second = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fixture.rematches.requestRematch(fixture.second.getSessionId());
                });
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                start.countDown();
                assertTrue(first.get(3, TimeUnit.SECONDS).isSuccess());
                assertTrue(second.get(3, TimeUnit.SECONDS).isSuccess());
            } finally {
                executor.shutdownNow();
            }
            assertEquals(1, fixture.rematches.creationClaimCount(fixture.source.getGameId()));
            assertEquals("CREATED", fixture.rematches.lifecycleName(fixture.source.getGameId()));
            assertEquals(1, fixture.firstCallback.matchFoundIds.size());
            assertEquals(1, fixture.secondCallback.matchFoundIds.size());
        }
    }

    /** Accept racing requester withdrawal follows lock order and never creates twice. */
    @Test
    void acceptVersusWithdrawalHasOneLinearizedOutcome() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.rematches.requestRematch(fixture.first.getSessionId());
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<OperationResult> accept = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fixture.rematches.respondToRematch(
                            fixture.second.getSessionId(), true);
                });
                Future<OperationResult> withdraw = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fixture.rematches.respondToRematch(
                            fixture.first.getSessionId(), false);
                });
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                start.countDown();
                OperationResult acceptResult = accept.get(3, TimeUnit.SECONDS);
                OperationResult withdrawalResult = withdraw.get(3, TimeUnit.SECONDS);
                assertTrue(acceptResult.isSuccess() ^ withdrawalResult.isSuccess());
            } finally {
                executor.shutdownNow();
            }
            assertTrue(fixture.rematches.creationClaimCount(fixture.source.getGameId()) <= 1);
            String lifecycle = fixture.rematches.lifecycleName(fixture.source.getGameId());
            assertTrue(lifecycle.equals("CREATED") || lifecycle.equals("EXPIRED"));
        }
    }

    /** Accept racing exact-session termination either creates first or expires without a game. */
    @Test
    void acceptVersusLogoutNeverRevivesEndedSession() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.rematches.requestRematch(fixture.first.getSessionId());
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<OperationResult> accept = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fixture.rematches.respondToRematch(
                            fixture.second.getSessionId(), true);
                });
                Future<?> logout = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    fixture.sessions.beginTermination(fixture.second.getSessionId())
                            .ifPresent(termination -> {
                                UUID playerId = termination.getSession().getPlayer().getPlayerId();
                                fixture.matchmaking.terminateSession(
                                        fixture.second.getSessionId(), playerId);
                                fixture.rematches.terminateSession(
                                        fixture.second.getSessionId(), playerId);
                                fixture.coordinator.disconnect(playerId);
                                fixture.sessions.completeTermination(termination);
                            });
                    return null;
                });
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                start.countDown();
                OperationResult result = accept.get(3, TimeUnit.SECONDS);
                logout.get(3, TimeUnit.SECONDS);
                assertTrue(result.isSuccess()
                        || result.getResultCode() == ResultCode.INVALID_SESSION
                        || result.getResultCode() == ResultCode.REMATCH_NOT_AVAILABLE);
            } finally {
                executor.shutdownNow();
            }
            assertTrue(fixture.sessions.findSession(fixture.second.getSessionId()).isEmpty());
            assertTrue(fixture.rematches.creationClaimCount(fixture.source.getGameId()) <= 1);
            if (fixture.rematches.lifecycleName(fixture.source.getGameId()).equals("EXPIRED")) {
                assertEquals(fixture.source.getGameId(), fixture.games.findByPlayerId(
                        fixture.first.getPlayer().getPlayerId()).orElseThrow().getGameId());
            }
        }
    }

    /** Ordinary matchmaking and rematch creation share one availability winner. */
    @Test
    void acceptVersusOrdinaryMatchmakingCannotCreateBothGames() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.rematches.requestRematch(fixture.first.getSessionId());
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<OperationResult> accept = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fixture.rematches.respondToRematch(
                            fixture.second.getSessionId(), true);
                });
                Future<MatchmakingResult> ordinary = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    MatchmakingResult result = fixture.matchmaking.join(
                            fixture.first.getSessionId(), fixture.first.getPlayer());
                    if (result.isSuccess()) {
                        fixture.rematches.expireForOrdinaryMatchmaking(
                                fixture.first.getPlayer().getPlayerId());
                    }
                    return result;
                });
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                start.countDown();
                OperationResult rematchResult = accept.get(3, TimeUnit.SECONDS);
                MatchmakingResult ordinaryResult = ordinary.get(3, TimeUnit.SECONDS);
                assertTrue(rematchResult.isSuccess()
                        ^ ordinaryResult.getState() == MatchmakingState.WAITING);
            } finally {
                executor.shutdownNow();
            }
            assertTrue(fixture.rematches.creationClaimCount(fixture.source.getGameId()) <= 1);
        }
    }

    /**
     * Finishes and publishes one current game as an eligible rematch source.
     *
     * @param fixture owning rematch fixture
     * @param game current game to finish
     * @param departingPlayerId participant whose departure finishes the game
     */
    private static void finishGame(Fixture fixture, GameSession game, UUID departingPlayerId) {
        assertTrue(game.leave(departingPlayerId).isSuccess());
        fixture.games.markTerminalFinalizationComplete(game.getGameId());
    }

    /** Owns a complete in-memory rematch source and closes coordinator resources. */
    private static final class Fixture implements AutoCloseable {
        private final SessionRegistry sessions = new SessionRegistry();
        private final GameSessionManager games = new GameSessionManager();
        private final ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        private final RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        private final GameCoordinator coordinator = new GameCoordinator(games, callbacks, ratings);
        private final BlockingSessionValidityGuard sessionGuard =
                new BlockingSessionValidityGuard(sessions);
        private final MatchmakingService matchmaking = new MatchmakingService(
                games, callbacks, coordinator, ratings,
                sessionGuard);
        private final RematchCoordinator rematches = new RematchCoordinator(
                sessions, games, matchmaking, callbacks);
        private final SessionInfo first = sessions.createGuest("First");
        private final SessionInfo second = sessions.createGuest("Second");
        private final RecordingCallback firstCallback = new RecordingCallback();
        private final RecordingCallback secondCallback = new RecordingCallback();
        private final GameSession source;

        /** Creates and finalizes an eligible source game. */
        private Fixture() {
            this(true);
        }

        /**
         * Creates a source game with optional terminal finalization publication.
         *
         * @param finish true to finish and publish finalization
         */
        private Fixture(boolean finish) {
            callbacks.register(first.getPlayer(), firstCallback);
            callbacks.register(second.getPlayer(), secondCallback);
            source = games.createGame(first.getPlayer(), second.getPlayer(),
                    first.getPlayer().getPlayerId());
            source.activatePlacement(Instant.parse("2026-08-24T12:00:00Z"),
                    Instant.parse("2026-08-24T12:02:00Z"));
            if (finish) {
                source.leave(second.getPlayer().getPlayerId());
                games.markTerminalFinalizationComplete(source.getGameId());
            }
        }

        /** Stops virtual-thread and deadline resources. */
        @Override
        public void close() {
            coordinator.close();
        }
    }

    /** Deterministically pauses one final matchmaking exact-session validation. */
    private static final class BlockingSessionValidityGuard
            implements MatchmakingService.SessionValidityGuard {
        private final SessionRegistry sessions;
        private volatile CountDownLatch blocked;
        private volatile CountDownLatch release;

        /**
         * Creates a guard backed by the fixture's single session registry.
         *
         * @param sessions exact session authority
         */
        private BlockingSessionValidityGuard(SessionRegistry sessions) {
            this.sessions = sessions;
        }

        /** Arms one validation call to stop until the test releases it. */
        private void blockNextValidation() {
            blocked = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        /**
         * Waits for the armed validation to reach the deterministic pause.
         *
         * @return true when validation is paused before the timeout
         * @throws InterruptedException if the test thread is interrupted
         */
        private boolean awaitBlocked() throws InterruptedException {
            CountDownLatch current = blocked;
            return current != null && current.await(2, TimeUnit.SECONDS);
        }

        /** Releases an armed validation and is safe to call during cleanup. */
        private void releaseValidation() {
            CountDownLatch current = release;
            if (current != null) {
                current.countDown();
            }
        }

        /** {@inheritDoc} */
        @Override
        public boolean isCurrent(UUID sessionId, UUID playerId) {
            CountDownLatch currentBlocked = blocked;
            CountDownLatch currentRelease = release;
            if (currentBlocked != null && currentRelease != null) {
                blocked = null;
                currentBlocked.countDown();
                try {
                    if (!currentRelease.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release session validation");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("session validation interrupted", exception);
                }
            }
            return sessions.findSession(sessionId)
                    .map(session -> session.getPlayer().getPlayerId().equals(playerId))
                    .orElse(false);
        }
    }

    /** Records rematch and match-found callbacks and can fail status delivery. */
    private static final class RecordingCallback implements FleetLinkClientCallback {
        private final List<RematchState> requestStates = new ArrayList<>();
        private final List<RematchState> statusStates = new ArrayList<>();
        private final List<UUID> statusGameIds = new ArrayList<>();
        private final List<UUID> matchFoundIds = new ArrayList<>();
        private boolean failStatus;

        /** {@inheritDoc} */
        @Override
        public void onMatchFound(GameView initialGame) {
            matchFoundIds.add(initialGame.getGameId());
        }

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchRequested(RematchStatusView rematchStatus) {
            requestStates.add(rematchStatus.getState());
        }

        /** {@inheritDoc} */
        @Override
        public void onRematchStatusChanged(RematchStatusView rematchStatus)
                throws RemoteException {
            statusStates.add(rematchStatus.getState());
            statusGameIds.add(rematchStatus.getCompletedGameId());
            if (failStatus) {
                throw new RemoteException("simulated rematch callback failure");
            }
        }
    }
}
