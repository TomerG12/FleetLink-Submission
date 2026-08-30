package io.github.tomerg12.fleetlink.client.integration;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.OperationToken;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.LogoutReconciliation;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.RematchOperationToken;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.ReturnToLobbyPlan;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.StatisticsOperationToken;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardResult;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsResult;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkServerRemote;

/**
 * Starts all synchronous RMI work on a dedicated executor and reconciles each result afterward.
 * Public methods return immediately and never invoke a potentially blocking gateway on the caller.
 */
public final class ClientOperationService implements AutoCloseable {

    /**
     * Opens the remote gateway lazily on the same background executor used for remote calls.
     */
    @FunctionalInterface
    interface GatewayFactory {

        /**
         * Creates an open gateway and may perform blocking registry or export work.
         *
         * @return open remote gateway
         * @throws Exception if gateway setup fails
         */
        ClientRemoteGateway open() throws Exception;
    }

    private final ClientStateCoordinator coordinator;
    private final GatewayFactory gatewayFactory;
    private final ExecutorService remoteExecutor;
    private final Object gatewayLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> shutdownFuture = new CompletableFuture<>();
    private ClientRemoteGateway gateway;

    /**
     * Creates an operation service with injectable transport and execution boundaries for tests.
     *
     * @param coordinator client state reconciliation boundary
     * @param gatewayFactory lazy blocking gateway factory
     * @param remoteExecutor executor dedicated to remote work
     */
    ClientOperationService(ClientStateCoordinator coordinator, GatewayFactory gatewayFactory,
                           ExecutorService remoteExecutor) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.gatewayFactory = Objects.requireNonNull(gatewayFactory, "gatewayFactory");
        this.remoteExecutor = Objects.requireNonNull(remoteExecutor, "remoteExecutor");
    }

    /**
     * Creates the production operation service for one configured RMI server.
     * Registry lookup and callback export remain lazy until the first remote request.
     *
     * @param coordinator client state reconciliation boundary
     * @param config validated RMI registry configuration
     * @return production operation service
     */
    public static ClientOperationService forRmi(ClientStateCoordinator coordinator,
                                                RmiClientConfig config) {
        Objects.requireNonNull(config, "config");
        return new ClientOperationService(coordinator,
                () -> RmiClientGateway.open(config, coordinator), createRemoteExecutor());
    }

    /**
     * Starts registered login on the dedicated remote executor without transforming the password.
     *
     * @param username submitted username
     * @param password exact submitted password sequence
     * @return future completed with the newest reconciled client state
     */
    public CompletableFuture<ClientState> login(String username, String password) {
        return startSessionCommand(username, password, false);
    }

    /**
     * Starts registered account creation on the dedicated remote executor.
     *
     * @param username requested username
     * @param password exact submitted password sequence
     * @return future completed with the newest reconciled client state
     */
    public CompletableFuture<ClientState> register(String username, String password) {
        return startSessionCommand(username, password, true);
    }

    /**
     * Starts a guest connection without blocking the caller.
     * Blank display names fail locally before registry lookup or RMI invocation.
     *
     * @param displayName guest display name from the existing username field
     * @return future completed with the newest reconciled client state
     */
    public CompletableFuture<ClientState> connectAsGuest(String displayName) {
        return startCommand(() -> {
            if (displayName == null || displayName.isBlank()) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Guest display name is required."));
            }
            String validatedName = displayName.trim();
            OperationToken token = coordinator.beginGuestConnection();
            return submitRemote(() -> {
                SessionResult result = gateway().connectAsGuest(validatedName);
                return coordinator.completeGuestConnection(token, result);
            }, token);
        });
    }

    /**
     * Validates local registered form structure and queues login or registration remotely.
     * The password is never stripped, trimmed, normalized, or otherwise transformed.
     *
     * @param username submitted username
     * @param password exact submitted password sequence
     * @param registration true for account creation and false for login
     * @return asynchronous reconciled session state
     */
    private CompletableFuture<ClientState> startSessionCommand(
            String username, String password, boolean registration) {
        return startCommand(() -> {
            if (username == null || username.isBlank()) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Username is required."));
            }
            if (password == null || password.isEmpty()) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Password is required."));
            }
            OperationToken token = coordinator.beginRegisteredConnection(registration);
            return submitRemote(() -> {
                SessionResult result = registration
                        ? gateway().register(username, password)
                        : gateway().login(username, password);
                return coordinator.completeRegisteredConnection(token, result, registration);
            }, token);
        });
    }

    /**
     * Starts server-authoritative matchmaking without polling or blocking the caller.
     *
     * @return future completed with the newest reconciled client state
     */
    public CompletableFuture<ClientState> joinMatchmaking() {
        return startCommand(() -> {
            UUID sessionId = requireSessionId();
            OperationToken token = coordinator.beginMatchmaking();
            return submitRemote(() -> {
                MatchmakingResult result = gateway().joinMatchmaking(sessionId);
                return coordinator.completeMatchmaking(token, result);
            }, token);
        });
    }

    /**
     * Starts authoritative matchmaking cancellation without blocking the caller.
     *
     * @return future completed with the newest reconciled client state
     */
    public CompletableFuture<ClientState> cancelMatchmaking() {
        return startCommand(() -> {
            UUID sessionId = requireSessionId();
            OperationToken token = coordinator.beginMatchmakingCancellation();
            return submitRemote(() -> {
                OperationResult result = gateway().cancelMatchmaking(sessionId);
                return coordinator.completeMatchmakingCancellation(token, result);
            }, token);
        });
    }

    /**
     * Starts one complete authoritative fleet submission without blocking the caller.
     *
     * @param placements complete local fleet request
     * @return future completed with the newest reconciled client state
     */
    public CompletableFuture<ClientState> submitFleet(List<ShipPlacement> placements) {
        return startCommand(() -> {
            List<ShipPlacement> request = List.copyOf(
                    Objects.requireNonNull(placements, "placements"));
            UUID sessionId = requireSessionId();
            OperationToken token = coordinator.beginFleetSubmission();
            return submitRemote(() -> {
                FleetSubmissionResult result = gateway().submitFleet(sessionId, request);
                return coordinator.completeFleetSubmission(token, result);
            }, token);
        });
    }

    /**
     * Starts one server-authoritative shot without blocking the caller or predicting its outcome.
     * Local phase, turn, and duplicate-target rejection is returned as an exceptional future.
     *
     * @param coordinate requested target coordinate
     * @return future completed with the newest reconciled authoritative client state
     */
    public CompletableFuture<ClientState> fire(Coordinate coordinate) {
        return startCommand(() -> {
            Coordinate request = Objects.requireNonNull(coordinate, "coordinate");
            UUID sessionId = requireSessionId();
            OperationToken token = coordinator.beginFire(request);
            return submitRemote(() -> {
                ShotResult result = gateway().fire(sessionId, request);
                return coordinator.completeFire(token, result);
            }, token);
        });
    }

    /**
     * Starts one authoritative personal-statistics and history-page read on the remote executor.
     * The result is reconciled only into the active session-bound dashboard generation.
     *
     * @param historyOffset zero-based database history offset
     * @param historyLimit requested page size from 1 through the protocol maximum
     * @return future completed with the newest dashboard state
     */
    public CompletableFuture<StatisticsDashboardState> loadPlayerStatistics(int historyOffset,
                                                                            int historyLimit) {
        return startCommand(() -> {
            if (historyOffset < 0 || historyLimit <= 0
                    || historyLimit > FleetLinkServerRemote.MAX_HISTORY_LIMIT) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("History pagination is out of range"));
            }
            UUID sessionId = requireSessionId();
            StatisticsOperationToken token = coordinator.beginPlayerStatisticsLoad();
            return submitStatisticsRemote(() -> {
                PlayerStatisticsResult result = gateway().getPlayerStatistics(
                        sessionId, historyOffset, historyLimit);
                return coordinator.completePlayerStatistics(token, result);
            }, token);
        });
    }

    /**
     * Starts one authoritative leaderboard read on the remote executor without local reordering.
     *
     * @param limit requested row limit from 1 through the protocol maximum
     * @return future completed with the newest dashboard state
     */
    public CompletableFuture<StatisticsDashboardState> loadLeaderboard(int limit) {
        return startCommand(() -> {
            if (limit <= 0 || limit > FleetLinkServerRemote.MAX_LEADERBOARD_LIMIT) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Leaderboard limit is out of range"));
            }
            UUID sessionId = requireSessionId();
            StatisticsOperationToken token = coordinator.beginLeaderboardLoad();
            return submitStatisticsRemote(() -> {
                LeaderboardResult result = gateway().getLeaderboard(sessionId, limit);
                return coordinator.completeLeaderboard(token, result);
            }, token);
        });
    }

    /**
     * Starts authoritative resignation without blocking the caller.
     *
     * @return future completed with Lobby after success or the newest reconciled state
     */
    public CompletableFuture<ClientState> leaveGame() {
        return startCommand(() -> {
            UUID sessionId = requireSessionId();
            OperationToken token = coordinator.beginLeaveGame();
            return submitLeaveRemote(() -> {
                OperationResult result = gateway().leaveGame(sessionId);
                return coordinator.completeLeaveGame(token, result);
            }, token);
        });
    }

    /**
     * Starts one positive rematch request on the existing single remote executor.
     * Local Game Over identity and duplicate-action checks run before remote work is queued.
     *
     * @return future completed with the newest reconciled client state
     */
    public CompletableFuture<ClientState> requestRematch() {
        return startCommand(() -> {
            RematchOperationToken token = coordinator.beginRematchRequest();
            return submitRematchRemote(() -> {
                OperationResult result = gateway().requestRematch(token.getSessionId());
                return coordinator.completeRematchOperation(token, result);
            }, token);
        });
    }

    /**
     * Starts acceptance or decline of the current authoritative incoming rematch request.
     *
     * @param accept true to accept and false to decline
     * @return future completed with the newest reconciled client state
     */
    public CompletableFuture<ClientState> respondToRematch(boolean accept) {
        return startCommand(() -> {
            RematchOperationToken token = coordinator.beginRematchResponse(accept);
            return submitRematchRemote(() -> {
                OperationResult result = gateway().respondToRematch(
                        token.getSessionId(), accept);
                return coordinator.completeRematchOperation(token, result);
            }, token);
        });
    }

    /**
     * Atomically leaves a completed result screen, then performs only the selected best-effort
     * false response on the remote executor. Local Lobby is committed before remote uncertainty.
     *
     * @return future containing the already committed Lobby state after optional cleanup attempt
     */
    public CompletableFuture<ClientState> returnToLobby() {
        return startCommand(() -> {
            ReturnToLobbyPlan plan = coordinator.beginReturnToLobby();
            if (plan.getAction() != ClientStateCoordinator.ReturnToLobbyAction.SEND_FALSE) {
                return CompletableFuture.completedFuture(plan.getLobbyState());
            }
            CompletableFuture<ClientState> future = new CompletableFuture<>();
            remoteExecutor.execute(() -> {
                try {
                    gateway().respondToRematch(plan.getSessionId(), false);
                } catch (Exception ignored) {
                    // The old Game Over scope is already destroyed, so transport is best effort.
                }
                future.complete(plan.getLobbyState());
            });
            return future;
        });
    }

    /**
     * Starts explicit authoritative logout and releases the callback gateway after terminal
     * session reconciliation.
     *
     * @return future completed with Login after success or the newest reconciled state
     */
    public CompletableFuture<ClientState> logout() {
        return startCommand(() -> {
            UUID sessionId = requireSessionId();
            OperationToken token = coordinator.beginLogout();
            return submitRemote(() -> {
                OperationResult result = gateway().logout(sessionId);
                LogoutReconciliation reconciliation = coordinator.completeLogout(token, result);
                if (reconciliation.isTerminal()) {
                    closeGateway();
                }
                return reconciliation.getState();
            }, token);
        });
    }

    /**
     * Stops new commands immediately, performs best-effort logout on the remote executor, and then
     * unexports the callback. Transport failure never prevents local cleanup or future completion.
     *
     * @return future completed after callback cleanup and executor shutdown are initiated
     */
    public CompletableFuture<Void> shutdownGracefully() {
        if (!closed.compareAndSet(false, true)) {
            return shutdownFuture;
        }
        try {
            remoteExecutor.execute(this::performGracefulShutdown);
        } catch (RuntimeException exception) {
            closeGateway();
            remoteExecutor.shutdown();
            shutdownFuture.complete(null);
        }
        return shutdownFuture;
    }

    /**
     * Stops queued remote work and releases an already-open callback gateway.
     * This local cleanup does not perform server logout; graceful logout is added with shutdown flow.
     */
    @Override
    public void close() {
        shutdownGracefully();
    }

    /**
     * Converts local validation, lifecycle, and phase races into exceptional futures.
     * This keeps stale screen actions from throwing synchronously through JavaFX event handlers.
     *
     * @param starter local command setup that returns the queued remote operation future
     * @return the command future, or an exceptional future when local setup rejects the command
     */
    private <T> CompletableFuture<T> startCommand(
            Supplier<CompletableFuture<T>> starter) {
        try {
            if (closed.get()) {
                throw new IllegalStateException("FleetLink client operations are shutting down");
            }
            return starter.get();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /**
     * Submits one checked statistics read and reconciles transport failure into only its dashboard
     * slice. The gameplay and navigation state is never changed by this path.
     *
     * @param operation checked statistics read executed on the remote executor
     * @param token session-bound dashboard token used for result reconciliation
     * @return future completed with the newest dashboard state
     */
    private CompletableFuture<StatisticsDashboardState> submitStatisticsRemote(
            StatisticsRemoteOperation operation, StatisticsOperationToken token) {
        CompletableFuture<StatisticsDashboardState> future = new CompletableFuture<>();
        remoteExecutor.execute(() -> {
            try {
                future.complete(operation.run());
            } catch (Exception exception) {
                future.complete(coordinator.failStatisticsOperation(
                        token, failureMessage(exception)));
            }
        });
        return future;
    }

    /**
     * Submits one checked remote task and converts transport failure into reconciled client state.
     *
     * @param operation checked operation executed on the remote executor
     * @param token operation token used if remote work fails
     * @return future completed after result or failure reconciliation
     */
    private CompletableFuture<ClientState> submitRemote(RemoteOperation operation,
                                                        OperationToken token) {
        CompletableFuture<ClientState> future = new CompletableFuture<>();
        remoteExecutor.execute(() -> {
            try {
                future.complete(operation.run());
            } catch (Exception exception) {
                future.complete(coordinator.failOperation(token, failureMessage(exception)));
            }
        });
        return future;
    }

    /**
     * Submits one leave mutation and uses its dedicated callback-aware transport reconciliation.
     *
     * @param operation checked leave operation executed on the remote executor
     * @param token exact leave generation and source phase
     * @return future completed with the newest client state
     */
    private CompletableFuture<ClientState> submitLeaveRemote(RemoteOperation operation,
                                                             OperationToken token) {
        CompletableFuture<ClientState> future = new CompletableFuture<>();
        remoteExecutor.execute(() -> {
            try {
                future.complete(operation.run());
            } catch (Exception exception) {
                future.complete(coordinator.failLeaveGame(token, failureMessage(exception)));
            }
        });
        return future;
    }

    /**
     * Submits one rematch mutation and reconciles transport uncertainty through its dedicated
     * identity token instead of the ordinary binary callback-epoch rule.
     *
     * @param operation checked rematch operation executed on the remote executor
     * @param token exact rematch lifecycle token
     * @return future completed with the newest client state
     */
    private CompletableFuture<ClientState> submitRematchRemote(
            RemoteOperation operation, RematchOperationToken token) {
        CompletableFuture<ClientState> future = new CompletableFuture<>();
        remoteExecutor.execute(() -> {
            try {
                future.complete(operation.run());
            } catch (Exception exception) {
                future.complete(coordinator.failRematchOperation(
                        token, failureMessage(exception)));
            }
        });
        return future;
    }

    /**
     * Lazily opens the gateway on the remote executor and reuses it for the client session.
     *
     * @return open remote gateway
     * @throws Exception if registry lookup or callback export fails
     */
    private ClientRemoteGateway gateway() throws Exception {
        synchronized (gatewayLock) {
            if (gateway == null) {
                gateway = gatewayFactory.open();
            }
            return gateway;
        }
    }

    /**
     * Releases the gateway on the remote executor so callback cleanup never blocks the UI caller.
     */
    private void closeGateway() {
        synchronized (gatewayLock) {
            if (gateway != null) {
                gateway.close();
                gateway = null;
            }
        }
    }

    /**
     * Performs best-effort server logout before callback cleanup on the remote executor.
     * Any transport failure is intentionally ignored because shutdown must still complete.
     */
    private void performGracefulShutdown() {
        try {
            ClientRemoteGateway currentGateway;
            synchronized (gatewayLock) {
                currentGateway = gateway;
            }
            if (currentGateway != null && coordinator.getState().getSessionInfo() != null) {
                currentGateway.logout(coordinator.getState().getSessionInfo().getSessionId());
            }
        } catch (Exception ignored) {
            // Shutdown remains best effort when the server or transport is unavailable.
        } finally {
            closeGateway();
            remoteExecutor.shutdown();
            shutdownFuture.complete(null);
        }
    }

    /**
     * Resolves the established session before a session-based operation is queued.
     *
     * @return current opaque session identifier
     * @throws IllegalStateException if no session exists
     */
    private UUID requireSessionId() {
        if (coordinator.getState().getSessionInfo() == null) {
            throw new IllegalStateException("No FleetLink session is established");
        }
        return coordinator.getState().getSessionInfo().getSessionId();
    }

    /**
     * Creates the daemon executor that keeps every potentially blocking remote call off JavaFX.
     *
     * @return single-threaded remote executor
     */
    private static ExecutorService createRemoteExecutor() {
        return Executors.newSingleThreadExecutor(action -> {
            Thread thread = new Thread(action, "fleetlink-rmi-client");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Converts a transport or lookup exception into stable player-facing status text.
     *
     * @param exception remote operation failure
     * @return non-blank transport failure message
     */
    private static String failureMessage(Exception exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            return "FleetLink server connection failed.";
        }
        return "FleetLink server connection failed: " + detail;
    }

    /**
     * Represents one checked operation executed only on the remote executor.
     */
    @FunctionalInterface
    private interface RemoteOperation {

        /**
         * Performs the potentially blocking operation and reconciles its result.
         *
         * @return newest client state
         * @throws Exception if remote setup or invocation fails
         */
        ClientState run() throws Exception;
    }

    /** Represents one checked statistics operation executed only on the remote executor. */
    @FunctionalInterface
    private interface StatisticsRemoteOperation {

        /**
         * Performs one potentially blocking statistics read and reconciles its result.
         *
         * @return newest statistics dashboard state
         * @throws Exception if remote setup or invocation fails
         */
        StatisticsDashboardState run() throws Exception;
    }
}
