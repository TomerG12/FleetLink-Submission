package io.github.tomerg12.fleetlink.client.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.OperationToken;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.LogoutReconciliation;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingState;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentBoardView;
import io.github.tomerg12.fleetlink.shared.protocol.OpponentCellView;
import io.github.tomerg12.fleetlink.shared.protocol.OwnBoardView;
import io.github.tomerg12.fleetlink.shared.protocol.OwnCellView;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.protocol.ShotOutcome;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import org.junit.jupiter.api.Test;

/**
 * Verifies monotonic callback/result reconciliation independently from JavaFX controls.
 */
class ClientStateCoordinatorTest {

    /**
     * Reuses the session-establishment reconciliation path for login and registration outcomes.
     */
    @Test
    void registeredSessionEstablishmentUsesCommonGenerationAndRecoverableFailure() {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        OperationToken failedLogin = coordinator.beginRegisteredConnection(false);

        coordinator.completeRegisteredConnection(failedLogin,
                SessionResult.failure(ResultCode.INVALID_CREDENTIALS, "Invalid credentials"),
                false);

        assertEquals(ClientPhase.LOGIN, coordinator.getState().getPhase());
        OperationToken registration = coordinator.beginRegisteredConnection(true);
        coordinator.completeRegisteredConnection(registration,
                SessionResult.success(ClientTestFixtures.registeredSession()), true);
        assertEquals(ClientPhase.LOBBY, coordinator.getState().getPhase());
        assertFalse(coordinator.getState().getSessionInfo().getPlayer().isGuest());
    }

    /**
     * Confirms match callback state is stored before UI scheduling and survives a late MATCHED result.
     */
    @Test
    void matchCallbackBeforeJoinResultCannotRegressState() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ClientStateCoordinator coordinator = connectedCoordinator(dispatcher);
        dispatcher.runAll();
        List<ClientState> delivered = new ArrayList<>();
        coordinator.setStateListener(delivered::add);

        OperationToken join = coordinator.beginMatchmaking();
        dispatcher.runAll();
        delivered.clear();
        coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());

        assertEquals(ClientPhase.SHIP_PLACEMENT, coordinator.getState().getPhase());
        assertTrue(delivered.isEmpty());

        dispatcher.runAll();
        assertEquals(ClientPhase.SHIP_PLACEMENT, delivered.get(delivered.size() - 1).getPhase());
        long callbackRevision = coordinator.getState().getRevision();

        coordinator.completeMatchmaking(join,
                MatchmakingResult.success(MatchmakingState.MATCHED));

        assertEquals(ClientPhase.SHIP_PLACEMENT, coordinator.getState().getPhase());
        assertEquals(callbackRevision, coordinator.getState().getRevision());
    }

    /**
     * Confirms a BATTLE callback supersedes the older placement snapshot returned by submitFleet.
     */
    @Test
    void battleCallbackBeforeFleetResultCannotRegressState() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ClientStateCoordinator coordinator = connectedCoordinator(dispatcher);
        coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
        OperationToken submission = coordinator.beginFleetSubmission();

        coordinator.acceptGameStateChanged(ClientTestFixtures.battleGame());
        long battleRevision = coordinator.getState().getRevision();
        coordinator.completeFleetSubmission(submission,
                FleetSubmissionResult.accepted(ClientTestFixtures.fleetPlacementGame()));

        assertEquals(ClientPhase.BATTLE, coordinator.getState().getPhase());
        assertEquals(ClientTestFixtures.battleGame().getGameId(),
                coordinator.getState().getGameView().getGameId());
        assertEquals(battleRevision, coordinator.getState().getRevision());
    }

    /**
     * Confirms late transport failure from an older operation cannot replace newer callback state.
     */
    @Test
    void newerCallbackWinsBeforeOlderOperationFailureProcessing() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run);
        OperationToken join = coordinator.beginMatchmaking();
        coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());

        coordinator.failOperation(join, "late transport failure");

        assertEquals(ClientPhase.SHIP_PLACEMENT, coordinator.getState().getPhase());
        assertEquals("Opponent found. Place your fleet.",
                coordinator.getState().getStatusMessage());
    }

    /**
     * Confirms a newer game callback cannot be regressed by an older fire result.
     */
    @Test
    void gameCallbackBeforeFireResultCannotRegressState() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run);
        coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
        coordinator.acceptGameStateChanged(ClientTestFixtures.battleGame());
        Coordinate target = new Coordinate(0, 0);
        OperationToken fire = coordinator.beginFire(target);
        GameView newer = ClientTestFixtures.battleGameAfterShot(
                false, target, OpponentCellView.HIT);

        coordinator.acceptGameStateChanged(newer);
        long callbackRevision = coordinator.getState().getRevision();
        coordinator.completeFire(fire, ShotResult.accepted(
                ShotOutcome.HIT, ClientTestFixtures.battleGame(false)));

        assertEquals(ClientPhase.BATTLE, coordinator.getState().getPhase());
        assertEquals(OpponentCellView.HIT,
                coordinator.getState().getGameView().getOpponentBoard().getCell(target));
        assertEquals(callbackRevision, coordinator.getState().getRevision());
    }

    /**
     * Confirms a terminal authoritative fire snapshot enters Game Over.
     */
    @Test
    void terminalFireResultEntersGameOverWithoutLocalPrediction() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run);
        coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
        coordinator.acceptGameStateChanged(ClientTestFixtures.battleGame());
        OperationToken fire = coordinator.beginFire(new Coordinate(0, 0));

        coordinator.completeFire(fire, ShotResult.accepted(
                ShotOutcome.SUNK, ClientTestFixtures.finishedGame(true)));

        assertEquals(ClientPhase.GAME_OVER, coordinator.getState().getPhase());
        assertEquals(ClientTestFixtures.finishedGame(true).getWinner().getPlayerId(),
                coordinator.getState().getGameView().getWinner().getPlayerId());
    }

    /**
     * Confirms return to Lobby clears only a game already marked complete by the server.
     */
    @Test
    void completedGameCanReturnToLobbyWithoutClearingSession() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run);
        coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
        coordinator.acceptGameStateChanged(ClientTestFixtures.finishedGame(true));

        ClientState lobby = coordinator.beginReturnToLobby().getLobbyState();

        assertEquals(ClientPhase.LOBBY, lobby.getPhase());
        assertEquals(ClientTestFixtures.session().getSessionId(),
                lobby.getSessionInfo().getSessionId());
        assertEquals(null, lobby.getGameView());
        assertThrows(IllegalStateException.class, coordinator::beginReturnToLobby);
        assertThrows(IllegalArgumentException.class, () ->
                coordinator.acceptGameStateChanged(ClientTestFixtures.finishedGame(true)));
        assertEquals(ClientPhase.LOBBY, coordinator.getState().getPhase());
    }

    /**
     * Confirms a terminal callback supersedes an older in-flight leave result.
     */
    @Test
    void gameOverCallbackBeforeLeaveResultCannotRegressToLobby() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run);
        coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
        coordinator.acceptGameStateChanged(ClientTestFixtures.battleGame());
        OperationToken leave = coordinator.beginLeaveGame();

        coordinator.acceptGameStateChanged(ClientTestFixtures.finishedGame(false));
        long finishedRevision = coordinator.getState().getRevision();
        coordinator.completeLeaveGame(leave, OperationResult.success());

        assertEquals(ClientPhase.GAME_OVER, coordinator.getState().getPhase());
        assertEquals(finishedRevision, coordinator.getState().getRevision());
    }

    /**
     * Confirms UI tasks read latest state even if dispatcher execution order differs from acceptance.
     */
    @Test
    void presentationTasksCannotReplayOlderAcceptedState() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ClientStateCoordinator coordinator = connectedCoordinator(dispatcher);
        dispatcher.runAll();
        List<ClientPhase> delivered = new ArrayList<>();
        coordinator.setStateListener(state -> delivered.add(state.getPhase()));

        OperationToken join = coordinator.beginMatchmaking();
        coordinator.completeMatchmaking(join,
                MatchmakingResult.success(MatchmakingState.WAITING));
        coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
        dispatcher.runAllReverse();

        assertEquals(List.of(ClientPhase.SHIP_PLACEMENT, ClientPhase.SHIP_PLACEMENT,
                ClientPhase.SHIP_PLACEMENT), delivered);
    }

    /**
     * Confirms successful logout terminates the session even when a match callback arrived first.
     */
    @Test
    void matchCallbackBeforeSuccessfulLogoutCannotDefeatSessionTermination() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run);
        OperationToken join = coordinator.beginMatchmaking();
        OperationToken logout = coordinator.beginLogout();
        coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());

        assertEquals(ClientPhase.SHIP_PLACEMENT, coordinator.getState().getPhase());

        LogoutReconciliation reconciliation = coordinator.completeLogout(
                logout, OperationResult.success());
        coordinator.completeMatchmaking(join,
                MatchmakingResult.success(MatchmakingState.WAITING));

        assertTrue(reconciliation.isTerminal());
        assertLoggedOut(reconciliation.getState());
        assertLoggedOut(coordinator.getState());
    }

    /**
     * Confirms invalid session is a terminal client outcome even after callback progression.
     */
    @Test
    void matchCallbackBeforeInvalidSessionLogoutStillEndsLocalSession() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run);
        OperationToken logout = beginWaitingLogout(coordinator);
        coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());

        LogoutReconciliation reconciliation = coordinator.completeLogout(logout,
                OperationResult.failure(ResultCode.INVALID_SESSION, "Session expired"));

        assertTrue(reconciliation.isTerminal());
        assertLoggedOut(reconciliation.getState());
        assertLoggedOut(coordinator.getState());
    }

    /**
     * Confirms a recoverable logout result cannot roll back a newer match callback.
     */
    @Test
    void matchCallbackBeforeRecoverableLogoutFailurePreservesCallbackState() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run);
        OperationToken logout = beginWaitingLogout(coordinator);
        coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
        long callbackRevision = coordinator.getState().getRevision();

        LogoutReconciliation reconciliation = coordinator.completeLogout(logout,
                OperationResult.failure(ResultCode.INVALID_REQUEST, "Logout not completed"));

        assertFalse(reconciliation.isTerminal());
        assertEquals(ClientPhase.SHIP_PLACEMENT, reconciliation.getState().getPhase());
        assertEquals(callbackRevision, reconciliation.getState().getRevision());
        assertEquals(ClientTestFixtures.fleetPlacementGame().getGameId(),
                reconciliation.getState().getGameView().getGameId());
    }

    /**
     * Confirms delayed presentation always reads terminal state instead of replaying a callback.
     */
    @Test
    void delayedCallbackPresentationCannotReplayGameAfterTerminalLogout() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        ClientStateCoordinator coordinator = connectedCoordinator(dispatcher);
        dispatcher.runAll();
        List<ClientState> delivered = new ArrayList<>();
        coordinator.setStateListener(delivered::add);
        OperationToken logout = beginWaitingLogout(coordinator);
        coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
        coordinator.completeLogout(logout, OperationResult.success());

        dispatcher.runAllReverse();

        assertFalse(delivered.isEmpty());
        for (ClientState presented : delivered) {
            assertLoggedOut(presented);
        }
        assertLoggedOut(coordinator.getState());
    }

    /**
     * Confirms a callback from the ended session cannot recreate authenticated or game state.
     */
    @Test
    void lateCallbackAfterTerminalLogoutCannotResurrectSession() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run);
        OperationToken logout = beginWaitingLogout(coordinator);
        coordinator.completeLogout(logout, OperationResult.success());

        assertThrows(IllegalStateException.class,
                () -> coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame()));

        assertLoggedOut(coordinator.getState());
    }

    /**
     * Confirms callback player and game correlation is enforced at the state boundary.
     */
    @Test
    void callbacksForAnotherPlayerOrGameAreRejected() {
        ClientStateCoordinator coordinator = connectedCoordinator(Runnable::run);
        GameView valid = ClientTestFixtures.fleetPlacementGame();
        OwnCellView[][] own = filledOwnBoard();
        OpponentCellView[][] opponent = filledOpponentBoard();
        PlayerView wrongPlayer = new PlayerView(UUID.randomUUID(), "Wrong Player", 1000, true);
        GameView wrongOwner = new GameView(valid.getGameId(), valid.getPhase(), wrongPlayer,
                valid.getOpponent(), false, new OwnBoardView(own),
                new OpponentBoardView(opponent), null, null);

        assertThrows(IllegalArgumentException.class,
                () -> coordinator.acceptMatchFound(wrongOwner));

        coordinator.acceptMatchFound(valid);
        GameView wrongGame = new GameView(UUID.randomUUID(), valid.getPhase(), valid.getPlayer(),
                valid.getOpponent(), false, new OwnBoardView(own),
                new OpponentBoardView(opponent), null, null);
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.acceptGameStateChanged(wrongGame));
    }

    /**
     * Establishes the deterministic guest session through the normal result path.
     *
     * @param dispatcher dispatcher used by the coordinator
     * @return connected coordinator in the lobby phase
     */
    private static ClientStateCoordinator connectedCoordinator(ClientUiDispatcher dispatcher) {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(dispatcher);
        OperationToken connection = coordinator.beginGuestConnection();
        coordinator.completeGuestConnection(connection,
                SessionResult.success(ClientTestFixtures.session()));
        return coordinator;
    }

    /**
     * Enters waiting matchmaking and begins logout for lifecycle reconciliation tests.
     *
     * @param coordinator connected coordinator
     * @return current logout operation token
     */
    private static OperationToken beginWaitingLogout(ClientStateCoordinator coordinator) {
        OperationToken join = coordinator.beginMatchmaking();
        coordinator.completeMatchmaking(join,
                MatchmakingResult.success(MatchmakingState.WAITING));
        return coordinator.beginLogout();
    }

    /**
     * Verifies the terminal local session invariant.
     *
     * @param state state expected to represent a completed logout
     */
    private static void assertLoggedOut(ClientState state) {
        assertEquals(ClientPhase.LOGIN, state.getPhase());
        assertNull(state.getSessionInfo());
        assertNull(state.getGameView());
    }

    /**
     * Creates a complete empty own-board array for callback validation tests.
     *
     * @return complete own-board cells
     */
    private static OwnCellView[][] filledOwnBoard() {
        OwnCellView[][] cells = new OwnCellView[10][10];
        for (int row = 0; row < 10; row++) {
            java.util.Arrays.fill(cells[row], OwnCellView.WATER);
        }
        return cells;
    }

    /**
     * Creates a complete unknown opponent-board array for callback validation tests.
     *
     * @return complete opponent-board cells
     */
    private static OpponentCellView[][] filledOpponentBoard() {
        OpponentCellView[][] cells = new OpponentCellView[10][10];
        for (int row = 0; row < 10; row++) {
            java.util.Arrays.fill(cells[row], OpponentCellView.UNKNOWN);
        }
        return cells;
    }

    /**
     * Queues UI actions so tests can inspect state before presentation work runs.
     */
    private static final class RecordingDispatcher implements ClientUiDispatcher {
        private final Queue<Runnable> actions = new ArrayDeque<>();

        /**
         * Queues one presentation action without running it immediately.
         *
         * @param action presentation action
         */
        @Override
        public void dispatch(Runnable action) {
            actions.add(action);
        }

        /**
         * Runs every queued action in FIFO order.
         */
        private void runAll() {
            while (!actions.isEmpty()) {
                actions.remove().run();
            }
        }

        /**
         * Runs queued actions in reverse order to simulate cross-thread scheduling reordering.
         */
        private void runAllReverse() {
            List<Runnable> reversed = new ArrayList<>(actions);
            actions.clear();
            for (int index = reversed.size() - 1; index >= 0; index--) {
                reversed.get(index).run();
            }
        }
    }
}
