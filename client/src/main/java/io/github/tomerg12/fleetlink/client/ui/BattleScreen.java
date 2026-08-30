package io.github.tomerg12.fleetlink.client.ui;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import io.github.tomerg12.fleetlink.client.integration.ClientOperationService;
import io.github.tomerg12.fleetlink.client.integration.ClientPhase;
import io.github.tomerg12.fleetlink.client.integration.ClientState;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentCellView;
import io.github.tomerg12.fleetlink.shared.protocol.OwnCellView;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Renders authoritative Battle snapshots, countdown, and timeout strikes while delegating every
 * game action to the asynchronous remote boundary.
 */
public final class BattleScreen {
    private final BattlePresentationModel model;
    private final Function<Coordinate, CompletableFuture<ClientState>> fireCommand;
    private final Supplier<CompletableFuture<ClientState>> leaveCommand;
    private final String stateStatus;

    /** Creates the metadata-safe waiting shell used before integrated state is available. */
    public BattleScreen() {
        this(new BattlePresentationModel(), ignored -> CompletableFuture.failedFuture(
                        new IllegalStateException("No Battle operation service is available")),
                () -> CompletableFuture.failedFuture(
                        new IllegalStateException("No Battle operation service is available")), "");
    }

    /**
     * Creates an interactive Battle screen from latest reconciled state.
     *
     * @param state reconciled state with authoritative game snapshot
     * @param operations asynchronous remote operation boundary
     * @throws NullPointerException if state or operations is null
     * @throws IllegalArgumentException if state has no authoritative game snapshot
     */
    public BattleScreen(ClientState state, ClientOperationService operations) {
        this(new BattlePresentationModel(), Objects.requireNonNull(operations, "operations")::fire,
                operations::leaveGame, Objects.requireNonNull(state, "state").getStatusMessage());
        if (state.getGameView() == null) {
            throw new IllegalArgumentException("Battle state requires an authoritative game view");
        }
        model.applyGameView(state.getGameView());
        model.setOperationPending(state.getPhase() == ClientPhase.FIRING
                || state.getPhase() == ClientPhase.LEAVING_GAME);
    }

    /**
     * Creates a battle screen with injectable command boundaries for focused tests.
     *
     * @param model battle presentation model
     * @param fireCommand asynchronous fire command
     * @param leaveCommand asynchronous leave command
     * @param stateStatus reconciled status text
     */
    BattleScreen(BattlePresentationModel model,
                 Function<Coordinate, CompletableFuture<ClientState>> fireCommand,
                 Supplier<CompletableFuture<ClientState>> leaveCommand,
                 String stateStatus) {
        this.model = Objects.requireNonNull(model, "model");
        this.fireCommand = Objects.requireNonNull(fireCommand, "fireCommand");
        this.leaveCommand = Objects.requireNonNull(leaveCommand, "leaveCommand");
        this.stateStatus = Objects.requireNonNull(stateStatus, "stateStatus");
    }

    /**
     * Returns the navigation identifier for this screen type.
     *
     * @return battle screen identifier
     */
    public static ScreenId screenId() {
        return ScreenId.BATTLE;
    }

    /**
     * Builds the complete authoritative Battle presentation and local interaction handlers.
     *
     * @return fully constructed battle screen
     */
    public Parent createView() {
        Label title = new Label("BATTLE");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("SELECT A TARGET WHEN IT IS YOUR TURN");
        subtitle.getStyleClass().add("mono-caption");
        Label statusDetail = new Label(statusDetail());
        statusDetail.getStyleClass().add("muted-text");
        Label statusChip = UiComponents.statusChip(statusLabel(), statusStyle());
        VBox statusBox = new VBox(8, statusChip, statusDetail);

        if (model.getGameView().isPresent()) {
            GameView gameView = model.getGameView().orElseThrow();
            Label countdownCaption = new Label("TURN DEADLINE");
            countdownCaption.getStyleClass().add("mono-caption");
            Label countdown = new Label();
            countdown.getStyleClass().add("section-title");
            DeadlineCountdown.bind(countdown, gameView.getDeadlineEpochMillis());
            Label strikes = new Label("TIMEOUT STRIKES: " + gameView.getYourTimeoutStrikes()
                    + " / 2   OPPONENT: " + gameView.getOpponentTimeoutStrikes() + " / 2");
            strikes.getStyleClass().add("mono-caption");
            statusBox.getChildren().addAll(countdownCaption, countdown, strikes);
            if (gameView.getYourTimeoutStrikes() == 1) {
                Label warning = new Label("WARNING: YOUR NEXT TIMEOUT LOSES THE GAME.");
                warning.getStyleClass().add("error-text");
                statusBox.getChildren().add(warning);
            }
        }

        BattleshipBoardView ownBoard = new BattleshipBoardView("YOUR FLEET");
        ownBoard.setInteractive(false);
        BattleshipBoardView enemyBoard = new BattleshipBoardView("ENEMY WATERS");
        renderAuthoritativeBoards(ownBoard, enemyBoard);
        enemyBoard.setInteractive(model.isTargetingEnabled());
        Label target = new Label("TARGET: NONE");
        target.getStyleClass().add("mono-caption");
        Button fire = UiComponents.primaryButton("FIRE", () -> { });
        fire.setOnAction(event -> submitFire(enemyBoard, target, statusDetail, fire));
        fire.setDisable(true);
        enemyBoard.setCellAction(coordinate -> {
            if (model.selectTarget(coordinate)) {
                renderOpponentBoard(enemyBoard);
                enemyBoard.setCellState(coordinate, BattleshipBoardView.CellState.SELECTED);
                target.setText("TARGET: " + BoardLabels.displayLabel(coordinate));
                fire.setDisable(!model.canFire());
            }
        });

        HBox boards = new HBox(28, ownBoard, enemyBoard);
        boards.setAlignment(Pos.CENTER);
        HBox.setHgrow(ownBoard, Priority.ALWAYS);
        HBox.setHgrow(enemyBoard, Priority.ALWAYS);
        Label logTitle = new Label("AUTHORITATIVE UPDATE");
        logTitle.getStyleClass().add("section-title");
        Label logDetail = new Label(stateStatus.isBlank()
                ? "Waiting for the next server update." : stateStatus);
        logDetail.getStyleClass().add("muted-text");
        logDetail.setWrapText(true);
        Button forfeit = UiComponents.dangerButton(forfeitLabel(), () -> { });
        forfeit.setOnAction(event -> submitLeave(enemyBoard, fire, forfeit, statusDetail));
        forfeit.setDisable(model.getGameView().isEmpty()
                || model.getTurnPresentation() == BattlePresentationModel.TurnPresentation.FINISHED
                || model.isOperationPending());
        VBox actions = UiComponents.surfaceCard(12, logTitle, logDetail, target, fire, forfeit);
        actions.setPrefWidth(250);
        HBox workspace = new HBox(28, boards, actions);
        HBox.setHgrow(boards, Priority.ALWAYS);
        VBox content = new VBox(20, new VBox(4, title, subtitle), statusBox, workspace);
        content.setPadding(new Insets(28));
        BorderPane body = new BorderPane(content);
        body.getStyleClass().add("battle-layout");
        return UiComponents.applicationShell(body);
    }

    /**
     * Sends the selected target through the asynchronous command boundary and performs no local
     * hit, turn, deadline, strike, or winner inference.
     *
     * @param enemyBoard opponent board disabled while the request is pending
     * @param target selected-target label
     * @param status authoritative-operation status label
     * @param fire fire button disabled while the request is pending
     */
    private void submitFire(BattleshipBoardView enemyBoard, Label target, Label status,
                            Button fire) {
        if (!model.canFire()) {
            return;
        }
        Coordinate coordinate = model.getSelectedTarget().orElseThrow();
        model.setOperationPending(true);
        enemyBoard.setInteractive(false);
        fire.setDisable(true);
        target.setText("TARGET SUBMITTED: " + BoardLabels.displayLabel(coordinate));
        status.setText("Waiting for the authoritative shot result...");
        fireCommand.apply(coordinate).whenComplete((state, failure) -> {
            if (failure != null) {
                Platform.runLater(() -> {
                    model.setOperationPending(false);
                    renderOpponentBoard(enemyBoard);
                    enemyBoard.setInteractive(model.isTargetingEnabled());
                    target.setText("TARGET: NONE");
                    fire.setDisable(true);
                    status.setText("This Battle action is no longer valid. Select another target.");
                });
            }
        });
    }

    /**
     * Sends a forfeit through the asynchronous leave boundary and disables duplicate actions.
     *
     * @param enemyBoard opponent board disabled during the leave request
     * @param fire fire button disabled during the leave request
     * @param forfeit forfeit button disabled during the leave request
     * @param status operation status label
     */
    private void submitLeave(BattleshipBoardView enemyBoard, Button fire, Button forfeit,
                             Label status) {
        if (model.isOperationPending()) {
            return;
        }
        model.setOperationPending(true);
        enemyBoard.setInteractive(false);
        fire.setDisable(true);
        forfeit.setDisable(true);
        status.setText("Waiting for the server to process the forfeit...");
        leaveCommand.get().whenComplete((state, failure) -> {
            if (failure != null) {
                Platform.runLater(() -> status.setText("This forfeit action is no longer valid."));
            }
        });
    }

    /**
     * Returns the exact player-facing voluntary departure label.
     *
     * @return Battle forfeit label
     */
    static String forfeitLabel() {
        return "FORFEIT";
    }

    /**
     * Derives the status-chip text from the current presentation model.
     *
     * @return status-chip label
     */
    private String statusLabel() {
        if (model.isOperationPending()) {
            return "SERVER ACTION PENDING";
        }
        return switch (model.getTurnPresentation()) {
            case WAITING_FOR_STATE -> "WAITING FOR GAME STATE";
            case YOUR_TURN -> "YOUR TURN";
            case OPPONENT_TURN -> "OPPONENT TURN";
            case FINISHED -> "GAME FINISHED";
        };
    }

    /**
     * Selects the visual status class from local control enablement only.
     *
     * @return status style class
     */
    private String statusStyle() {
        return model.isTargetingEnabled() ? "status-ready" : "status-waiting";
    }

    /**
     * Builds the player-facing status detail from the authoritative presentation model.
     *
     * @return current status detail
     */
    private String statusDetail() {
        if (model.isOperationPending()) {
            return "Waiting for an authoritative server response.";
        }
        if (model.getGameView().isEmpty()) {
            return "Waiting for current game state.";
        }
        GameView gameView = model.getGameView().orElseThrow();
        return switch (model.getTurnPresentation()) {
            case YOUR_TURN -> "Select a target against "
                    + gameView.getOpponent().getDisplayName() + ".";
            case OPPONENT_TURN -> "Waiting for "
                    + gameView.getOpponent().getDisplayName() + ".";
            case FINISHED -> "The server marked this game as complete.";
            case WAITING_FOR_STATE -> "Waiting for battle to begin.";
        };
    }

    /**
     * Renders both board components solely from the current authoritative `GameView`.
     *
     * @param ownBoard receiving player's board component
     * @param enemyBoard opponent discovery board component
     */
    private void renderAuthoritativeBoards(BattleshipBoardView ownBoard,
                                           BattleshipBoardView enemyBoard) {
        if (model.getGameView().isEmpty()) {
            return;
        }
        GameView gameView = model.getGameView().orElseThrow();
        for (int row = 0; row < Coordinate.BOARD_SIZE; row++) {
            for (int column = 0; column < Coordinate.BOARD_SIZE; column++) {
                Coordinate coordinate = new Coordinate(row, column);
                ownBoard.setCellState(coordinate,
                        ownCellState(gameView.getOwnBoard().getCell(coordinate)));
            }
        }
        renderOpponentBoard(enemyBoard);
    }

    /**
     * Renders the opponent discovery board from the current authoritative snapshot.
     *
     * @param enemyBoard opponent board component receiving safe cell states
     */
    private void renderOpponentBoard(BattleshipBoardView enemyBoard) {
        if (model.getGameView().isEmpty()) {
            return;
        }
        GameView gameView = model.getGameView().orElseThrow();
        for (int row = 0; row < Coordinate.BOARD_SIZE; row++) {
            for (int column = 0; column < Coordinate.BOARD_SIZE; column++) {
                Coordinate coordinate = new Coordinate(row, column);
                enemyBoard.setCellState(coordinate,
                        opponentCellState(gameView.getOpponentBoard().getCell(coordinate)));
            }
        }
    }

    /**
     * Maps one authoritative own-board protocol value to the reusable board component state.
     *
     * @param cell authoritative own-board cell
     * @return board component state
     */
    static BattleshipBoardView.CellState ownCellState(OwnCellView cell) {
        return switch (cell) {
            case WATER -> BattleshipBoardView.CellState.EMPTY;
            case SHIP -> BattleshipBoardView.CellState.OCCUPIED;
            case MISS -> BattleshipBoardView.CellState.MISS;
            case HIT -> BattleshipBoardView.CellState.HIT;
        };
    }

    /**
     * Maps one authoritative opponent-board protocol value to the reusable board component state.
     *
     * @param cell authoritative discovery-only opponent cell
     * @return board component state
     */
    static BattleshipBoardView.CellState opponentCellState(OpponentCellView cell) {
        return switch (cell) {
            case UNKNOWN -> BattleshipBoardView.CellState.EMPTY;
            case MISS -> BattleshipBoardView.CellState.MISS;
            case HIT -> BattleshipBoardView.CellState.HIT;
        };
    }
}
