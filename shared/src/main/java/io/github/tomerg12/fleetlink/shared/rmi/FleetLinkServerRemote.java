package io.github.tomerg12.fleetlink.shared.rmi;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.GameViewResult;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsResult;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.UUID;

/**
 * Defines the small session-based RMI boundary invoked by FleetLink clients.
 * Calls are synchronous from the client perspective. Expected authentication, session, and game
 * rule failures are returned as result DTOs, while {@link RemoteException} represents RMI failure.
 */
public interface FleetLinkServerRemote extends Remote {

    /** Maximum number of personal history rows accepted by one request. */
    int MAX_HISTORY_LIMIT = 50;

    /** Maximum number of committed leaderboard rows accepted by one request. */
    int MAX_LEADERBOARD_LIMIT = 100;

    /**
     * Authenticates a registered player and establishes a server session.
     * A client invokes this synchronous call before session-based operations. Success registers the
     * callback and creates session state; expected credential failures return a failed result. Later
     * match and game changes may produce callbacks on the supplied remote reference.
     *
     * @param username the registered username supplied for authentication
     * @param password the password supplied only for this authentication request
     * @param callback the exported client callback used for server-initiated updates
     * @return session information on success or an explicit expected failure
     * @throws RemoteException if RMI cannot deliver or complete the server call
     */
    SessionResult login(String username, String password, FleetLinkClientCallback callback)
            throws RemoteException;

    /**
     * Registers a player and establishes a server session when registration succeeds.
     * A client invokes this synchronous call before session-based operations. Success may persist
     * the account in a later server implementation, registers the callback, and creates session
     * state. Expected validation or username conflicts return a failed result.
     *
     * @param username the requested registered username
     * @param password the password supplied only for account registration
     * @param callback the exported client callback used for server-initiated updates
     * @return session information on success or an explicit expected failure
     * @throws RemoteException if RMI cannot deliver or complete the server call
     */
    SessionResult register(String username, String password, FleetLinkClientCallback callback)
            throws RemoteException;

    /**
     * Establishes a temporary guest session without creating persistent account state.
     * A client invokes this synchronous call before session-based operations. Success registers the
     * callback and creates temporary session state; expected display-name failures return a result.
     *
     * @param displayName the requested guest display name
     * @param callback the exported client callback used for server-initiated updates
     * @return guest session information on success or an explicit expected failure
     * @throws RemoteException if RMI cannot deliver or complete the server call
     */
    SessionResult connectAsGuest(String displayName, FleetLinkClientCallback callback)
            throws RemoteException;

    /**
     * Ends the caller's server session and removes it from valid future operations.
     * A client invokes this synchronous call. The server may also remove waiting state or end an
     * active game according to later server policy, which may trigger opponent callbacks.
     *
     * @param sessionId the opaque session identifier returned during connection
     * @return success or an explicit session/state failure
     * @throws RemoteException if RMI cannot deliver or complete the server call
     */
    OperationResult logout(UUID sessionId) throws RemoteException;

    /**
     * Requests matchmaking using only the server-owned identity and rating resolved from session.
     * A client invokes this synchronous call. Success changes matchmaking state to waiting or
     * matched; a later or immediate match is communicated through the registered callback.
     *
     * @param sessionId the caller's opaque session identifier
     * @return the resulting matchmaking state or an explicit expected failure
     * @throws RemoteException if RMI cannot deliver or complete the server call
     */
    MatchmakingResult joinMatchmaking(UUID sessionId) throws RemoteException;

    /**
     * Cancels the caller's current matchmaking request when server state permits cancellation.
     * A client invokes this synchronous call. Success removes the session from waiting state; it
     * does not cancel a game that the server has already created.
     *
     * @param sessionId the caller's opaque session identifier
     * @return success or an explicit session/matchmaking failure
     * @throws RemoteException if RMI cannot deliver or complete the server call
     */
    OperationResult cancelMatchmaking(UUID sessionId) throws RemoteException;

    /**
     * Submits the caller's complete requested fleet as one authoritative validation boundary.
     * A client invokes this synchronous call after local arrangement. The server validates every
     * placement before changing game state, so rejection must not partially commit the fleet. An
     * accepted fleet may cause game-state callbacks when both players become ready.
     *
     * @param sessionId the caller's opaque session identifier
     * @param placements the complete fleet requested for the caller's active game
     * @return acceptance with a safe snapshot or an explicit rejection result
     * @throws RemoteException if RMI cannot deliver or complete the server call
     */
    FleetSubmissionResult submitFleet(UUID sessionId, List<ShipPlacement> placements)
            throws RemoteException;

    /**
     * Requests one authoritative shot at a validated board coordinate.
     * A client invokes this synchronous call without supplying turn or game state. The server
     * resolves the active game from session, validates the complete action, and commits at most one
     * shot and turn transition. Accepted state changes may be followed by callbacks to both players.
     *
     * @param sessionId the caller's opaque session identifier
     * @param coordinate the zero-based target coordinate
     * @return the accepted shot outcome and snapshot or an explicit rule rejection
     * @throws RemoteException if RMI cannot deliver or complete the server call
     */
    ShotResult fire(UUID sessionId, Coordinate coordinate) throws RemoteException;

    /**
     * Retrieves the caller's latest safe authoritative game snapshot.
     * A client invokes this synchronous call for initial or recovery loading. It does not change
     * game state or replace callback delivery; missing sessions or games return explicit failures.
     *
     * @param sessionId the caller's opaque session identifier
     * @return the current player-specific snapshot or an explicit lookup failure
     * @throws RemoteException if RMI cannot deliver or complete the server call
     */
    GameViewResult getCurrentGame(UUID sessionId) throws RemoteException;

    /**
     * Retrieves committed personal aggregates and one history page for a registered caller.
     * A client invokes this synchronous read after establishing a session. The server resolves the
     * player from that session, reads current rating from process-live state, and queries committed
     * aggregates and history without waiting for asynchronous completion persistence. This read
     * changes no server state and produces no callback.
     *
     * @param sessionId the caller's opaque session identifier
     * @param historyOffset nonnegative zero-based history offset
     * @param historyLimit requested history rows from 1 through {@value #MAX_HISTORY_LIMIT}
     * @return statistics on success or an explicit session, request, or guest failure
     * @throws RemoteException if RMI cannot deliver or complete the server call
     */
    PlayerStatisticsResult getPlayerStatistics(UUID sessionId, int historyOffset,
                                               int historyLimit) throws RemoteException;

    /**
     * Retrieves the public committed registered-player leaderboard for any valid session.
     * A client invokes this synchronous read with no client-supplied identity or rating authority.
     * The database performs ordering, limiting, and bounded aggregation. This read changes no
     * server state and produces no callback.
     *
     * @param sessionId the caller's opaque session identifier
     * @param limit requested leaderboard rows from 1 through {@value #MAX_LEADERBOARD_LIMIT}
     * @return ordered leaderboard entries or an explicit session or request failure
     * @throws RemoteException if RMI cannot deliver or complete the server call
     */
    LeaderboardResult getLeaderboard(UUID sessionId, int limit) throws RemoteException;

    /**
     * Leaves the caller's active game according to authoritative server policy.
     * A client invokes this synchronous call. Success may end the game as a resignation and may
     * trigger a final game-state callback to the opponent.
     *
     * @param sessionId the caller's opaque session identifier
     * @return success or an explicit session/game-state failure
     * @throws RemoteException if RMI cannot deliver or complete the server call
     */
    OperationResult leaveGame(UUID sessionId) throws RemoteException;

    /**
     * Records positive intent for the current authoritative eligible rematch opportunity resolved
     * from the caller's session at operation processing time. The client invokes this call
     * synchronously and cannot target an arbitrary completed game. An equal duplicate request is
     * idempotent, while an opponent's simultaneous request may complete mutual agreement. No new
     * game is created before both players agree. Rematch and new-game callbacks may arrive before
     * this call returns because callback delivery and the synchronous response are independent.
     *
     * @param sessionId opaque session that supplies authoritative caller identity
     * @return success or an explicit rematch/session business failure
     * @throws RemoteException only when RMI transport cannot deliver or complete the server call
     */
    OperationResult requestRematch(UUID sessionId) throws RemoteException;

    /**
     * Responds to the current authoritative pending opportunity resolved from the caller's session
     * at operation processing time. The client invokes this call synchronously and cannot target an
     * arbitrary game. True records positive agreement. False expires an unrequested opportunity,
     * declines an opponent's sole request, or withdraws the caller's own sole request according to
     * authoritative server state. Equal duplicates may be idempotent. False after creation claim
     * cannot cancel or roll back the new game. Expected state failures use the returned result,
     * while rematch callbacks and
     * {@code onMatchFound} for a new game may arrive before this call returns.
     *
     * @param sessionId opaque session that supplies authoritative caller identity
     * @param accept true for positive agreement, false for departure, decline, or withdrawal
     * @return success or an explicit rematch/session business failure
     * @throws RemoteException only when RMI transport cannot deliver or complete the server call
     */
    OperationResult respondToRematch(UUID sessionId, boolean accept) throws RemoteException;
}
