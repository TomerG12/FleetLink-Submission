package io.github.tomerg12.fleetlink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentBoardView;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentCellView;
import io.github.tomerg12.fleetlink.shared.protocol.OwnBoardView;
import io.github.tomerg12.fleetlink.shared.protocol.OwnCellView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies Game Over presentation is derived only from authoritative terminal data, including
 * NO_CONTEST.
 */
class GameOverPresentationModelTest {

    /**
     * Confirms winner identity and all-ships-sunk reason drive victory presentation.
     */
    @Test
    void authoritativeWinnerAndSunkReasonDriveVictory() {
        GameOverPresentationModel model = new GameOverPresentationModel(
                game(GamePhase.FINISHED, true, GameEndReason.ALL_SHIPS_SUNK));
        assertTrue(model.isWinner());
        assertEquals("VICTORY", model.resultTitle());
        assertEquals("All enemy ships were sunk.", model.resultDetail());
    }

    /**
     * Confirms authoritative defeat, departure, and timeout reasons remain distinct without local
     * inference.
     */
    @Test
    void authoritativeDefeatAndDepartureReasonsRemainDistinct() {
        GameOverPresentationModel sunk = new GameOverPresentationModel(
                game(GamePhase.FINISHED, false, GameEndReason.ALL_SHIPS_SUNK));
        GameOverPresentationModel resignation = new GameOverPresentationModel(
                game(GamePhase.FINISHED, true, GameEndReason.RESIGNATION));
        GameOverPresentationModel disconnect = new GameOverPresentationModel(
                game(GamePhase.FINISHED, true, GameEndReason.DISCONNECT));
        GameOverPresentationModel timeout = new GameOverPresentationModel(
                game(GamePhase.FINISHED, false, GameEndReason.TIMEOUT));
        assertFalse(sunk.isWinner());
        assertEquals("DEFEAT", sunk.resultTitle());
        assertEquals("Your fleet was sunk.", sunk.resultDetail());
        assertEquals("Opponent resigned.", resignation.resultDetail());
        assertEquals("Opponent disconnected.", disconnect.resultDetail());
        assertEquals("You lost on timeout.", timeout.resultDetail());
    }

    /**
     * Confirms NO_CONTEST renders safely without dereferencing or inventing a winner.
     */
    @Test
    void noContestHasNoWinnerPresentation() {
        GameView noContest = noContestGame();
        GameOverPresentationModel model = new GameOverPresentationModel(noContest);
        assertTrue(model.isNoContest());
        assertFalse(model.isWinner());
        assertEquals("NO CONTEST", model.resultTitle());
        assertNull(model.getGameView().getWinner());
    }

    /**
     * Confirms non-terminal snapshots cannot create final presentation.
     */
    @Test
    void activeBattleCannotCreateGameOverPresentation() {
        assertThrows(IllegalArgumentException.class, () ->
                new GameOverPresentationModel(game(GamePhase.BATTLE, true, null)));
    }

    /**
     * Creates a standard terminal or active snapshot for presentation-model tests.
     *
     * @param phase authoritative game phase
     * @param playerWins whether the receiving player is the winner or active-turn owner
     * @param reason terminal reason, or null for active Battle
     * @return authoritative game snapshot
     */
    private static GameView game(GamePhase phase, boolean playerWins, GameEndReason reason) {
        Boards boards = boards();
        PlayerView player = new PlayerView(UUID.randomUUID(), "Player", 1000, true);
        PlayerView other = new PlayerView(UUID.randomUUID(), "Opponent", 1000, true);
        PlayerView winner = phase == GamePhase.FINISHED ? (playerWins ? player : other) : null;
        long deadline = phase == GamePhase.BATTLE ? 1L : 0L;
        return new GameView(UUID.randomUUID(), phase, player, other,
                phase == GamePhase.BATTLE && playerWins, boards.own(), boards.opponent(),
                winner, reason, deadline, 0, 0);
    }

    /**
     * Creates a winnerless authoritative NO_CONTEST snapshot.
     *
     * @return finished NO_CONTEST snapshot
     */
    private static GameView noContestGame() {
        Boards boards = boards();
        PlayerView player = new PlayerView(UUID.randomUUID(), "Player", 1000, true);
        PlayerView other = new PlayerView(UUID.randomUUID(), "Opponent", 1000, true);
        return new GameView(UUID.randomUUID(), GamePhase.FINISHED, player, other, false,
                boards.own(), boards.opponent(), null, GameEndReason.NO_CONTEST, 0L, 0, 0);
    }

    /**
     * Creates empty safe boards used by test snapshots.
     *
     * @return own and opponent board pair
     */
    private static Boards boards() {
        OwnCellView[][] own = new OwnCellView[10][10];
        OpponentCellView[][] opponent = new OpponentCellView[10][10];
        for (int row = 0; row < 10; row++) {
            java.util.Arrays.fill(own[row], OwnCellView.WATER);
            java.util.Arrays.fill(opponent[row], OpponentCellView.UNKNOWN);
        }
        return new Boards(new OwnBoardView(own), new OpponentBoardView(opponent));
    }

    /**
     * Groups the two safe board types required by a GameView.
     *
     * @param own receiving player's own-board view
     * @param opponent receiving player's opponent-board view
     */
    private record Boards(OwnBoardView own, OpponentBoardView opponent) {
    }
}
