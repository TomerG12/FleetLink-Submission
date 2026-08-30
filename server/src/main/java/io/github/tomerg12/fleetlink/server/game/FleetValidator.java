package io.github.tomerg12.fleetlink.server.game;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.Orientation;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShipType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a complete submitted fleet without mutating authoritative board state.
 * Fleet validation covers composition, board boundaries, orientation expansion, and overlap.
 */
final class FleetValidator {

    /**
     * Creates the stateless fleet validator.
     */
    FleetValidator() {
    }

    /**
     * Validates a complete fleet and converts each placement into internal ship state.
     * Adjacent ships are allowed because FleetLink has no no-touch placement rule.
     *
     * @param placements the complete requested fleet
     * @return validated internal ships ready for one atomic board commit
     * @throws IllegalArgumentException if the fleet is incomplete, duplicated, out of bounds,
     *         overlapping, null, or otherwise invalid
     */
    List<ShipState> validate(List<ShipPlacement> placements) {
        if (placements == null) {
            throw new IllegalArgumentException("fleet must not be null");
        }
        if (placements.size() != ShipType.values().length) {
            throw new IllegalArgumentException("fleet must contain exactly one of each ship type");
        }

        EnumSet<ShipType> seenTypes = EnumSet.noneOf(ShipType.class);
        Set<Coordinate> occupied = new HashSet<>();
        List<ShipState> fleet = new ArrayList<>();

        for (ShipPlacement placement : placements) {
            if (placement == null) {
                throw new IllegalArgumentException("fleet must not contain null placements");
            }
            if (!seenTypes.add(placement.getShipType())) {
                throw new IllegalArgumentException("fleet contains a duplicate ship type");
            }

            List<Coordinate> cells = expandPlacement(placement);
            for (Coordinate coordinate : cells) {
                if (!occupied.add(coordinate)) {
                    throw new IllegalArgumentException("fleet ships overlap");
                }
            }
            fleet.add(new ShipState(placement.getShipType(), cells));
        }

        if (seenTypes.size() != ShipType.values().length) {
            throw new IllegalArgumentException("fleet must contain every ship type");
        }
        return List.copyOf(fleet);
    }

    /**
     * Expands one placement into the exact board coordinates occupied by its ship.
     *
     * @param placement the requested ship placement
     * @return the occupied coordinates in start-to-end order
     * @throws IllegalArgumentException if the ship would extend outside the fixed board
     */
    private List<Coordinate> expandPlacement(ShipPlacement placement) {
        List<Coordinate> cells = new ArrayList<>();
        Coordinate start = placement.getStart();
        for (int offset = 0; offset < placement.getShipType().getLength(); offset++) {
            int row = start.getRow();
            int column = start.getColumn();
            if (placement.getOrientation() == Orientation.HORIZONTAL) {
                column += offset;
            } else {
                row += offset;
            }
            if (row < 0 || row >= Coordinate.BOARD_SIZE
                    || column < 0 || column >= Coordinate.BOARD_SIZE) {
                throw new IllegalArgumentException("ship placement leaves the board");
            }
            cells.add(new Coordinate(row, column));
        }
        return cells;
    }
}
