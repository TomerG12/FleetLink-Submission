package io.github.tomerg12.fleetlink.server.persistence;

/**
 * Stores the terminal result assigned to one completed-game participant.
 */
public enum ParticipantResult {
    /** The participant won the completed game. */
    WIN,

    /** The participant lost the completed game. */
    LOSS
}
