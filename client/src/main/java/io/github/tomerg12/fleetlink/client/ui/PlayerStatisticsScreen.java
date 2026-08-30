package io.github.tomerg12.fleetlink.client.ui;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import io.github.tomerg12.fleetlink.client.integration.ClientOperationService;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator;
import io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState;
import io.github.tomerg12.fleetlink.client.integration.StatisticsDashboardState.LoadStatus;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchHistoryEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Owns the lifecycle and presentation of the asynchronous statistics dashboard. Overview, history,
 * and leaderboard remain internal tabs within the established statistics screen.
 */
public final class PlayerStatisticsScreen {
    static final int HISTORY_PAGE_SIZE = 10;
    static final int LEADERBOARD_LIMIT = 100;
    private static final List<String> SECTION_TITLES =
            List.of("OVERVIEW", "MATCH HISTORY", "LEADERBOARD");
    private static final List<String> HISTORY_COLUMNS = List.of(
            "OPPONENT", "RESULT", "END REASON", "TURNS", "DURATION", "ACCURACY",
            "SUNK", "RATING", "COMPLETED");
    private static final List<String> LEADERBOARD_COLUMNS =
            List.of("RANK", "PLAYER", "RATING", "GAMES", "WINS");

    private final ScreenNavigator navigator;
    private final ClientOperationService operations;
    private final ClientStateCoordinator coordinator;
    private final Label personalStatus = new Label("Statistics have not loaded yet.");
    private final Label leaderboardStatus = new Label("Leaderboard has not loaded yet.");
    private final Label rating = metricValue();
    private final Label games = metricValue();
    private final Label wins = metricValue();
    private final Label losses = metricValue();
    private final Label winRate = metricValue();
    private final Label shipsSunk = metricValue();
    private final Label accuracy = metricValue();
    private final Label averageHits = metricValue();
    private final Label totalShots = metricValue();
    private final Label hits = metricValue();
    private final TableView<MatchHistoryEntryView> historyTable = new TableView<>();
    private final TableView<LeaderboardEntryView> leaderboardTable = new TableView<>();
    private final Button previousPage = new Button("PREVIOUS");
    private final Button nextPage = new Button("NEXT");
    private final Button personalRetry = new Button("RETRY PERSONAL DATA");
    private final Button leaderboardRetry = new Button("RETRY LEADERBOARD");
    private final Button refresh = new Button("REFRESH");
    private ClientStateCoordinator.DashboardSubscription subscription;
    private int historyOffset;

    /**
     * Creates the screen with existing navigation, remote-operation, and state boundaries.
     *
     * @param navigator client screen navigator
     * @param operations asynchronous client operation boundary
     * @param coordinator session-bound dashboard state owner
     */
    public PlayerStatisticsScreen(ScreenNavigator navigator, ClientOperationService operations,
                                  ClientStateCoordinator coordinator) {
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        refresh.setDisable(true);
        previousPage.setDisable(true);
        nextPage.setDisable(true);
        personalRetry.setVisible(false);
        personalRetry.setManaged(false);
        leaderboardRetry.setVisible(false);
        leaderboardRetry.setManaged(false);
    }

    /**
     * Returns the established application navigation identifier represented by this screen.
     *
     * @return player-statistics screen identifier
     */
    public static ScreenId screenId() {
        return ScreenId.PLAYER_STATISTICS;
    }

    /**
     * Returns the internal dashboard sections that reuse the one statistics ScreenId.
     *
     * @return immutable section titles in display order
     */
    static List<String> sectionTitles() {
        return SECTION_TITLES;
    }

    /**
     * Returns the authoritative-data history columns in display order.
     *
     * @return immutable history column titles
     */
    static List<String> historyColumnTitles() {
        return HISTORY_COLUMNS;
    }

    /**
     * Returns the server-ranked leaderboard columns in display order.
     *
     * @return immutable leaderboard column titles
     */
    static List<String> leaderboardColumnTitles() {
        return LEADERBOARD_COLUMNS;
    }

    /**
     * Creates the complete stable dashboard shell before remote loading begins.
     *
     * @return fully constructed statistics scene graph
     */
    public Parent createView() {
        Label title = new Label("PLAYER STATISTICS");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("COMMITTED PERFORMANCE, HISTORY, AND LEADERBOARD");
        subtitle.getStyleClass().add("mono-caption");
        configureActions();

        TabPane sections = new TabPane(
                tab(SECTION_TITLES.get(0), createOverview()),
                tab(SECTION_TITLES.get(1), createHistory()),
                tab(SECTION_TITLES.get(2), createLeaderboard()));
        sections.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        sections.getStyleClass().add("statistics-tabs");
        VBox.setVgrow(sections, Priority.ALWAYS);

        refresh.getStyleClass().add("secondary-button");
        VBox titleBlock = new VBox(4, title, subtitle);
        HBox.setHgrow(titleBlock, Priority.ALWAYS);
        HBox heading = new HBox(18, titleBlock, refresh);
        heading.getStyleClass().add("statistics-heading");
        VBox content = new VBox(20, heading, sections);
        content.setPadding(new Insets(32));
        VBox.setVgrow(content, Priority.ALWAYS);

        BorderPane body = new BorderPane();
        PlayerView player = coordinator.getState().getSessionInfo().getPlayer();
        body.setLeft(UiComponents.dashboardSidebar(
                navigator, ScreenId.PLAYER_STATISTICS, player.getDisplayName(),
                player.isGuest(), this::logout));
        body.setCenter(content);
        body.getStyleClass().add("statistics-layout");
        return UiComponents.applicationShell(body);
    }

    /**
     * Activates a fresh session-bound listener after the stable shell is attached to the stage,
     * then requests personal and leaderboard data independently.
     */
    public void activate() {
        if (subscription != null) {
            throw new IllegalStateException("Statistics screen is already active");
        }
        subscription = coordinator.activateStatisticsDashboard(this::applyState);
        loadPersonal(0);
        loadLeaderboard();
    }

    /** Detaches presentation and invalidates requests submitted by this screen activation. */
    public void deactivate() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }

    /**
     * Creates the overview metric grid and its independent status and retry controls.
     *
     * @return overview content
     */
    private VBox createOverview() {
        GridPane metrics = new GridPane();
        metrics.setHgap(16);
        metrics.setVgap(16);
        addMetric(metrics, 0, 0, "CURRENT RATING", rating);
        addMetric(metrics, 1, 0, "TOTAL GAMES", games);
        addMetric(metrics, 2, 0, "WINS", wins);
        addMetric(metrics, 3, 0, "LOSSES", losses);
        addMetric(metrics, 0, 1, "WIN RATE", winRate);
        addMetric(metrics, 1, 1, "SHIPS SUNK", shipsSunk);
        addMetric(metrics, 2, 1, "ACCURACY", accuracy);
        addMetric(metrics, 3, 1, "AVG HITS / GAME", averageHits);
        addMetric(metrics, 0, 2, "TOTAL SHOTS", totalShots);
        addMetric(metrics, 1, 2, "TOTAL HITS", hits);
        personalStatus.getStyleClass().add("muted-text");
        personalStatus.setWrapText(true);
        personalRetry.getStyleClass().add("secondary-button");
        return new VBox(16, personalStatus, personalRetry, metrics);
    }

    /**
     * Creates the server-paginated history table without client-side sorting.
     *
     * @return history content
     */
    private VBox createHistory() {
        Label emptyHistory = new Label("No completed matches are available.");
        emptyHistory.getStyleClass().add("empty-state-text");
        historyTable.setPlaceholder(emptyHistory);
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        historyTable.getColumns().add(historyColumn(HISTORY_COLUMNS.get(0), entry ->
                StatisticsPresentation.opponent(entry.getOpponentDisplayName(),
                        entry.isOpponentGuest())));
        historyTable.getColumns().add(historyColumn(HISTORY_COLUMNS.get(1), entry ->
                StatisticsPresentation.outcome(entry.getOutcome())));
        historyTable.getColumns().add(historyColumn(HISTORY_COLUMNS.get(2), entry ->
                StatisticsPresentation.endReason(entry.getEndReason())));
        historyTable.getColumns().add(historyColumn(HISTORY_COLUMNS.get(3), entry ->
                Long.toString(entry.getTurnsTaken())));
        historyTable.getColumns().add(historyColumn(HISTORY_COLUMNS.get(4), entry ->
                StatisticsPresentation.duration(entry.getDuration())));
        historyTable.getColumns().add(historyColumn(HISTORY_COLUMNS.get(5), entry ->
                StatisticsPresentation.percentage(entry.getAccuracy())));
        historyTable.getColumns().add(historyColumn(HISTORY_COLUMNS.get(6), entry ->
                Long.toString(entry.getShipsSunk())));
        historyTable.getColumns().add(historyColumn(HISTORY_COLUMNS.get(7), entry ->
                StatisticsPresentation.ratingDelta(entry.getRatingDelta())));
        historyTable.getColumns().add(historyColumn(HISTORY_COLUMNS.get(8), entry ->
                StatisticsPresentation.completedAt(entry.getCompletedAt())));
        VBox.setVgrow(historyTable, Priority.ALWAYS);
        previousPage.getStyleClass().add("secondary-button");
        nextPage.getStyleClass().add("secondary-button");
        return new VBox(12, historyTable, new HBox(10, previousPage, nextPage));
    }

    /**
     * Creates the leaderboard table that preserves the exact server-provided row order.
     *
     * @return leaderboard content
     */
    private VBox createLeaderboard() {
        leaderboardTable.setPlaceholder(new Label("No ranked players are available."));
        leaderboardTable.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        leaderboardTable.getColumns().add(leaderboardColumn(LEADERBOARD_COLUMNS.get(0), entry ->
                Integer.toString(entry.getRank())));
        leaderboardTable.getColumns().add(leaderboardColumn(LEADERBOARD_COLUMNS.get(1),
                LeaderboardEntryView::getUsername));
        leaderboardTable.getColumns().add(leaderboardColumn(LEADERBOARD_COLUMNS.get(2), entry ->
                Integer.toString(entry.getRating())));
        leaderboardTable.getColumns().add(leaderboardColumn(LEADERBOARD_COLUMNS.get(3), entry ->
                Long.toString(entry.getGamesPlayed())));
        leaderboardTable.getColumns().add(leaderboardColumn(LEADERBOARD_COLUMNS.get(4), entry ->
                Long.toString(entry.getWins())));
        VBox.setVgrow(leaderboardTable, Priority.ALWAYS);
        leaderboardStatus.getStyleClass().add("muted-text");
        leaderboardStatus.setWrapText(true);
        leaderboardRetry.getStyleClass().add("secondary-button");
        return new VBox(12, leaderboardStatus, leaderboardRetry, leaderboardTable);
    }

    /** Wires controls only to existing asynchronous operation and navigation boundaries. */
    private void configureActions() {
        refresh.setOnAction(event -> {
            refresh.setDisable(true);
            loadPersonal(historyOffset);
            loadLeaderboard();
        });
        personalRetry.setOnAction(event -> loadPersonal(historyOffset));
        leaderboardRetry.setOnAction(event -> loadLeaderboard());
        previousPage.setOnAction(event -> loadPersonal(
                Math.max(0, historyOffset - HISTORY_PAGE_SIZE)));
        nextPage.setOnAction(event -> loadPersonal(historyOffset + HISTORY_PAGE_SIZE));
    }

    /**
     * Applies the latest reconciled dashboard state on the configured UI dispatcher.
     *
     * @param state newest immutable dashboard snapshot
     */
    private void applyState(StatisticsDashboardState state) {
        StatisticsControlState controls = StatisticsControlState.evaluate(
                state.getPersonalStatus(), state.getPersonalStatistics(),
                state.getLeaderboardStatus(), coordinator.getState().getSessionInfo()
                        .getPlayer().isGuest());
        refresh.setDisable(!controls.isRefreshEnabled());
        personalRetry.setDisable(!controls.isPersonalRetryEnabled());
        personalRetry.setVisible(controls.isPersonalRetryEnabled());
        personalRetry.setManaged(controls.isPersonalRetryEnabled());
        leaderboardRetry.setDisable(!controls.isLeaderboardRetryEnabled());
        leaderboardRetry.setVisible(controls.isLeaderboardRetryEnabled());
        leaderboardRetry.setManaged(controls.isLeaderboardRetryEnabled());
        previousPage.setDisable(!controls.isPreviousEnabled());
        nextPage.setDisable(!controls.isNextEnabled());
        applyPersonal(state);
        applyLeaderboard(state);
    }

    /**
     * Applies only the personal slice while preserving independent leaderboard presentation.
     *
     * @param state newest dashboard snapshot
     */
    private void applyPersonal(StatisticsDashboardState state) {
        PlayerStatisticsView statistics = state.getPersonalStatistics();
        personalStatus.setText(statusText(state.getPersonalStatus(), state.getPersonalMessage(),
                "Personal statistics are current."));
        if (statistics == null) {
            clearPersonalValues();
            return;
        }
        historyOffset = statistics.getHistoryOffset();
        rating.setText(Integer.toString(statistics.getCurrentRating()));
        games.setText(Long.toString(statistics.getTotalGames()));
        wins.setText(Long.toString(statistics.getWins()));
        losses.setText(Long.toString(statistics.getLosses()));
        winRate.setText(StatisticsPresentation.percentage(statistics.getWinRate()));
        shipsSunk.setText(Long.toString(statistics.getShipsSunk()));
        accuracy.setText(StatisticsPresentation.percentage(statistics.getAccuracy()));
        averageHits.setText(StatisticsPresentation.average(
                statistics.getAverageHitsPerGame()));
        totalShots.setText(Long.toString(statistics.getTotalShots()));
        hits.setText(Long.toString(statistics.getHits()));
        historyTable.getItems().setAll(statistics.getHistory());
    }

    /**
     * Applies only the leaderboard slice in authoritative server order.
     *
     * @param state newest dashboard snapshot
     */
    private void applyLeaderboard(StatisticsDashboardState state) {
        leaderboardStatus.setText(statusText(state.getLeaderboardStatus(),
                state.getLeaderboardMessage(), "Leaderboard is current."));
        leaderboardTable.getItems().setAll(state.getLeaderboardEntries());
    }

    /** Restores neutral placeholders when no personal payload is available. */
    private void clearPersonalValues() {
        for (Label value : List.of(rating, games, wins, losses, winRate, shipsSunk,
                accuracy, averageHits, totalShots, hits)) {
            value.setText("--");
        }
        historyTable.getItems().clear();
    }

    /**
     * Selects concise text for every load outcome.
     *
     * @param status current slice status
     * @param message current loading or failure message
     * @param successText text used for success
     * @return player-facing status text
     */
    private static String statusText(LoadStatus status, String message, String successText) {
        return switch (status) {
            case IDLE -> "Waiting to load.";
            case SUCCESS -> successText;
            case LOADING, EXPECTED_FAILURE, TRANSPORT_FAILURE -> message;
        };
    }

    /**
     * Starts a personal page request through the dedicated remote operation service.
     *
     * @param offset zero-based server history offset
     */
    private void loadPersonal(int offset) {
        operations.loadPlayerStatistics(offset, HISTORY_PAGE_SIZE);
    }

    /** Starts a leaderboard request through the dedicated remote operation service. */
    private void loadLeaderboard() {
        operations.loadLeaderboard(LEADERBOARD_LIMIT);
    }

    /** Starts explicit asynchronous logout without navigating ahead of server acceptance. */
    private void logout() {
        operations.logout();
    }

    /**
     * Adds one metric card whose value is updated from authoritative dashboard state.
     *
     * @param grid metric grid
     * @param column grid column
     * @param row grid row
     * @param label metric label
     * @param value mutable metric value label
     */
    private static void addMetric(GridPane grid, int column, int row, String label, Label value) {
        VBox card = UiComponents.surfaceCard(7, metricLabel(label), value);
        card.getStyleClass().add("metric-card");
        card.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(card, Priority.ALWAYS);
        grid.add(card, column, row);
    }

    /**
     * Creates a compact metric caption.
     *
     * @param text caption text
     * @return styled label
     */
    private static Label metricLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("metric-label");
        return label;
    }

    /**
     * Creates a neutral metric value placeholder.
     *
     * @return styled value label
     */
    private static Label metricValue() {
        Label label = new Label("--");
        label.getStyleClass().add("metric-value");
        return label;
    }

    /**
     * Creates a non-closable internal dashboard tab.
     *
     * @param title tab title
     * @param content tab content
     * @return configured tab
     */
    private static Tab tab(String title, Parent content) {
        return new Tab(title, content);
    }

    /**
     * Creates one read-only history text column.
     *
     * @param title column title
     * @param value row-to-text projection
     * @return configured history column
     */
    private static TableColumn<MatchHistoryEntryView, String> historyColumn(
            String title, Function<MatchHistoryEntryView, String> value) {
        TableColumn<MatchHistoryEntryView, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    /**
     * Creates one read-only leaderboard text column.
     *
     * @param title column title
     * @param value row-to-text projection
     * @return configured leaderboard column
     */
    private static TableColumn<LeaderboardEntryView, String> leaderboardColumn(
            String title, Function<LeaderboardEntryView, String> value) {
        TableColumn<LeaderboardEntryView, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }
}
