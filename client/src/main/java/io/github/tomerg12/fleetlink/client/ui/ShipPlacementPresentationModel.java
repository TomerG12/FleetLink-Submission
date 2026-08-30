package io.github.tomerg12.fleetlink.client.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.Orientation;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShipType;

/**
 * Holds responsive local fleet-arrangement state without becoming authoritative game state.
 */
public final class ShipPlacementPresentationModel {
    private final Map<ShipType, ShipPlacement> placements = new EnumMap<>(ShipType.class);
    private ShipType selectedShip;
    private Orientation orientation;

    /**
     * Creates an empty arrangement with the carrier selected horizontally.
     */
    public ShipPlacementPresentationModel() {
        selectedShip = ShipType.CARRIER;
        orientation = Orientation.HORIZONTAL;
    }

    /**
     * Selects which shared ship type the next local placement action edits.
     *
     * @param shipType ship selected by the player
     */
    public void selectShip(ShipType shipType) {
        selectedShip = Objects.requireNonNull(shipType, "shipType");
    }

    /**
     * Rotates the currently selected ship for subsequent local placement attempts.
     */
    public void rotateSelected() {
        orientation = orientation == Orientation.HORIZONTAL ? Orientation.VERTICAL : Orientation.HORIZONTAL;
    }

    /**
     * Attempts to place the selected ship from the supplied starting cell.
     * Only board bounds and overlap are checked locally for immediate feedback.
     *
     * @param start local starting cell
     * @return true when the local preview was updated, otherwise false
     */
    public boolean placeSelected(Coordinate start) {
        Objects.requireNonNull(start, "start");
        List<Coordinate> candidate = createCandidate(start);
        if (candidate.isEmpty()) {
            return false;
        }

        Set<Coordinate> occupied = new HashSet<>();
        for (Map.Entry<ShipType, ShipPlacement> entry : placements.entrySet()) {
            if (entry.getKey() != selectedShip) {
                occupied.addAll(coordinatesFor(entry.getValue()));
            }
        }
        for (Coordinate coordinate : candidate) {
            if (occupied.contains(coordinate)) {
                return false;
            }
        }

        placements.put(selectedShip, new ShipPlacement(selectedShip, start, orientation));
        return true;
    }

    /**
     * Removes all local placement previews.
     */
    public void clearPlacements() {
        placements.clear();
    }

    /**
     * Returns whether every shared ship type currently has a local placement preview.
     *
     * @return true when all five ships are locally placed
     */
    public boolean isFleetComplete() {
        return placements.size() == ShipType.values().length;
    }

    /**
     * Returns the ship currently selected for placement.
     *
     * @return selected ship type
     */
    public ShipType getSelectedShip() {
        return selectedShip;
    }

    /**
     * Returns the shared orientation used for the next placement attempt.
     *
     * @return selected orientation
     */
    public Orientation getOrientation() {
        return orientation;
    }

    /**
     * Returns the immutable local cells occupied by one ship preview.
     *
     * @param shipType ship whose preview is requested
     * @return immutable placement cells, or an empty list when not placed
     */
    public List<Coordinate> placementFor(ShipType shipType) {
        Objects.requireNonNull(shipType, "shipType");
        ShipPlacement placement = placements.get(shipType);
        return placement == null ? List.of() : coordinatesFor(placement);
    }

    /**
     * Returns all cells currently occupied by local ship previews.
     *
     * @return immutable set of occupied local cells
     */
    public Set<Coordinate> occupiedCells() {
        Set<Coordinate> occupied = new HashSet<>();
        for (ShipPlacement placement : placements.values()) {
            occupied.addAll(coordinatesFor(placement));
        }
        return Set.copyOf(occupied);
    }

    /**
     * Creates the complete immutable transport request from the current local arrangement.
     *
     * @return one placement for every shared ship type in enum order
     * @throws IllegalStateException if the local fleet is incomplete
     */
    public List<ShipPlacement> createFleetSubmission() {
        if (!isFleetComplete()) {
            throw new IllegalStateException("complete fleet is required for submission");
        }
        List<ShipPlacement> submission = new ArrayList<>(ShipType.values().length);
        for (ShipType shipType : ShipType.values()) {
            submission.add(placements.get(shipType));
        }
        return List.copyOf(submission);
    }

    /**
     * Builds one candidate placement from the current selection and orientation.
     *
     * @param start requested starting cell
     * @return candidate cells, or an empty list when the ship would leave the board
     */
    private List<Coordinate> createCandidate(Coordinate start) {
        List<Coordinate> candidate = new ArrayList<>(selectedShip.getLength());
        for (int offset = 0; offset < selectedShip.getLength(); offset++) {
            int row = start.getRow() + (orientation == Orientation.VERTICAL ? offset : 0);
            int column = start.getColumn() + (orientation == Orientation.HORIZONTAL ? offset : 0);
            if (row >= Coordinate.BOARD_SIZE || column >= Coordinate.BOARD_SIZE) {
                return List.of();
            }
            candidate.add(new Coordinate(row, column));
        }
        return candidate;
    }

    /**
     * Expands one stored transport placement into the cells used by local preview rendering.
     *
     * @param placement stored local transport placement
     * @return immutable occupied coordinates for the placement
     */
    private static List<Coordinate> coordinatesFor(ShipPlacement placement) {
        List<Coordinate> coordinates = new ArrayList<>(placement.getShipType().getLength());
        for (int offset = 0; offset < placement.getShipType().getLength(); offset++) {
            int row = placement.getStart().getRow()
                    + (placement.getOrientation() == Orientation.VERTICAL ? offset : 0);
            int column = placement.getStart().getColumn()
                    + (placement.getOrientation() == Orientation.HORIZONTAL ? offset : 0);
            coordinates.add(new Coordinate(row, column));
        }
        return List.copyOf(coordinates);
    }
}
