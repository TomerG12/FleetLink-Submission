package io.github.tomerg12.fleetlink.shared.protocol;

/**
 * Describes the authoritative rematch negotiation state visible to one player.
 */
public enum RematchState {
    /** A rematch may be requested for the completed game. */
    AVAILABLE,

    /** The receiving player requested a rematch and awaits the opponent. */
    REQUESTED_BY_YOU,

    /** The opponent requested a rematch and awaits the receiving player. */
    REQUESTED_BY_OPPONENT,

    /** Both players accepted and the server may create a new game. */
    ACCEPTED,

    /** One player declined the rematch. */
    DECLINED,

    /** The rematch opportunity is no longer available. */
    EXPIRED
}
