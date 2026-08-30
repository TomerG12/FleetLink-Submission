package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Identifies one cell on the fixed FleetLink board using zero-based indexes.
 * The UI converts columns A-J and rows 1-10 to this transport representation.
 */
public final class Coordinate implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The number of rows and columns on a FleetLink board. */
    public static final int BOARD_SIZE = 10;

    private final int row;
    private final int column;

    /**
     * Creates a validated board coordinate.
     *
     * @param row the zero-based row index from 0 through 9
     * @param column the zero-based column index from 0 through 9
     * @throws IllegalArgumentException if either index is outside the board
     */
    public Coordinate(int row, int column) {
        validateIndex(row, "row");
        validateIndex(column, "column");
        this.row = row;
        this.column = column;
    }

    /**
     * Returns the zero-based row index.
     *
     * @return the row from 0 through 9
     */
    public int getRow() {
        return row;
    }

    /**
     * Returns the zero-based column index.
     *
     * @return the column from 0 through 9
     */
    public int getColumn() {
        return column;
    }

    /**
     * Compares coordinates by their row and column indexes.
     *
     * @param other the object to compare with this coordinate
     * @return true when both coordinates identify the same cell
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Coordinate coordinate)) {
            return false;
        }
        return row == coordinate.row && column == coordinate.column;
    }

    /**
     * Computes a hash from the row and column used by equality.
     *
     * @return the coordinate hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(row, column);
    }

    /**
     * Validates one zero-based board index before it becomes transport state.
     *
     * @param index the row or column index to validate
     * @param name the index name used in the failure message
     * @throws IllegalArgumentException if the index is outside the board
     */
    private static void validateIndex(int index, String name) {
        if (index < 0 || index >= BOARD_SIZE) {
            throw new IllegalArgumentException(name + " must be between 0 and 9");
        }
    }
}
