package io.github.tomerg12.fleetlink.client.ui;

import java.util.Objects;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;

/**
 * Converts the shared zero-based board coordinate into FleetLink display labels.
 */
public final class BoardLabels {

    /**
     * Prevents construction because this type exposes only board-label helpers.
     */
    private BoardLabels() {
    }

    /**
     * Converts a shared coordinate into a user-facing label such as A1 or J10.
     *
     * @param coordinate validated shared board coordinate
     * @return user-facing board label
     */
    public static String displayLabel(Coordinate coordinate) {
        Coordinate validated = Objects.requireNonNull(coordinate, "coordinate");
        return columnLabel(validated.getColumn()) + (validated.getRow() + 1);
    }

    /**
     * Converts a zero-based board column used by the board renderer into A-J.
     *
     * @param column zero-based column from the shared board range
     * @return uppercase column label
     */
    static String columnLabel(int column) {
        return Character.toString('A' + column);
    }
}
