package io.github.tomerg12.fleetlink.shared.protocol;

/**
 * Provides stable machine-readable outcomes for expected protocol failures.
 * Clients use these values instead of parsing a human-readable message.
 */
public enum ResultCode {
    /** The requested operation completed successfully. */
    SUCCESS,

    /** The submitted login credentials were not accepted. */
    INVALID_CREDENTIALS,

    /** Registration could not use the requested username. */
    USERNAME_UNAVAILABLE,

    /** The session identifier is absent, expired, or unknown. */
    INVALID_SESSION,

    /** The session is already waiting for matchmaking. */
    ALREADY_WAITING,

    /** The session is not currently waiting for matchmaking. */
    NOT_WAITING,

    /** The session has no active game. */
    NOT_IN_GAME,

    /** The operation is not allowed in the current game phase. */
    INVALID_GAME_PHASE,

    /** The submitted fleet violates an authoritative fleet rule. */
    INVALID_FLEET,

    /** The player already submitted a fleet for the active game. */
    FLEET_ALREADY_SUBMITTED,

    /** The server rejected a shot because another player owns the turn. */
    NOT_YOUR_TURN,

    /** The target cell was already fired upon. */
    DUPLICATE_SHOT,

    /** The requested target is not valid for the active game. */
    INVALID_TARGET,

    /** The completed game does not currently allow a rematch. */
    REMATCH_NOT_AVAILABLE,

    /** A rematch request or response is already pending for the player. */
    REMATCH_ALREADY_PENDING,

    /** The request was structurally valid but not acceptable in current server state. */
    INVALID_REQUEST,

    /** The operation requires persistent registered-account identity. */
    REGISTERED_ACCOUNT_REQUIRED
}
