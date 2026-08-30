package io.github.tomerg12.fleetlink.shared.protocol;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Provides a complete authoritative game snapshot filtered for one receiving player.
 * Separate board types ensure that the opponent portion cannot carry undiscovered ship positions.
 */
public final class GameView implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID gameId;
    private final GamePhase phase;
    private final PlayerView player;
    private final PlayerView opponent;
    private final boolean yourTurn;
    private final OwnBoardView ownBoard;
    private final OpponentBoardView opponentBoard;
    private final PlayerView winner;
    private final GameEndReason endReason;

    /** Absolute server-authored deadline used only for client presentation. */
    private final long deadlineEpochMillis;

    /** Receiving participant's cumulative authoritative Battle timeout strikes. */
    private final int yourTimeoutStrikes;

    /** Opposing participant's cumulative authoritative Battle timeout strikes. */
    private final int opponentTimeoutStrikes;

    /**
     * Creates a player-specific authoritative game snapshot without deadline metadata.
     * This compatibility constructor is intended for callers that do not yet provide the T5.5
     * presentation fields; authoritative server snapshots should use the complete constructor.
     *
     * @param gameId the active or completed game identifier
     * @param phase the authoritative game phase
     * @param player the receiving player's safe information
     * @param opponent the opponent's safe information
     * @param yourTurn whether the receiving player currently owns the turn
     * @param ownBoard the receiving player's full own-board view
     * @param opponentBoard the receiving player's discovery-only opponent view
     * @param winner the winner for a finished game, or null before completion or for NO_CONTEST
     * @param endReason the end reason for a finished game, or null before completion
     * @throws NullPointerException if a required argument is null
     * @throws IllegalArgumentException if identities, turn data, terminal data, or neutral deadline
     *         metadata violate the snapshot invariants
     */
    public GameView(UUID gameId, GamePhase phase, PlayerView player, PlayerView opponent,
                    boolean yourTurn, OwnBoardView ownBoard, OpponentBoardView opponentBoard,
                    PlayerView winner, GameEndReason endReason) {
        this(gameId, phase, player, opponent, yourTurn, ownBoard, opponentBoard, winner, endReason,
                0L, 0, 0);
    }

    /**
     * Creates a complete player-specific authoritative game snapshot.
     *
     * @param gameId the active or completed game identifier
     * @param phase the authoritative game phase
     * @param player the receiving player's safe information
     * @param opponent the opponent's safe information
     * @param yourTurn whether the receiving player currently owns the turn
     * @param ownBoard the receiving player's full own-board view
     * @param opponentBoard the receiving player's discovery-only opponent view
     * @param winner the winner for a finished game, or null before completion or for NO_CONTEST
     * @param endReason the end reason for a finished game, or null before completion
     * @param deadlineEpochMillis authoritative deadline in epoch milliseconds, or zero when none
     * @param yourTimeoutStrikes receiver's cumulative authoritative Battle timeout strikes
     * @param opponentTimeoutStrikes opponent's cumulative authoritative Battle timeout strikes
     * @throws NullPointerException if a required argument is null
     * @throws IllegalArgumentException if participant, terminal, deadline, or strike data is invalid
     */
    public GameView(UUID gameId, GamePhase phase, PlayerView player, PlayerView opponent,
                    boolean yourTurn, OwnBoardView ownBoard, OpponentBoardView opponentBoard,
                    PlayerView winner, GameEndReason endReason, long deadlineEpochMillis,
                    int yourTimeoutStrikes, int opponentTimeoutStrikes) {
        this.gameId = Objects.requireNonNull(gameId, "gameId");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.player = Objects.requireNonNull(player, "player");
        this.opponent = Objects.requireNonNull(opponent, "opponent");
        this.ownBoard = Objects.requireNonNull(ownBoard, "ownBoard");
        this.opponentBoard = Objects.requireNonNull(opponentBoard, "opponentBoard");
        validateSnapshot(phase, yourTurn, player, opponent, winner, endReason,
                deadlineEpochMillis, yourTimeoutStrikes, opponentTimeoutStrikes);
        this.yourTurn = yourTurn;
        this.winner = winner;
        this.endReason = endReason;
        this.deadlineEpochMillis = deadlineEpochMillis;
        this.yourTimeoutStrikes = yourTimeoutStrikes;
        this.opponentTimeoutStrikes = opponentTimeoutStrikes;
    }

    /**
     * Returns the game identifier carried for snapshot correlation.
     *
     * @return the game identifier
     */
    public UUID getGameId() {
        return gameId;
    }

    /**
     * Returns the authoritative phase.
     *
     * @return the current game phase
     */
    public GamePhase getPhase() {
        return phase;
    }

    /**
     * Returns the receiving player's safe information.
     *
     * @return the receiving player view
     */
    public PlayerView getPlayer() {
        return player;
    }

    /**
     * Returns the opponent's safe information.
     *
     * @return the opponent view
     */
    public PlayerView getOpponent() {
        return opponent;
    }

    /**
     * Indicates whether the server currently allows the receiver to take a Battle turn.
     *
     * @return true when it is the receiving player's turn
     */
    public boolean isYourTurn() {
        return yourTurn;
    }

    /**
     * Returns the board that may reveal the receiver's own ship positions.
     *
     * @return the own-board snapshot
     */
    public OwnBoardView getOwnBoard() {
        return ownBoard;
    }

    /**
     * Returns the discovery-only opponent board.
     *
     * @return the opponent-board snapshot whose type has no undiscovered ship state
     */
    public OpponentBoardView getOpponentBoard() {
        return opponentBoard;
    }

    /**
     * Returns the authoritative winner when one exists.
     *
     * @return a participant for normal terminal outcomes, or null before completion and for
     *         NO_CONTEST
     */
    public PlayerView getWinner() {
        return winner;
    }

    /**
     * Returns the authoritative end reason for a finished game.
     *
     * @return the end reason, or null before the game finishes
     */
    public GameEndReason getEndReason() {
        return endReason;
    }

    /**
     * Returns the server-authored absolute deadline used only for client presentation.
     *
     * @return epoch-millisecond deadline, or zero when the finished state has no active deadline
     */
    public long getDeadlineEpochMillis() {
        return deadlineEpochMillis;
    }

    /**
     * Returns the receiver's cumulative authoritative Battle timeout strikes.
     *
     * @return strike count from zero through two
     */
    public int getYourTimeoutStrikes() {
        return yourTimeoutStrikes;
    }

    /**
     * Returns the opponent's cumulative authoritative Battle timeout strikes.
     *
     * @return strike count from zero through two
     */
    public int getOpponentTimeoutStrikes() {
        return opponentTimeoutStrikes;
    }

    /**
     * Enforces participant, turn, terminal, and T5.5 presentation invariants.
     *
     * @param phase authoritative snapshot phase
     * @param yourTurn whether the receiving participant owns the current Battle turn
     * @param player receiving participant
     * @param opponent opposing participant
     * @param winner terminal winner, or null before completion and for NO_CONTEST
     * @param endReason terminal reason, or null before completion
     * @param deadlineEpochMillis authoritative active deadline, or zero when none
     * @param yourTimeoutStrikes receiving participant's cumulative Battle timeout strikes
     * @param opponentTimeoutStrikes opponent's cumulative Battle timeout strikes
     * @throws IllegalArgumentException if any cross-field snapshot invariant is violated
     */
    private static void validateSnapshot(GamePhase phase, boolean yourTurn, PlayerView player,
                                         PlayerView opponent, PlayerView winner,
                                         GameEndReason endReason, long deadlineEpochMillis,
                                         int yourTimeoutStrikes, int opponentTimeoutStrikes) {
        if (player.getPlayerId().equals(opponent.getPlayerId())) {
            throw new IllegalArgumentException("player and opponent must have different identities");
        }
        if (yourTurn && phase != GamePhase.BATTLE) {
            throw new IllegalArgumentException("a turn is available only during battle");
        }
        if (deadlineEpochMillis < 0L) {
            throw new IllegalArgumentException("deadline must not be negative");
        }
        if (yourTimeoutStrikes < 0 || yourTimeoutStrikes > 2
                || opponentTimeoutStrikes < 0 || opponentTimeoutStrikes > 2) {
            throw new IllegalArgumentException("timeout strikes must be between zero and two");
        }
        if (phase != GamePhase.BATTLE
                && (yourTimeoutStrikes != 0 || opponentTimeoutStrikes != 0)
                && phase != GamePhase.FINISHED) {
            throw new IllegalArgumentException("timeout strikes are Battle state only");
        }
        if (phase == GamePhase.FINISHED) {
            if (endReason == null) {
                throw new IllegalArgumentException("a finished game requires an end reason");
            }
            if (deadlineEpochMillis != 0L) {
                throw new IllegalArgumentException("a finished game cannot carry an active deadline");
            }
            if (endReason == GameEndReason.NO_CONTEST) {
                if (winner != null) {
                    throw new IllegalArgumentException("NO_CONTEST must not declare a winner");
                }
            } else {
                if (winner == null) {
                    throw new IllegalArgumentException("a normal finished game requires a winner");
                }
                if (!winner.getPlayerId().equals(player.getPlayerId())
                        && !winner.getPlayerId().equals(opponent.getPlayerId())) {
                    throw new IllegalArgumentException("winner must be one of the game participants");
                }
            }
        } else if (winner != null || endReason != null) {
            throw new IllegalArgumentException("an unfinished game cannot contain outcome data");
        }
    }
}
