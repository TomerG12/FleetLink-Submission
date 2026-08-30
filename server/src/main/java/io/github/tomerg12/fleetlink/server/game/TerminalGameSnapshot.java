package io.github.tomerg12.fleetlink.server.game;

import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Captures the authoritative terminal facts owned by one finished in-memory game.
 * This domain value contains match timing and telemetry but no persistence or handoff type.
 */
public final class TerminalGameSnapshot {
    private final UUID gameId;
    private final Instant startedAt;
    private final PlayerView playerOne;
    private final PlayerView playerTwo;
    private final ParticipantTelemetrySnapshot playerOneTelemetry;
    private final ParticipantTelemetrySnapshot playerTwoTelemetry;
    private final UUID winnerPlayerId;
    private final GameEndReason endReason;

    /**
     * Creates one immutable terminal game-domain snapshot.
     *
     * @param gameId authoritative game identifier
     * @param startedAt authoritative placement activation time
     * @param playerOne first game participant
     * @param playerTwo second game participant
     * @param playerOneTelemetry first participant telemetry
     * @param playerTwoTelemetry second participant telemetry
     * @param winnerPlayerId authoritative winner identifier, or null only for NO_CONTEST
     * @param endReason authoritative terminal reason
     * @throws NullPointerException if a required game, participant, or reason value is null
     * @throws IllegalArgumentException if participant or terminal-winner invariants are violated
     */
    public TerminalGameSnapshot(UUID gameId, Instant startedAt,
                                PlayerView playerOne, PlayerView playerTwo,
                                ParticipantTelemetrySnapshot playerOneTelemetry,
                                ParticipantTelemetrySnapshot playerTwoTelemetry,
                                UUID winnerPlayerId, GameEndReason endReason) {
        this.gameId = Objects.requireNonNull(gameId, "gameId");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.playerOne = Objects.requireNonNull(playerOne, "playerOne");
        this.playerTwo = Objects.requireNonNull(playerTwo, "playerTwo");
        this.playerOneTelemetry = Objects.requireNonNull(
                playerOneTelemetry, "playerOneTelemetry");
        this.playerTwoTelemetry = Objects.requireNonNull(
                playerTwoTelemetry, "playerTwoTelemetry");
        this.endReason = Objects.requireNonNull(endReason, "endReason");
        if (playerOne.getPlayerId().equals(playerTwo.getPlayerId())) {
            throw new IllegalArgumentException("terminal participants must have different identities");
        }
        if (!playerOne.getPlayerId().equals(playerOneTelemetry.getPlayerId())
                || !playerTwo.getPlayerId().equals(playerTwoTelemetry.getPlayerId())) {
            throw new IllegalArgumentException("terminal telemetry identity must match participant");
        }
        if (endReason == GameEndReason.NO_CONTEST) {
            if (winnerPlayerId != null) {
                throw new IllegalArgumentException("NO_CONTEST must not declare a winner");
            }
        } else {
            if (winnerPlayerId == null) {
                throw new IllegalArgumentException("normal terminal state requires a winner");
            }
            if (!winnerPlayerId.equals(playerOne.getPlayerId())
                    && !winnerPlayerId.equals(playerTwo.getPlayerId())) {
                throw new IllegalArgumentException("terminal winner must be a game participant");
            }
        }
        this.winnerPlayerId = winnerPlayerId;
    }

    /**
     * Returns the authoritative in-memory game identifier.
     *
     * @return game identifier
     */
    public UUID getGameId() {
        return gameId;
    }

    /**
     * Returns the authoritative match start time.
     *
     * @return placement activation time
     */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * Returns the first participant captured from the game.
     *
     * @return first participant
     */
    public PlayerView getPlayerOne() {
        return playerOne;
    }

    /**
     * Returns the second participant captured from the game.
     *
     * @return second participant
     */
    public PlayerView getPlayerTwo() {
        return playerTwo;
    }

    /**
     * Returns the first participant's terminal telemetry.
     *
     * @return first participant telemetry
     */
    public ParticipantTelemetrySnapshot getPlayerOneTelemetry() {
        return playerOneTelemetry;
    }

    /**
     * Returns the second participant's terminal telemetry.
     *
     * @return second participant telemetry
     */
    public ParticipantTelemetrySnapshot getPlayerTwoTelemetry() {
        return playerTwoTelemetry;
    }

    /**
     * Returns the authoritative winner identifier when the terminal outcome has a winner.
     *
     * @return winner player identifier, or null only for NO_CONTEST
     */
    public UUID getWinnerPlayerId() {
        return winnerPlayerId;
    }

    /**
     * Returns the authoritative reason the game finished.
     *
     * @return terminal end reason
     */
    public GameEndReason getEndReason() {
        return endReason;
    }
}
