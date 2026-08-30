package io.github.tomerg12.fleetlink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentBoardView;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentCellView;
import io.github.tomerg12.fleetlink.shared.protocol.OwnBoardView;
import io.github.tomerg12.fleetlink.shared.protocol.OwnCellView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import org.junit.jupiter.api.Test;

/**
 * Verifies battle control enablement without invoking remote game behavior.
 */
class BattlePresentationModelTest {

    /**
     * Confirms targeting remains disabled until the presentation explicitly represents the player's turn.
     */
    @Test
    void targetSelectionRequiresPlayersTurn() {
        BattlePresentationModel model = new BattlePresentationModel();
        Coordinate target = new Coordinate(3, 4);

        assertEquals(BattlePresentationModel.TurnPresentation.WAITING_FOR_STATE, model.getTurnPresentation());
        assertFalse(model.selectTarget(target));
        assertFalse(model.canFire());

        model.applyTurnPresentation(BattlePresentationModel.TurnPresentation.YOUR_TURN);
        assertTrue(model.selectTarget(target));
        assertTrue(model.canFire());
        assertEquals(target, model.getSelectedTarget().orElseThrow());
    }

    /**
     * Confirms a turn transition clears stale local target selection.
     */
    @Test
    void leavingPlayersTurnClearsTargetSelection() {
        BattlePresentationModel model = new BattlePresentationModel();
        model.applyTurnPresentation(BattlePresentationModel.TurnPresentation.YOUR_TURN);
        assertTrue(model.selectTarget(new Coordinate(2, 2)));

        model.applyTurnPresentation(BattlePresentationModel.TurnPresentation.OPPONENT_TURN);

        assertTrue(model.getSelectedTarget().isEmpty());
        assertFalse(model.canFire());
    }

    /**
     * Confirms a pending remote action clears selection and disables duplicate targeting.
     */
    @Test
    void pendingRemoteActionDisablesTargetingUntilReconciledState() {
        BattlePresentationModel model = new BattlePresentationModel();
        model.applyGameView(battleGame(true));
        assertTrue(model.selectTarget(new Coordinate(1, 1)));

        model.setOperationPending(true);

        assertTrue(model.isOperationPending());
        assertFalse(model.isTargetingEnabled());
        assertTrue(model.getSelectedTarget().isEmpty());
        assertFalse(model.canFire());

        model.setOperationPending(false);
        assertTrue(model.isTargetingEnabled());
    }

    /**
     * Confirms an authoritative resolved cell cannot enter pending state and an unknown target
     * remains immediately usable during the same turn.
     */
    @Test
    void resolvedTargetIsRejectedWithoutLockingTheCurrentTurn() {
        BattlePresentationModel model = new BattlePresentationModel();
        GameView gameView = battleGame(true, new Coordinate(1, 1));
        model.applyGameView(gameView);

        assertFalse(model.selectTarget(new Coordinate(1, 1)));
        assertFalse(model.isOperationPending());
        assertEquals(BattlePresentationModel.TurnPresentation.YOUR_TURN,
                model.getTurnPresentation());
        assertTrue(model.selectTarget(new Coordinate(1, 2)));
        assertTrue(model.canFire());
    }

    /**
     * Confirms turn presentation and board mappings come only from authoritative snapshot fields.
     */
    @Test
    void authoritativeSnapshotDrivesTurnAndSafeBoardPresentation() {
        BattlePresentationModel model = new BattlePresentationModel();
        GameView gameView = battleGame(true);

        model.applyGameView(gameView);

        assertEquals(BattlePresentationModel.TurnPresentation.YOUR_TURN,
                model.getTurnPresentation());
        assertEquals(gameView, model.getGameView().orElseThrow());
        assertEquals(BattleshipBoardView.CellState.OCCUPIED,
                BattleScreen.ownCellState(OwnCellView.SHIP));
        assertEquals(BattleshipBoardView.CellState.HIT,
                BattleScreen.opponentCellState(OpponentCellView.HIT));

        model.applyGameView(battleGame(false));
        assertEquals(BattlePresentationModel.TurnPresentation.OPPONENT_TURN,
                model.getTurnPresentation());
    }

    /**
     * Creates one valid safe battle snapshot for presentation tests.
     *
     * @param yourTurn authoritative turn flag
     * @return complete battle snapshot
     */
    private static GameView battleGame(boolean yourTurn) {
        return battleGame(yourTurn, null);
    }

    /**
     * Creates one safe battle snapshot with an optional resolved opponent cell.
     *
     * @param yourTurn authoritative turn flag
     * @param resolved optional resolved target
     * @return complete battle snapshot
     */
    private static GameView battleGame(boolean yourTurn, Coordinate resolved) {
        OwnCellView[][] own = new OwnCellView[10][10];
        OpponentCellView[][] opponent = new OpponentCellView[10][10];
        for (int row = 0; row < 10; row++) {
            java.util.Arrays.fill(own[row], OwnCellView.WATER);
            java.util.Arrays.fill(opponent[row], OpponentCellView.UNKNOWN);
        }
        own[0][0] = OwnCellView.SHIP;
        if (resolved != null) {
            opponent[resolved.getRow()][resolved.getColumn()] = OpponentCellView.MISS;
        }
        PlayerView player = new PlayerView(java.util.UUID.randomUUID(), "Alpha", 1000, true);
        PlayerView other = new PlayerView(java.util.UUID.randomUUID(), "Bravo", 1000, true);
        return new GameView(java.util.UUID.randomUUID(), GamePhase.BATTLE, player, other,
                yourTurn, new OwnBoardView(own), new OpponentBoardView(opponent), null, null);
    }
}
