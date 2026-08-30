package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Stores a defensive 10x10 snapshot of discovered opponent-board information.
 * Its cell type cannot represent an undiscovered opponent ship.
 */
public final class OpponentBoardView implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final OpponentCellView[][] cells;

    /**
     * Creates an immutable-shape opponent snapshot from a complete 10x10 array.
     *
     * @param cells the complete discovered opponent-board states
     * @throws NullPointerException if the outer array is null
     * @throws IllegalArgumentException if the array is not 10x10 or contains a null row or cell
     */
    public OpponentBoardView(OpponentCellView[][] cells) {
        this.cells = copyAndValidate(cells);
    }

    /**
     * Returns one discovered opponent-board cell from the immutable snapshot.
     *
     * @param coordinate the validated coordinate to read
     * @return the discovered state at the coordinate
     * @throws NullPointerException if the coordinate is null
     */
    public OpponentCellView getCell(Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        return cells[coordinate.getRow()][coordinate.getColumn()];
    }

    /**
     * Returns a deep copy suitable for presentation code that needs the complete board.
     *
     * @return a defensive 10x10 copy of discovered opponent states
     */
    public OpponentCellView[][] toArray() {
        return copyCells(cells);
    }

    /**
     * Validates board dimensions and cells while taking an initial defensive copy.
     *
     * @param source the supplied opponent-board array
     * @return a validated deep copy
     * @throws NullPointerException if the outer array is null
     * @throws IllegalArgumentException if the board shape or any cell is invalid
     */
    private static OpponentCellView[][] copyAndValidate(OpponentCellView[][] source) {
        Objects.requireNonNull(source, "cells");
        if (source.length != Coordinate.BOARD_SIZE) {
            throw new IllegalArgumentException("opponent board must contain 10 rows");
        }
        OpponentCellView[][] copy =
                new OpponentCellView[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
        for (int row = 0; row < Coordinate.BOARD_SIZE; row++) {
            if (source[row] == null || source[row].length != Coordinate.BOARD_SIZE) {
                throw new IllegalArgumentException("each opponent board row must contain 10 cells");
            }
            for (int column = 0; column < Coordinate.BOARD_SIZE; column++) {
                if (source[row][column] == null) {
                    throw new IllegalArgumentException("opponent board cells must not be null");
                }
                copy[row][column] = source[row][column];
            }
        }
        return copy;
    }

    /**
     * Deep-copies a board that already satisfies the constructor invariant.
     *
     * @param source the validated opponent-board array
     * @return a deep copy of every row
     */
    private static OpponentCellView[][] copyCells(OpponentCellView[][] source) {
        OpponentCellView[][] copy =
                new OpponentCellView[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
        for (int row = 0; row < Coordinate.BOARD_SIZE; row++) {
            System.arraycopy(source[row], 0, copy[row], 0, Coordinate.BOARD_SIZE);
        }
        return copy;
    }
}
