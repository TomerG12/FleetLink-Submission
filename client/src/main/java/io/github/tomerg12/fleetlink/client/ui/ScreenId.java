package io.github.tomerg12.fleetlink.client.ui;

/**
 * Identifies the JavaFX screens owned by the client navigation boundary.
 */
public enum ScreenId {
    /** Login and session entry screen. */
    LOGIN,

    /** Lobby and matchmaking screen. */
    LOBBY,

    /** Local fleet arrangement screen. */
    SHIP_PLACEMENT,

    /** Active battle presentation screen. */
    BATTLE,

    /** Completed-match result screen. */
    GAME_OVER,

    /** Player statistics and match-history screen. */
    PLAYER_STATISTICS
}
