package io.github.tomerg12.fleetlink.shared.protocol;

/**
 * Describes the direction in which a submitted ship extends from its start coordinate.
 */
public enum Orientation {
    /** The ship extends across increasing column indexes. */
    HORIZONTAL,

    /** The ship extends across increasing row indexes. */
    VERTICAL
}
