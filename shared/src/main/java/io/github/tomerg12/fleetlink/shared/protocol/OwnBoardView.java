package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Stores a defensive 10x10 snapshot of the receiving player's own board.
 */
public final class OwnBoardView implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final OwnCellView[][] cells;

    /**
     * Creates an immutable-shape own-board snapshot from a complete 10x10 array.
     *
     * @param cells the complete own-board cell states
     * @throws NullPointerException if the outer array is null
     * @throws IllegalArgumentException if the array is not 10x10 or contains a null row or cell
     */
    public OwnBoardView(OwnCellView[][] cells) {
        this.cells = copyAndValidate(cells);
    }

    /**
     * Returns one own-board cell from the immutable snapshot.
     *
     * @param coordinate the validated coordinate to read
     * @return the own-board state at the coordinate
     * @throws NullPointerException if the coordinate is null
     */
    public OwnCellView getCell(Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        return cells[coordinate.getRow()][coordinate.getColumn()];
    }

    /**
     * Returns a deep copy suitable for presentation code that needs the complete board.
     *
     * @return a defensive 10x10 copy of the own-board states
     */
    public OwnCellView[][] toArray() {
        return copyCells(cells);
    }

    /**
     * Validates board dimensions and cells while taking an initial defensive copy.
     *
     * @param source the supplied own-board array
     * @return a validated deep copy
     * @throws NullPointerException if the outer array is null
     * @throws IllegalArgumentException if the board shape or any cell is invalid
     */
    private static OwnCellView[][] copyAndValidate(OwnCellView[][] source) {
        Objects.requireNonNull(source, "cells");
        if (source.length != Coordinate.BOARD_SIZE) {
            throw new IllegalArgumentException("own board must contain 10 rows");
        }
        OwnCellView[][] copy = new OwnCellView[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
        for (int row = 0; row < Coordinate.BOARD_SIZE; row++) {
            if (source[row] == null || source[row].length != Coordinate.BOARD_SIZE) {
                throw new IllegalArgumentException("each own board row must contain 10 cells");
            }
            for (int column = 0; column < Coordinate.BOARD_SIZE; column++) {
                if (source[row][column] == null) {
                    throw new IllegalArgumentException("own board cells must not be null");
                }
                copy[row][column] = source[row][column];
            }
        }
        return copy;
    }

    /**
     * Deep-copies a board that already satisfies the constructor invariant.
     *
     * @param source the validated own-board array
     * @return a deep copy of every row
     */
    private static OwnCellView[][] copyCells(OwnCellView[][] source) {
        OwnCellView[][] copy = new OwnCellView[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
        for (int row = 0; row < Coordinate.BOARD_SIZE; row++) {
            System.arraycopy(source[row], 0, copy[row], 0, Coordinate.BOARD_SIZE);
        }
        return copy;
    }
}
