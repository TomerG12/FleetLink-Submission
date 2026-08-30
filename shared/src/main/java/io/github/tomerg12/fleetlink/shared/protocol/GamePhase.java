package io.github.tomerg12.fleetlink.shared.protocol;

/**
 * Identifies the authoritative high-level phase of a game snapshot.
 */
public enum GamePhase {
    /** Players may submit their complete fleets. */
    FLEET_PLACEMENT,

    /** Players may take server-validated turns. */
    BATTLE,

    /** The game has reached an authoritative terminal state. */
    FINISHED
}
