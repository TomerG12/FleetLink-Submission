package io.github.tomerg12.fleetlink.shared.protocol;

/**
 * Describes only information discovered on an opponent board.
 * This type deliberately has no value for an undiscovered ship segment.
 */
public enum OpponentCellView {
    /** The receiving player has not discovered the contents of this cell. */
    UNKNOWN,

    /** The receiving player fired at this cell and missed. */
    MISS,

    /** The receiving player fired at this cell and hit an opponent ship. */
    HIT
}
