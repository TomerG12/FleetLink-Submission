package io.github.tomerg12.fleetlink.shared.protocol;

/**
 * Explains why an authoritative game snapshot reached its terminal phase.
 */
public enum GameEndReason {
    /** One player lost every ship. */
    ALL_SHIPS_SUNK,

    /** One player voluntarily left the active game. */
    RESIGNATION,

    /** One player disconnected and the server ended the game. */
    DISCONNECT,

    /** One player lost because an authoritative server deadline expired. */
    TIMEOUT,

    /** Neither participant completed placement before the common deadline. */
    NO_CONTEST
}
