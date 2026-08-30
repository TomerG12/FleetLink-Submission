package io.github.tomerg12.fleetlink.client.ui;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.tomerg12.fleetlink.client.integration.ClientOperationService;
import io.github.tomerg12.fleetlink.client.integration.ClientPhase;
import io.github.tomerg12.fleetlink.client.integration.ClientState;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator;
import io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Builds the Lobby, owns its bounded statistics-preview lifecycle, and preserves deterministic
 * local matchmaking presentation.
 */
public final class LobbyScreen {
    static final int PREVIEW_HISTORY_OFFSET = 0;
    static final int PREVIEW_HISTORY_LIMIT = 3;
    static final int PREVIEW_LEADERBOARD_LIMIT = 5;

    private final ScreenNavigator navigator;
    private final LobbyPresentationModel model;
    private final ClientOperationService operations;
    private final ClientStateCoordinator coordinator;
    private final PlayerView player;
    private final boolean registered;
    private final Label rating = metricValue();
    private final Label games = metricValue();
    private final Label wins = metricValue();
    private final Label winRate = metricValue();
    private final Label personalStatus = previewStatus();
    private final Label leaderboardStatus = previewStatus();
    private final VBox recentRows = new VBox(0);
    private final VBox leaderboardRows = new VBox(0);
    private ClientStateCoordinator.DashboardSubscription subscription;

    /**
     * Creates the Lobby over the established session and existing dashboard reconciliation owner.
     *
     * @param navigator client screen navigator
     * @param operations asynchronous client operations
     * @param coordinator session-bound dashboard state owner
     * @param initialState reconciled state used only for safe session identity
     * @throws IllegalArgumentException if no established session exists
     */
    public LobbyScreen(ScreenNavigator navigator, ClientOperationService operations,
                       ClientStateCoordinator coordinator, ClientState initialState) {
        this(navigator, new LobbyPresentationModel(), operations, coordinator, initialState);
    }

    /**
     * Creates the Lobby with an injectable local matchmaking model.
     *
     * @param navigator client screen navigator
     * @param model local matchmaking presentation model
     * @param operations asynchronous client operations
     * @param coordinator session-bound dashboard state owner
     * @param initialState reconciled state used only for safe session identity
     * @throws IllegalArgumentException if no established session exists
     */
    LobbyScreen(ScreenNavigator navigator, LobbyPresentationModel model,
                ClientOperationService operations, ClientStateCoordinator coordinator,
                ClientState initialState) {
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.model = Objects.requireNonNull(model, "model");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        Objects.requireNonNull(initialState, "initialState");
        if (initialState.getSessionInfo() == null) {
            throw new IllegalArgumentException("Lobby requires an established session");
        }
        this.player = initialState.getSessionInfo().getPlayer();
        this.registered = !player.isGuest();
    }

    /**
     * Returns the navigation identifier represented by this screen.
     *
     * @return Lobby screen identifier
     */
    public static ScreenId screenId() {
        return ScreenId.LOBBY;
    }

    /**
     * Creates the stable Lobby shell before any preview request is activated.
     *
     * @return fully constructed Lobby shell
     */
    public Parent createView() {
        VBox sidebar = UiComponents.dashboardSidebar(navigator, ScreenId.LOBBY,
                player.getDisplayName(), player.isGuest(), this::logout);

        Label title = new Label("FLEETLINK LOBBY");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("GLOBAL MULTIPLAYER SESSION");
        subtitle.getStyleClass().add("mono-caption");
        VBox heading = new VBox(4, title, subtitle);

        VBox summary = createSummaryCard();
        VBox matchmaking = new VBox();
        matchmaking.getStyleClass().add("matchmaking-card");
        matchmaking.setAlignment(Pos.CENTER);
        matchmaking.setPadding(new Insets(32));
        renderMatchmakingState(matchmaking);
        HBox.setHgrow(matchmaking, Priority.ALWAYS);

        HBox topGrid = new HBox(20, summary, matchmaking);
        topGrid.setAlignment(Pos.CENTER);
        VBox recent = createPreviewCard("RECENT MATCHES", recentRows, personalStatus);
        VBox leaders = createPreviewCard("TOP PLAYERS", leaderboardRows, leaderboardStatus);
        HBox.setHgrow(recent, Priority.ALWAYS);
        HBox.setHgrow(leaders, Priority.ALWAYS);
        HBox previews = new HBox(20, recent, leaders);

        Button fullStatistics = UiComponents.secondaryButton(
                "VIEW FULL STATISTICS", () -> navigator.navigate(fullStatisticsDestination()));
        VBox content = new VBox(24, heading, topGrid, previews, fullStatistics);
        content.setPadding(new Insets(32));
        VBox.setVgrow(previews, Priority.ALWAYS);

        applyPreviewState(null);
        BorderPane body = new BorderPane();
        body.setLeft(sidebar);
        body.setCenter(content);
        body.getStyleClass().add("lobby-layout");
        return UiComponents.applicationShell(body);
    }

    /**
     * Activates a fresh session-bound preview after the stable Lobby shell is attached. Registered
     * sessions request personal offset 0, limit 3; every session requests leaderboard limit 5.
     */
    public void activate() {
        if (subscription != null) {
            throw new IllegalStateException("Lobby preview is already active");
        }
        subscription = activatePreview(coordinator, registered, this::applyPreviewState,
                operations::loadPlayerStatistics, operations::loadLeaderboard);
    }

    /**
     * Activates bounded preview reads through injectable operation functions for deterministic
     * lifecycle and guest-request tests.
     *
     * @param coordinator session-bound dashboard state owner
     * @param registered whether personal statistics are available for this session
     * @param listener preview state consumer
     * @param personalLoader personal read function receiving offset and limit
     * @param leaderboardLoader leaderboard read function receiving limit
     * @return subscription that invalidates this activation on close
     */
    static ClientStateCoordinator.DashboardSubscription activatePreview(
            ClientStateCoordinator coordinator, boolean registered,
            Consumer<StatisticsDashboardState> listener,
            BiFunction<Integer, Integer, CompletableFuture<StatisticsDashboardState>> personalLoader,
            Function<Integer, CompletableFuture<StatisticsDashboardState>> leaderboardLoader) {
        ClientStateCoordinator.DashboardSubscription activated =
                coordinator.activateStatisticsDashboard(listener);
        if (registered) {
            personalLoader.apply(PREVIEW_HISTORY_OFFSET, PREVIEW_HISTORY_LIMIT);
        }
        leaderboardLoader.apply(PREVIEW_LEADERBOARD_LIMIT);
        return activated;
    }

    /**
     * Returns the existing full Statistics destination used by the Lobby action.
     *
     * @return player statistics screen identifier
     */
    static ScreenId fullStatisticsDestination() {
        return ScreenId.PLAYER_STATISTICS;
    }

    /** Detaches the Lobby listener and invalidates every read owned by this activation. */
    public void deactivate() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }

    /**
     * Creates the compact live player-summary card.
     *
     * @return player summary card
     */
    private VBox createSummaryCard() {
        Label title = new Label("PLAYER SUMMARY");
        title.getStyleClass().add("section-title");
        Label identity = new Label(player.getDisplayName());
        identity.getStyleClass().add("muted-text");
        GridPane metrics = new GridPane();
        metrics.setHgap(10);
        metrics.setVgap(10);
        metrics.add(metricCard("RATING", rating), 0, 0);
        metrics.add(metricCard("GAMES", games), 1, 0);
        metrics.add(metricCard("WINS", wins), 0, 1);
        metrics.add(metricCard("WIN RATE", winRate), 1, 1);
        VBox card = UiComponents.surfaceCard(12, title, identity, metrics);
        card.setPrefWidth(390);
        return card;
    }

    /**
     * Creates one compact metric card around a retained live value label.
     *
     * @param title metric caption
     * @param value retained value label
     * @return compact metric card
     */
    private static VBox metricCard(String title, Label value) {
        Label caption = new Label(title);
        caption.getStyleClass().add("metric-label");
        VBox card = new VBox(5, caption, value);
        card.getStyleClass().add("metric-card");
        return card;
    }

    /**
     * Creates one compact preview card with retained row and status containers.
     *
     * @param title card heading
     * @param rows server-ordered row container
     * @param status neutral loading, empty, or failure copy
     * @return compact preview card
     */
    private static VBox createPreviewCard(String title, VBox rows, Label status) {
        Label heading = new Label(title);
        heading.getStyleClass().add("section-title");
        return UiComponents.surfaceCard(12, heading, rows, status);
    }

    /**
     * Applies one immutable dashboard revision to preview copy and server-ordered rows.
     *
     * @param state current dashboard state, or null before activation
     */
    private void applyPreviewState(StatisticsDashboardState state) {
        rating.setText(LobbyPresentationModel.ratingText(registered, state));
        games.setText(LobbyPresentationModel.gamesText(registered, state));
        wins.setText(LobbyPresentationModel.winsText(registered, state));
        winRate.setText(LobbyPresentationModel.winRateText(registered, state));
        renderRows(recentRows, LobbyPresentationModel.recentMatchRows(registered, state));
        renderRows(leaderboardRows, LobbyPresentationModel.leaderboardRows(state));
        applyStatus(personalStatus,
                LobbyPresentationModel.personalStatusText(registered, state));
        applyStatus(leaderboardStatus,
                LobbyPresentationModel.leaderboardStatusText(state));
    }

    /**
     * Updates a preview status and removes blank successful copy from layout.
     *
     * @param label retained preview status label
     * @param text selected status text
     */
    private static void applyStatus(Label label, String text) {
        label.setText(text);
        label.setVisible(!text.isBlank());
        label.setManaged(label.isVisible());
    }

    /**
     * Renders compact rows in their supplied authoritative order.
     *
     * @param container row container to replace
     * @param rows immutable formatted rows
     */
    private static void renderRows(VBox container, List<String> rows) {
        container.getChildren().clear();
        for (String row : rows) {
            Label label = new Label(row);
            label.getStyleClass().add("data-row");
            label.setMaxWidth(Double.MAX_VALUE);
            container.getChildren().add(label);
        }
    }

    /**
     * Creates a retained metric value label.
     *
     * @return styled placeholder label
     */
    private static Label metricValue() {
        Label value = new Label("--");
        value.getStyleClass().add("metric-value");
        return value;
    }

    /**
     * Creates a retained preview status label.
     *
     * @return styled wrapping status label
     */
    private static Label previewStatus() {
        Label status = new Label();
        status.getStyleClass().add("muted-text");
        status.setWrapText(true);
        return status;
    }

    /**
     * Renders the local idle or searching state without simulating server progress.
     *
     * @param container matchmaking presentation container
     */
    private void renderMatchmakingState(VBox container) {
        container.getChildren().setAll(createMatchmakingStateNodes(container));
    }

    /**
     * Creates controls for the current deterministic matchmaking presentation state.
     *
     * @param container container refreshed after local actions
     * @return state-specific nodes
     */
    private Node[] createMatchmakingStateNodes(VBox container) {
        if (model.getMatchmakingState() == LobbyPresentationModel.MatchmakingState.SEARCHING) {
            Label chip = UiComponents.statusChip("WAITING", "status-waiting");
            Label title = new Label("LOOKING FOR OPPONENTS");
            title.getStyleClass().add("section-title");
            Label detail = new Label(model.getDetailMessage());
            detail.getStyleClass().add("muted-text");
            Button cancel = UiComponents.secondaryButton("CANCEL SEARCH",
                    () -> cancelMatchmaking(container));
            return new Node[] {chip, title, detail, cancel};
        }

        Label chip = UiComponents.statusChip("READY", "status-ready");
        Label title = new Label("READY FOR MATCHMAKING");
        title.getStyleClass().add("section-title");
        Label detail = new Label(model.getDetailMessage());
        detail.getStyleClass().add("muted-text");
        Button start = UiComponents.primaryButton("START MATCHMAKING",
                () -> startMatchmaking(container));
        return new Node[] {chip, title, detail, start};
    }

    /**
     * Enters local pending state before queuing callback-driven matchmaking.
     *
     * @param container matchmaking presentation container
     */
    private void startMatchmaking(VBox container) {
        model.startSearching();
        renderMatchmakingState(container);
        operations.joinMatchmaking().whenComplete((state, failure) ->
                Platform.runLater(() -> {
                    if (failure != null) {
                        model.showFailure("Matchmaking could not be started.");
                        renderMatchmakingState(container);
                    } else if (state.getPhase() == ClientPhase.LOBBY) {
                        model.showFailure(state.getStatusMessage());
                        renderMatchmakingState(container);
                    }
                }));
    }

    /**
     * Starts authoritative matchmaking cancellation and renders its reconciled result.
     *
     * @param container matchmaking presentation container
     */
    private void cancelMatchmaking(VBox container) {
        operations.cancelMatchmaking().whenComplete((state, failure) ->
                Platform.runLater(() -> {
                    if (failure != null) {
                        model.showFailure("Matchmaking cancellation could not be started.");
                        renderMatchmakingState(container);
                    } else if (state.getPhase() == ClientPhase.LOBBY) {
                        model.cancelSearching();
                        renderMatchmakingState(container);
                    } else if (state.getPhase() == ClientPhase.MATCHMAKING
                            && !state.getStatusMessage().isBlank()) {
                        model.showSearchingMessage(state.getStatusMessage());
                        renderMatchmakingState(container);
                    }
                }));
    }

    /** Starts explicit asynchronous logout and lets reconciled state drive Login navigation. */
    private void logout() {
        operations.logout();
    }
}
