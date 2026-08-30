package io.github.tomerg12.fleetlink.server.game;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.GameViewResult;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShotOutcome;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Owns the complete authoritative in-memory state and state machine for one FleetLink game.
 * Time values are supplied by the coordinator; this class owns no clock, scheduler, persistence,
 * callback, or client infrastructure.
 */
public final class GameSession {
    private final UUID gameId;
    private final PlayerView playerOne;
    private final PlayerView playerTwo;
    private final UUID startingPlayerId;
    private final Board playerOneBoard = new Board();
    private final Board playerTwoBoard = new Board();
    private final FleetValidator fleetValidator = new FleetValidator();

    private GamePhase phase = GamePhase.FLEET_PLACEMENT;
    private boolean placementActivated;
    private boolean playerOneFleetSubmitted;
    private boolean playerTwoFleetSubmitted;
    private UUID currentTurnPlayerId;
    private PlayerView winner;
    private GameEndReason endReason;
    private Instant activeDeadline;
    private long deadlineGeneration;
    private int playerOneTimeoutStrikes;
    private int playerTwoTimeoutStrikes;
    private Instant startedAt;
    private int playerOneShotsFired;
    private int playerTwoShotsFired;
    private int playerOneHits;
    private int playerTwoHits;
    private int playerOneShipsSunk;
    private int playerTwoShipsSunk;
    private int playerOneTurnsTaken;
    private int playerTwoTurnsTaken;

    /**
     * Creates one indexed game in explicit pre-activation placement state.
     *
     * @param gameId unique game identifier
     * @param playerOne first participant
     * @param playerTwo second participant
     * @param startingPlayerId participant who receives the first Battle turn
     * @throws NullPointerException if any required argument is null
     * @throws IllegalArgumentException if participants are identical or the starter is not a participant
     */
    public GameSession(UUID gameId, PlayerView playerOne, PlayerView playerTwo,
                       UUID startingPlayerId) {
        this.gameId = Objects.requireNonNull(gameId, "gameId");
        this.playerOne = Objects.requireNonNull(playerOne, "playerOne");
        this.playerTwo = Objects.requireNonNull(playerTwo, "playerTwo");
        this.startingPlayerId = Objects.requireNonNull(startingPlayerId, "startingPlayerId");
        if (playerOne.getPlayerId().equals(playerTwo.getPlayerId())) {
            throw new IllegalArgumentException("game participants must have different identities");
        }
        if (!containsPlayer(startingPlayerId)) {
            throw new IllegalArgumentException("starting player must be a game participant");
        }
    }

    /**
     * Returns the immutable identifier used by coordinator indexes and admission lanes.
     *
     * @return immutable game identifier
     */
    public UUID getGameId() {
        return gameId;
    }

    /**
     * Returns both participant identifiers in stable game order.
     *
     * @return participant identifiers in player-one, player-two order
     */
    public List<UUID> getParticipantIds() {
        return List.of(playerOne.getPlayerId(), playerTwo.getPlayerId());
    }

    /**
     * Checks whether an identifier belongs to either game participant.
     *
     * @param playerId identifier to test
     * @return true when the identifier belongs to this game
     */
    public boolean containsPlayer(UUID playerId) {
        return playerId != null && (playerOne.getPlayerId().equals(playerId)
                || playerTwo.getPlayerId().equals(playerId));
    }

    /**
     * Reports whether the authoritative state machine is terminal.
     *
     * @return true when the game reached FINISHED
     */
    public synchronized boolean isFinished() {
        return phase == GamePhase.FINISHED;
    }

    /**
     * Reports whether the common placement window has been activated.
     *
     * @return true after authoritative placement activation committed
     */
    public synchronized boolean isPlacementActivated() {
        return placementActivated;
    }

    /**
     * Returns the current authoritative absolute deadline.
     *
     * @return active absolute deadline, or null when none is active
     */
    public synchronized Instant getActiveDeadline() {
        return activeDeadline;
    }

    /**
     * Returns the generation used to reject stale scheduled expiry work.
     *
     * @return current monotonic deadline generation, zero before first activation
     */
    public synchronized long getDeadlineGeneration() {
        return deadlineGeneration;
    }

    /**
     * Activates the common placement window exactly once while preserving pre-activation invariants.
     *
     * @param activationTime authoritative match start time supplied by the coordinator
     * @param deadline common placement deadline supplied by the coordinator
     * @throws NullPointerException if either time is null
     * @throws IllegalStateException if placement is already active or pre-activation state is invalid
     */
    public synchronized void activatePlacement(Instant activationTime, Instant deadline) {
        Objects.requireNonNull(activationTime, "activationTime");
        Objects.requireNonNull(deadline, "deadline");
        if (phase != GamePhase.FLEET_PLACEMENT || placementActivated) {
            throw new IllegalStateException("placement can be activated exactly once");
        }
        if (playerOneFleetSubmitted || playerTwoFleetSubmitted || currentTurnPlayerId != null
                || winner != null || endReason != null || activeDeadline != null
                || deadlineGeneration != 0L || playerOneTimeoutStrikes != 0
                || playerTwoTimeoutStrikes != 0 || startedAt != null
                || playerOneShotsFired != 0 || playerTwoShotsFired != 0
                || playerOneHits != 0 || playerTwoHits != 0
                || playerOneShipsSunk != 0 || playerTwoShipsSunk != 0
                || playerOneTurnsTaken != 0 || playerTwoTurnsTaken != 0) {
            throw new IllegalStateException("pre-activation game invariant is violated");
        }
        if (!deadline.isAfter(activationTime)) {
            throw new IllegalArgumentException("placement deadline must follow activation time");
        }
        startedAt = activationTime;
        placementActivated = true;
        installDeadline(deadline);
    }

    /**
     * Atomically captures terminal facts without external or database I/O.
     *
     * @return immutable terminal game-domain state
     * @throws IllegalStateException if the game is not finished
     */
    public synchronized TerminalGameSnapshot captureTerminalState() {
        if (phase != GamePhase.FINISHED || endReason == null || startedAt == null) {
            throw new IllegalStateException("terminal snapshot requires an activated finished game");
        }
        UUID winnerId = winner == null ? null : winner.getPlayerId();
        return new TerminalGameSnapshot(gameId, startedAt, playerOne, playerTwo,
                telemetryFor(playerOne.getPlayerId()), telemetryFor(playerTwo.getPlayerId()),
                winnerId, endReason);
    }

    /**
     * Validates and commits one complete fleet using the authoritative ingress timestamp.
     * Operation validity is checked before deadline comparison, while request-data validation occurs
     * only after an on-time command has won the deadline race.
     *
     * @param playerId submitting participant
     * @param placements complete fleet request
     * @param receivedAt server ingress time captured by the admission sequencer
     * @param battleDeadline deadline to install only if this submission starts Battle
     * @return authoritative fleet result
     * @throws NullPointerException if either supplied time is null
     */
    public synchronized FleetSubmissionResult submitFleet(UUID playerId,
                                                           List<ShipPlacement> placements,
                                                           Instant receivedAt,
                                                           Instant battleDeadline) {
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(battleDeadline, "battleDeadline");
        if (!containsPlayer(playerId)) {
            return FleetSubmissionResult.rejected(
                    ResultCode.NOT_IN_GAME, "Player is not part of this game", null);
        }
        if (phase != GamePhase.FLEET_PLACEMENT || !placementActivated) {
            return FleetSubmissionResult.rejected(
                    ResultCode.INVALID_GAME_PHASE, "Fleet placement is not active",
                    phase == GamePhase.FINISHED ? snapshotFor(playerId) : null);
        }
        if (fleetSubmitted(playerId)) {
            return FleetSubmissionResult.rejected(
                    ResultCode.FLEET_ALREADY_SUBMITTED, "Fleet was already submitted",
                    snapshotFor(playerId));
        }
        if (!receivedAt.isBefore(activeDeadline)) {
            expirePlacementDeadline();
            return FleetSubmissionResult.rejected(ResultCode.INVALID_GAME_PHASE,
                    "Fleet placement deadline expired", snapshotFor(playerId));
        }

        List<ShipState> fleet;
        try {
            fleet = fleetValidator.validate(placements);
        } catch (IllegalArgumentException exception) {
            return FleetSubmissionResult.rejected(
                    ResultCode.INVALID_FLEET, exception.getMessage(), snapshotFor(playerId));
        }
        boardFor(playerId).commitFleet(fleet);
        markFleetSubmitted(playerId);
        if (playerOneFleetSubmitted && playerTwoFleetSubmitted) {
            phase = GamePhase.BATTLE;
            currentTurnPlayerId = startingPlayerId;
            installDeadline(battleDeadline);
        }
        return FleetSubmissionResult.accepted(snapshotFor(playerId));
    }

    /**
     * Validates and applies one shot using the authoritative ingress timestamp.
     * Phase and turn ownership are validated first so a stale operation cannot affect a newer turn.
     * Once the command is valid for the current turn, deadline comparison occurs before target-data
     * validation, which ensures exact deadline equality always belongs to timeout.
     *
     * @param playerId firing participant
     * @param coordinate requested target
     * @param receivedAt server ingress time captured by the admission sequencer
     * @param nextTurnDeadline deadline to install after a turn transfer
     * @return authoritative shot result
     * @throws NullPointerException if either supplied time is null
     */
    public synchronized ShotResult fire(UUID playerId, Coordinate coordinate, Instant receivedAt,
                                        Instant nextTurnDeadline) {
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(nextTurnDeadline, "nextTurnDeadline");
        if (!containsPlayer(playerId)) {
            return ShotResult.rejected(ResultCode.NOT_IN_GAME,
                    "Player is not part of this game", null);
        }
        if (phase != GamePhase.BATTLE) {
            return ShotResult.rejected(ResultCode.INVALID_GAME_PHASE,
                    "Shots are allowed only during battle",
                    phase == GamePhase.FINISHED ? snapshotFor(playerId) : null);
        }
        if (!playerId.equals(currentTurnPlayerId)) {
            return ShotResult.rejected(ResultCode.NOT_YOUR_TURN,
                    "It is not your turn", snapshotFor(playerId));
        }
        if (!receivedAt.isBefore(activeDeadline)) {
            boolean terminal = expireBattleTurn(playerId, nextTurnDeadline);
            return ShotResult.rejected(
                    terminal ? ResultCode.INVALID_GAME_PHASE : ResultCode.NOT_YOUR_TURN,
                    "Battle turn deadline expired", snapshotFor(playerId));
        }
        if (coordinate == null) {
            return ShotResult.rejected(ResultCode.INVALID_TARGET,
                    "Target coordinate is required", snapshotFor(playerId));
        }
        Board targetBoard = boardFor(opponentId(playerId));
        if (targetBoard.wasFiredAt(coordinate)) {
            return ShotResult.rejected(ResultCode.DUPLICATE_SHOT,
                    "Target was already fired upon", snapshotFor(playerId));
        }

        ShotOutcome outcome = targetBoard.fireAt(coordinate);
        recordAcceptedShot(playerId, outcome);
        if (targetBoard.areAllShipsSunk()) {
            phase = GamePhase.FINISHED;
            currentTurnPlayerId = null;
            winner = playerFor(playerId);
            endReason = GameEndReason.ALL_SHIPS_SUNK;
            clearDeadline();
        } else {
            currentTurnPlayerId = opponentId(playerId);
            installDeadline(nextTurnDeadline);
        }
        return ShotResult.accepted(outcome, snapshotFor(playerId));
    }

    /**
     * Applies an admitted scheduled expiry only when generation and deadline still match.
     *
     * @param expectedGeneration generation captured when the timer was scheduled
     * @param now server time when the expiry command executes
     * @param nextTurnDeadline deadline for the opponent after a first Battle strike
     * @return true when authoritative state changed
     * @throws NullPointerException if either supplied time is null
     */
    public synchronized boolean expireDeadline(long expectedGeneration, Instant now,
                                               Instant nextTurnDeadline) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(nextTurnDeadline, "nextTurnDeadline");
        if (phase == GamePhase.FINISHED || activeDeadline == null
                || deadlineGeneration != expectedGeneration || now.isBefore(activeDeadline)) {
            return false;
        }
        if (phase == GamePhase.FLEET_PLACEMENT) {
            if (!placementActivated) {
                return false;
            }
            expirePlacementDeadline();
            return true;
        }
        if (phase == GamePhase.BATTLE && currentTurnPlayerId != null) {
            expireBattleTurn(currentTurnPlayerId, nextTurnDeadline);
            return true;
        }
        return false;
    }

    /**
     * Returns the latest safe snapshot without exposing pre-activation placement as playable.
     *
     * @param playerId requesting participant
     * @return current snapshot or explicit failure
     */
    public synchronized GameViewResult getCurrentGame(UUID playerId) {
        if (!containsPlayer(playerId)) {
            return GameViewResult.failure(ResultCode.NOT_IN_GAME, "Player is not part of this game");
        }
        if (phase == GamePhase.FLEET_PLACEMENT && !placementActivated) {
            return GameViewResult.failure(ResultCode.INVALID_GAME_PHASE,
                    "Match placement has not been activated yet");
        }
        return GameViewResult.success(snapshotFor(playerId));
    }

    /**
     * Ends an unfinished game as a resignation by the supplied participant.
     *
     * @param playerId leaving participant
     * @return terminal resignation result or an expected failure
     */
    public synchronized OperationResult leave(UUID playerId) {
        return finishByDeparture(playerId, GameEndReason.RESIGNATION);
    }

    /**
     * Ends an unfinished game as a disconnect by the supplied participant.
     *
     * @param playerId disconnected participant
     * @return terminal disconnect result or an expected failure
     */
    public synchronized OperationResult disconnect(UUID playerId) {
        return finishByDeparture(playerId, GameEndReason.DISCONNECT);
    }

    /**
     * Resolves the participant opposite the supplied identifier.
     *
     * @param playerId participant whose opponent is requested
     * @return the opposing participant identifier
     * @throws IllegalArgumentException if the identifier does not belong to this game
     */
    public UUID opponentId(UUID playerId) {
        if (playerOne.getPlayerId().equals(playerId)) {
            return playerTwo.getPlayerId();
        }
        if (playerTwo.getPlayerId().equals(playerId)) {
            return playerOne.getPlayerId();
        }
        throw new IllegalArgumentException("player is not part of this game");
    }

    /**
     * Applies one resignation or disconnect terminal transition and invalidates the active deadline.
     *
     * @param playerId participant leaving the game
     * @param reason authoritative departure reason
     * @return success when this call performs the terminal transition, otherwise an expected failure
     */
    private OperationResult finishByDeparture(UUID playerId, GameEndReason reason) {
        if (!containsPlayer(playerId)) {
            return OperationResult.failure(ResultCode.NOT_IN_GAME, "Player is not part of this game");
        }
        if (phase == GamePhase.FINISHED) {
            return OperationResult.failure(ResultCode.INVALID_GAME_PHASE,
                    "Game is already finished");
        }
        phase = GamePhase.FINISHED;
        currentTurnPlayerId = null;
        winner = playerFor(opponentId(playerId));
        endReason = reason;
        clearDeadline();
        return OperationResult.success();
    }

    /**
     * Applies the common placement deadline policy for either single-AFK or double-AFK expiry.
     */
    private void expirePlacementDeadline() {
        phase = GamePhase.FINISHED;
        currentTurnPlayerId = null;
        if (!playerOneFleetSubmitted && !playerTwoFleetSubmitted) {
            winner = null;
            endReason = GameEndReason.NO_CONTEST;
        } else {
            UUID winnerId = playerOneFleetSubmitted
                    ? playerOne.getPlayerId() : playerTwo.getPlayerId();
            winner = playerFor(winnerId);
            endReason = GameEndReason.TIMEOUT;
        }
        clearDeadline();
    }

    /**
     * Applies one cumulative Battle timeout strike and either transfers the turn or ends the game.
     *
     * @param timedOutPlayerId participant whose active turn expired
     * @param nextTurnDeadline deadline to install when the first strike transfers the turn
     * @return true when the timeout is the player's second strike and finishes the game
     */
    private boolean expireBattleTurn(UUID timedOutPlayerId, Instant nextTurnDeadline) {
        incrementTurnsTaken(timedOutPlayerId);
        int strikes = incrementTimeoutStrikes(timedOutPlayerId);
        if (strikes >= 2) {
            phase = GamePhase.FINISHED;
            currentTurnPlayerId = null;
            winner = playerFor(opponentId(timedOutPlayerId));
            endReason = GameEndReason.TIMEOUT;
            clearDeadline();
            return true;
        }
        currentTurnPlayerId = opponentId(timedOutPlayerId);
        installDeadline(nextTurnDeadline);
        return false;
    }

    /**
     * Increments the authoritative cumulative timeout counter for one participant.
     *
     * @param playerId participant receiving the timeout strike
     * @return updated strike count
     */
    private int incrementTimeoutStrikes(UUID playerId) {
        if (playerOne.getPlayerId().equals(playerId)) {
            return ++playerOneTimeoutStrikes;
        }
        return ++playerTwoTimeoutStrikes;
    }

    /**
     * Records semantic counters for one accepted shot after duplicate validation succeeds.
     *
     * @param playerId participant who fired
     * @param outcome authoritative board outcome
     */
    private void recordAcceptedShot(UUID playerId, ShotOutcome outcome) {
        if (playerOne.getPlayerId().equals(playerId)) {
            playerOneShotsFired++;
            playerOneTurnsTaken++;
            if (outcome != ShotOutcome.MISS) {
                playerOneHits++;
            }
            if (outcome == ShotOutcome.SUNK) {
                playerOneShipsSunk++;
            }
            return;
        }
        playerTwoShotsFired++;
        playerTwoTurnsTaken++;
        if (outcome != ShotOutcome.MISS) {
            playerTwoHits++;
        }
        if (outcome == ShotOutcome.SUNK) {
            playerTwoShipsSunk++;
        }
    }

    /**
     * Counts one expired Battle turn without counting a shot.
     *
     * @param playerId participant whose turn expired
     */
    private void incrementTurnsTaken(UUID playerId) {
        if (playerOne.getPlayerId().equals(playerId)) {
            playerOneTurnsTaken++;
        } else {
            playerTwoTurnsTaken++;
        }
    }

    /**
     * Captures counters for one participant while the game monitor is held.
     *
     * @param playerId participant whose counters are requested
     * @return immutable participant telemetry
     */
    private ParticipantTelemetrySnapshot telemetryFor(UUID playerId) {
        if (playerOne.getPlayerId().equals(playerId)) {
            return new ParticipantTelemetrySnapshot(playerId, playerOneShotsFired,
                    playerOneHits, playerOneShipsSunk, playerOneTurnsTaken);
        }
        return new ParticipantTelemetrySnapshot(playerId, playerTwoShotsFired,
                playerTwoHits, playerTwoShipsSunk, playerTwoTurnsTaken);
    }

    /**
     * Reads the cumulative timeout counter for one participant.
     *
     * @param playerId participant whose strike count is requested
     * @return authoritative timeout strike count
     * @throws IllegalArgumentException if the identifier does not belong to this game
     */
    private int timeoutStrikes(UUID playerId) {
        if (playerOne.getPlayerId().equals(playerId)) {
            return playerOneTimeoutStrikes;
        }
        if (playerTwo.getPlayerId().equals(playerId)) {
            return playerTwoTimeoutStrikes;
        }
        throw new IllegalArgumentException("player is not part of this game");
    }

    /**
     * Installs a new authoritative deadline and advances its stale-work generation.
     *
     * @param deadline non-null deadline for the newly active placement window or turn
     */
    private void installDeadline(Instant deadline) {
        activeDeadline = Objects.requireNonNull(deadline, "deadline");
        deadlineGeneration++;
    }

    /**
     * Clears the active deadline and advances generation so previously scheduled work becomes stale.
     */
    private void clearDeadline() {
        activeDeadline = null;
        deadlineGeneration++;
    }

    /**
     * Reports whether the supplied participant already committed a fleet.
     *
     * @param playerId game participant
     * @return true when that participant has submitted a fleet
     */
    private boolean fleetSubmitted(UUID playerId) {
        return playerOne.getPlayerId().equals(playerId)
                ? playerOneFleetSubmitted : playerTwoFleetSubmitted;
    }

    /**
     * Marks the supplied participant's fleet as submitted after complete validation and commit.
     *
     * @param playerId game participant whose fleet was committed
     */
    private void markFleetSubmitted(UUID playerId) {
        if (playerOne.getPlayerId().equals(playerId)) {
            playerOneFleetSubmitted = true;
        } else {
            playerTwoFleetSubmitted = true;
        }
    }

    /**
     * Resolves the authoritative board owned by one participant.
     *
     * @param playerId participant whose board is requested
     * @return authoritative board
     * @throws IllegalArgumentException if the identifier does not belong to this game
     */
    private Board boardFor(UUID playerId) {
        if (playerOne.getPlayerId().equals(playerId)) {
            return playerOneBoard;
        }
        if (playerTwo.getPlayerId().equals(playerId)) {
            return playerTwoBoard;
        }
        throw new IllegalArgumentException("player is not part of this game");
    }

    /**
     * Resolves safe participant information for one game identifier.
     *
     * @param playerId participant identifier
     * @return matching safe player view
     * @throws IllegalArgumentException if the identifier does not belong to this game
     */
    private PlayerView playerFor(UUID playerId) {
        if (playerOne.getPlayerId().equals(playerId)) {
            return playerOne;
        }
        if (playerTwo.getPlayerId().equals(playerId)) {
            return playerTwo;
        }
        throw new IllegalArgumentException("player is not part of this game");
    }

    /**
     * Builds one receiver-specific authoritative snapshot from the current synchronized state.
     *
     * @param playerId participant receiving the snapshot
     * @return safe authoritative game snapshot
     */
    private GameView snapshotFor(UUID playerId) {
        PlayerView receiver = playerFor(playerId);
        UUID otherId = opponentId(playerId);
        PlayerView opponent = playerFor(otherId);
        boolean yourTurn = phase == GamePhase.BATTLE && playerId.equals(currentTurnPlayerId);
        long deadlineMillis = activeDeadline == null ? 0L : activeDeadline.toEpochMilli();
        return GameViewFactory.create(gameId, phase, receiver, opponent,
                boardFor(playerId), boardFor(otherId), yourTurn, winner, endReason,
                deadlineMillis, timeoutStrikes(playerId), timeoutStrikes(otherId));
    }
}
