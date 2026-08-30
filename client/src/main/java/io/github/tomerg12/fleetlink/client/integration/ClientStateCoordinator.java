package io.github.tomerg12.fleetlink.client.integration;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingState;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardResult;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentCellView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsResult;
import io.github.tomerg12.fleetlink.shared.protocol.RematchState;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.protocol.ShotOutcome;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;

/**
 * Reconciles synchronous operation results and callbacks before presentation work is scheduled.
 * Ordinary game operations use generation plus callback-epoch freshness. Rematch mutations use a
 * dedicated exact-lifecycle token so a newer authoritative status is preserved while a still-current
 * local operation can retire instead of remaining permanently busy.
 */
public final class ClientStateCoordinator {

    /** Identifies the independently generated statistics request streams. */
    private enum StatisticsOperationKind {
        /** Personal aggregates and one database-paginated history page. */
        PERSONAL,
        /** Server-ordered registered-player leaderboard rows. */
        LEADERBOARD
    }

    /**
     * Binds one statistics request to its stream generation, screen activation, and session.
     */
    static final class StatisticsOperationToken {
        private final StatisticsOperationKind kind;
        private final long generation;
        private final long activation;
        private final UUID sessionId;

        /**
         * Creates one opaque token used to reject stale dashboard completions.
         *
         * @param kind independent dashboard request stream
         * @param generation newest generation in that stream
         * @param activation statistics-screen activation that submitted the request
         * @param sessionId session that submitted the request
         */
        private StatisticsOperationToken(StatisticsOperationKind kind, long generation,
                                         long activation, UUID sessionId) {
            this.kind = kind;
            this.generation = generation;
            this.activation = activation;
            this.sessionId = sessionId;
        }
    }

    /**
     * Owns one active statistics presentation listener and invalidates its pending work on close.
     */
    public final class DashboardSubscription implements AutoCloseable {
        private final long activation;
        private boolean closed;

        /**
         * Captures the activation whose listener and operation tokens must be invalidated.
         *
         * @param activation statistics-screen activation identifier
         */
        private DashboardSubscription(long activation) {
            this.activation = activation;
        }

        /**
         * Detaches this screen activation and prevents its queued presentation work from running.
         */
        @Override
        public void close() {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                if (dashboardActivation == activation) {
                    invalidateDashboardLocked();
                }
            }
        }
    }

    /**
     * Identifies independent remote operations whose generations prevent older completions from
     * overwriting newer work.
     */
    private enum OperationKind {
        /** Temporary guest session establishment. */
        SESSION_ESTABLISHMENT,

        /** Joining the matchmaking pool. */
        JOIN_MATCHMAKING,

        /** Cancelling a waiting matchmaking request. */
        CANCEL_MATCHMAKING,

        /** Submitting the complete local fleet. */
        SUBMIT_FLEET,

        /** Firing one authoritative battle shot. */
        FIRE,

        /** Leaving one active game. */
        LEAVE_GAME,

        /** Invalidating the established server session. */
        LOGOUT
    }

    /**
     * Captures the operation generation and callback epoch at request submission time.
     */
    static final class OperationToken {
        private final OperationKind kind;
        private final long generation;
        private final long callbackEpoch;
        private final ClientPhase sourcePhase;

        /**
         * Creates one opaque token for later stale-result reconciliation.
         *
         * @param kind operation category
         * @param generation generation assigned to this operation
         * @param callbackEpoch callback epoch observed before remote work began
         * @param sourcePhase client phase restored after a current transport failure
         */
        private OperationToken(OperationKind kind, long generation, long callbackEpoch,
                               ClientPhase sourcePhase) {
            this.kind = kind;
            this.generation = generation;
            this.callbackEpoch = callbackEpoch;
            this.sourcePhase = sourcePhase;
        }
    }

    /**
     * Binds one mutation in the single rematch stream to exact lifecycle and callback freshness.
     */
    static final class RematchOperationToken {
        private final long generation;
        private final long gameOverActivation;
        private final UUID sessionId;
        private final UUID completedGameId;
        private final long rematchCallbackEpochAtBegin;
        private final RematchClientState.InFlightAction action;

        /**
         * Creates one opaque token before a rematch remote call begins.
         *
         * @param generation active generation in the single rematch stream
         * @param gameOverActivation exact completed-screen activation
         * @param sessionId exact session that owns the activation
         * @param completedGameId exact completed game that owns the negotiation
         * @param rematchCallbackEpochAtBegin authoritative callback epoch at submission
         * @param action local mutation represented by the token
         */
        private RematchOperationToken(long generation, long gameOverActivation, UUID sessionId,
                                      UUID completedGameId, long rematchCallbackEpochAtBegin,
                                      RematchClientState.InFlightAction action) {
            this.generation = generation;
            this.gameOverActivation = gameOverActivation;
            this.sessionId = sessionId;
            this.completedGameId = completedGameId;
            this.rematchCallbackEpochAtBegin = rematchCallbackEpochAtBegin;
            this.action = action;
        }

        /**
         * Returns the exact session that must be supplied to the remote operation.
         *
         * @return captured session identifier
         */
        UUID getSessionId() {
            return sessionId;
        }

        /**
         * Returns the callback epoch captured for deterministic freshness tests.
         *
         * @return rematch callback epoch at operation start
         */
        long getRematchCallbackEpochAtBegin() {
            return rematchCallbackEpochAtBegin;
        }
    }

    /** Identifies the remote cleanup chosen by atomic local Lobby return. */
    enum ReturnToLobbyAction {
        /** Send one best-effort false response after committing local Lobby. */
        SEND_FALSE
    }

    /**
     * Captures the immutable cleanup decision made with the local Lobby lifecycle transition.
     */
    static final class ReturnToLobbyPlan {
        private final ReturnToLobbyAction action;
        private final UUID sessionId;
        private final ClientState lobbyState;

        /**
         * Creates one committed Lobby plan.
         *
         * @param action selected cleanup behavior
         * @param sessionId exact prior session used only by SEND_FALSE
         * @param lobbyState locally committed Lobby state
         */
        private ReturnToLobbyPlan(ReturnToLobbyAction action, UUID sessionId,
                                  ClientState lobbyState) {
            this.action = Objects.requireNonNull(action, "action");
            this.sessionId = sessionId;
            this.lobbyState = Objects.requireNonNull(lobbyState, "lobbyState");
        }

        /**
         * Returns the selected cleanup behavior.
         *
         * @return immutable action plan
         */
        ReturnToLobbyAction getAction() {
            return action;
        }

        /**
         * Returns the exact session captured for best-effort false response.
         *
         * @return session identifier, or null unless SEND_FALSE was selected
         */
        UUID getSessionId() {
            return sessionId;
        }

        /**
         * Returns the Lobby state committed before any best-effort remote work.
         *
         * @return committed local Lobby state
         */
        ClientState getLobbyState() {
            return lobbyState;
        }
    }

    /**
     * Reports the state and lifecycle effect produced by one logout result reconciliation.
     */
    static final class LogoutReconciliation {
        private final ClientState state;
        private final boolean terminal;

        /**
         * Captures the state observed when logout reconciliation finishes.
         *
         * @param state newest reconciled client state
         * @param terminal whether the current logout generation ended the local session
         */
        private LogoutReconciliation(ClientState state, boolean terminal) {
            this.state = state;
            this.terminal = terminal;
        }

        /**
         * Returns the state produced or preserved by logout reconciliation.
         *
         * @return newest reconciled client state
         */
        ClientState getState() {
            return state;
        }

        /**
         * Indicates whether this result authoritatively ended the local session.
         *
         * @return true when gateway cleanup may follow terminal reconciliation
         */
        boolean isTerminal() {
            return terminal;
        }
    }

    private final Object lock = new Object();
    private final ClientUiDispatcher uiDispatcher;
    private final Map<OperationKind, Long> activeGenerations = new EnumMap<>(OperationKind.class);
    private Consumer<ClientState> stateListener = ignored -> { };
    private ClientState state = new ClientState(ClientPhase.LOGIN, null, null, null, "", 0);
    private final Map<StatisticsOperationKind, Long> activeStatisticsGenerations =
            new EnumMap<>(StatisticsOperationKind.class);
    private Consumer<StatisticsDashboardState> dashboardListener;
    private StatisticsDashboardState dashboardState;
    private long dashboardActivation;
    private long nextStatisticsGeneration;
    private long nextGeneration;
    private long callbackEpoch;
    private long gameOverActivation;
    private long nextRematchGeneration;
    private Long activeRematchGeneration;
    private long rematchCallbackEpoch;

    /**
     * Creates a coordinator that publishes state only through the supplied UI dispatcher.
     *
     * @param uiDispatcher boundary used to schedule presentation work
     */
    public ClientStateCoordinator(ClientUiDispatcher uiDispatcher) {
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
    }

    /**
     * Registers the listener that receives immutable state snapshots on the UI boundary.
     *
     * @param listener presentation listener
     */
    public void setStateListener(Consumer<ClientState> listener) {
        synchronized (lock) {
            stateListener = Objects.requireNonNull(listener, "listener");
        }
    }

    /**
     * Returns the latest state already accepted by the coordinator.
     *
     * @return immutable current client state
     */
    public ClientState getState() {
        synchronized (lock) {
            return state;
        }
    }

    /**
     * Activates a fresh statistics dashboard for the current session and schedules its empty shell
     * state through the UI dispatcher. Closing the returned subscription invalidates its requests.
     *
     * @param listener active screen presentation listener
     * @return lifecycle subscription for the active statistics screen
     * @throws IllegalStateException if no session is established
     */
    public DashboardSubscription activateStatisticsDashboard(
            Consumer<StatisticsDashboardState> listener) {
        Consumer<StatisticsDashboardState> validatedListener =
                Objects.requireNonNull(listener, "listener");
        long activation;
        synchronized (lock) {
            if (state.getSessionInfo() == null) {
                throw new IllegalStateException("Statistics require an established session");
            }
            activation = ++dashboardActivation;
            activeStatisticsGenerations.clear();
            dashboardState = StatisticsDashboardState.initial(
                    state.getSessionInfo().getSessionId());
            dashboardListener = validatedListener;
        }
        publishDashboard(activation);
        return new DashboardSubscription(activation);
    }

    /**
     * Returns the latest accepted dashboard state for diagnostics and tests.
     *
     * @return current dashboard state, or null while the screen is inactive
     */
    public StatisticsDashboardState getStatisticsDashboardState() {
        synchronized (lock) {
            return dashboardState;
        }
    }

    /**
     * Starts the newest personal-statistics generation for the active screen and session.
     *
     * @return token used to reconcile the personal result
     * @throws IllegalStateException if the statistics screen is inactive or the session changed
     */
    StatisticsOperationToken beginPlayerStatisticsLoad() {
        StatisticsOperationToken token;
        long activation;
        synchronized (lock) {
            token = createStatisticsTokenLocked(StatisticsOperationKind.PERSONAL);
            dashboardState = dashboardState.withPersonalLoading();
            activation = dashboardActivation;
        }
        publishDashboard(activation);
        return token;
    }

    /**
     * Applies a personal-statistics result only when its session, activation, and generation remain
     * current. The leaderboard slice is merged from the latest dashboard state under the lock.
     *
     * @param token token created before remote work began
     * @param result authoritative personal-statistics result
     * @return newest dashboard state, or null after screen deactivation
     */
    StatisticsDashboardState completePlayerStatistics(StatisticsOperationToken token,
                                                       PlayerStatisticsResult result) {
        Objects.requireNonNull(result, "result");
        StatisticsDashboardState changed = null;
        long activation = token.activation;
        synchronized (lock) {
            if (consumeStatisticsTokenLocked(token)) {
                dashboardState = result.isSuccess()
                        ? dashboardState.withPersonalSuccess(result.getStatistics())
                        : dashboardState.withPersonalExpectedFailure(
                                result.getResultCode(), result.getMessage());
                changed = dashboardState;
            }
        }
        if (changed != null) {
            publishDashboard(activation);
        }
        return getStatisticsDashboardState();
    }

    /**
     * Starts the newest leaderboard generation for the active screen and session.
     *
     * @return token used to reconcile the leaderboard result
     * @throws IllegalStateException if the statistics screen is inactive or the session changed
     */
    StatisticsOperationToken beginLeaderboardLoad() {
        StatisticsOperationToken token;
        long activation;
        synchronized (lock) {
            token = createStatisticsTokenLocked(StatisticsOperationKind.LEADERBOARD);
            dashboardState = dashboardState.withLeaderboardLoading();
            activation = dashboardActivation;
        }
        publishDashboard(activation);
        return token;
    }

    /**
     * Applies server-ranked leaderboard rows only when the request is still current. Personal state
     * is merged from the latest dashboard snapshot under the coordinator lock.
     *
     * @param token token created before remote work began
     * @param result authoritative leaderboard result
     * @return newest dashboard state, or null after screen deactivation
     */
    StatisticsDashboardState completeLeaderboard(StatisticsOperationToken token,
                                                  LeaderboardResult result) {
        Objects.requireNonNull(result, "result");
        StatisticsDashboardState changed = null;
        long activation = token.activation;
        synchronized (lock) {
            if (consumeStatisticsTokenLocked(token)) {
                dashboardState = result.isSuccess()
                        ? dashboardState.withLeaderboardSuccess(result.getEntries())
                        : dashboardState.withLeaderboardExpectedFailure(
                                result.getResultCode(), result.getMessage());
                changed = dashboardState;
            }
        }
        if (changed != null) {
            publishDashboard(activation);
        }
        return getStatisticsDashboardState();
    }

    /**
     * Applies a transport failure only to the independent statistics generation that submitted it.
     *
     * @param token token created before remote work began
     * @param message player-facing transport failure text
     * @return newest dashboard state, or null after screen deactivation
     */
    StatisticsDashboardState failStatisticsOperation(StatisticsOperationToken token,
                                                      String message) {
        Objects.requireNonNull(message, "message");
        StatisticsDashboardState changed = null;
        long activation = token.activation;
        synchronized (lock) {
            if (consumeStatisticsTokenLocked(token)) {
                dashboardState = token.kind == StatisticsOperationKind.PERSONAL
                        ? dashboardState.withPersonalTransportFailure(message)
                        : dashboardState.withLeaderboardTransportFailure(message);
                changed = dashboardState;
            }
        }
        if (changed != null) {
            publishDashboard(activation);
        }
        return getStatisticsDashboardState();
    }

    /**
     * Creates a session-bound request token and records it as newest in its independent stream.
     *
     * @param kind personal or leaderboard request stream
     * @return active request token
     */
    private StatisticsOperationToken createStatisticsTokenLocked(StatisticsOperationKind kind) {
        if (dashboardListener == null || dashboardState == null
                || state.getSessionInfo() == null
                || !state.getSessionInfo().getSessionId().equals(dashboardState.getSessionId())) {
            throw new IllegalStateException("Statistics dashboard is not active for this session");
        }
        long generation = ++nextStatisticsGeneration;
        activeStatisticsGenerations.put(kind, generation);
        return new StatisticsOperationToken(kind, generation, dashboardActivation,
                dashboardState.getSessionId());
    }

    /**
     * Consumes a statistics token only if its stream, screen activation, and session remain current.
     *
     * @param token request token to validate
     * @return true when the completion may merge into dashboard state
     */
    private boolean consumeStatisticsTokenLocked(StatisticsOperationToken token) {
        Objects.requireNonNull(token, "token");
        Long activeGeneration = activeStatisticsGenerations.get(token.kind);
        if (dashboardListener == null || dashboardState == null
                || dashboardActivation != token.activation
                || !dashboardState.getSessionId().equals(token.sessionId)
                || state.getSessionInfo() == null
                || !state.getSessionInfo().getSessionId().equals(token.sessionId)
                || activeGeneration == null
                || activeGeneration.longValue() != token.generation) {
            return false;
        }
        activeStatisticsGenerations.remove(token.kind);
        return true;
    }

    /** Invalidates dashboard requests and detaches the active presentation listener. */
    private void invalidateDashboardLocked() {
        dashboardActivation++;
        activeStatisticsGenerations.clear();
        dashboardListener = null;
        dashboardState = null;
    }

    /**
     * Schedules dashboard presentation while rechecking activation at execution time. Callback and
     * remote threads therefore never touch JavaFX controls or an inactive screen.
     *
     * @param activation screen activation whose newest state should be delivered
     */
    private void publishDashboard(long activation) {
        uiDispatcher.dispatch(() -> {
            Consumer<StatisticsDashboardState> listener;
            StatisticsDashboardState snapshot;
            synchronized (lock) {
                if (dashboardActivation != activation || dashboardListener == null) {
                    return;
                }
                listener = dashboardListener;
                snapshot = dashboardState;
            }
            listener.accept(snapshot);
        });
    }

    /**
     * Starts a guest connection and publishes the local pending state.
     *
     * @return token used to reconcile the eventual synchronous result
     * @throws IllegalStateException if a session or connection already exists
     */
    OperationToken beginGuestConnection() {
        return beginOperation(OperationKind.SESSION_ESTABLISHMENT, ClientPhase.LOGIN,
                ClientPhase.CONNECTING, "Connecting to FleetLink...");
    }

    /**
     * Applies a guest result when its operation remains current and no callback superseded it.
     *
     * @param token token created before the remote call
     * @param result authoritative synchronous result
     * @return newest client state, including a newer callback state when the result was stale
     */
    ClientState completeGuestConnection(OperationToken token, SessionResult result) {
        return completeSessionEstablishment(token, result, "Connected as ");
    }

    /**
     * Starts registered login or registration in the common session-establishment generation.
     *
     * @param registration true for account creation and false for login
     * @return token used to reconcile the eventual synchronous result
     * @throws IllegalStateException if another connection or session already exists
     */
    OperationToken beginRegisteredConnection(boolean registration) {
        String message = registration ? "Creating account..." : "Signing in...";
        return beginOperation(OperationKind.SESSION_ESTABLISHMENT, ClientPhase.LOGIN,
                ClientPhase.CONNECTING, message);
    }

    /**
     * Applies registered session establishment through the same stale-result guard as guest mode.
     *
     * @param token token created before remote work
     * @param result authoritative session result
     * @param registration true when the operation created an account
     * @return newest client state
     */
    ClientState completeRegisteredConnection(OperationToken token, SessionResult result,
                                             boolean registration) {
        String prefix = registration ? "Account created for " : "Signed in as ";
        return completeSessionEstablishment(token, result, prefix);
    }

    /**
     * Reconciles every guest, login, and registration result under one operation generation.
     *
     * @param token common session-establishment token
     * @param result authoritative synchronous session result
     * @param successPrefix player-facing success message prefix
     * @return newest client state
     */
    private ClientState completeSessionEstablishment(OperationToken token, SessionResult result,
                                                     String successPrefix) {
        Objects.requireNonNull(result, "result");
        ClientState changed = null;
        synchronized (lock) {
            if (consumeFreshTokenLocked(token)) {
                if (result.isSuccess()) {
                    invalidateRematchScopeLocked();
                    changed = replaceStateLocked(ClientPhase.LOBBY, result.getSessionInfo(), null,
                            successPrefix + result.getSessionInfo().getPlayer().getDisplayName() + ".");
                } else {
                    changed = replaceStateLocked(ClientPhase.LOGIN, null, null, result.getMessage());
                }
            }
        }
        publishIfChanged(changed);
        return getState();
    }

    /**
     * Starts matchmaking for the established lobby session.
     *
     * @return token used to reconcile the synchronous matchmaking result
     * @throws IllegalStateException if the client is not idle in the lobby
     */
    OperationToken beginMatchmaking() {
        return beginOperation(OperationKind.JOIN_MATCHMAKING, ClientPhase.LOBBY,
                ClientPhase.MATCHMAKING, "Searching for an opponent...");
    }

    /**
     * Applies a matchmaking result unless a match callback already advanced client state.
     *
     * @param token token created before the remote call
     * @param result authoritative synchronous matchmaking result
     * @return newest client state
     */
    ClientState completeMatchmaking(OperationToken token, MatchmakingResult result) {
        Objects.requireNonNull(result, "result");
        ClientState changed = null;
        synchronized (lock) {
            if (consumeFreshTokenLocked(token)) {
                if (!result.isSuccess()) {
                    changed = replaceStateLocked(ClientPhase.LOBBY, state.getSessionInfo(), null,
                            result.getMessage());
                } else if (result.getState() == MatchmakingState.WAITING) {
                    changed = replaceStateLocked(ClientPhase.MATCHMAKING, state.getSessionInfo(), null,
                            "Waiting for an opponent...");
                } else {
                    changed = replaceStateLocked(ClientPhase.MATCHMAKING, state.getSessionInfo(), null,
                            "Match found. Waiting for game state...");
                }
            }
        }
        publishIfChanged(changed);
        return getState();
    }

    /**
     * Starts cancellation while the client is waiting in matchmaking.
     *
     * @return token used to reconcile the cancellation result
     * @throws IllegalStateException if the client is not in matchmaking
     */
    OperationToken beginMatchmakingCancellation() {
        return beginOperation(OperationKind.CANCEL_MATCHMAKING, ClientPhase.MATCHMAKING,
                ClientPhase.MATCHMAKING, "Cancelling matchmaking...");
    }

    /**
     * Applies a cancellation result unless a match callback already created a game.
     *
     * @param token token created before the remote call
     * @param result authoritative cancellation result
     * @return newest client state
     */
    ClientState completeMatchmakingCancellation(OperationToken token, OperationResult result) {
        Objects.requireNonNull(result, "result");
        ClientState changed = null;
        synchronized (lock) {
            if (consumeFreshTokenLocked(token)) {
                ClientPhase phase = result.isSuccess() ? ClientPhase.LOBBY : ClientPhase.MATCHMAKING;
                String message = result.isSuccess() ? "Matchmaking cancelled." : result.getMessage();
                changed = replaceStateLocked(phase, state.getSessionInfo(), null, message);
            }
        }
        publishIfChanged(changed);
        return getState();
    }

    /**
     * Starts one complete fleet submission for the current placement game.
     *
     * @return token used to reconcile the synchronous fleet result
     * @throws IllegalStateException if the client is not in ship placement
     */
    OperationToken beginFleetSubmission() {
        return beginOperation(OperationKind.SUBMIT_FLEET, ClientPhase.SHIP_PLACEMENT,
                ClientPhase.SUBMITTING_FLEET, "Submitting fleet...");
    }

    /**
     * Applies a fleet result unless a game-state callback already supplied newer state.
     *
     * @param token token created before the remote call
     * @param result authoritative synchronous fleet result
     * @return newest client state
     */
    ClientState completeFleetSubmission(OperationToken token, FleetSubmissionResult result) {
        Objects.requireNonNull(result, "result");
        ClientState changed = null;
        synchronized (lock) {
            if (consumeFreshTokenLocked(token)) {
                GameView resultView = result.getGameView();
                if (result.isAccepted()) {
                    changed = stateFromGameLocked(resultView, "Fleet accepted.", true);
                } else if (resultView != null && resultView.getPhase() != GamePhase.FLEET_PLACEMENT) {
                    changed = stateFromGameLocked(resultView, result.getMessage(), false);
                } else {
                    GameView currentView = resultView == null ? state.getGameView() : resultView;
                    changed = replaceStateLocked(ClientPhase.SHIP_PLACEMENT,
                            state.getSessionInfo(), currentView, result.getMessage());
                }
            }
        }
        publishIfChanged(changed);
        return getState();
    }

    /**
     * Starts one shot only when the latest authoritative snapshot permits the target.
     * The validation prevents stale Battle controls from queuing invalid remote actions.
     *
     * @param coordinate requested target coordinate
     * @return token used to reconcile the synchronous shot result
     * @throws NullPointerException if the coordinate is null
     * @throws IllegalStateException if Battle or the receiving player's turn is no longer current
     * @throws IllegalArgumentException if the target is already resolved in authoritative state
     */
    OperationToken beginFire(Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        ClientState changed;
        OperationToken token;
        synchronized (lock) {
            if (state.getPhase() != ClientPhase.BATTLE || state.getGameView() == null
                    || !state.getGameView().isYourTurn()) {
                throw new IllegalStateException("fire is not valid in the current battle state");
            }
            if (state.getGameView().getOpponentBoard().getCell(coordinate)
                    != OpponentCellView.UNKNOWN) {
                throw new IllegalArgumentException("Target was already resolved by the server");
            }
            token = createTokenLocked(OperationKind.FIRE, ClientPhase.BATTLE);
            changed = replaceStateLocked(ClientPhase.FIRING, state.getSessionInfo(),
                    state.getGameView(), "Firing at " + coordinateLabel(coordinate) + "...");
        }
        publishIfChanged(changed);
        return token;
    }

    /**
     * Applies only the outcome and snapshot returned by the authoritative shot operation.
     * A callback accepted after the operation began makes this result stale.
     *
     * @param token token created before the remote call
     * @param result authoritative synchronous shot result
     * @return newest reconciled client state
     */
    ClientState completeFire(OperationToken token, ShotResult result) {
        Objects.requireNonNull(result, "result");
        ClientState changed = null;
        synchronized (lock) {
            if (consumeFreshTokenLocked(token)) {
                GameView resultView = result.getGameView();
                if (resultView != null) {
                    String message = result.isAccepted()
                            ? shotMessage(result.getOutcome()) : result.getMessage();
                    changed = stateFromGameLocked(resultView, message, false);
                } else {
                    changed = replaceStateLocked(ClientPhase.BATTLE, state.getSessionInfo(),
                            state.getGameView(), result.getMessage());
                }
            }
        }
        publishIfChanged(changed);
        return getState();
    }

    /**
     * Starts authoritative forfeit from placement, placement waiting, or Battle.
     *
     * @return token used to reconcile the leave result with its exact source phase
     * @throws IllegalStateException if the current phase does not admit a voluntary forfeit
     */
    OperationToken beginLeaveGame() {
        ClientState changed;
        OperationToken token;
        synchronized (lock) {
            ClientPhase sourcePhase = state.getPhase();
            if (sourcePhase != ClientPhase.SHIP_PLACEMENT
                    && sourcePhase != ClientPhase.WAITING_FOR_BATTLE
                    && sourcePhase != ClientPhase.BATTLE) {
                throw new IllegalStateException(
                        "leave operation is not valid in phase " + sourcePhase);
            }
            token = createTokenLocked(OperationKind.LEAVE_GAME, sourcePhase);
            changed = replaceStateLocked(ClientPhase.LEAVING_GAME, state.getSessionInfo(),
                    state.getGameView(), "Waiting for the server to process the forfeit...");
        }
        publishIfChanged(changed);
        return token;
    }

    /**
     * Applies authoritative game departure and clears local game state only after success.
     *
     * @param token token created before the remote call
     * @param result authoritative leave result
     * @return newest reconciled client state
     */
    ClientState completeLeaveGame(OperationToken token, OperationResult result) {
        Objects.requireNonNull(result, "result");
        ClientState changed = null;
        synchronized (lock) {
            if (consumeCurrentTokenLocked(token) && state.getPhase() == ClientPhase.LEAVING_GAME) {
                if (result.isSuccess()) {
                    changed = replaceStateLocked(ClientPhase.LOBBY,
                            state.getSessionInfo(), null, "Game forfeited.");
                } else {
                    changed = restoreAfterLeaveFailureLocked(token, result.getMessage());
                }
            }
        }
        publishIfChanged(changed);
        return getState();
    }

    /**
     * Applies leave-specific transport recovery without treating a newer nonterminal callback as
     * stale. A terminal callback or later lifecycle has stronger authority and receives no update.
     *
     * @param token token created before the leave request
     * @param message player-facing transport failure explanation
     * @return newest reconciled client state
     */
    ClientState failLeaveGame(OperationToken token, String message) {
        Objects.requireNonNull(message, "message");
        ClientState changed = null;
        synchronized (lock) {
            if (consumeCurrentTokenLocked(token) && state.getPhase() == ClientPhase.LEAVING_GAME) {
                changed = restoreAfterLeaveFailureLocked(token, message);
            }
        }
        publishIfChanged(changed);
        return getState();
    }

    /**
     * Starts a positive request in the single rematch mutation stream.
     *
     * @return exact token used by the asynchronous remote operation
     * @throws IllegalStateException if Game Over identity or Request enablement is no longer current
     */
    RematchOperationToken beginRematchRequest() {
        return beginRematchOperation(RematchClientState.InFlightAction.REQUEST);
    }

    /**
     * Starts acceptance or decline only from the newest correlated incoming request.
     *
     * @param accept true to accept and false to decline
     * @return exact token used by the asynchronous remote operation
     * @throws IllegalStateException if the incoming request or operation stream is no longer current
     */
    RematchOperationToken beginRematchResponse(boolean accept) {
        return beginRematchOperation(accept
                ? RematchClientState.InFlightAction.ACCEPT
                : RematchClientState.InFlightAction.DECLINE);
    }

    /**
     * Reconciles a structured synchronous rematch result without overwriting newer callback state.
     * Identity freshness retires exactly one active generation. Callback freshness is considered
     * separately so a non-terminal callback can be preserved while the local operation retires.
     *
     * @param token exact token created before remote work
     * @param result authoritative operation result
     * @return newest client state, including a newer callback or game when this result is stale
     */
    ClientState completeRematchOperation(RematchOperationToken token, OperationResult result) {
        Objects.requireNonNull(result, "result");
        ClientState changed = null;
        synchronized (lock) {
            if (consumeCurrentRematchIdentityLocked(token)) {
                RematchClientState current = requireCurrentRematchLocked();
                boolean callbackAdvanced = rematchCallbackEpoch
                        != token.rematchCallbackEpochAtBegin;
                RematchStatusView status = current.getAuthoritativeStatus();
                boolean requestAcknowledged = current.isRequestAcknowledged();
                boolean declineAcknowledged = current.isDeclineAcknowledged();
                boolean creationCommitted = current.isCreationCommitted();
                ResultCode feedbackCode = null;
                String feedbackMessage = "";

                if (result.isSuccess()) {
                    if (token.action == RematchClientState.InFlightAction.REQUEST) {
                        requestAcknowledged = true;
                        creationCommitted = callbackAdvanced && status != null
                                && status.getState() == RematchState.REQUESTED_BY_OPPONENT;
                    } else if (token.action == RematchClientState.InFlightAction.ACCEPT) {
                        creationCommitted = true;
                    } else if (token.action == RematchClientState.InFlightAction.DECLINE) {
                        declineAcknowledged = true;
                    }
                } else {
                    feedbackCode = result.getResultCode();
                    feedbackMessage = result.getMessage();
                }

                RematchClientState updated = new RematchClientState(
                        current.getSessionId(), current.getCompletedGameId(), status,
                        RematchClientState.InFlightAction.NONE, requestAcknowledged,
                        declineAcknowledged, creationCommitted, feedbackCode, false,
                        feedbackMessage);
                changed = replaceStateLocked(ClientPhase.GAME_OVER, state.getSessionInfo(),
                        state.getGameView(), updated, rematchStatusMessage(updated));
            }
        }
        publishIfChanged(changed);
        return getState();
    }

    /**
     * Retires a still-current rematch mutation after transport uncertainty while preserving every
     * authoritative callback accepted since the operation began.
     *
     * @param token exact token created before remote work
     * @param message recoverable player-facing transport feedback
     * @return newest client state
     */
    ClientState failRematchOperation(RematchOperationToken token, String message) {
        Objects.requireNonNull(message, "message");
        ClientState changed = null;
        synchronized (lock) {
            if (consumeCurrentRematchIdentityLocked(token)) {
                RematchClientState current = requireCurrentRematchLocked();
                RematchClientState updated = new RematchClientState(
                        current.getSessionId(), current.getCompletedGameId(),
                        current.getAuthoritativeStatus(), RematchClientState.InFlightAction.NONE,
                        current.isRequestAcknowledged(), current.isDeclineAcknowledged(),
                        current.isCreationCommitted(), null, true, message);
                changed = replaceStateLocked(ClientPhase.GAME_OVER, state.getSessionInfo(),
                        state.getGameView(), updated, rematchStatusMessage(updated));
            }
        }
        publishIfChanged(changed);
        return getState();
    }

    /**
     * Atomically chooses rematch cleanup and destroys the completed-game lifecycle before remote
     * best-effort work can begin.
     *
     * The existing false response is always queued so the server can expire an unrequested
     * opportunity or linearize cleanup after an in-flight rematch action.
     *
     * @return immutable SEND_FALSE plan with committed Lobby state
     * @throws IllegalStateException if Game Over is stale or creation is already committed
     */
    ReturnToLobbyPlan beginReturnToLobby() {
        ClientState changed;
        ReturnToLobbyAction action;
        UUID sessionId;
        synchronized (lock) {
            if (state.getPhase() != ClientPhase.GAME_OVER || state.getSessionInfo() == null
                    || state.getGameView() == null
                    || state.getGameView().getPhase() != GamePhase.FINISHED) {
                throw new IllegalStateException("return to Lobby requires a completed game");
            }
            RematchClientState rematch = state.getRematchState();
            if (rematch != null && rematch.isCreationCommitted()) {
                throw new IllegalStateException("a committed rematch must await the new game");
            }
            sessionId = state.getSessionInfo().getSessionId();
            action = ReturnToLobbyAction.SEND_FALSE;
            invalidateRematchScopeLocked();
            changed = replaceStateLocked(ClientPhase.LOBBY, state.getSessionInfo(), null,
                    "Returned to Lobby.");
        }
        publishIfChanged(changed);
        return new ReturnToLobbyPlan(action,
                action == ReturnToLobbyAction.SEND_FALSE ? sessionId : null, changed);
    }

    /**
     * Starts explicit logout from an idle or matchmaking Lobby session.
     *
     * @return token used to reconcile the logout result
     * @throws IllegalStateException if logout is not valid in the current phase
     */
    OperationToken beginLogout() {
        ClientState changed;
        OperationToken token;
        synchronized (lock) {
            if (state.getSessionInfo() == null
                    || (state.getPhase() != ClientPhase.LOBBY
                    && state.getPhase() != ClientPhase.MATCHMAKING)) {
                throw new IllegalStateException("logout is not valid in phase " + state.getPhase());
            }
            ClientPhase sourcePhase = state.getPhase();
            token = createTokenLocked(OperationKind.LOGOUT, sourcePhase);
            changed = replaceStateLocked(ClientPhase.LOGGING_OUT, state.getSessionInfo(),
                    null, "Logging out...");
        }
        publishIfChanged(changed);
        return token;
    }

    /**
     * Reconciles logout with session-lifecycle semantics. Success and invalid session are terminal
     * for a current logout generation even when a callback advanced the ordinary game-state epoch.
     *
     * @param token token created before the remote call
     * @param result authoritative logout result
     * @return state and whether terminal session reconciliation was accepted
     */
    LogoutReconciliation completeLogout(OperationToken token, OperationResult result) {
        Objects.requireNonNull(result, "result");
        ClientState changed = null;
        ClientState reconciled;
        boolean terminal = false;
        synchronized (lock) {
            boolean terminalResult = result.isSuccess()
                    || result.getResultCode() == ResultCode.INVALID_SESSION;
            if (terminalResult && consumeCurrentTokenLocked(token)) {
                activeGenerations.clear();
                invalidateDashboardLocked();
                invalidateRematchScopeLocked();
                String message = result.isSuccess() ? "Logged out." : result.getMessage();
                changed = replaceStateLocked(ClientPhase.LOGIN, null, null, message);
                terminal = true;
            } else if (!terminalResult && consumeFreshTokenLocked(token)) {
                changed = replaceStateLocked(token.sourcePhase, state.getSessionInfo(),
                        state.getGameView(), result.getMessage());
            }
            reconciled = state;
        }
        publishIfChanged(changed);
        return new LogoutReconciliation(reconciled, terminal);
    }

    /**
     * Records a match callback only for the exact session bound to its exported endpoint.
     *
     * @param endpointSessionId exact locally bound callback session
     * @param initialGame authoritative initial game snapshot
     */
    public void acceptMatchFound(UUID endpointSessionId, GameView initialGame) {
        acceptGameCallback(endpointSessionId, initialGame, true);
    }

    /**
     * Records a game-state callback only for the exact session bound to its exported endpoint.
     *
     * @param endpointSessionId exact locally bound callback session
     * @param gameView authoritative changed game snapshot
     */
    public void acceptGameStateChanged(UUID endpointSessionId, GameView gameView) {
        acceptGameCallback(endpointSessionId, gameView, false);
    }

    /**
     * Provides package-scoped direct callback simulation using the current exact session.
     * Production callback endpoints always use the explicit session overload.
     *
     * @param initialGame authoritative initial game snapshot
     */
    void acceptMatchFound(GameView initialGame) {
        acceptMatchFound(requireCurrentSessionId(), initialGame);
    }

    /**
     * Provides package-scoped direct callback simulation using the current exact session.
     * Production callback endpoints always use the explicit session overload.
     *
     * @param gameView authoritative changed game snapshot
     */
    void acceptGameStateChanged(GameView gameView) {
        acceptGameStateChanged(requireCurrentSessionId(), gameView);
    }

    /**
     * Reconciles a rematch callback only after exact session, completed-game, and opponent checks.
     * Correlation failure is ignored without advancing callback freshness or retiring an operation.
     *
     * @param endpointSessionId exact session captured by the exported callback endpoint
     * @param status authoritative player-specific rematch status
     * @return true when the callback was correlated and accepted
     */
    public boolean acceptRematchStatus(UUID endpointSessionId, RematchStatusView status) {
        Objects.requireNonNull(endpointSessionId, "endpointSessionId");
        Objects.requireNonNull(status, "status");
        ClientState changed = null;
        synchronized (lock) {
            if (!isRematchCallbackCorrelatedLocked(endpointSessionId, status)) {
                return false;
            }
            RematchClientState current = state.getRematchState();
            if (isTerminalRematchState(current.getAuthoritativeStatus())
                    && !isTerminalRematchState(status)) {
                return false;
            }

            rematchCallbackEpoch++;
            RematchClientState.InFlightAction action = current.getInFlightAction();
            boolean requestAcknowledged = current.isRequestAcknowledged();
            boolean declineAcknowledged = current.isDeclineAcknowledged();
            boolean creationCommitted = current.isCreationCommitted();

            if (isTerminalRematchState(status)) {
                activeRematchGeneration = null;
                action = RematchClientState.InFlightAction.NONE;
                requestAcknowledged = false;
                declineAcknowledged = false;
                creationCommitted = status.getState() == RematchState.ACCEPTED;
            } else if (status.getState() == RematchState.REQUESTED_BY_OPPONENT
                    && requestAcknowledged && action == RematchClientState.InFlightAction.NONE) {
                creationCommitted = true;
            }

            RematchClientState updated = new RematchClientState(
                    current.getSessionId(), current.getCompletedGameId(), status, action,
                    requestAcknowledged, declineAcknowledged, creationCommitted,
                    null, false, "");
            changed = replaceStateLocked(ClientPhase.GAME_OVER, state.getSessionInfo(),
                    state.getGameView(), updated, rematchStatusMessage(updated));
        }
        publishIfChanged(changed);
        return true;
    }

    /**
     * Applies a transport failure only when its originating operation has not become stale.
     *
     * @param token token created before remote work began
     * @param message player-facing transport failure message
     * @return newest client state
     */
    ClientState failOperation(OperationToken token, String message) {
        Objects.requireNonNull(message, "message");
        ClientState changed = null;
        synchronized (lock) {
            if (consumeFreshTokenLocked(token)) {
                changed = replaceStateLocked(token.sourcePhase, state.getSessionInfo(),
                        state.getGameView(), message);
            }
        }
        publishIfChanged(changed);
        return getState();
    }

    /**
     * Starts one generation-scoped operation after validating its required source phase.
     *
     * @param kind operation category
     * @param requiredPhase phase required before starting
     * @param pendingPhase phase published while the request runs
     * @param message pending status text
     * @return operation token containing the current callback epoch
     */
    private OperationToken beginOperation(OperationKind kind, ClientPhase requiredPhase,
                                          ClientPhase pendingPhase, String message) {
        ClientState changed;
        OperationToken token;
        synchronized (lock) {
            if (state.getPhase() != requiredPhase) {
                throw new IllegalStateException("operation is not valid in phase " + state.getPhase());
            }
            token = createTokenLocked(kind, requiredPhase);
            changed = replaceStateLocked(pendingPhase, state.getSessionInfo(),
                    state.getGameView(), message);
        }
        publishIfChanged(changed);
        return token;
    }

    /**
     * Creates and activates one operation token while the coordinator lock is held.
     *
     * @param kind operation category
     * @param sourcePhase phase restored if current remote work fails
     * @return active operation token
     */
    private OperationToken createTokenLocked(OperationKind kind, ClientPhase sourcePhase) {
        long generation = ++nextGeneration;
        activeGenerations.put(kind, generation);
        return new OperationToken(kind, generation, callbackEpoch, sourcePhase);
    }

    /**
     * Applies one callback atomically before handing its state snapshot to the UI dispatcher.
     *
     * @param endpointSessionId exact session captured by the callback endpoint
     * @param gameView authoritative callback snapshot
     * @param startsNewGame whether the callback establishes a newly matched game
     */
    private void acceptGameCallback(UUID endpointSessionId, GameView gameView,
                                    boolean startsNewGame) {
        Objects.requireNonNull(endpointSessionId, "endpointSessionId");
        Objects.requireNonNull(gameView, "gameView");
        ClientState changed;
        synchronized (lock) {
            validateCallbackGameLocked(endpointSessionId, gameView, startsNewGame);
            if (startsNewGame) {
                invalidateRematchScopeLocked();
            }
            callbackEpoch++;
            if (!startsNewGame && preservesPendingLeaveLocked(gameView)) {
                changed = replaceStateLocked(ClientPhase.LEAVING_GAME, state.getSessionInfo(),
                        gameView, "Waiting for the server to process the forfeit...");
            } else {
                changed = stateFromGameLocked(gameView, callbackMessage(gameView), false);
            }
        }
        publishIfChanged(changed);
    }

    /**
     * Reports whether a validated nonterminal callback updates the pending leave snapshot without
     * releasing the active mutation barrier.
     *
     * @param gameView newest authoritative callback snapshot
     * @return true when the exact leave generation remains pending
     */
    private boolean preservesPendingLeaveLocked(GameView gameView) {
        return state.getPhase() == ClientPhase.LEAVING_GAME
                && activeGenerations.containsKey(OperationKind.LEAVE_GAME)
                && gameView.getPhase() != GamePhase.FINISHED;
    }

    /**
     * Restores the screen phase after a current leave failure while retaining the newest callback
     * snapshot. Battle progression wins over an older placement source; a placement snapshot
     * restores the exact placement source phase captured by the token.
     *
     * @param token current leave token containing its exact source phase
     * @param message authoritative or transport failure text
     * @return restored client state
     */
    private ClientState restoreAfterLeaveFailureLocked(OperationToken token, String message) {
        GameView gameView = state.getGameView();
        ClientPhase restoredPhase = gameView != null && gameView.getPhase() == GamePhase.BATTLE
                ? ClientPhase.BATTLE : token.sourcePhase;
        return replaceStateLocked(restoredPhase, state.getSessionInfo(), gameView, message);
    }

    /**
     * Validates callback ownership and game correlation against established client state.
     *
     * @param endpointSessionId exact session captured by the callback endpoint
     * @param gameView callback snapshot
     * @param startsNewGame whether a different game identifier is expected
     * @throws IllegalStateException if no session exists
     * @throws IllegalArgumentException if the callback belongs to another player or game
     */
    private void validateCallbackGameLocked(UUID endpointSessionId, GameView gameView,
                                            boolean startsNewGame) {
        if (state.getSessionInfo() == null) {
            throw new IllegalStateException("game callback arrived before session establishment");
        }
        if (!state.getSessionInfo().getSessionId().equals(endpointSessionId)) {
            throw new IllegalArgumentException("game callback belongs to another session");
        }
        if (!state.getSessionInfo().getPlayer().getPlayerId().equals(gameView.getPlayer().getPlayerId())) {
            throw new IllegalArgumentException("game callback belongs to another player");
        }
        if (!startsNewGame) {
            if (state.getGameView() == null) {
                throw new IllegalArgumentException("game callback has no current game");
            }
            if (!state.getGameView().getGameId().equals(gameView.getGameId())) {
                throw new IllegalArgumentException("game callback belongs to another game");
            }
        }
    }

    /**
     * Converts an authoritative game phase into the corresponding client phase and state.
     *
     * @param gameView authoritative game snapshot
     * @param message status text to publish
     * @param acceptedFleet whether a placement snapshot follows an accepted fleet
     * @return newly accepted client state
     */
    private ClientState stateFromGameLocked(GameView gameView, String message,
                                            boolean acceptedFleet) {
        ClientPhase phase = switch (gameView.getPhase()) {
            case FLEET_PLACEMENT -> acceptedFleet
                    ? ClientPhase.WAITING_FOR_BATTLE : ClientPhase.SHIP_PLACEMENT;
            case BATTLE -> ClientPhase.BATTLE;
            case FINISHED -> ClientPhase.GAME_OVER;
        };
        RematchClientState rematchState = null;
        if (phase == ClientPhase.GAME_OVER) {
            boolean sameActivation = state.getPhase() == ClientPhase.GAME_OVER
                    && state.getGameView() != null
                    && state.getGameView().getGameId().equals(gameView.getGameId())
                    && state.getRematchState() != null
                    && state.getSessionInfo() != null
                    && state.getSessionInfo().getSessionId().equals(
                            state.getRematchState().getSessionId());
            if (sameActivation) {
                rematchState = state.getRematchState();
            } else {
                invalidateRematchScopeLocked();
                rematchState = RematchClientState.initial(
                        state.getSessionInfo().getSessionId(), gameView.getGameId());
            }
        }
        return replaceStateLocked(phase, state.getSessionInfo(), gameView, rematchState, message);
    }

    /**
     * Returns a concise status message derived only from authoritative callback state.
     *
     * @param gameView callback snapshot
     * @return player-facing callback status
     */
    private static String callbackMessage(GameView gameView) {
        return switch (gameView.getPhase()) {
            case FLEET_PLACEMENT -> "Opponent found. Place your fleet.";
            case BATTLE -> "Both fleets are ready. Battle started.";
            case FINISHED -> "Game finished.";
        };
    }

    /**
     * Formats an accepted server-calculated shot outcome for presentation.
     *
     * @param outcome authoritative shot outcome
     * @return player-facing accepted-shot message
     */
    private static String shotMessage(ShotOutcome outcome) {
        return switch (Objects.requireNonNull(outcome, "outcome")) {
            case MISS -> "Shot confirmed: miss.";
            case HIT -> "Shot confirmed: hit.";
            case SUNK -> "Shot confirmed: ship sunk.";
        };
    }

    /**
     * Formats a protocol coordinate with the UI's A-J and 1-10 convention.
     *
     * @param coordinate zero-based protocol coordinate
     * @return display coordinate
     */
    private static String coordinateLabel(Coordinate coordinate) {
        return Character.toString((char) ('A' + coordinate.getColumn()))
                + (coordinate.getRow() + 1);
    }

    /**
     * Consumes a token and reports whether both its generation and callback epoch remain current.
     *
     * @param token operation token to validate
     * @return true when the synchronous result may still update client state
     */
    private boolean consumeFreshTokenLocked(OperationToken token) {
        if (!consumeCurrentTokenLocked(token)) {
            return false;
        }
        return callbackEpoch == token.callbackEpoch;
    }

    /**
     * Consumes a token when its operation generation is still current without comparing epochs.
     * This narrower check supports terminal lifecycle barriers that callbacks cannot supersede.
     *
     * @param token operation token to validate
     * @return true when the token represented the current operation generation
     */
    private boolean consumeCurrentTokenLocked(OperationToken token) {
        Objects.requireNonNull(token, "token");
        Long activeGeneration = activeGenerations.get(token.kind);
        if (activeGeneration == null || activeGeneration.longValue() != token.generation) {
            return false;
        }
        activeGenerations.remove(token.kind);
        return true;
    }

    /**
     * Starts one exact rematch action after revalidating the latest immutable Game Over slice.
     *
     * @param action REQUEST, ACCEPT, or DECLINE
     * @return active rematch operation token
     * @throws IllegalStateException if the action is stale or another action owns the stream
     */
    private RematchOperationToken beginRematchOperation(
            RematchClientState.InFlightAction action) {
        Objects.requireNonNull(action, "action");
        ClientState changed;
        RematchOperationToken token;
        synchronized (lock) {
            RematchClientState current = requireCurrentRematchLocked();
            boolean permitted = switch (action) {
                case REQUEST -> current.canRequest();
                case ACCEPT -> current.canAccept();
                case DECLINE -> current.canDecline();
                case WITHDRAW, NONE -> false;
            };
            if (!permitted || activeRematchGeneration != null) {
                throw new IllegalStateException("rematch action is not valid in current state");
            }
            long generation = ++nextRematchGeneration;
            activeRematchGeneration = generation;
            token = new RematchOperationToken(generation, gameOverActivation,
                    current.getSessionId(), current.getCompletedGameId(),
                    rematchCallbackEpoch, action);
            RematchClientState updated = new RematchClientState(
                    current.getSessionId(), current.getCompletedGameId(),
                    current.getAuthoritativeStatus(), action,
                    current.isRequestAcknowledged(), current.isDeclineAcknowledged(),
                    current.isCreationCommitted(), null, false, "");
            changed = replaceStateLocked(ClientPhase.GAME_OVER, state.getSessionInfo(),
                    state.getGameView(), updated, rematchStatusMessage(updated));
        }
        publishIfChanged(changed);
        return token;
    }

    /**
     * Consumes only an operation whose generation and exact lifecycle identity remain current.
     * A stale completion cannot clear a newer stream owner or mutate a newer game or session.
     *
     * @param token token to validate and retire
     * @return true when the completion still owns the current rematch operation
     */
    private boolean consumeCurrentRematchIdentityLocked(RematchOperationToken token) {
        Objects.requireNonNull(token, "token");
        RematchClientState current = state.getRematchState();
        if (activeRematchGeneration == null
                || activeRematchGeneration.longValue() != token.generation
                || gameOverActivation != token.gameOverActivation
                || state.getPhase() != ClientPhase.GAME_OVER
                || state.getSessionInfo() == null
                || !state.getSessionInfo().getSessionId().equals(token.sessionId)
                || state.getGameView() == null
                || !state.getGameView().getGameId().equals(token.completedGameId)
                || current == null
                || !current.getSessionId().equals(token.sessionId)
                || !current.getCompletedGameId().equals(token.completedGameId)
                || current.getInFlightAction() != token.action) {
            return false;
        }
        activeRematchGeneration = null;
        return true;
    }

    /**
     * Returns the current exact Game Over rematch slice after validating its lifecycle binding.
     *
     * @return current immutable rematch state
     * @throws IllegalStateException if the active state is not one exact completed-game scope
     */
    private RematchClientState requireCurrentRematchLocked() {
        RematchClientState rematch = state.getRematchState();
        if (state.getPhase() != ClientPhase.GAME_OVER || state.getSessionInfo() == null
                || state.getGameView() == null
                || state.getGameView().getPhase() != GamePhase.FINISHED
                || rematch == null
                || !state.getSessionInfo().getSessionId().equals(rematch.getSessionId())
                || !state.getGameView().getGameId().equals(rematch.getCompletedGameId())) {
            throw new IllegalStateException("rematch requires the current completed game");
        }
        return rematch;
    }

    /**
     * Checks every mandatory session, phase, game, and opponent rematch callback key.
     *
     * @param endpointSessionId exact callback endpoint session
     * @param status callback status to correlate
     * @return true only for the active completed-game rematch scope
     */
    private boolean isRematchCallbackCorrelatedLocked(UUID endpointSessionId,
                                                       RematchStatusView status) {
        if (state.getSessionInfo() == null
                || !state.getSessionInfo().getSessionId().equals(endpointSessionId)
                || state.getPhase() != ClientPhase.GAME_OVER
                || state.getGameView() == null
                || state.getGameView().getPhase() != GamePhase.FINISHED
                || !status.getCompletedGameId().equals(state.getGameView().getGameId())
                || !status.getOpponent().getPlayerId().equals(
                        state.getGameView().getOpponent().getPlayerId())
                || state.getRematchState() == null) {
            return false;
        }
        RematchClientState rematch = state.getRematchState();
        return rematch.getSessionId().equals(endpointSessionId)
                && rematch.getCompletedGameId().equals(status.getCompletedGameId());
    }

    /**
     * Reports whether a status has terminal authority over the current negotiation.
     *
     * @param status authoritative status, or null
     * @return true for accepted, declined, or expired status
     */
    private static boolean isTerminalRematchState(RematchStatusView status) {
        if (status == null) {
            return false;
        }
        return status.getState() == RematchState.ACCEPTED
                || status.getState() == RematchState.DECLINED
                || status.getState() == RematchState.EXPIRED;
    }

    /** Invalidates every local rematch token and changes completed-screen activation identity. */
    private void invalidateRematchScopeLocked() {
        gameOverActivation++;
        activeRematchGeneration = null;
    }

    /**
     * Returns the current exact session for package-scoped direct callback simulation.
     *
     * @return current established session identifier
     * @throws IllegalStateException if no session exists
     */
    private UUID requireCurrentSessionId() {
        synchronized (lock) {
            if (state.getSessionInfo() == null) {
                throw new IllegalStateException("callback arrived before session establishment");
            }
            return state.getSessionInfo().getSessionId();
        }
    }

    /**
     * Derives concise Game Over rematch feedback without moving completed-result semantics.
     *
     * @param rematch current client-only interaction slice
     * @return player-facing rematch status text
     */
    private static String rematchStatusMessage(RematchClientState rematch) {
        return switch (rematch.getPresentation()) {
            case INITIAL -> "Request a rematch or return to Lobby.";
            case REQUEST_IN_FLIGHT -> "Requesting a rematch...";
            case REQUEST_ACKNOWLEDGED -> "Rematch requested. Waiting for your opponent.";
            case REQUESTED_BY_YOU -> "Rematch requested. Waiting for your opponent.";
            case REQUESTED_BY_OPPONENT -> "Your opponent requested a rematch.";
            case ACCEPT_IN_FLIGHT -> "Accepting the rematch...";
            case DECLINE_IN_FLIGHT -> "Declining the rematch...";
            case WITHDRAW_IN_FLIGHT -> "Withdrawing the rematch request...";
            case DECLINED -> "The rematch was declined.";
            case EXPIRED -> "The rematch opportunity expired.";
            case RECOVERABLE_FAILURE -> rematch.getFeedbackMessage();
            case AWAITING_NEW_GAME -> "Rematch accepted. Starting new game...";
        };
    }

    /**
     * Replaces state while incrementing the local publication revision.
     *
     * @param phase new client phase
     * @param sessionInfo current session
     * @param gameView current game snapshot
     * @param message status message
     * @return immutable replacement state
     */
    private ClientState replaceStateLocked(ClientPhase phase,
                                           io.github.tomerg12.fleetlink.shared.protocol.SessionInfo sessionInfo,
                                           GameView gameView, String message) {
        return replaceStateLocked(phase, sessionInfo, gameView, null, message);
    }

    /**
     * Replaces state with an explicit rematch slice while enforcing its Game Over-only lifetime.
     *
     * @param phase new client phase
     * @param sessionInfo current session
     * @param gameView current game snapshot
     * @param rematchState rematch slice, or null outside Game Over
     * @param message status message
     * @return immutable replacement state
     */
    private ClientState replaceStateLocked(ClientPhase phase,
                                           io.github.tomerg12.fleetlink.shared.protocol.SessionInfo sessionInfo,
                                           GameView gameView, RematchClientState rematchState,
                                           String message) {
        if (phase != ClientPhase.GAME_OVER && rematchState != null) {
            throw new IllegalArgumentException("rematch state is valid only during Game Over");
        }
        state = new ClientState(phase, sessionInfo, gameView, rematchState,
                message, state.getRevision() + 1);
        return state;
    }

    /**
     * Schedules presentation delivery only after the accepted state has been stored.
     *
     * @param changed accepted state, or null when an operation result was stale
     */
    private void publishIfChanged(ClientState changed) {
        if (changed == null) {
            return;
        }
        Consumer<ClientState> listener;
        synchronized (lock) {
            listener = stateListener;
        }
        uiDispatcher.dispatch(() -> listener.accept(getState()));
    }
}
