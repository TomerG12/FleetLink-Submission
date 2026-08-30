package io.github.tomerg12.fleetlink.shared.protocol;

/**
 * Describes a cell on the receiving player's own board, where ship positions are safe to reveal.
 */
public enum OwnCellView {
    /** The cell contains water and has not been targeted. */
    WATER,

    /** The cell contains an untargeted ship segment owned by the receiving player. */
    SHIP,

    /** An opponent targeted this water cell. */
    MISS,

    /** An opponent hit the receiving player's ship at this cell. */
    HIT
}
