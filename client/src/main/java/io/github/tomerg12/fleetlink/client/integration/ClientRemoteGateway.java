package io.github.tomerg12.fleetlink.client.integration;

import java.rmi.RemoteException;
import java.util.List;
import java.util.UUID;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardResult;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsResult;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;

/**
 * Separates synchronous remote operations from JavaFX screens and presentation state.
 * Every operation can block and must be invoked only by the client remote executor.
 */
public interface ClientRemoteGateway extends AutoCloseable {

    /**
     * Authenticates a registered account through the exported client callback.
     *
     * @param username submitted account username
     * @param password exact submitted password sequence
     * @return authoritative session result
     * @throws RemoteException if the transport cannot complete the call
     */
    SessionResult login(String username, String password) throws RemoteException;

    /**
     * Creates and connects a registered account through the exported client callback.
     *
     * @param username submitted account username
     * @param password exact submitted password sequence
     * @return authoritative session result
     * @throws RemoteException if the transport cannot complete the call
     */
    SessionResult register(String username, String password) throws RemoteException;

    /**
     * Establishes a temporary guest session through the exported client callback.
     *
     * @param displayName requested guest display name
     * @return authoritative session result
     * @throws RemoteException if the transport cannot complete the call
     */
    SessionResult connectAsGuest(String displayName) throws RemoteException;

    /**
     * Joins server-authoritative matchmaking for an established session.
     *
     * @param sessionId established session identifier
     * @return authoritative matchmaking result
     * @throws RemoteException if the transport cannot complete the call
     */
    MatchmakingResult joinMatchmaking(UUID sessionId) throws RemoteException;

    /**
     * Cancels authoritative matchmaking when the server still permits cancellation.
     *
     * @param sessionId established session identifier
     * @return authoritative cancellation result
     * @throws RemoteException if the transport cannot complete the call
     */
    OperationResult cancelMatchmaking(UUID sessionId) throws RemoteException;

    /**
     * Submits the complete fleet as one server-authoritative operation.
     *
     * @param sessionId established session identifier
     * @param placements complete immutable fleet request
     * @return authoritative fleet submission result
     * @throws RemoteException if the transport cannot complete the call
     */
    FleetSubmissionResult submitFleet(UUID sessionId, List<ShipPlacement> placements)
            throws RemoteException;

    /**
     * Fires one server-authoritative shot without supplying local turn or outcome data.
     *
     * @param sessionId established session identifier
     * @param coordinate requested target coordinate
     * @return authoritative shot result and current safe snapshot when available
     * @throws RemoteException if the transport cannot complete the call
     */
    ShotResult fire(UUID sessionId, Coordinate coordinate) throws RemoteException;

    /**
     * Reads committed personal aggregates and one database-paginated history page.
     *
     * @param sessionId established session identifier
     * @param historyOffset zero-based history offset
     * @param historyLimit bounded history page size
     * @return authoritative personal statistics result
     * @throws RemoteException if the transport cannot complete the call
     */
    PlayerStatisticsResult getPlayerStatistics(UUID sessionId, int historyOffset,
                                               int historyLimit) throws RemoteException;

    /**
     * Reads the server-ordered registered-player leaderboard.
     *
     * @param sessionId established session identifier, including a guest session
     * @param limit bounded maximum row count
     * @return authoritative leaderboard result
     * @throws RemoteException if the transport cannot complete the call
     */
    LeaderboardResult getLeaderboard(UUID sessionId, int limit) throws RemoteException;

    /**
     * Records positive rematch intent for the server-resolved current opportunity.
     *
     * @param sessionId established session identifier
     * @return authoritative operation result
     * @throws RemoteException if the transport cannot complete the call
     */
    OperationResult requestRematch(UUID sessionId) throws RemoteException;

    /**
     * Accepts, declines, or withdraws the server-resolved current rematch opportunity.
     *
     * @param sessionId established session identifier
     * @param accept true for acceptance, false for decline or withdrawal
     * @return authoritative operation result
     * @throws RemoteException if the transport cannot complete the call
     */
    OperationResult respondToRematch(UUID sessionId, boolean accept) throws RemoteException;

    /**
     * Leaves the caller's active game through the authoritative server policy.
     *
     * @param sessionId established session identifier
     * @return authoritative leave result
     * @throws RemoteException if the transport cannot complete the call
     */
    OperationResult leaveGame(UUID sessionId) throws RemoteException;

    /**
     * Invalidates the established server session before callback cleanup.
     *
     * @param sessionId established session identifier
     * @return authoritative logout result
     * @throws RemoteException if the transport cannot complete the call
     */
    OperationResult logout(UUID sessionId) throws RemoteException;

    /**
     * Releases the exported callback and local transport resources without performing logout.
     */
    @Override
    void close();
}
