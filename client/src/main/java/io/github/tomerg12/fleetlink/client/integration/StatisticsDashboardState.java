package io.github.tomerg12.fleetlink.client.integration;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;

/**
 * Stores the independently reconciled personal-statistics and leaderboard slices for one active
 * statistics dashboard. This read state is deliberately separate from the gameplay lifecycle.
 */
public final class StatisticsDashboardState {

    /** Describes the current request state of one independently loaded dashboard slice. */
    public enum LoadStatus {
        /** No request has started for this dashboard activation. */
        IDLE,
        /** The newest request is running on the remote executor. */
        LOADING,
        /** The newest request returned authoritative data. */
        SUCCESS,
        /** The server rejected the newest request with a protocol result code. */
        EXPECTED_FAILURE,
        /** Transport or registry work failed for the newest request. */
        TRANSPORT_FAILURE
    }

    private final UUID sessionId;
    private final LoadStatus personalStatus;
    private final PlayerStatisticsView personalStatistics;
    private final ResultCode personalResultCode;
    private final String personalMessage;
    private final LoadStatus leaderboardStatus;
    private final List<LeaderboardEntryView> leaderboardEntries;
    private final ResultCode leaderboardResultCode;
    private final String leaderboardMessage;
    private final long revision;

    /**
     * Creates one immutable dashboard snapshot used only by the named factory and merge methods.
     *
     * @param sessionId session that owns this dashboard activation
     * @param personalStatus personal slice load status
     * @param personalStatistics personal payload when available
     * @param personalResultCode personal expected result code when available
     * @param personalMessage personal status or failure text
     * @param leaderboardStatus leaderboard slice load status
     * @param leaderboardEntries ordered leaderboard payload
     * @param leaderboardResultCode leaderboard expected result code when available
     * @param leaderboardMessage leaderboard status or failure text
     * @param revision monotonically increasing dashboard revision
     */
    private StatisticsDashboardState(UUID sessionId, LoadStatus personalStatus,
                                     PlayerStatisticsView personalStatistics,
                                     ResultCode personalResultCode, String personalMessage,
                                     LoadStatus leaderboardStatus,
                                     List<LeaderboardEntryView> leaderboardEntries,
                                     ResultCode leaderboardResultCode,
                                     String leaderboardMessage, long revision) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.personalStatus = Objects.requireNonNull(personalStatus, "personalStatus");
        this.personalStatistics = personalStatistics;
        this.personalResultCode = personalResultCode;
        this.personalMessage = Objects.requireNonNull(personalMessage, "personalMessage");
        this.leaderboardStatus = Objects.requireNonNull(leaderboardStatus, "leaderboardStatus");
        this.leaderboardEntries = List.copyOf(
                Objects.requireNonNull(leaderboardEntries, "leaderboardEntries"));
        this.leaderboardResultCode = leaderboardResultCode;
        this.leaderboardMessage = Objects.requireNonNull(leaderboardMessage, "leaderboardMessage");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        this.revision = revision;
    }

    /**
     * Creates fresh read state for one statistics-screen activation.
     *
     * @param sessionId established session identifier
     * @return empty dashboard state bound to that session
     */
    static StatisticsDashboardState initial(UUID sessionId) {
        return new StatisticsDashboardState(sessionId, LoadStatus.IDLE, null, null, "",
                LoadStatus.IDLE, List.of(), null, "", 0);
    }

    /**
     * Marks the personal slice as loading while retaining already displayed data.
     *
     * @return merged loading state
     */
    StatisticsDashboardState withPersonalLoading() {
        return personal(LoadStatus.LOADING, personalStatistics, null, "Loading statistics...");
    }

    /**
     * Merges one successful personal snapshot without altering the leaderboard slice.
     *
     * @param statistics authoritative personal snapshot
     * @return merged dashboard state
     */
    StatisticsDashboardState withPersonalSuccess(PlayerStatisticsView statistics) {
        return personal(LoadStatus.SUCCESS, Objects.requireNonNull(statistics, "statistics"),
                ResultCode.SUCCESS, "");
    }

    /**
     * Merges one expected personal failure without altering the leaderboard slice.
     *
     * @param resultCode authoritative non-success result code
     * @param message player-facing server explanation
     * @return merged dashboard state
     */
    StatisticsDashboardState withPersonalExpectedFailure(ResultCode resultCode, String message) {
        return personal(LoadStatus.EXPECTED_FAILURE, null,
                Objects.requireNonNull(resultCode, "resultCode"), requireMessage(message));
    }

    /**
     * Merges one personal transport failure while preserving previously displayed data.
     *
     * @param message player-facing transport explanation
     * @return merged dashboard state
     */
    StatisticsDashboardState withPersonalTransportFailure(String message) {
        return personal(LoadStatus.TRANSPORT_FAILURE, personalStatistics, null,
                requireMessage(message));
    }

    /**
     * Marks the leaderboard slice as loading while retaining already displayed rows.
     *
     * @return merged loading state
     */
    StatisticsDashboardState withLeaderboardLoading() {
        return leaderboard(LoadStatus.LOADING, leaderboardEntries, null,
                "Loading leaderboard...");
    }

    /**
     * Merges successful server-ordered leaderboard rows without altering personal state.
     *
     * @param entries authoritative ordered leaderboard rows
     * @return merged dashboard state
     */
    StatisticsDashboardState withLeaderboardSuccess(List<LeaderboardEntryView> entries) {
        return leaderboard(LoadStatus.SUCCESS, List.copyOf(entries), ResultCode.SUCCESS, "");
    }

    /**
     * Merges one expected leaderboard failure without altering personal state.
     *
     * @param resultCode authoritative non-success result code
     * @param message player-facing server explanation
     * @return merged dashboard state
     */
    StatisticsDashboardState withLeaderboardExpectedFailure(ResultCode resultCode,
                                                              String message) {
        return leaderboard(LoadStatus.EXPECTED_FAILURE, List.of(),
                Objects.requireNonNull(resultCode, "resultCode"), requireMessage(message));
    }

    /**
     * Merges one leaderboard transport failure while preserving previously displayed rows.
     *
     * @param message player-facing transport explanation
     * @return merged dashboard state
     */
    StatisticsDashboardState withLeaderboardTransportFailure(String message) {
        return leaderboard(LoadStatus.TRANSPORT_FAILURE, leaderboardEntries, null,
                requireMessage(message));
    }

    /**
     * Rebuilds only the personal slice and increments the dashboard revision.
     *
     * @param status replacement personal status
     * @param statistics replacement personal payload
     * @param resultCode replacement result code
     * @param message replacement message
     * @return merged dashboard state
     */
    private StatisticsDashboardState personal(LoadStatus status,
                                               PlayerStatisticsView statistics,
                                               ResultCode resultCode, String message) {
        return new StatisticsDashboardState(sessionId, status, statistics, resultCode, message,
                leaderboardStatus, leaderboardEntries, leaderboardResultCode,
                leaderboardMessage, revision + 1);
    }

    /**
     * Rebuilds only the leaderboard slice and increments the dashboard revision.
     *
     * @param status replacement leaderboard status
     * @param entries replacement server-ordered rows
     * @param resultCode replacement result code
     * @param message replacement message
     * @return merged dashboard state
     */
    private StatisticsDashboardState leaderboard(LoadStatus status,
                                                  List<LeaderboardEntryView> entries,
                                                  ResultCode resultCode, String message) {
        return new StatisticsDashboardState(sessionId, personalStatus, personalStatistics,
                personalResultCode, personalMessage, status, entries, resultCode, message,
                revision + 1);
    }

    /**
     * Rejects blank failure text before it reaches presentation state.
     *
     * @param message proposed failure text
     * @return validated text
     */
    private static String requireMessage(String message) {
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message;
    }

    /**
     * Returns the session that owns this dashboard activation.
     *
     * @return established session identifier
     */
    public UUID getSessionId() {
        return sessionId;
    }

    /**
     * Returns the personal slice request status.
     *
     * @return personal load status
     */
    public LoadStatus getPersonalStatus() {
        return personalStatus;
    }

    /**
     * Returns personal statistics when current or retained data is available.
     *
     * @return personal snapshot, or null before success or after expected rejection
     */
    public PlayerStatisticsView getPersonalStatistics() {
        return personalStatistics;
    }

    /**
     * Returns the expected personal result code when one is available.
     *
     * @return protocol result code, or null for idle, loading, or transport failure
     */
    public ResultCode getPersonalResultCode() {
        return personalResultCode;
    }

    /**
     * Returns personal loading or failure text.
     *
     * @return personal status message
     */
    public String getPersonalMessage() {
        return personalMessage;
    }

    /**
     * Returns the leaderboard slice request status.
     *
     * @return leaderboard load status
     */
    public LoadStatus getLeaderboardStatus() {
        return leaderboardStatus;
    }

    /**
     * Returns immutable rows in the exact server-provided order.
     *
     * @return authoritative leaderboard rows
     */
    public List<LeaderboardEntryView> getLeaderboardEntries() {
        return leaderboardEntries;
    }

    /**
     * Returns the expected leaderboard result code when one is available.
     *
     * @return protocol result code, or null for idle, loading, or transport failure
     */
    public ResultCode getLeaderboardResultCode() {
        return leaderboardResultCode;
    }

    /**
     * Returns leaderboard loading or failure text.
     *
     * @return leaderboard status message
     */
    public String getLeaderboardMessage() {
        return leaderboardMessage;
    }

    /**
     * Returns the monotonic revision for accepted dashboard state changes.
     *
     * @return dashboard revision
     */
    public long getRevision() {
        return revision;
    }
}
