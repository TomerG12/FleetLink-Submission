package io.github.tomerg12.fleetlink.client.integration;

/**
 * Identifies the reconciled client lifecycle phase used to drive presentation and navigation.
 */
public enum ClientPhase {
    /** No server session exists and the login screen is active. */
    LOGIN,

    /** A guest connection is running on the remote executor. */
    CONNECTING,

    /** A valid session exists and the player is idle in the lobby. */
    LOBBY,

    /** The player is joining, waiting in, or cancelling matchmaking. */
    MATCHMAKING,

    /** The server created a game whose fleet is not yet submitted. */
    SHIP_PLACEMENT,

    /** A complete fleet submission is running on the remote executor. */
    SUBMITTING_FLEET,

    /** The server accepted the local fleet and the opponent is not ready yet. */
    WAITING_FOR_BATTLE,

    /** The latest authoritative snapshot is in the battle phase. */
    BATTLE,

    /** One shot request is running while the previous authoritative snapshot remains visible. */
    FIRING,

    /** An active battle resignation is running on the remote executor. */
    LEAVING_GAME,

    /** The latest authoritative snapshot is terminal. */
    GAME_OVER,

    /** Explicit session logout is running while the current Lobby remains visible. */
    LOGGING_OUT
}
