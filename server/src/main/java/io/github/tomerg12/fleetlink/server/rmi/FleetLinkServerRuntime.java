package io.github.tomerg12.fleetlink.server.rmi;

import io.github.tomerg12.fleetlink.server.account.AccountService;
import io.github.tomerg12.fleetlink.server.account.PasswordHasher;
import io.github.tomerg12.fleetlink.server.completion.CompletionRecorder;
import io.github.tomerg12.fleetlink.server.completion.JpaCompletedGameStore;
import io.github.tomerg12.fleetlink.server.deadline.ScheduledExecutorDeadlineScheduler;
import io.github.tomerg12.fleetlink.server.game.GameSessionManager;
import io.github.tomerg12.fleetlink.server.matchmaking.MatchmakingService;
import io.github.tomerg12.fleetlink.server.persistence.FleetLinkPersistence;
import io.github.tomerg12.fleetlink.server.persistence.PlayerRepository;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.server.rematch.RematchCoordinator;
import io.github.tomerg12.fleetlink.server.service.ClientCallbackRegistry;
import io.github.tomerg12.fleetlink.server.service.GameCoordinator;
import io.github.tomerg12.fleetlink.server.session.SessionRegistry;
import io.github.tomerg12.fleetlink.server.statistics.StatisticsQueryService;
import io.github.tomerg12.fleetlink.server.statistics.StatisticsRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns production server service wiring and closes gameplay deadline resources before completion
 * retry infrastructure and persistence.
 */
public final class FleetLinkServerRuntime implements AutoCloseable {
    private final FleetLinkPersistence persistence;
    private final CompletionRecorder completionRecorder;
    private final GameCoordinator gameCoordinator;
    private final FleetLinkServerRemoteImpl remoteAdapter;

    /**
     * Stores a fully wired runtime and its ordered lifecycle resources.
     *
     * @param persistence process-wide persistence owner
     * @param completionRecorder off-lane completion retry owner
     * @param gameCoordinator gameplay sequencing/deadline owner
     * @param remoteAdapter RMI business adapter
     * @throws NullPointerException if any lifecycle dependency is null
     */
    private FleetLinkServerRuntime(FleetLinkPersistence persistence,
                                   CompletionRecorder completionRecorder,
                                   GameCoordinator gameCoordinator,
                                   FleetLinkServerRemoteImpl remoteAdapter) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.completionRecorder = Objects.requireNonNull(completionRecorder, "completionRecorder");
        this.gameCoordinator = Objects.requireNonNull(gameCoordinator, "gameCoordinator");
        this.remoteAdapter = Objects.requireNonNull(remoteAdapter, "remoteAdapter");
    }

    /**
     * Creates the production file-backed account, session, matchmaking, and game runtime.
     *
     * @return open production server runtime
     */
    public static FleetLinkServerRuntime production() {
        FleetLinkPersistence persistence = FleetLinkPersistence.production();
        CompletionRecorder recorder = null;
        GameCoordinator coordinator = null;
        try {
            Clock clock = Clock.systemUTC();
            PlayerRepository players = new PlayerRepository(persistence.getEntityManagerFactory());
            ClientCallbackRegistry callbacks = new ClientCallbackRegistry();
            SessionRegistry sessions = new SessionRegistry(players::existsById);
            GameSessionManager games = new GameSessionManager();
            RegisteredRatingRegistry ratings = new RegisteredRatingRegistry();
            recorder = new CompletionRecorder(
                    new JpaCompletedGameStore(persistence.getEntityManagerFactory()));
            coordinator = new GameCoordinator(games, callbacks, recorder, clock,
                    new ScheduledExecutorDeadlineScheduler(clock), ratings);
            MatchmakingService matchmaking = new MatchmakingService(
                    games, callbacks, coordinator, ratings,
                    (sessionId, playerId) -> sessions.findSession(sessionId)
                            .map(session -> session.getPlayer().getPlayerId().equals(playerId))
                            .orElse(false));
            RematchCoordinator rematches = new RematchCoordinator(
                    sessions, games, matchmaking, callbacks);
            AccountService accounts = new AccountService(players, sessions, callbacks,
                    PasswordHasher.production(), clock, UUID::randomUUID, ratings);
            StatisticsQueryService statistics = new StatisticsQueryService(
                    new StatisticsRepository(persistence.getEntityManagerFactory()), ratings);
            FleetLinkServerRemoteImpl remote = new FleetLinkServerRemoteImpl(
                    sessions, callbacks, matchmaking, coordinator, rematches,
                    accounts, statistics);
            return new FleetLinkServerRuntime(persistence, recorder, coordinator, remote);
        } catch (RuntimeException exception) {
            if (coordinator != null) coordinator.close();
            if (recorder != null) recorder.close();
            persistence.close();
            throw exception;
        }
    }

    /**
     * Returns the unexported remote adapter owned by this runtime.
     *
     * @return RMI business adapter
     */
    public FleetLinkServerRemoteImpl getRemoteAdapter() {
        return remoteAdapter;
    }

    /**
     * Stops gameplay deadline/sequencer resources before retry cleanup and persistence shutdown.
     */
    @Override
    public void close() {
        gameCoordinator.close();
        completionRecorder.close();
        persistence.close();
    }
}
