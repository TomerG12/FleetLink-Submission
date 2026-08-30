package io.github.tomerg12.fleetlink.shared.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies T5.5 deadline, timeout-strike, and winnerless terminal invariants in the shared contract. */
class GameViewDeadlineTest {
    /** Accepts authoritative Battle deadline and role-oriented cumulative strike metadata. */
    @Test
    void acceptsBattleDeadlineAndStrikeMetadata() {
        PlayerView player = player("Player");
        PlayerView opponent = player("Opponent");
        GameView view = new GameView(UUID.randomUUID(), GamePhase.BATTLE, player, opponent, true,
                ownBoard(), opponentBoard(), null, null, 123_456L, 1, 0);

        assertEquals(123_456L, view.getDeadlineEpochMillis());
        assertEquals(1, view.getYourTimeoutStrikes());
        assertEquals(0, view.getOpponentTimeoutStrikes());
    }

    /** Keeps the compatibility constructor neutral for legacy callers that provide no T5.5 metadata. */
    @Test
    void compatibilityConstructorUsesNeutralDeadlineMetadata() {
        PlayerView player = player("Player");
        PlayerView opponent = player("Opponent");
        GameView view = new GameView(UUID.randomUUID(), GamePhase.BATTLE, player, opponent, false,
                ownBoard(), opponentBoard(), null, null);

        assertEquals(0L, view.getDeadlineEpochMillis());
        assertEquals(0, view.getYourTimeoutStrikes());
        assertEquals(0, view.getOpponentTimeoutStrikes());
    }

    /** Accepts FINISHED plus NO_CONTEST only when the authoritative winner is null. */
    @Test
    void noContestRequiresWinnerlessTerminalSnapshot() {
        PlayerView player = player("Player");
        PlayerView opponent = player("Opponent");
        GameView noContest = new GameView(UUID.randomUUID(), GamePhase.FINISHED,
                player, opponent, false, ownBoard(), opponentBoard(), null,
                GameEndReason.NO_CONTEST, 0L, 0, 0);

        assertNull(noContest.getWinner());
        assertEquals(GameEndReason.NO_CONTEST, noContest.getEndReason());
        assertThrows(IllegalArgumentException.class, () -> new GameView(UUID.randomUUID(),
                GamePhase.FINISHED, player, opponent, false, ownBoard(), opponentBoard(), player,
                GameEndReason.NO_CONTEST, 0L, 0, 0));
    }

    /** Rejects invalid deadline and timeout-strike combinations at the transport boundary. */
    @Test
    void rejectsInvalidDeadlineAndStrikeMetadata() {
        PlayerView player = player("Player");
        PlayerView opponent = player("Opponent");

        assertThrows(IllegalArgumentException.class, () -> new GameView(UUID.randomUUID(),
                GamePhase.BATTLE, player, opponent, true, ownBoard(), opponentBoard(), null, null,
                -1L, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GameView(UUID.randomUUID(),
                GamePhase.BATTLE, player, opponent, true, ownBoard(), opponentBoard(), null, null,
                1L, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> new GameView(UUID.randomUUID(),
                GamePhase.FLEET_PLACEMENT, player, opponent, false,
                ownBoard(), opponentBoard(), null, null, 1L, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new GameView(UUID.randomUUID(),
                GamePhase.FINISHED, player, opponent, false, ownBoard(), opponentBoard(), player,
                GameEndReason.TIMEOUT, 1L, 2, 0));
    }

    /**
     * Creates a safe player value for snapshot contract tests.
     *
     * @param name display name
     * @return safe player view
     */
    private static PlayerView player(String name) {
        return new PlayerView(UUID.randomUUID(), name, 1000, true);
    }

    /**
     * Creates an empty receiver-visible board.
     *
     * @return safe own-board view
     */
    private static OwnBoardView ownBoard() {
        OwnCellView[][] cells = new OwnCellView[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
        for (OwnCellView[] row : cells) {
            Arrays.fill(row, OwnCellView.WATER);
        }
        return new OwnBoardView(cells);
    }

    /**
     * Creates a fully unknown discovery-only opponent board.
     *
     * @return safe opponent-board view
     */
    private static OpponentBoardView opponentBoard() {
        OpponentCellView[][] cells =
                new OpponentCellView[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
        for (OpponentCellView[] row : cells) {
            Arrays.fill(row, OpponentCellView.UNKNOWN);
        }
        return new OpponentBoardView(cells);
    }
}
