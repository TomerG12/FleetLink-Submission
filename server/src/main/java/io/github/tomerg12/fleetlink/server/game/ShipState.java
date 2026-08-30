package io.github.tomerg12.fleetlink.server.game;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.ShipType;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Stores the authoritative in-memory state of one placed ship.
 * The ship owns a fixed set of board coordinates and records only hits on those coordinates.
 */
final class ShipState {

    private final ShipType shipType;
    private final List<Coordinate> occupiedCells;
    private final Set<Coordinate> hitCells = new HashSet<>();

    /**
     * Creates a ship from the exact cells validated for one placement.
     *
     * @param shipType the shared ship type whose fixed length must match the supplied cells
     * @param occupiedCells the unique board coordinates occupied by the ship
     * @throws NullPointerException if the ship type or cell list is null
     * @throws IllegalArgumentException if a cell is null, duplicated, or the count is incorrect
     */
    ShipState(ShipType shipType, List<Coordinate> occupiedCells) {
        this.shipType = Objects.requireNonNull(shipType, "shipType");
        Objects.requireNonNull(occupiedCells, "occupiedCells");
        if (occupiedCells.size() != shipType.getLength()) {
            throw new IllegalArgumentException("ship cell count must match ship length");
        }
        Set<Coordinate> uniqueCells = new HashSet<>();
        for (Coordinate coordinate : occupiedCells) {
            if (coordinate == null) {
                throw new IllegalArgumentException("ship cells must not contain null");
            }
            if (!uniqueCells.add(coordinate)) {
                throw new IllegalArgumentException("ship cells must be unique");
            }
        }
        this.occupiedCells = List.copyOf(occupiedCells);
    }

    /**
     * Returns the fixed shared type of this ship.
     *
     * @return the ship type
     */
    ShipType getShipType() {
        return shipType;
    }

    /**
     * Returns the immutable list of board cells occupied by this ship.
     *
     * @return the occupied coordinates
     */
    List<Coordinate> getOccupiedCells() {
        return occupiedCells;
    }

    /**
     * Checks whether this ship occupies one board coordinate.
     *
     * @param coordinate the coordinate to check
     * @return true when the coordinate belongs to this ship
     */
    boolean occupies(Coordinate coordinate) {
        return occupiedCells.contains(coordinate);
    }

    /**
     * Records a hit on one occupied coordinate.
     * Duplicate recording is harmless because hit state is set based.
     *
     * @param coordinate the occupied coordinate that was targeted
     * @throws IllegalArgumentException if the coordinate is not part of this ship
     */
    void recordHit(Coordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        if (!occupies(coordinate)) {
            throw new IllegalArgumentException("hit coordinate does not belong to ship");
        }
        hitCells.add(coordinate);
    }

    /**
     * Reports whether every occupied cell has been hit.
     *
     * @return true when the ship is sunk
     */
    boolean isSunk() {
        return hitCells.size() == occupiedCells.size();
    }
}
