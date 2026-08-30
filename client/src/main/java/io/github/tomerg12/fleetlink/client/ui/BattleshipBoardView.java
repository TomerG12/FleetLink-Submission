package io.github.tomerg12.fleetlink.client.ui;

import java.util.Objects;
import java.util.function.Consumer;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * Reusable JavaFX rendering of a labeled 10x10 Battleship board.
 */
public final class BattleshipBoardView extends VBox {

    /**
     * Visual states supported by reusable board cells.
     */
    public enum CellState {
        /** Empty water cell. */
        EMPTY,

        /** Local player ship occupies the cell. */
        OCCUPIED,

        /** An authoritative hit is displayed at the cell. */
        HIT,

        /** An authoritative miss is displayed at the cell. */
        MISS,

        /** Local target or placement selection highlight. */
        SELECTED
    }

    private final Button[][] cells = new Button[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
    private Consumer<Coordinate> cellAction = coordinate -> { };
    private boolean interactive;

    /**
     * Creates a labeled board using the shared A-J and 1-10 coordinate convention.
     *
     * @param title board heading shown above the grid
     */
    public BattleshipBoardView(String title) {
        Objects.requireNonNull(title, "title");
        setSpacing(10);
        setAlignment(Pos.CENTER);

        Label heading = new Label(title);
        heading.getStyleClass().add("section-title");
        getChildren().addAll(heading, createGrid());
    }

    /**
     * Sets the local callback used when an interactive board cell is activated.
     *
     * @param action cell callback
     */
    public void setCellAction(Consumer<Coordinate> action) {
        cellAction = Objects.requireNonNull(action, "action");
    }

    /**
     * Controls whether board cells accept local mouse or keyboard targeting actions.
     *
     * @param enabled true to allow cell actions
     */
    public void setInteractive(boolean enabled) {
        interactive = enabled;
        for (Button[] row : cells) {
            for (Button cell : row) {
                cell.setMouseTransparent(!enabled);
                cell.setFocusTraversable(enabled);
                updateInteractiveStyle(cell, enabled);
            }
        }
    }

    /**
     * Clears all transient and result styling from board cells.
     */
    public void clearCellStates() {
        for (int row = 0; row < Coordinate.BOARD_SIZE; row++) {
            for (int column = 0; column < Coordinate.BOARD_SIZE; column++) {
                setCellState(new Coordinate(row, column), CellState.EMPTY);
            }
        }
    }

    /**
     * Applies one visual state to a board coordinate.
     *
     * @param coordinate cell to update
     * @param state requested visual state
     */
    public void setCellState(Coordinate coordinate, CellState state) {
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(state, "state");
        Button cell = cells[coordinate.getRow()][coordinate.getColumn()];
        cell.getStyleClass().removeAll("board-cell-occupied", "board-cell-hit", "board-cell-miss", "board-cell-selected");
        String stateClass = cellStyleClass(state);
        if (!stateClass.isEmpty()) {
            cell.getStyleClass().add(stateClass);
        }
    }

    /**
     * Maps one reusable cell state to its design-system CSS class.
     *
     * @param state cell state
     * @return state-specific CSS class, or an empty string for empty cells
     */
    public static String cellStyleClass(CellState state) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case EMPTY -> "";
            case OCCUPIED -> "board-cell-occupied";
            case HIT -> "board-cell-hit";
            case MISS -> "board-cell-miss";
            case SELECTED -> "board-cell-selected";
        };
    }

    /**
     * Builds the coordinate labels and 100 reusable cell buttons.
     *
     * @return complete board grid
     */
    private GridPane createGrid() {
        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.getStyleClass().add("board-grid");

        for (int column = 0; column < Coordinate.BOARD_SIZE; column++) {
            Label label = new Label(BoardLabels.columnLabel(column));
            label.getStyleClass().add("board-axis-label");
            GridPane.setHalignment(label, javafx.geometry.HPos.CENTER);
            grid.add(label, column + 1, 0);
        }

        for (int row = 0; row < Coordinate.BOARD_SIZE; row++) {
            Label label = new Label(Integer.toString(row + 1));
            label.getStyleClass().add("board-axis-label");
            GridPane.setHalignment(label, javafx.geometry.HPos.CENTER);
            grid.add(label, 0, row + 1);

            for (int column = 0; column < Coordinate.BOARD_SIZE; column++) {
                Coordinate coordinate = new Coordinate(row, column);
                Button cell = createCell(coordinate);
                cells[row][column] = cell;
                grid.add(cell, column + 1, row + 1);
            }
        }

        setInteractive(false);
        return grid;
    }

    /**
     * Creates one reusable board cell and binds its action to the current callback.
     *
     * @param coordinate cell coordinate
     * @return configured board cell button
     */
    private Button createCell(Coordinate coordinate) {
        Button cell = new Button();
        cell.setMinSize(32, 32);
        cell.setPrefSize(32, 32);
        cell.setMaxSize(32, 32);
        cell.getStyleClass().add("board-cell");
        cell.setOnAction(event -> {
            if (interactive) {
                cellAction.accept(coordinate);
            }
        });
        return cell;
    }

    /**
     * Keeps the interactive hover style in sync without affecting result-state classes.
     *
     * @param cell cell being updated
     * @param enabled whether interaction is enabled
     */
    private void updateInteractiveStyle(Button cell, boolean enabled) {
        cell.getStyleClass().remove("board-cell-interactive");
        if (enabled) {
            cell.getStyleClass().add("board-cell-interactive");
        }
    }
}
