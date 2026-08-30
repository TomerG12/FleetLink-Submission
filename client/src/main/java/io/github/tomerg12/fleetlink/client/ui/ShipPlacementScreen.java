package io.github.tomerg12.fleetlink.client.ui;

import java.util.Objects;

import io.github.tomerg12.fleetlink.client.integration.ClientOperationService;
import io.github.tomerg12.fleetlink.client.integration.ClientPhase;
import io.github.tomerg12.fleetlink.client.integration.ClientState;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.ShipType;
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
 * Builds the local fleet-arrangement shell while displaying the authoritative placement deadline.
 */
public final class ShipPlacementScreen {
    private final ShipPlacementPresentationModel model;
    private final ClientOperationService operations;
    private final long deadlineEpochMillis;
    private final ClientPhase initialClientPhase;
    private final GamePhase initialGamePhase;

    /**
     * Creates the metadata-safe placement shell without an authoritative deadline.
     *
     * @param operations asynchronous client operations
     * @throws NullPointerException if operations is null
     */
    public ShipPlacementScreen(ClientOperationService operations) {
        this(new ShipPlacementPresentationModel(), operations, 0L,
                ClientPhase.SHIP_PLACEMENT, GamePhase.FLEET_PLACEMENT);
    }

    /**
     * Creates placement from the latest reconciled authoritative game state.
     *
     * @param state reconciled state containing the current game snapshot
     * @param operations asynchronous client operations
     * @throws NullPointerException if state or operations is null
     * @throws IllegalArgumentException if the state has no game snapshot
     */
    public ShipPlacementScreen(ClientState state, ClientOperationService operations) {
        this(new ShipPlacementPresentationModel(), operations, requireGameView(state),
                Objects.requireNonNull(state, "state").getPhase());
    }

    /**
     * Creates placement from one already validated authoritative game snapshot.
     *
     * @param model local arrangement model
     * @param operations asynchronous client operations
     * @param gameView authoritative placement snapshot
     * @param clientPhase reconciled placement lifecycle phase
     */
    private ShipPlacementScreen(ShipPlacementPresentationModel model,
                                ClientOperationService operations, GameView gameView,
                                ClientPhase clientPhase) {
        this(model, operations, gameView.getDeadlineEpochMillis(), clientPhase,
                gameView.getPhase());
    }

    /**
     * Creates a screen with injectable local model for focused presentation tests.
     *
     * @param model local arrangement model
     * @param operations asynchronous client operations
     * @param deadlineEpochMillis authoritative placement deadline, or zero in metadata-only tests
     * @param clientPhase reconciled placement lifecycle phase
     * @param gamePhase authoritative placement game phase
     * @throws NullPointerException if model or operations is null
     */
    ShipPlacementScreen(ShipPlacementPresentationModel model,
                        ClientOperationService operations, long deadlineEpochMillis,
                        ClientPhase clientPhase, GamePhase gamePhase) {
        this.model = Objects.requireNonNull(model, "model");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.deadlineEpochMillis = deadlineEpochMillis;
        this.initialClientPhase = Objects.requireNonNull(clientPhase, "clientPhase");
        this.initialGamePhase = Objects.requireNonNull(gamePhase, "gamePhase");
    }

    /**
     * Returns the navigation identifier for this screen type.
     *
     * @return ship-placement screen identifier
     */
    public static ScreenId screenId() {
        return ScreenId.SHIP_PLACEMENT;
    }

    /**
     * Creates the complete ship-placement graph and local arrangement interactions.
     *
     * @return fully constructed ship-placement shell
     */
    public Parent createView() {
        Label title = new Label("PLACE YOUR FLEET");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("PLACE ALL SHIPS BEFORE STARTING");
        subtitle.getStyleClass().add("mono-caption");
        Label countdown = new Label();
        countdown.getStyleClass().add("section-title");
        DeadlineCountdown.bind(countdown, deadlineEpochMillis);
        VBox heading = new VBox(4, title, subtitle,
                new Label("PLACEMENT DEADLINE"), countdown);

        ShipPlacementControlState initialControls = controlState(
                initialClientPhase, initialGamePhase);
        Label status = new Label(presentationMessage(initialControls.getPresentation()));
        status.getStyleClass().add("muted-text");
        status.setWrapText(true);
        BattleshipBoardView board = new BattleshipBoardView("YOUR FLEET");
        board.setInteractive(true);
        Label selected = new Label();
        selected.getStyleClass().add("mono-caption");
        Label orientation = new Label();
        orientation.getStyleClass().add("mono-caption");
        Button ready = UiComponents.primaryButton("CONFIRM FLEET", () -> { });
        Button forfeit = UiComponents.dangerButton(forfeitLabel(), () -> { });

        VBox shipButtons = new VBox(10);
        for (ShipType shipType : ShipType.values()) {
            Button button = UiComponents.secondaryButton(shipLabel(shipType), () -> {
                model.selectShip(shipType);
                refreshSelection(selected, orientation);
                renderBoard(board);
            });
            shipButtons.getChildren().add(button);
        }
        Button rotate = UiComponents.secondaryButton("ROTATE SHIP", () -> {
            model.rotateSelected();
            refreshSelection(selected, orientation);
        });
        Button clear = UiComponents.secondaryButton("CLEAR BOARD", () -> {
            model.clearPlacements();
            status.setText("All local ship placements were cleared.");
            status.getStyleClass().remove("error-text");
            renderBoard(board);
            ready.setDisable(!controlState(ClientPhase.SHIP_PLACEMENT,
                    GamePhase.FLEET_PLACEMENT).isConfirmEnabled());
        });
        board.setCellAction(coordinate -> {
            if (model.placeSelected(coordinate)) {
                status.setText(model.getSelectedShip().name() + " placed at "
                        + BoardLabels.displayLabel(coordinate) + ".");
                status.getStyleClass().remove("error-text");
            } else {
                status.setText("That local placement would leave the board or overlap another ship.");
                if (!status.getStyleClass().contains("error-text")) {
                    status.getStyleClass().add("error-text");
                }
            }
            renderBoard(board);
            ready.setDisable(!controlState(ClientPhase.SHIP_PLACEMENT,
                    GamePhase.FLEET_PLACEMENT).isConfirmEnabled());
        });

        Label shipsTitle = new Label("AVAILABLE SHIPS");
        shipsTitle.getStyleClass().add("section-title");
        VBox editingControls = new VBox(14, selected, orientation, shipButtons, rotate, clear, ready);
        VBox controls = UiComponents.surfaceCard(14, shipsTitle, editingControls, forfeit);
        controls.setPrefWidth(300);
        ready.setOnAction(event -> submitFleet(
                board, status, editingControls, ready, forfeit));
        forfeit.setOnAction(event -> submitForfeit(
                board, status, editingControls, ready, forfeit));
        refreshSelection(selected, orientation);
        renderBoard(board);
        applyControlState(initialControls, board, editingControls, ready, forfeit);

        VBox boardColumn = new VBox(18, board, status);
        boardColumn.setAlignment(Pos.CENTER);
        HBox.setHgrow(boardColumn, Priority.ALWAYS);
        HBox workspace = new HBox(28, boardColumn, controls);
        workspace.setAlignment(Pos.CENTER);
        VBox content = new VBox(24, heading, workspace);
        content.setPadding(new Insets(32));
        BorderPane body = new BorderPane(content);
        body.getStyleClass().add("placement-layout");
        return UiComponents.applicationShell(body);
    }

    /**
     * Submits the complete locally arranged fleet through the asynchronous operation boundary.
     * UI controls are disabled while the authoritative result is pending and restored only for a
     * recoverable placement-state result.
     *
     * @param board local arrangement board
     * @param status status label for pending or failure feedback
     * @param editingControls placement controls disabled during submission
     * @param ready confirm button whose enabled state follows local completeness
     * @param forfeit voluntary forfeit action disabled during submission
     */
    private void submitFleet(BattleshipBoardView board, Label status,
                             VBox editingControls, Button ready, Button forfeit) {
        applyControlState(controlState(ClientPhase.SUBMITTING_FLEET,
                GamePhase.FLEET_PLACEMENT), board, editingControls, ready, forfeit);
        status.getStyleClass().remove("error-text");
        status.setText("Submitting fleet...");
        operations.submitFleet(model.createFleetSubmission()).whenComplete((state, failure) ->
                Platform.runLater(() -> {
                    if (failure != null) {
                        restoreAfterFailure(board, status, editingControls, ready, forfeit,
                                "Fleet submission could not be started.");
                    } else if (state.getPhase() == ClientPhase.SHIP_PLACEMENT) {
                        restoreAfterFailure(board, status, editingControls, ready, forfeit,
                                state.getStatusMessage());
                    } else if (state.getPhase() == ClientPhase.WAITING_FOR_BATTLE) {
                        applyControlState(controlState(state), board, editingControls,
                                ready, forfeit);
                        status.setText("Fleet accepted. Waiting for the opponent.");
                    }
                }));
    }

    /**
     * Starts a voluntary placement forfeit through the shared asynchronous leave operation and
     * leaves every terminal outcome decision to authoritative callbacks and results.
     *
     * @param board local board disabled while the forfeit is pending
     * @param status player-facing pending or failure text
     * @param editingControls local arrangement controls
     * @param ready fleet confirmation action
     * @param forfeit voluntary forfeit action
     */
    private void submitForfeit(BattleshipBoardView board, Label status,
                               VBox editingControls, Button ready, Button forfeit) {
        applyControlState(controlState(ClientPhase.LEAVING_GAME,
                GamePhase.FLEET_PLACEMENT), board, editingControls, ready, forfeit);
        status.getStyleClass().remove("error-text");
        status.setText("Waiting for the server to process the forfeit...");
        operations.leaveGame().whenComplete((state, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                restoreAfterFailure(board, status, editingControls, ready, forfeit,
                        "This forfeit action is no longer valid.");
            } else if (state.getGameView() != null
                    && state.getGameView().getPhase() == GamePhase.FLEET_PLACEMENT
                    && (state.getPhase() == ClientPhase.SHIP_PLACEMENT
                    || state.getPhase() == ClientPhase.WAITING_FOR_BATTLE)) {
                applyControlState(controlState(state), board, editingControls, ready, forfeit);
                status.setText(state.getStatusMessage());
                if (!status.getStyleClass().contains("error-text")) {
                    status.getStyleClass().add("error-text");
                }
            }
        }));
    }

    /**
     * Restores local editing after an authoritative or transport failure leaves placement active.
     *
     * @param board placement board to make interactive again
     * @param status status label receiving failure detail
     * @param editingControls placement controls to re-enable
     * @param ready confirm button to recalculate from local completeness
     * @param forfeit voluntary forfeit action to restore
     * @param message player-facing failure message
     */
    private void restoreAfterFailure(BattleshipBoardView board, Label status,
                                     VBox editingControls, Button ready, Button forfeit,
                                     String message) {
        applyControlState(controlState(ClientPhase.SHIP_PLACEMENT,
                GamePhase.FLEET_PLACEMENT), board, editingControls, ready, forfeit);
        status.setText(message);
        if (!status.getStyleClass().contains("error-text")) {
            status.getStyleClass().add("error-text");
        }
    }

    /**
     * Builds the product-facing ship label from shared immutable ship metadata.
     *
     * @param shipType shared ship type
     * @return product-facing label for the ship type
     */
    static String shipLabel(ShipType shipType) {
        return shipType.name().replace('_', ' ') + " - " + shipType.getLength() + " CELLS";
    }

    /**
     * Returns the exact product-facing voluntary departure label.
     *
     * @return placement forfeit label
     */
    static String forfeitLabel() {
        return "FORFEIT";
    }

    /**
     * Derives current immutable control state using local completeness only as an input.
     *
     * @param clientPhase reconciled client lifecycle phase
     * @param gamePhase authoritative game phase
     * @return placement control state
     */
    private ShipPlacementControlState controlState(ClientPhase clientPhase, GamePhase gamePhase) {
        return ShipPlacementControlState.evaluate(clientPhase, gamePhase, model.isFleetComplete());
    }

    /**
     * Derives control state from one reconciled placement client snapshot.
     *
     * @param state newest reconciled state
     * @return placement control state
     */
    private ShipPlacementControlState controlState(ClientState state) {
        return controlState(state.getPhase(), requireGameView(state).getPhase());
    }

    /**
     * Applies immutable lifecycle decisions to every placement mutation control.
     *
     * @param state derived placement control state
     * @param board local fleet board
     * @param editingControls arrangement controls
     * @param ready fleet confirmation action
     * @param forfeit voluntary forfeit action
     */
    private static void applyControlState(ShipPlacementControlState state,
                                          BattleshipBoardView board, VBox editingControls,
                                          Button ready, Button forfeit) {
        board.setInteractive(state.isEditingEnabled());
        editingControls.setDisable(!state.isEditingEnabled());
        ready.setDisable(!state.isConfirmEnabled());
        forfeit.setDisable(!state.isForfeitEnabled());
    }

    /**
     * Selects concise player-facing copy for the immutable placement presentation category.
     *
     * @param presentation placement lifecycle presentation
     * @return player-facing status text
     */
    private static String presentationMessage(ShipPlacementControlState.Presentation presentation) {
        return switch (presentation) {
            case PLACING -> "Select a ship, choose a starting cell, and arrange the complete fleet.";
            case SUBMISSION_PENDING -> "Submitting fleet...";
            case WAITING_FOR_OPPONENT -> "Fleet accepted. Waiting for the opponent.";
            case FORFEIT_PENDING -> "Waiting for the server to process the forfeit...";
        };
    }

    /**
     * Refreshes labels describing the currently selected ship and local orientation.
     *
     * @param selected label receiving selected ship text
     * @param orientation label receiving orientation text
     */
    private void refreshSelection(Label selected, Label orientation) {
        selected.setText("SELECTED: " + model.getSelectedShip().name());
        orientation.setText("ORIENTATION: " + model.getOrientation().name());
    }

    /**
     * Re-renders local placement cells from the presentation model without affecting server state.
     *
     * @param board local Battleship board component
     */
    private void renderBoard(BattleshipBoardView board) {
        board.clearCellStates();
        for (ShipType shipType : ShipType.values()) {
            BattleshipBoardView.CellState state = shipType == model.getSelectedShip()
                    ? BattleshipBoardView.CellState.SELECTED
                    : BattleshipBoardView.CellState.OCCUPIED;
            for (Coordinate coordinate : model.placementFor(shipType)) {
                board.setCellState(coordinate, state);
            }
        }
    }

    /**
     * Extracts the authoritative game snapshot required by an integrated placement screen.
     *
     * @param state reconciled client state
     * @return authoritative current game snapshot
     * @throws NullPointerException if state is null
     * @throws IllegalArgumentException if state has no current game snapshot
     */
    private static GameView requireGameView(ClientState state) {
        Objects.requireNonNull(state, "state");
        if (state.getGameView() == null) {
            throw new IllegalArgumentException("Ship Placement requires an authoritative game view");
        }
        return state.getGameView();
    }
}
