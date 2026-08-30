package io.github.tomerg12.fleetlink.server.game;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.ShotOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stores one player's authoritative fixed-size Battleship board in memory.
 * GameSession provides the synchronization boundary around board mutations.
 */
final class Board {

    private final ShipState[][] shipAt =
            new ShipState[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
    private final boolean[][] firedAt =
            new boolean[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
    private final List<ShipState> ships = new ArrayList<>();

    /**
     * Creates an empty 10x10 board.
     */
    Board() {
    }

    /**
     * Commits a complete already-validated fleet atomically to an empty board.
     * This method defensively rechecks overlap before mutating the board so a programming error in
     * a caller cannot leave partial fleet state behind.
     *
     * @param fleet the complete fleet to place
     * @throws NullPointerException if the fleet is null
     * @throws IllegalStateException if a fleet was already committed
     * @throws IllegalArgumentException if the supplied fleet contains null or overlapping ships
     */
    void commitFleet(List<ShipState> fleet) {
        Objects.requireNonNull(fleet, "fleet");
        if (!ships.isEmpty()) {
            throw new IllegalStateException("board already contains a fleet");
        }

        boolean[][] occupied = new boolean[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
        for (ShipState ship : fleet) {
            if (ship == null) {
                throw new IllegalArgumentException("fleet must not contain null ships");
            }
            for (Coordinate coordinate : ship.getOccupiedCells()) {
                int row = coordinate.getRow();
                int column = coordinate.getColumn();
                if (occupied[row][column]) {
                    throw new IllegalArgumentException("fleet ships overlap");
                }
                occupied[row][column] = true;
            }
        }

        for (ShipState ship : fleet) {
            ships.add(ship);
            for (Coordinate coordinate : ship.getOccupiedCells()) {
                shipAt[coordinate.getRow()][coordinate.getColumn()] = ship;
            }
        }
    }

    /**
     * Reports whether a coordinate has already been targeted on this board.
     *
     * @param coordinate the board coordinate to inspect
     * @return true when a shot was already recorded at the coordinate
     */
    boolean wasFiredAt(Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        return firedAt[coordinate.getRow()][coordinate.getColumn()];
    }

    /**
     * Applies one non-duplicate shot and returns its authoritative outcome.
     *
     * @param coordinate the target coordinate
     * @return MISS, HIT, or SUNK according to the target cell and ship state
     * @throws NullPointerException if the coordinate is null
     * @throws IllegalStateException if the target was already fired upon
     */
    ShotOutcome fireAt(Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        int row = coordinate.getRow();
        int column = coordinate.getColumn();
        if (firedAt[row][column]) {
            throw new IllegalStateException("target was already fired upon");
        }

        firedAt[row][column] = true;
        ShipState ship = shipAt[row][column];
        if (ship == null) {
            return ShotOutcome.MISS;
        }
        ship.recordHit(coordinate);
        return ship.isSunk() ? ShotOutcome.SUNK : ShotOutcome.HIT;
    }

    /**
     * Reports whether every ship on this board has sunk.
     * An empty board is not considered defeated.
     *
     * @return true only when the board contains ships and all of them are sunk
     */
    boolean areAllShipsSunk() {
        return !ships.isEmpty() && ships.stream().allMatch(ShipState::isSunk);
    }

    /**
     * Reports whether one cell currently contains a ship segment.
     *
     * @param coordinate the coordinate to inspect
     * @return true when a ship occupies the coordinate
     */
    boolean hasShipAt(Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        return shipAt[coordinate.getRow()][coordinate.getColumn()] != null;
    }
}
