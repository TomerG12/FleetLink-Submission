package io.github.tomerg12.fleetlink.shared.protocol;

/**
 * Identifies a ship and its fixed length without embedding fleet validation in the protocol.
 */
public enum ShipType {
    /** The carrier ship. */
    CARRIER(5),

    /** The battleship. */
    BATTLESHIP(4),

    /** The cruiser ship. */
    CRUISER(3),

    /** The submarine ship. */
    SUBMARINE(3),

    /** The destroyer ship. */
    DESTROYER(2);

    /** The fixed number of board cells occupied by this ship type. */
    private final int length;

    /**
     * Creates a ship type with its shared immutable board length.
     *
     * @param length the fixed number of board cells occupied by the ship
     */
    ShipType(int length) {
        this.length = length;
    }

    /**
     * Returns the fixed number of board cells occupied by this ship type.
     *
     * @return the ship length in board cells
     */
    public int getLength() {
        return length;
    }
}
