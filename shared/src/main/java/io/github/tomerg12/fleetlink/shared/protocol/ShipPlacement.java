package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * Carries one requested ship position as part of a complete fleet submission.
 * The authoritative server later validates ship lengths, board fit, overlap, and fleet composition.
 */
public final class ShipPlacement implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ShipType shipType;
    private final Coordinate start;
    private final Orientation orientation;

    /**
     * Creates a complete description of one requested ship position.
     *
     * @param shipType the ship being placed
     * @param start the zero-based start coordinate
     * @param orientation the direction from the start coordinate
     * @throws NullPointerException if any argument is null
     */
    public ShipPlacement(ShipType shipType, Coordinate start, Orientation orientation) {
        this.shipType = Objects.requireNonNull(shipType, "shipType");
        this.start = Objects.requireNonNull(start, "start");
        this.orientation = Objects.requireNonNull(orientation, "orientation");
    }

    /**
     * Returns the submitted ship type.
     *
     * @return the ship type
     */
    public ShipType getShipType() {
        return shipType;
    }

    /**
     * Returns the submitted start coordinate.
     *
     * @return the immutable start coordinate
     */
    public Coordinate getStart() {
        return start;
    }

    /**
     * Returns the submitted orientation.
     *
     * @return the ship orientation
     */
    public Orientation getOrientation() {
        return orientation;
    }

    /**
     * Compares placements by ship type, start coordinate, and orientation.
     *
     * @param other the object to compare with this placement
     * @return true when both placements carry the same request
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ShipPlacement placement)) {
            return false;
        }
        return shipType == placement.shipType
                && start.equals(placement.start)
                && orientation == placement.orientation;
    }

    /**
     * Computes a hash from all fields used by equality.
     *
     * @return the placement hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(shipType, start, orientation);
    }
}
