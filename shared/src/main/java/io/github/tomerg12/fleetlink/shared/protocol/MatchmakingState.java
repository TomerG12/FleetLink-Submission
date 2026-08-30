package io.github.tomerg12.fleetlink.shared.protocol;

/**
 * Describes the server state produced by a successful matchmaking request.
 */
public enum MatchmakingState {
    /** The player is waiting for a compatible opponent. */
    WAITING,

    /** The server has paired the player and will provide a game snapshot. */
    MATCHED
}
