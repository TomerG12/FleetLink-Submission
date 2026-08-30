package io.github.tomerg12.fleetlink.server.rmi;

import io.github.tomerg12.fleetlink.server.account.AccountService;
import io.github.tomerg12.fleetlink.server.matchmaking.MatchmakingService;
import io.github.tomerg12.fleetlink.server.rematch.RematchCoordinator;
import io.github.tomerg12.fleetlink.server.service.ClientCallbackRegistry;
import io.github.tomerg12.fleetlink.server.service.GameCoordinator;
import io.github.tomerg12.fleetlink.server.session.SessionRegistry;
import io.github.tomerg12.fleetlink.server.session.SessionRegistry.Termination;
import io.github.tomerg12.fleetlink.server.statistics.StatisticsQueryService;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.GameViewResult;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsResult;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionInfo;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkServerRemote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapts the public RMI contract to registered and guest sessions plus the authoritative game core.
 * Registered authentication is available when the production account service is supplied. The
 * account-free constructor remains available for focused core fixtures.
 */
public final class FleetLinkServerRemoteImpl implements FleetLinkServerRemote {

    private final SessionRegistry sessionRegistry;
    private final ClientCallbackRegistry callbackRegistry;
    private final MatchmakingService matchmakingService;
    private final RematchCoordinator rematchCoordinator;
    private final GameCoordinator gameCoordinator;
    private final AccountService accountService;
    private final StatisticsQueryService statisticsQueryService;

    /**
     * Creates the remote adapter over the current in-memory server services.
     *
     * @param sessionRegistry the temporary session registry
     * @param callbackRegistry the callback registry for connected players
     * @param matchmakingService the rating-aware matchmaking service
     * @param gameCoordinator the authoritative game action coordinator
     * @param rematchCoordinator process-memory rematch coordinator
     */
    public FleetLinkServerRemoteImpl(SessionRegistry sessionRegistry,
                                     ClientCallbackRegistry callbackRegistry,
                                     MatchmakingService matchmakingService,
                                     GameCoordinator gameCoordinator,
                                     RematchCoordinator rematchCoordinator) {
        this(sessionRegistry, callbackRegistry, matchmakingService, gameCoordinator,
                rematchCoordinator, null, null);
    }

    /**
     * Creates the remote adapter with registered account authentication enabled.
     *
     * @param sessionRegistry temporary session and identity reservation registry
     * @param callbackRegistry callback registry for connected players
     * @param matchmakingService rating-aware matchmaking service
     * @param gameCoordinator authoritative game action coordinator
     * @param rematchCoordinator process-memory rematch coordinator
     * @param accountService registered account and authentication boundary
     */
    public FleetLinkServerRemoteImpl(SessionRegistry sessionRegistry,
                                     ClientCallbackRegistry callbackRegistry,
                                     MatchmakingService matchmakingService,
                                     GameCoordinator gameCoordinator,
                                     RematchCoordinator rematchCoordinator,
                                     AccountService accountService) {
        this(sessionRegistry, callbackRegistry, matchmakingService, gameCoordinator,
                rematchCoordinator, accountService, null);
    }

    /**
     * Creates the complete production adapter with registered accounts and statistics enabled.
     *
     * @param sessionRegistry temporary session and identity reservation registry
     * @param callbackRegistry callback registry for connected players
     * @param matchmakingService rating-aware matchmaking service
     * @param gameCoordinator authoritative game action coordinator
     * @param rematchCoordinator process-memory rematch coordinator
     * @param accountService registered account and authentication boundary
     * @param statisticsQueryService committed statistics and live-rating query boundary
     */
    public FleetLinkServerRemoteImpl(SessionRegistry sessionRegistry,
                                     ClientCallbackRegistry callbackRegistry,
                                     MatchmakingService matchmakingService,
                                     GameCoordinator gameCoordinator,
                                     RematchCoordinator rematchCoordinator,
                                     AccountService accountService,
                                     StatisticsQueryService statisticsQueryService) {
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry");
        this.callbackRegistry = Objects.requireNonNull(callbackRegistry, "callbackRegistry");
        this.matchmakingService = Objects.requireNonNull(matchmakingService, "matchmakingService");
        this.gameCoordinator = Objects.requireNonNull(gameCoordinator, "gameCoordinator");
        this.rematchCoordinator = Objects.requireNonNull(rematchCoordinator, "rematchCoordinator");
        this.accountService = accountService;
        this.statisticsQueryService = statisticsQueryService;
    }

    /**
     * Authenticates a registered account and creates one temporary server session.
     *
     * @param username the supplied username
     * @param password the supplied password
     * @param callback the supplied callback reference
     * @return authenticated session or a contract-safe credential, request, or availability failure
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public SessionResult login(String username, String password, FleetLinkClientCallback callback)
            throws RemoteException {
        if (accountService == null) {
            return SessionResult.failure(ResultCode.INVALID_REQUEST,
                    "Registered login is unavailable without server persistence");
        }
        return accountService.login(username, password, callback);
    }

    /**
     * Persists and connects a new registered account through the configured account service.
     *
     * @param username the requested username
     * @param password the requested password
     * @param callback the supplied callback reference
     * @return registered session or a contract-safe validation or username-availability failure
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public SessionResult register(String username, String password, FleetLinkClientCallback callback)
            throws RemoteException {
        if (accountService == null) {
            return SessionResult.failure(ResultCode.INVALID_REQUEST,
                    "Registration is unavailable without server persistence");
        }
        return accountService.register(username, password, callback);
    }

    /**
     * Creates a temporary in-memory guest session and registers its exported callback.
     * No persistent player or credential state is created.
     *
     * @param displayName the requested guest display name
     * @param callback the exported callback reference
     * @return the new guest session or an explicit invalid-request failure
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public SessionResult connectAsGuest(String displayName, FleetLinkClientCallback callback)
            throws RemoteException {
        if (callback == null) {
            return SessionResult.failure(ResultCode.INVALID_REQUEST,
                    "Guest connection requires a callback");
        }
        try {
            SessionInfo session = sessionRegistry.createGuest(displayName);
            callbackRegistry.register(session.getPlayer(), callback);
            return SessionResult.success(session);
        } catch (NullPointerException | IllegalArgumentException exception) {
            return SessionResult.failure(ResultCode.INVALID_REQUEST,
                    "Guest display name must not be blank");
        }
    }

    /**
     * Invalidates one session, removes waiting state, and ends an unfinished game as disconnect.
     * Callback cleanup is performed even when the session has no active game.
     *
     * @param sessionId the opaque session identifier
     * @return success or INVALID_SESSION
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public OperationResult logout(UUID sessionId) throws RemoteException {
        Optional<Termination> started = sessionRegistry.beginTermination(sessionId);
        if (started.isEmpty()) {
            return invalidSessionOperation();
        }
        Termination termination = started.get();
        UUID playerId = termination.getSession().getPlayer().getPlayerId();
        FleetLinkClientCallback endingCallback = callbackRegistry.find(playerId).orElse(null);
        try {
            matchmakingService.terminateSession(sessionId, playerId);
            rematchCoordinator.terminateSession(sessionId, playerId);
            gameCoordinator.disconnect(playerId);
            rematchCoordinator.abandonCompletedGame(sessionId, playerId);
            return OperationResult.success();
        } finally {
            callbackRegistry.unregister(playerId, endingCallback);
            sessionRegistry.completeTermination(termination);
        }
    }

    /**
     * Resolves the caller from session state and joins authoritative matchmaking.
     *
     * @param sessionId the caller's session identifier
     * @return WAITING, MATCHED, or an explicit session/state failure
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public MatchmakingResult joinMatchmaking(UUID sessionId) throws RemoteException {
        PlayerView player = resolvePlayer(sessionId).orElse(null);
        if (player == null) {
            return MatchmakingResult.failure(ResultCode.INVALID_SESSION, "Invalid session");
        }
        MatchmakingResult result = matchmakingService.join(sessionId, player);
        if (result.isSuccess()) {
            rematchCoordinator.expireForOrdinaryMatchmaking(player.getPlayerId());
        }
        return result;
    }

    /**
     * Resolves the caller and removes an existing waiting request.
     *
     * @param sessionId the caller's session identifier
     * @return success, INVALID_SESSION, or NOT_WAITING
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public OperationResult cancelMatchmaking(UUID sessionId) throws RemoteException {
        PlayerView player = resolvePlayer(sessionId).orElse(null);
        if (player == null) {
            return invalidSessionOperation();
        }
        return matchmakingService.cancel(player.getPlayerId());
    }

    /**
     * Resolves the caller and atomically submits the complete fleet to the indexed game.
     *
     * @param sessionId the caller's session identifier
     * @param placements the complete requested fleet
     * @return the authoritative fleet result
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public FleetSubmissionResult submitFleet(UUID sessionId, List<ShipPlacement> placements)
            throws RemoteException {
        PlayerView player = resolvePlayer(sessionId).orElse(null);
        if (player == null) {
            return FleetSubmissionResult.rejected(
                    ResultCode.INVALID_SESSION, "Invalid session", null);
        }
        return gameCoordinator.submitFleet(player.getPlayerId(), placements);
    }

    /**
     * Resolves the caller and delegates one complete authoritative shot operation.
     *
     * @param sessionId the caller's session identifier
     * @param coordinate the target coordinate
     * @return the authoritative shot result
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public ShotResult fire(UUID sessionId, Coordinate coordinate) throws RemoteException {
        PlayerView player = resolvePlayer(sessionId).orElse(null);
        if (player == null) {
            return ShotResult.rejected(ResultCode.INVALID_SESSION, "Invalid session", null);
        }
        return gameCoordinator.fire(player.getPlayerId(), coordinate);
    }

    /**
     * Resolves the caller and returns the latest safe player-specific game snapshot.
     *
     * @param sessionId the caller's session identifier
     * @return the current game result or INVALID_SESSION
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public GameViewResult getCurrentGame(UUID sessionId) throws RemoteException {
        PlayerView player = resolvePlayer(sessionId).orElse(null);
        if (player == null) {
            return GameViewResult.failure(ResultCode.INVALID_SESSION, "Invalid session");
        }
        return gameCoordinator.getCurrentGame(player.getPlayerId());
    }

    /**
     * Resolves session identity before bounds and guest policy, then returns live rating with
     * committed personal statistics without waiting for completion persistence.
     *
     * @param sessionId the caller's opaque session identifier
     * @param historyOffset nonnegative database history offset
     * @param historyLimit requested rows from 1 through the protocol maximum
     * @return statistics or an explicit session, request, guest, or availability failure
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public PlayerStatisticsResult getPlayerStatistics(UUID sessionId, int historyOffset,
                                                      int historyLimit) throws RemoteException {
        PlayerView player = resolvePlayer(sessionId).orElse(null);
        if (player == null) {
            return PlayerStatisticsResult.failure(ResultCode.INVALID_SESSION, "Invalid session");
        }
        if (historyOffset < 0 || historyLimit < 1
                || historyLimit > FleetLinkServerRemote.MAX_HISTORY_LIMIT) {
            return PlayerStatisticsResult.failure(ResultCode.INVALID_REQUEST,
                    "History offset or limit is invalid");
        }
        if (player.isGuest()) {
            return PlayerStatisticsResult.failure(ResultCode.REGISTERED_ACCOUNT_REQUIRED,
                    "Personal statistics require a registered account");
        }
        if (statisticsQueryService == null) {
            return PlayerStatisticsResult.failure(ResultCode.INVALID_REQUEST,
                    "Statistics are unavailable without server persistence");
        }
        return PlayerStatisticsResult.success(statisticsQueryService.getPlayerStatistics(
                player.getPlayerId(), historyOffset, historyLimit));
    }

    /**
     * Resolves session identity before validating the bounded public leaderboard request.
     * Both guest and registered sessions may read the committed result.
     *
     * @param sessionId the caller's opaque session identifier
     * @param limit requested rows from 1 through the protocol maximum
     * @return leaderboard or an explicit session, request, or availability failure
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public LeaderboardResult getLeaderboard(UUID sessionId, int limit) throws RemoteException {
        if (resolvePlayer(sessionId).isEmpty()) {
            return LeaderboardResult.failure(ResultCode.INVALID_SESSION, "Invalid session");
        }
        if (limit < 1 || limit > FleetLinkServerRemote.MAX_LEADERBOARD_LIMIT) {
            return LeaderboardResult.failure(ResultCode.INVALID_REQUEST,
                    "Leaderboard limit is invalid");
        }
        if (statisticsQueryService == null) {
            return LeaderboardResult.failure(ResultCode.INVALID_REQUEST,
                    "Leaderboard is unavailable without server persistence");
        }
        return LeaderboardResult.success(statisticsQueryService.getLeaderboard(limit));
    }

    /**
     * Resolves the caller and ends an unfinished game as a voluntary resignation.
     *
     * @param sessionId the caller's session identifier
     * @return success or an explicit session/game failure
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public OperationResult leaveGame(UUID sessionId) throws RemoteException {
        PlayerView player = resolvePlayer(sessionId).orElse(null);
        if (player == null) {
            return invalidSessionOperation();
        }
        OperationResult result = gameCoordinator.leaveGame(player.getPlayerId());
        if (result.isSuccess()) {
            rematchCoordinator.abandonCompletedGame(sessionId, player.getPlayerId());
        }
        return result;
    }

    /**
     * Records positive intent for the caller's current authoritative rematch opportunity.
     *
     * @param sessionId the caller's session identifier
     * @return authoritative rematch result
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public OperationResult requestRematch(UUID sessionId) throws RemoteException {
        return rematchCoordinator.requestRematch(sessionId);
    }

    /**
     * Applies positive agreement or records departure, decline, or requester withdrawal against
     * the current completed-game opportunity.
     *
     * @param sessionId the caller's session identifier
     * @param accept true for agreement, false for departure, decline, or withdrawal
     * @return authoritative rematch result
     * @throws RemoteException if RMI cannot deliver or complete the call
     */
    @Override
    public OperationResult respondToRematch(UUID sessionId, boolean accept) throws RemoteException {
        return rematchCoordinator.respondToRematch(sessionId, accept);
    }

    /**
     * Resolves the server-owned player associated with a session identifier.
     *
     * @param sessionId the opaque session identifier
     * @return the player when the session is valid
     */
    private Optional<PlayerView> resolvePlayer(UUID sessionId) {
        return sessionRegistry.resolvePlayer(sessionId);
    }

    /**
     * Creates the standard payload-free invalid-session result used by remote operations.
     *
     * @return an INVALID_SESSION operation result
     */
    private static OperationResult invalidSessionOperation() {
        return OperationResult.failure(ResultCode.INVALID_SESSION, "Invalid session");
    }
}
