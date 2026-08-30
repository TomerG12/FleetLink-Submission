package io.github.tomerg12.fleetlink.client.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.OperationToken;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import org.junit.jupiter.api.Test;

/** Verifies the D-014 leave barrier and its callback-aware result and transport reconciliation. */
class ClientLeaveReconciliationTest {

    /** Preserves pending leave across placement-to-Battle progression and blocks mutations. */
    @Test
    void waitingLeaveAcceptsBattleCallbackWithoutReleasingBarrier() {
        ClientStateCoordinator coordinator = waitingCoordinator();
        OperationToken leave = coordinator.beginLeaveGame();
        GameView battle = ClientTestFixtures.battleGame();

        coordinator.acceptGameStateChanged(battle);

        assertEquals(ClientPhase.LEAVING_GAME, coordinator.getState().getPhase());
        assertSame(battle, coordinator.getState().getGameView());
        assertThrows(IllegalStateException.class,
                () -> coordinator.beginFire(new Coordinate(0, 0)));
        assertThrows(IllegalStateException.class, coordinator::beginLeaveGame);
        coordinator.completeLeaveGame(leave, OperationResult.success());
        assertEquals(ClientPhase.LOBBY, coordinator.getState().getPhase());
    }

    /** Preserves pending leave and rejects fleet submission after a newer placement callback. */
    @Test
    void placementCallbackPreservesPlacementLeaveBarrier() {
        ClientStateCoordinator coordinator = placementCoordinator();
        coordinator.beginLeaveGame();
        GameView newerPlacement = ClientTestFixtures.fleetPlacementGame();

        coordinator.acceptGameStateChanged(newerPlacement);

        assertEquals(ClientPhase.LEAVING_GAME, coordinator.getState().getPhase());
        assertSame(newerPlacement, coordinator.getState().getGameView());
        assertThrows(IllegalStateException.class, coordinator::beginFleetSubmission);
    }

    /** Reconciles success and both failure kinds after a newer Battle callback. */
    @Test
    void newerBattleCallbackStillAllowsCurrentLeaveToSettle() {
        ClientStateCoordinator success = placementCoordinator();
        OperationToken successToken = success.beginLeaveGame();
        success.acceptGameStateChanged(ClientTestFixtures.battleGame());
        success.completeLeaveGame(successToken, OperationResult.success());
        assertEquals(ClientPhase.LOBBY, success.getState().getPhase());

        ClientStateCoordinator expected = placementCoordinator();
        OperationToken expectedToken = expected.beginLeaveGame();
        GameView expectedBattle = ClientTestFixtures.battleGame();
        expected.acceptGameStateChanged(expectedBattle);
        expected.completeLeaveGame(expectedToken, OperationResult.failure(
                ResultCode.NOT_IN_GAME, "Forfeit rejected"));
        assertEquals(ClientPhase.BATTLE, expected.getState().getPhase());
        assertSame(expectedBattle, expected.getState().getGameView());
        assertEquals("Forfeit rejected", expected.getState().getStatusMessage());

        ClientStateCoordinator transport = placementCoordinator();
        OperationToken transportToken = transport.beginLeaveGame();
        GameView transportBattle = ClientTestFixtures.battleGame();
        transport.acceptGameStateChanged(transportBattle);
        transport.failLeaveGame(transportToken, "Transport unavailable");
        assertEquals(ClientPhase.BATTLE, transport.getState().getPhase());
        assertSame(transportBattle, transport.getState().getGameView());
        assertEquals("Transport unavailable", transport.getState().getStatusMessage());
    }

    /** Restores each exact placement source after expected and transport failure. */
    @Test
    void placementFailuresRestoreExactSourceWithNewestView() {
        assertPlacementFailureRestores(ClientPhase.SHIP_PLACEMENT, false);
        assertPlacementFailureRestores(ClientPhase.SHIP_PLACEMENT, true);
        assertPlacementFailureRestores(ClientPhase.WAITING_FOR_BATTLE, false);
        assertPlacementFailureRestores(ClientPhase.WAITING_FOR_BATTLE, true);
    }

    /** Terminal callback and later Lobby lifecycle have authority over late leave settlement. */
    @Test
    void terminalCallbackMakesLateLeaveSettlementInvisibleAfterLaterLifecycle() {
        ClientStateCoordinator success = battleCoordinator();
        OperationToken successToken = success.beginLeaveGame();
        success.acceptGameStateChanged(ClientTestFixtures.finishedGame(false));
        success.beginReturnToLobby();
        long successRevision = success.getState().getRevision();
        success.completeLeaveGame(successToken, OperationResult.success());
        assertEquals(ClientPhase.LOBBY, success.getState().getPhase());
        assertEquals(successRevision, success.getState().getRevision());

        ClientStateCoordinator transport = battleCoordinator();
        OperationToken transportToken = transport.beginLeaveGame();
        transport.acceptGameStateChanged(ClientTestFixtures.finishedGame(false));
        long terminalRevision = transport.getState().getRevision();
        transport.failLeaveGame(transportToken, "Late transport failure");
        assertEquals(ClientPhase.GAME_OVER, transport.getState().getPhase());
        assertEquals(terminalRevision, transport.getState().getRevision());
    }

    /** Allows exactly placement, waiting, and Battle as leave source phases. */
    @Test
    void leaveAdmissionAllowsOnlyApprovedSourcePhases() {
        ClientStateCoordinator placement = placementCoordinator();
        placement.beginLeaveGame();
        assertEquals(ClientPhase.LEAVING_GAME, placement.getState().getPhase());
        ClientStateCoordinator waiting = waitingCoordinator();
        waiting.beginLeaveGame();
        assertEquals(ClientPhase.LEAVING_GAME, waiting.getState().getPhase());
        ClientStateCoordinator battle = battleCoordinator();
        battle.beginLeaveGame();
        assertEquals(ClientPhase.LEAVING_GAME, battle.getState().getPhase());

        ClientStateCoordinator lobby = connectedCoordinator();
        assertThrows(IllegalStateException.class, lobby::beginLeaveGame);

        ClientStateCoordinator submitting = placementCoordinator();
        submitting.beginFleetSubmission();
        assertThrows(IllegalStateException.class, submitting::beginLeaveGame);

        ClientStateCoordinator firing = battleCoordinator();
        firing.beginFire(new Coordinate(0, 0));
        assertThrows(IllegalStateException.class, firing::beginLeaveGame);

        ClientStateCoordinator gameOver = battleCoordinator();
        gameOver.acceptGameStateChanged(ClientTestFixtures.finishedGame(true));
        assertThrows(IllegalStateException.class, gameOver::beginLeaveGame);
    }

    /**
     * Exercises one placement-source failure combination.
     *
     * @param source expected placement source
     * @param transport whether transport rather than an expected result fails
     */
    private static void assertPlacementFailureRestores(ClientPhase source, boolean transport) {
        ClientStateCoordinator coordinator = source == ClientPhase.SHIP_PLACEMENT
                ? placementCoordinator() : waitingCoordinator();
        OperationToken token = coordinator.beginLeaveGame();
        GameView newest = ClientTestFixtures.fleetPlacementGame();
        coordinator.acceptGameStateChanged(newest);
        if (transport) {
            coordinator.failLeaveGame(token, "Transport unavailable");
        } else {
            coordinator.completeLeaveGame(token, OperationResult.failure(
                    ResultCode.NOT_IN_GAME, "Forfeit rejected"));
        }
        assertEquals(source, coordinator.getState().getPhase());
        assertSame(newest, coordinator.getState().getGameView());
    }

    /**
     * Creates an established Lobby coordinator.
     *
     * @return connected coordinator
     */
    private static ClientStateCoordinator connectedCoordinator() {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        OperationToken connection = coordinator.beginGuestConnection();
        coordinator.completeGuestConnection(connection,
                SessionResult.success(ClientTestFixtures.session()));
        return coordinator;
    }

    /**
     * Creates a coordinator in active fleet placement.
     *
     * @return placement coordinator
     */
    private static ClientStateCoordinator placementCoordinator() {
        ClientStateCoordinator coordinator = connectedCoordinator();
        coordinator.acceptMatchFound(ClientTestFixtures.fleetPlacementGame());
        return coordinator;
    }

    /**
     * Creates a coordinator waiting after an accepted local fleet.
     *
     * @return waiting coordinator
     */
    private static ClientStateCoordinator waitingCoordinator() {
        ClientStateCoordinator coordinator = placementCoordinator();
        OperationToken submission = coordinator.beginFleetSubmission();
        coordinator.completeFleetSubmission(submission,
                FleetSubmissionResult.accepted(ClientTestFixtures.fleetPlacementGame()));
        return coordinator;
    }

    /**
     * Creates a coordinator in authoritative Battle.
     *
     * @return Battle coordinator
     */
    private static ClientStateCoordinator battleCoordinator() {
        ClientStateCoordinator coordinator = placementCoordinator();
        coordinator.acceptGameStateChanged(ClientTestFixtures.battleGame());
        assertEquals(GamePhase.BATTLE, coordinator.getState().getGameView().getPhase());
        return coordinator;
    }
}
