package io.github.tomerg12.fleetlink.server.rmi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.account.AccountService;
import io.github.tomerg12.fleetlink.server.account.PasswordHasher;
import io.github.tomerg12.fleetlink.server.completion.CompletedGameSnapshot;
import io.github.tomerg12.fleetlink.server.completion.CompletedParticipantSnapshot;
import io.github.tomerg12.fleetlink.server.completion.CompletionRecordOutcome;
import io.github.tomerg12.fleetlink.server.completion.CompletionRecorder;
import io.github.tomerg12.fleetlink.server.completion.JpaCompletedGameStore;
import io.github.tomerg12.fleetlink.server.deadline.ScheduledExecutorDeadlineScheduler;
import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.server.matchmaking.MatchmakingService;
import io.github.tomerg12.fleetlink.server.persistence.FleetLinkPersistence;
import io.github.tomerg12.fleetlink.server.persistence.ParticipantResult;
import io.github.tomerg12.fleetlink.server.persistence.PersistenceTestSupport;
import io.github.tomerg12.fleetlink.server.persistence.PlayerEntity;
import io.github.tomerg12.fleetlink.server.persistence.PlayerRepository;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.server.rematch.RematchCoordinator;
import io.github.tomerg12.fleetlink.server.service.ClientCallbackRegistry;
import io.github.tomerg12.fleetlink.server.service.GameCoordinator;
import io.github.tomerg12.fleetlink.server.session.SessionRegistry;
import io.github.tomerg12.fleetlink.server.statistics.StatisticsQueryService;
import io.github.tomerg12.fleetlink.server.statistics.StatisticsRepository;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchOutcome;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsResult;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkServerRemote;
import java.rmi.Remote;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies T6.2 validation, guest policy, nested serialization, and bounds through real RMI.
 */
class StatisticsRmiIntegrationTest {

    /**
     * Exercises every required outcome and a real nested history response through an exported stub.
     *
     * @throws Exception if local registry, export, persistence, or RMI invocation fails
     */
    @Test
    void exportedStatisticsOperationsEnforceContractAndSerializeNestedPayloads() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Registry registry = LocateRegistry.createRegistry(0);
            Remote serverStub = UnicastRemoteObject.exportObject(fixture.server, 0);
            RecordingCallback registeredCallback = new RecordingCallback();
            RecordingCallback guestCallback = new RecordingCallback();
            FleetLinkClientCallback registeredCallbackStub = (FleetLinkClientCallback)
                    UnicastRemoteObject.exportObject(registeredCallback, 0);
            FleetLinkClientCallback guestCallbackStub = (FleetLinkClientCallback)
                    UnicastRemoteObject.exportObject(guestCallback, 0);
            try {
                registry.rebind(FleetLinkServerMain.BINDING_NAME, serverStub);
                FleetLinkServerRemote remote = (FleetLinkServerRemote)
                        registry.lookup(FleetLinkServerMain.BINDING_NAME);
                UUID invalidSession = UUID.randomUUID();

                assertEquals(ResultCode.INVALID_SESSION,
                        remote.getPlayerStatistics(invalidSession, -1, 0).getResultCode());
                assertEquals(ResultCode.INVALID_SESSION,
                        remote.getLeaderboard(invalidSession, 0).getResultCode());

                SessionResult registered = remote.register(
                        "StatsAccount", "registered password", registeredCallbackStub);
                SessionResult guest = remote.connectAsGuest("Stats Guest", guestCallbackStub);
                assertTrue(registered.isSuccess());
                assertTrue(guest.isSuccess());
                UUID registeredSession = registered.getSessionInfo().getSessionId();
                UUID guestSession = guest.getSessionInfo().getSessionId();
                fixture.recordMixedHistory(
                        registered.getSessionInfo().getPlayer().getPlayerId());

                PlayerStatisticsResult guestStatistics = remote.getPlayerStatistics(
                        guestSession, 0, 10);
                assertEquals(ResultCode.REGISTERED_ACCOUNT_REQUIRED,
                        guestStatistics.getResultCode());
                assertNull(guestStatistics.getStatistics());
                assertEquals(ResultCode.INVALID_REQUEST,
                        remote.getPlayerStatistics(guestSession, -1, 10).getResultCode());
                assertEquals(ResultCode.INVALID_REQUEST,
                        remote.getPlayerStatistics(guestSession, 0, 0).getResultCode());
                LeaderboardResult guestLeaderboard = remote.getLeaderboard(guestSession, 100);
                assertTrue(guestLeaderboard.isSuccess());

                PlayerStatisticsResult statistics = remote.getPlayerStatistics(
                        registeredSession, 0, 50);
                LeaderboardResult leaderboard = remote.getLeaderboard(registeredSession, 100);
                assertTrue(statistics.isSuccess());
                assertTrue(leaderboard.isSuccess());
                assertEquals(1, statistics.getStatistics().getHistory().size());
                assertEquals("Serialized Guest",
                        statistics.getStatistics().getHistory().get(0).getOpponentDisplayName());
                assertTrue(statistics.getStatistics().getHistory().get(0).isOpponentGuest());
                assertEquals(MatchOutcome.WIN,
                        statistics.getStatistics().getHistory().get(0).getOutcome());
                assertFalse(leaderboard.getEntries().isEmpty());
                assertEquals("StatsAccount", leaderboard.getEntries().get(0).getUsername());

                assertInvalidStatisticsBounds(remote, registeredSession);
                assertInvalidLeaderboardBounds(remote, registeredSession);
                assertTrue(remote.getPlayerStatistics(registeredSession, 0, 50).isSuccess());
                assertTrue(remote.getLeaderboard(guestSession, 100).isSuccess());
            } finally {
                UnicastRemoteObject.unexportObject(registeredCallback, true);
                UnicastRemoteObject.unexportObject(guestCallback, true);
                UnicastRemoteObject.unexportObject(fixture.server, true);
                UnicastRemoteObject.unexportObject(registry, true);
            }
        }
    }

    /**
     * Verifies every malformed personal-statistics bound returns INVALID_REQUEST for a valid session.
     *
     * @param remote exported server stub
     * @param sessionId valid registered session
     * @throws Exception if RMI invocation fails
     */
    private static void assertInvalidStatisticsBounds(FleetLinkServerRemote remote, UUID sessionId)
            throws Exception {
        assertEquals(ResultCode.INVALID_REQUEST,
                remote.getPlayerStatistics(sessionId, -1, 1).getResultCode());
        assertEquals(ResultCode.INVALID_REQUEST,
                remote.getPlayerStatistics(sessionId, 0, 0).getResultCode());
        assertEquals(ResultCode.INVALID_REQUEST,
                remote.getPlayerStatistics(sessionId, 0, -1).getResultCode());
        assertEquals(ResultCode.INVALID_REQUEST,
                remote.getPlayerStatistics(sessionId, 0, 51).getResultCode());
    }

    /**
     * Verifies every malformed leaderboard bound returns INVALID_REQUEST for a valid session.
     *
     * @param remote exported server stub
     * @param sessionId valid registered session
     * @throws Exception if RMI invocation fails
     */
    private static void assertInvalidLeaderboardBounds(FleetLinkServerRemote remote, UUID sessionId)
            throws Exception {
        assertEquals(ResultCode.INVALID_REQUEST,
                remote.getLeaderboard(sessionId, 0).getResultCode());
        assertEquals(ResultCode.INVALID_REQUEST,
                remote.getLeaderboard(sessionId, -1).getResultCode());
        assertEquals(ResultCode.INVALID_REQUEST,
                remote.getLeaderboard(sessionId, 101).getResultCode());
    }

    /**
     * Owns one complete isolated persistent service graph for exported statistics calls.
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
        private final Clock clock = Clock.systemUTC();
        private final GameCoordinator coordinator = new GameCoordinator(
                games, callbacks, recorder, clock,
                new ScheduledExecutorDeadlineScheduler(clock), ratings);
        private final MatchmakingService matchmaking = new MatchmakingService(
                games, callbacks, coordinator, ratings,
                (sessionId, playerId) -> sessions.findSession(sessionId)
                        .map(session -> session.getPlayer().getPlayerId().equals(playerId))
                        .orElse(false));
        private final RematchCoordinator rematches = new RematchCoordinator(
                sessions, games, matchmaking, callbacks);
        private final AccountService accounts = new AccountService(
                players, sessions, callbacks, PasswordHasher.forTesting(31, new SecureRandom()),
                clock, UUID::randomUUID, ratings);
        private final StatisticsQueryService statistics = new StatisticsQueryService(
                new StatisticsRepository(persistence.getEntityManagerFactory()), ratings);
        private final FleetLinkServerRemoteImpl server = new FleetLinkServerRemoteImpl(
                sessions, callbacks, matchmaking, coordinator, rematches, accounts, statistics);

        /**
         * Persists one mixed match so the exported statistics response contains nested history.
         *
         * @param registeredPlayerId registered session identity
         */
        private void recordMixedHistory(UUID registeredPlayerId) {
            PlayerEntity registered = players.findById(registeredPlayerId).orElseThrow();
            UUID guestId = UUID.randomUUID();
            Instant completedAt = Instant.parse("2026-08-23T15:00:00Z");
            CompletedParticipantSnapshot winner = new CompletedParticipantSnapshot(
                    registered.getId(), registered.getUsername(), false, registered.getRating(),
                    ParticipantResult.WIN, 6, 3, 1, 6, 0, null);
            CompletedParticipantSnapshot guest = new CompletedParticipantSnapshot(
                    guestId, "Serialized Guest", true, 1000, ParticipantResult.LOSS,
                    5, 2, 0, 5, 0, null);
            CompletedGameSnapshot snapshot = new CompletedGameSnapshot(UUID.randomUUID(),
                    completedAt.minusSeconds(90), completedAt, GameEndReason.RESIGNATION,
                    registered.getId(), List.of(winner, guest));

            assertEquals(CompletionRecordOutcome.RECORDED, store.record(snapshot));
        }

        /**
         * Stops gameplay and completion resources before closing the process-wide persistence owner.
         */
        @Override
        public void close() {
            coordinator.close();
            recorder.close();
            persistence.close();
        }
    }

    /**
     * Provides an exportable callback whose methods intentionally retain no presentation state.
     */
    private static final class RecordingCallback implements FleetLinkClientCallback {
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
