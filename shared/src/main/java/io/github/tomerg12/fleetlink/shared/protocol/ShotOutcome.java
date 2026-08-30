package io.github.tomerg12.fleetlink.shared.protocol;

/**
 * Summarizes the authoritative outcome of an accepted shot request.
 */
public enum ShotOutcome {
    /** The accepted shot hit water. */
    MISS,

    /** The accepted shot hit a ship that remains afloat. */
    HIT,

    /** The accepted shot sank a ship. */
    SUNK
}
