package io.github.tomerg12.fleetlink.server.rmi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.account.AccountService;
import io.github.tomerg12.fleetlink.server.account.PasswordHasher;
import io.github.tomerg12.fleetlink.server.completion.CompletedGameSnapshot;
import io.github.tomerg12.fleetlink.server.completion.CompletedParticipantSnapshot;
import io.github.tomerg12.fleetlink.server.completion.CompletionRecorder;
import io.github.tomerg12.fleetlink.server.completion.JpaCompletedGameStore;
import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.server.matchmaking.MatchmakingService;
import io.github.tomerg12.fleetlink.server.persistence.CompletedGameEntity;
import io.github.tomerg12.fleetlink.server.persistence.FleetLinkPersistence;
import io.github.tomerg12.fleetlink.server.persistence.GameParticipantEntity;
import io.github.tomerg12.fleetlink.server.persistence.ParticipantResult;
import io.github.tomerg12.fleetlink.server.persistence.PersistenceTestSupport;
import io.github.tomerg12.fleetlink.server.persistence.PlayerEntity;
import io.github.tomerg12.fleetlink.server.persistence.PlayerRepository;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.server.rematch.RematchCoordinator;
import io.github.tomerg12.fleetlink.server.service.ClientCallbackRegistry;
import io.github.tomerg12.fleetlink.server.service.GameCoordinator;
import io.github.tomerg12.fleetlink.server.session.SessionRegistry;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingState;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkServerRemote;
import jakarta.persistence.EntityManager;
import java.rmi.Remote;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Verifies real registered RMI calls, mixed matchmaking, logout races, and durable summaries.
 */
class RegisteredAccountRmiIntegrationTest {

    /**
     * Registers through an exported RMI stub, logs out, and logs in with the same persistent UUID.
     *
     * @throws Exception if local RMI setup or invocation fails
     */
    @Test
    void exportedRegisterAndLoginPreservePersistentIdentity() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Registry registry = LocateRegistry.createRegistry(0);
            Remote serverStub = UnicastRemoteObject.exportObject(fixture.server, 0);
            RecordingCallback callback = new RecordingCallback();
            FleetLinkClientCallback callbackStub = (FleetLinkClientCallback)
                    UnicastRemoteObject.exportObject(callback, 0);
            try {
                registry.rebind("FleetLinkServer", serverStub);
                FleetLinkServerRemote remote = (FleetLinkServerRemote)
                        registry.lookup("FleetLinkServer");
                SessionResult registered = remote.register(
                        "RmiAccount", " exact password ", callbackStub);
                UUID playerId = registered.getSessionInfo().getPlayer().getPlayerId();

                assertTrue(registered.isSuccess());
                assertFalse(registered.getSessionInfo().getPlayer().isGuest());
                assertTrue(remote.logout(registered.getSessionInfo().getSessionId()).isSuccess());
                assertEquals(ResultCode.INVALID_CREDENTIALS,
                        remote.login("rmiaccount", "exact password", callbackStub).getResultCode());
                SessionResult login = remote.login(
                        "RMIACCOUNT", " exact password ", callbackStub);
                assertTrue(login.isSuccess());
                assertEquals(playerId, login.getSessionInfo().getPlayer().getPlayerId());
                assertEquals(1, fixture.players.count());
            } finally {
                UnicastRemoteObject.unexportObject(callback, true);
                UnicastRemoteObject.unexportObject(fixture.server, true);
                UnicastRemoteObject.unexportObject(registry, true);
            }
        }
    }

    /**
     * Creates a guest session without writing a persistent Player row.
     *
     * @throws Exception if the guest connection unexpectedly fails
     */
    @Test
    void guestConnectionCreatesNoPlayerRow() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SessionResult guest = fixture.server.connectAsGuest(
                    "Temporary Guest", new RecordingCallback());

            assertTrue(guest.isSuccess());
            assertTrue(guest.getSessionInfo().getPlayer().isGuest());
            assertEquals(0, fixture.players.count());
        }
    }

    /**
     * Persists and reloads a mixed registered and guest completion reached only through exported
     * server and callback stubs.
     *
     * @throws Exception if RMI setup, invocation, or completion recording fails
     */
    @Test
    void exportedMixedGamePersistsAuthoritativeCompletionAndPlayerLink() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Registry registry = LocateRegistry.createRegistry(0);
            Remote serverStub = UnicastRemoteObject.exportObject(fixture.server, 0);
            RecordingCallback accountCallback = new RecordingCallback();
            RecordingCallback guestCallback = new RecordingCallback();
            FleetLinkClientCallback accountCallbackStub = (FleetLinkClientCallback)
                    UnicastRemoteObject.exportObject(accountCallback, 0);
            FleetLinkClientCallback guestCallbackStub = (FleetLinkClientCallback)
                    UnicastRemoteObject.exportObject(guestCallback, 0);
            try {
                registry.rebind("FleetLinkServer", serverStub);
                FleetLinkServerRemote remote = (FleetLinkServerRemote)
                        registry.lookup("FleetLinkServer");
                SessionResult account = remote.register(
                        "MixedHistory", "password", accountCallbackStub);
                SessionResult guest = remote.connectAsGuest("Guest Rival", guestCallbackStub);

                assertTrue(account.isSuccess());
                assertTrue(guest.isSuccess());
                UUID accountSessionId = account.getSessionInfo().getSessionId();
                UUID guestSessionId = guest.getSessionInfo().getSessionId();
                assertEquals(MatchmakingState.WAITING,
                        remote.joinMatchmaking(accountSessionId).getState());
                assertEquals(MatchmakingState.MATCHED,
                        remote.joinMatchmaking(guestSessionId).getState());
                GameView active = remote.getCurrentGame(accountSessionId).getGameView();

                assertTrue(remote.leaveGame(guestSessionId).isSuccess());
                GameView terminal = remote.getCurrentGame(accountSessionId).getGameView();
                fixture.awaitRecordedGames(1);

                assertEquals(active.getGameId(), terminal.getGameId());
                assertEquals(GamePhase.FINISHED, terminal.getPhase());
                assertEquals(account.getSessionInfo().getPlayer().getPlayerId(),
                        terminal.getWinner().getPlayerId());
                assertEquals(GameEndReason.RESIGNATION, terminal.getEndReason());
                assertEquals(1, fixture.store.countGames());
                assertEquals(2, fixture.store.countParticipants());

                CompletedGameSnapshot stored = fixture.store.find(terminal.getGameId())
                        .orElseThrow();
                assertEquals(terminal.getGameId(), stored.getGameId());
                assertEquals(GameEndReason.RESIGNATION, stored.getEndReason());
                assertParticipant(stored, account.getSessionInfo().getPlayer().getPlayerId(),
                        "MixedHistory", false,
                        account.getSessionInfo().getPlayer().getRating(), ParticipantResult.WIN);
                assertParticipant(stored, guest.getSessionInfo().getPlayer().getPlayerId(),
                        "Guest Rival", true, 1000, ParticipantResult.LOSS);
                assertPlayerLinks(fixture, terminal.getGameId(),
                        account.getSessionInfo().getPlayer().getPlayerId());
            } finally {
                UnicastRemoteObject.unexportObject(accountCallback, true);
                UnicastRemoteObject.unexportObject(guestCallback, true);
                UnicastRemoteObject.unexportObject(fixture.server, true);
                UnicastRemoteObject.unexportObject(registry, true);
            }
        }
    }

    /**
     * Keeps identity reserved while logout cleanup is blocked and protects the replacement callback.
     *
     * @throws Exception if concurrent logout coordination fails
     */
    @Test
    void concurrentLogoutAndLoginCannotReplaceOrRemoveWrongCallback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            RecordingCallback oldCallback = new RecordingCallback();
            BlockingCallback guestCallback = new BlockingCallback();
            SessionResult account = fixture.server.register("RaceAccount", "password", oldCallback);
            SessionResult guest = fixture.server.connectAsGuest("Guest", guestCallback);
            fixture.server.joinMatchmaking(account.getSessionInfo().getSessionId());
            assertEquals(MatchmakingState.MATCHED,
                    fixture.server.joinMatchmaking(guest.getSessionInfo().getSessionId()).getState());

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                CompletableFuture<Void> logout = CompletableFuture.runAsync(() -> {
                    try {
                        fixture.server.logout(account.getSessionInfo().getSessionId());
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }, executor);
                assertTrue(guestCallback.entered.await(5, TimeUnit.SECONDS));
                assertEquals(ResultCode.INVALID_REQUEST,
                        fixture.server.login("raceaccount", "password", new RecordingCallback())
                                .getResultCode());
                guestCallback.release.countDown();
                logout.get(5, TimeUnit.SECONDS);

                RecordingCallback replacement = new RecordingCallback();
                SessionResult login = fixture.server.login(
                        "RACEACCOUNT", "password", replacement);
                assertTrue(login.isSuccess());
                UUID playerId = login.getSessionInfo().getPlayer().getPlayerId();
                fixture.callbacks.unregister(playerId, oldCallback);
                assertSame(replacement, fixture.callbacks.find(playerId).orElseThrow());
            } finally {
                guestCallback.release.countDown();
                executor.shutdownNow();
            }
        }
    }

    /**
     * Persists a mixed logout-triggered disconnect but skips a later guest-only completion.
     *
     * @throws Exception if completion recording does not settle in time
     */
    @Test
    void matchmakingCombinationsApplyCompletionEligibility() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SessionResult account = fixture.server.register(
                    "HistoryUser", "password", new RecordingCallback());
            SessionResult guest = fixture.server.connectAsGuest("Guest One", new RecordingCallback());
            fixture.server.joinMatchmaking(account.getSessionInfo().getSessionId());
            fixture.server.joinMatchmaking(guest.getSessionInfo().getSessionId());
            fixture.server.logout(account.getSessionInfo().getSessionId());
            fixture.awaitRecordedGames(1);

            SessionResult firstGuest = fixture.server.connectAsGuest(
                    "Guest Two", new RecordingCallback());
            SessionResult secondGuest = fixture.server.connectAsGuest(
                    "Guest Three", new RecordingCallback());
            fixture.server.joinMatchmaking(firstGuest.getSessionInfo().getSessionId());
            fixture.server.joinMatchmaking(secondGuest.getSessionInfo().getSessionId());
            fixture.server.leaveGame(firstGuest.getSessionInfo().getSessionId());

            assertEquals(1, fixture.store.countGames());
            assertEquals(2, fixture.store.countParticipants());

            SessionResult accountAgain = fixture.server.login(
                    "historyuser", "password", new RecordingCallback());
            fixture.server.joinMatchmaking(guest.getSessionInfo().getSessionId());
            fixture.server.joinMatchmaking(accountAgain.getSessionInfo().getSessionId());
            fixture.server.leaveGame(guest.getSessionInfo().getSessionId());
            fixture.awaitRecordedGames(2);

            SessionResult secondAccount = fixture.server.register(
                    "SecondHistory", "password", new RecordingCallback());
            fixture.server.joinMatchmaking(secondAccount.getSessionInfo().getSessionId());
            fixture.server.joinMatchmaking(accountAgain.getSessionInfo().getSessionId());
            UUID ratedGameId = fixture.server.getCurrentGame(
                    accountAgain.getSessionInfo().getSessionId()).getGameView().getGameId();
            fixture.server.leaveGame(secondAccount.getSessionInfo().getSessionId());
            fixture.awaitRecordedGames(3);
            assertEquals(6, fixture.store.countParticipants());
            UUID winnerId = accountAgain.getSessionInfo().getPlayer().getPlayerId();
            UUID loserId = secondAccount.getSessionInfo().getPlayer().getPlayerId();
            PlayerEntity durableWinner = fixture.players.findById(winnerId).orElseThrow();
            PlayerEntity durableLoser = fixture.players.findById(loserId).orElseThrow();
            assertEquals(1016, durableWinner.getRating());
            assertEquals(984, durableLoser.getRating());
            assertEquals(1L, durableWinner.getRatingRevision());
            assertEquals(1L, durableLoser.getRatingRevision());
            CompletedGameSnapshot rated = fixture.store.find(ratedGameId).orElseThrow();
            assertEquals(16, rated.getParticipants().stream()
                    .filter(participant -> participant.getPlayerId().equals(winnerId))
                    .findFirst().orElseThrow().getRatingDelta());
            assertEquals(-16, rated.getParticipants().stream()
                    .filter(participant -> participant.getPlayerId().equals(loserId))
                    .findFirst().orElseThrow().getRatingDelta());
            assertEquals(0L, rated.getParticipants().getFirst().getRatingRevisionBefore());
        }
    }

    /**
     * Verifies all persisted participant snapshot fields for one expected identity.
     *
     * @param snapshot stored completed game
     * @param playerId expected participant identity
     * @param displayName expected display name
     * @param guest expected guest flag
     * @param rating expected rating at match time
     * @param result expected terminal result
     */
    private static void assertParticipant(CompletedGameSnapshot snapshot, UUID playerId,
                                          String displayName, boolean guest, int rating,
                                          ParticipantResult result) {
        CompletedParticipantSnapshot participant = snapshot.getParticipants().stream()
                .filter(candidate -> candidate.getPlayerId().equals(playerId))
                .findFirst().orElseThrow();
        assertEquals(displayName, participant.getDisplayName());
        assertEquals(guest, participant.isGuest());
        assertEquals(rating, participant.getRatingAtMatch());
        assertEquals(result, participant.getResult());
        assertEquals(0, participant.getShotsFired());
        assertEquals(0, participant.getHits());
        assertEquals(0, participant.getShipsSunk());
        assertEquals(0, participant.getTurnsTaken());
        assertEquals(0, participant.getRatingDelta());
        assertNull(participant.getRatingRevisionBefore());
    }

    /**
     * Verifies the registered participant owns a Player foreign key and the guest does not.
     *
     * @param fixture persistence fixture that owns the operation EntityManager
     * @param gameId stored game identifier
     * @param registeredPlayerId expected registered player identity
     */
    private static void assertPlayerLinks(Fixture fixture, UUID gameId,
                                          UUID registeredPlayerId) {
        try (EntityManager entityManager = fixture.persistence.getEntityManagerFactory()
                .createEntityManager()) {
            CompletedGameEntity game = entityManager.find(CompletedGameEntity.class, gameId);
            assertNotNull(game);
            assertEquals(2, game.getParticipants().size());
            GameParticipantEntity registered = game.getParticipants().stream()
                    .filter(participant -> !participant.isGuest()).findFirst().orElseThrow();
            GameParticipantEntity guest = game.getParticipants().stream()
                    .filter(GameParticipantEntity::isGuest).findFirst().orElseThrow();
            assertNotNull(registered.getPlayer());
            assertEquals(registeredPlayerId, registered.getPlayer().getId());
            assertEquals(registered.getPlayer().getId(), registered.getPlayerIdSnapshot());
            assertNull(guest.getPlayer());
        }
    }

    /**
     * Owns one isolated persistent RMI service graph.
     */
    private static final class Fixture implements AutoCloseable {
        private final FleetLinkPersistence persistence = PersistenceTestSupport.openMemory();
        private final PlayerRepository players = new PlayerRepository(
                persistence.getEntityManagerFactory());
        private final ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
        private final SessionRegistry sessions = new SessionRegistry(players::existsById);
        private final RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
        private final GameSessionManager games = new GameSessionManager();
        private final JpaCompletedGameStore store = new JpaCompletedGameStore(
                persistence.getEntityManagerFactory());
        private final CompletionRecorder recorder = new CompletionRecorder(store);
        private final GameCoordinator coordinator = new GameCoordinator(
                games, callbacks, recorder, Clock.systemUTC(),
                new io.github.tomerg12.fleetlink.server.deadline.ScheduledExecutorDeadlineScheduler(
                        Clock.systemUTC()), ratings);
        private final MatchmakingService matchmaking = new MatchmakingService(
                games, callbacks, coordinator, ratings,
                (sessionId, playerId) -> sessions.findSession(sessionId)
                        .map(session -> session.getPlayer().getPlayerId().equals(playerId))
                        .orElse(false));
        private final RematchCoordinator rematches = new RematchCoordinator(
                sessions, games, matchmaking, callbacks);
        private final AccountService accounts = new AccountService(players, sessions, callbacks,
                PasswordHasher.forTesting(31, new SecureRandom()), Clock.systemUTC(),
                UUID::randomUUID, ratings);
        private final FleetLinkServerRemoteImpl server = new FleetLinkServerRemoteImpl(
                sessions, callbacks, matchmaking, coordinator, rematches, accounts);

        /**
         * Waits until the requested number of durable completed games is visible.
         *
         * @param expected expected durable row count
         * @throws Exception if recording does not settle before the timeout
         */
        private void awaitRecordedGames(long expected) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (store.countGames() != expected && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(expected, store.countGames());
        }

        /**
         * Stops completion retry work before closing persistence.
         */
        @Override
        public void close() {
            recorder.close();
            persistence.close();
        }
    }

    /**
     * Provides a callback object and records no presentation state.
     */
    private static class RecordingCallback implements FleetLinkClientCallback {
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

    /**
     * Blocks the terminal callback so logout remains inside cleanup for a concurrency assertion.
     */
    private static final class BlockingCallback extends RecordingCallback {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        /** {@inheritDoc} */
        @Override
        public void onGameStateChanged(GameView gameView) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
