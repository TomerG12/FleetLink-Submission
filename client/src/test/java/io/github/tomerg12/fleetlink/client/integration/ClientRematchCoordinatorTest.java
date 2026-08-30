package io.github.tomerg12.fleetlink.client.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.rmi.RemoteException;
import java.util.List;
import java.util.UUID;

import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.LogoutReconciliation;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.OperationToken;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.RematchOperationToken;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.ReturnToLobbyAction;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator.ReturnToLobbyPlan;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchState;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import io.github.tomerg12.fleetlink.shared.protocol.SessionInfo;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import org.junit.jupiter.api.Test;

/**
 * Verifies exact rematch identity, callback freshness, terminal retirement, and Lobby lifecycle.
 */
class ClientRematchCoordinatorTest {

    /** Confirms every new completed game receives one isolated initial rematch slice. */
    @Test
    void initialGameOverBindsExactSessionAndCompletedGame() {
        ClientStateCoordinator coordinator = gameOverCoordinator();

        RematchClientState rematch = coordinator.getState().getRematchState();

        assertEquals(ClientPhase.GAME_OVER, coordinator.getState().getPhase());
        assertEquals(ClientTestFixtures.session().getSessionId(), rematch.getSessionId());
        assertEquals(ClientTestFixtures.finishedGame(true).getGameId(),
                rematch.getCompletedGameId());
        assertEquals(RematchClientState.Presentation.INITIAL, rematch.getPresentation());
        assertTrue(rematch.canRequest());
        assertTrue(rematch.canReturnToLobby());
        assertFalse(rematch.canAccept());
        assertFalse(rematch.canDecline());
    }

    /** Confirms REQUESTED_BY_YOU is preserved while REQUEST remains owner and after success. */
    @Test
    void requestedByYouBeforeRequestSuccessPreservesStatusAndRetiresOperation() {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        RematchOperationToken token = coordinator.beginRematchRequest();

        assertTrue(coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.REQUESTED_BY_YOU)));
        assertEquals(RematchClientState.InFlightAction.REQUEST,
                rematch(coordinator).getInFlightAction());

        coordinator.completeRematchOperation(token, OperationResult.success());

        assertEquals(RematchState.REQUESTED_BY_YOU,
                rematch(coordinator).getAuthoritativeStatus().getState());
        assertEquals(RematchClientState.InFlightAction.NONE,
                rematch(coordinator).getInFlightAction());
        assertTrue(rematch(coordinator).isRequestAcknowledged());
        assertFalse(rematch(coordinator).canRequest());
    }

    /** Confirms transport uncertainty cannot erase a callback or leave REQUEST permanently busy. */
    @Test
    void requestedByYouBeforeRequestTransportFailureRemainsAuthoritative() {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        RematchOperationToken token = coordinator.beginRematchRequest();
        coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.REQUESTED_BY_YOU));

        coordinator.failRematchOperation(token, "transport uncertain");

        assertEquals(RematchState.REQUESTED_BY_YOU,
                rematch(coordinator).getAuthoritativeStatus().getState());
        assertEquals(RematchClientState.InFlightAction.NONE,
                rematch(coordinator).getInFlightAction());
        assertTrue(rematch(coordinator).isTransportFailure());
        assertFalse(rematch(coordinator).canRequest());
    }

    /** Confirms simultaneous-request callback does not expose Accept or Decline until REQUEST ends. */
    @Test
    void incomingRequestDuringUnresolvedRequestKeepsSingleStreamOwner() {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        coordinator.beginRematchRequest();

        coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.REQUESTED_BY_OPPONENT));

        assertEquals(RematchClientState.InFlightAction.REQUEST,
                rematch(coordinator).getInFlightAction());
        assertFalse(rematch(coordinator).canAccept());
        assertFalse(rematch(coordinator).canDecline());
        assertThrows(IllegalStateException.class, () -> coordinator.beginRematchResponse(true));
    }

    /** Confirms successful simultaneous intent commits creation but still remains in Game Over. */
    @Test
    void incomingRequestThenRequestSuccessAwaitsAuthoritativeNewGame() {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        RematchOperationToken token = coordinator.beginRematchRequest();
        coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.REQUESTED_BY_OPPONENT));

        coordinator.completeRematchOperation(token, OperationResult.success());

        assertTrue(rematch(coordinator).isCreationCommitted());
        assertEquals(RematchClientState.Presentation.AWAITING_NEW_GAME,
                rematch(coordinator).getPresentation());
        assertEquals(ClientPhase.GAME_OVER, coordinator.getState().getPhase());
        assertFalse(rematch(coordinator).canReturnToLobby());
    }

    /** Confirms failed simultaneous Request restores safe incoming-request controls. */
    @Test
    void incomingRequestThenRequestTransportFailureRestoresResponseControls() {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        RematchOperationToken token = coordinator.beginRematchRequest();
        coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.REQUESTED_BY_OPPONENT));

        coordinator.failRematchOperation(token, "unknown request outcome");

        assertEquals(RematchState.REQUESTED_BY_OPPONENT,
                rematch(coordinator).getAuthoritativeStatus().getState());
        assertTrue(rematch(coordinator).canAccept());
        assertTrue(rematch(coordinator).canDecline());
        assertFalse(rematch(coordinator).isCreationCommitted());
    }

    /** Confirms plain Request acknowledgement is stored without inventing a transport status. */
    @Test
    void requestSuccessWithoutCallbackStoresOnlyAcknowledgement() {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        RematchOperationToken token = coordinator.beginRematchRequest();

        coordinator.completeRematchOperation(token, OperationResult.success());

        RematchClientState acknowledged = rematch(coordinator);
        assertNull(acknowledged.getAuthoritativeStatus());
        assertTrue(acknowledged.isRequestAcknowledged());
        assertEquals(RematchClientState.InFlightAction.NONE,
                acknowledged.getInFlightAction());
        assertFalse(acknowledged.isCreationCommitted());
        assertEquals(RematchClientState.Presentation.REQUEST_ACKNOWLEDGED,
                acknowledged.getPresentation());
        assertNotEquals(RematchClientState.Presentation.INITIAL,
                acknowledged.getPresentation());
        assertFalse(acknowledged.canRequest());
        assertFalse(acknowledged.canAccept());
        assertFalse(acknowledged.canDecline());
        assertTrue(acknowledged.canReturnToLobby());

        ReturnToLobbyPlan plan = coordinator.beginReturnToLobby();
        assertEquals(ReturnToLobbyAction.SEND_FALSE, plan.getAction());
        assertEquals(sessionId(), plan.getSessionId());
        assertEquals(ClientPhase.LOBBY, plan.getLobbyState().getPhase());
        assertEquals(ClientPhase.LOBBY, coordinator.getState().getPhase());
        assertNull(coordinator.getState().getRematchState());
        assertFalse(coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.REQUESTED_BY_YOU)));
    }

    /** Confirms a later own-request callback supersedes local acknowledgement presentation. */
    @Test
    void acknowledgedRequestThenRequestedByYouCallbackUsesAuthoritativePresentation() {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        coordinator.completeRematchOperation(coordinator.beginRematchRequest(),
                OperationResult.success());
        assertEquals(RematchClientState.Presentation.REQUEST_ACKNOWLEDGED,
                rematch(coordinator).getPresentation());

        coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.REQUESTED_BY_YOU));

        assertEquals(RematchState.REQUESTED_BY_YOU,
                rematch(coordinator).getAuthoritativeStatus().getState());
        assertEquals(RematchClientState.Presentation.REQUESTED_BY_YOU,
                rematch(coordinator).getPresentation());
        assertFalse(rematch(coordinator).canRequest());
        assertTrue(rematch(coordinator).canReturnToLobby());
    }

    /** Confirms later incoming status combines with prior acknowledgement as mutual agreement. */
    @Test
    void acknowledgedRequestThenIncomingCallbackCommitsCreation() {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        coordinator.completeRematchOperation(coordinator.beginRematchRequest(),
                OperationResult.success());
        assertEquals(RematchClientState.Presentation.REQUEST_ACKNOWLEDGED,
                rematch(coordinator).getPresentation());

        coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.REQUESTED_BY_OPPONENT));

        assertTrue(rematch(coordinator).isCreationCommitted());
        assertEquals(RematchClientState.Presentation.AWAITING_NEW_GAME,
                rematch(coordinator).getPresentation());
        assertFalse(rematch(coordinator).canAccept());
        assertFalse(rematch(coordinator).canDecline());
        assertFalse(rematch(coordinator).canReturnToLobby());
        assertEquals(ClientPhase.GAME_OVER, coordinator.getState().getPhase());
    }

    /** Confirms ACCEPTED retires Accept and a late result or failure has zero effect. */
    @Test
    void acceptedCallbackBeforeAcceptResultRetiresOperationWithoutNavigation() {
        ClientStateCoordinator coordinator = incomingRequestCoordinator();
        RematchOperationToken token = coordinator.beginRematchResponse(true);
        coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.ACCEPTED));
        long revision = coordinator.getState().getRevision();

        coordinator.completeRematchOperation(token, OperationResult.success());
        coordinator.failRematchOperation(token, "late failure");

        assertEquals(revision, coordinator.getState().getRevision());
        assertEquals(ClientPhase.GAME_OVER, coordinator.getState().getPhase());
        assertTrue(rematch(coordinator).isCreationCommitted());

        UUID newGameId = UUID.fromString("00000000-0000-0000-0000-000000000035");
        coordinator.acceptMatchFound(sessionId(),
                ClientTestFixtures.fleetPlacementGame(newGameId));
        assertEquals(ClientPhase.SHIP_PLACEMENT, coordinator.getState().getPhase());
        assertEquals(newGameId, coordinator.getState().getGameView().getGameId());
    }

    /** Confirms DECLINED retires Decline and remains a usable terminal Game Over state. */
    @Test
    void declinedCallbackBeforeDeclineResultWinsPermanently() {
        ClientStateCoordinator coordinator = incomingRequestCoordinator();
        RematchOperationToken token = coordinator.beginRematchResponse(false);
        coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.DECLINED));
        long revision = coordinator.getState().getRevision();

        coordinator.completeRematchOperation(token, OperationResult.success());

        assertEquals(revision, coordinator.getState().getRevision());
        assertEquals(RematchClientState.Presentation.DECLINED,
                rematch(coordinator).getPresentation());
        assertTrue(rematch(coordinator).canReturnToLobby());
        assertFalse(rematch(coordinator).canAccept());
    }

    /** Confirms EXPIRED terminal authority retires an unresolved mutation and ignores its result. */
    @Test
    void expiredCallbackBeforeOperationResultRetiresGeneration() {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        RematchOperationToken token = coordinator.beginRematchRequest();
        coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.EXPIRED));
        long revision = coordinator.getState().getRevision();

        coordinator.completeRematchOperation(token, OperationResult.success());

        assertEquals(revision, coordinator.getState().getRevision());
        assertEquals(RematchClientState.Presentation.EXPIRED,
                rematch(coordinator).getPresentation());
        assertTrue(rematch(coordinator).canReturnToLobby());
    }

    /** Confirms current expected failures are structured and transport failures restore safe input. */
    @Test
    void expectedAndTransportFailuresPreserveNewestAuthoritativeState() {
        ClientStateCoordinator unavailable = gameOverCoordinator();
        unavailable.completeRematchOperation(unavailable.beginRematchRequest(),
                OperationResult.failure(ResultCode.REMATCH_NOT_AVAILABLE, "Unavailable"));
        assertEquals(ResultCode.REMATCH_NOT_AVAILABLE,
                rematch(unavailable).getFeedbackCode());
        assertFalse(rematch(unavailable).canRequest());

        ClientStateCoordinator incoming = incomingRequestCoordinator();
        incoming.failRematchOperation(incoming.beginRematchResponse(true), "network failed");
        assertEquals(RematchState.REQUESTED_BY_OPPONENT,
                rematch(incoming).getAuthoritativeStatus().getState());
        assertTrue(rematch(incoming).canAccept());
        assertTrue(rematch(incoming).canDecline());
    }

    /** Confirms failed correlation changes neither callback epoch nor active operation ownership. */
    @Test
    void invalidCallbackDoesNotAdvanceEpochOrRetireRequest() {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        RematchOperationToken first = coordinator.beginRematchRequest();
        PlayerView wrongOpponent = new PlayerView(UUID.randomUUID(), "Wrong", 1000, true);

        assertFalse(coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(
                        ClientTestFixtures.finishedGame(true).getGameId(), wrongOpponent,
                        RematchState.REQUESTED_BY_YOU)));
        assertEquals(RematchClientState.InFlightAction.REQUEST,
                rematch(coordinator).getInFlightAction());
        coordinator.completeRematchOperation(first,
                OperationResult.failure(ResultCode.INVALID_REQUEST, "Retry"));
        RematchOperationToken second = coordinator.beginRematchRequest();

        assertEquals(first.getRematchCallbackEpochAtBegin(),
                second.getRematchCallbackEpochAtBegin());
    }

    /** Confirms onMatchFound is the only event that replaces Game Over with the new game. */
    @Test
    void matchFoundInvalidatesOldResultsAndStatuses() {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        RematchOperationToken token = coordinator.beginRematchRequest();
        UUID newGameId = UUID.fromString("00000000-0000-0000-0000-000000000030");
        GameView newGame = ClientTestFixtures.fleetPlacementGame(newGameId);

        coordinator.acceptMatchFound(sessionId(), newGame);
        long revision = coordinator.getState().getRevision();
        coordinator.completeRematchOperation(token, OperationResult.success());
        coordinator.failRematchOperation(token, "late failure");
        assertFalse(coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.ACCEPTED)));

        assertEquals(revision, coordinator.getState().getRevision());
        assertEquals(ClientPhase.SHIP_PLACEMENT, coordinator.getState().getPhase());
        assertEquals(newGameId, coordinator.getState().getGameView().getGameId());
        assertNull(coordinator.getState().getRematchState());
    }

    /** Confirms onMatchFound also defeats an older Accept completion and every terminal old status. */
    @Test
    void matchFoundBeforeAcceptResultAndLateTerminalStatusesKeepsNewGame() {
        ClientStateCoordinator acceptCoordinator = incomingRequestCoordinator();
        RematchOperationToken accept = acceptCoordinator.beginRematchResponse(true);
        UUID acceptedGameId = UUID.fromString("00000000-0000-0000-0000-000000000034");
        acceptCoordinator.acceptMatchFound(sessionId(),
                ClientTestFixtures.fleetPlacementGame(acceptedGameId));
        acceptCoordinator.completeRematchOperation(accept, OperationResult.success());
        assertEquals(acceptedGameId, acceptCoordinator.getState().getGameView().getGameId());
        assertEquals(ClientPhase.SHIP_PLACEMENT, acceptCoordinator.getState().getPhase());

        for (RematchState terminal : List.of(
                RematchState.ACCEPTED, RematchState.DECLINED, RematchState.EXPIRED)) {
            ClientStateCoordinator coordinator = gameOverCoordinator();
            UUID newGameId = UUID.randomUUID();
            coordinator.acceptMatchFound(sessionId(),
                    ClientTestFixtures.fleetPlacementGame(newGameId));
            long revision = coordinator.getState().getRevision();

            assertFalse(coordinator.acceptRematchStatus(sessionId(),
                    ClientTestFixtures.rematchStatus(terminal)));
            assertEquals(revision, coordinator.getState().getRevision());
            assertEquals(newGameId, coordinator.getState().getGameView().getGameId());
            assertEquals(ClientPhase.SHIP_PLACEMENT, coordinator.getState().getPhase());
        }
    }

    /** Confirms every non-committed Lobby departure queues authoritative server expiration. */
    @Test
    void returnToLobbyPlansMatchTheApprovedPolicy() {
        ClientStateCoordinator initial = gameOverCoordinator();
        ReturnToLobbyPlan initialPlan = initial.beginReturnToLobby();
        assertEquals(ReturnToLobbyAction.SEND_FALSE, initialPlan.getAction());
        assertEquals(ClientPhase.LOBBY, initialPlan.getLobbyState().getPhase());

        ClientStateCoordinator inFlight = gameOverCoordinator();
        inFlight.beginRematchRequest();
        assertEquals(ReturnToLobbyAction.SEND_FALSE,
                inFlight.beginReturnToLobby().getAction());

        ClientStateCoordinator incoming = incomingRequestCoordinator();
        assertEquals(ReturnToLobbyAction.SEND_FALSE,
                incoming.beginReturnToLobby().getAction());

        ClientStateCoordinator requestedByYou = gameOverCoordinator();
        requestedByYou.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.REQUESTED_BY_YOU));
        assertEquals(ReturnToLobbyAction.SEND_FALSE,
                requestedByYou.beginReturnToLobby().getAction());

        for (boolean accept : List.of(true, false)) {
            ClientStateCoordinator responseInFlight = incomingRequestCoordinator();
            responseInFlight.beginRematchResponse(accept);
            assertEquals(ReturnToLobbyAction.SEND_FALSE,
                    responseInFlight.beginReturnToLobby().getAction());
        }

        ClientStateCoordinator committed = incomingRequestCoordinator();
        committed.completeRematchOperation(committed.beginRematchResponse(true),
                OperationResult.success());
        assertThrows(IllegalStateException.class, committed::beginReturnToLobby);
        assertEquals(ClientPhase.GAME_OVER, committed.getState().getPhase());
    }

    /** Confirms same-session new game remains authoritative after local Lobby commitment. */
    @Test
    void matchFoundAfterLocalLobbyAcceptsNewGameAndRejectsOldGameStatus() {
        ClientStateCoordinator coordinator = incomingRequestCoordinator();
        coordinator.beginReturnToLobby();
        UUID newGameId = UUID.fromString("00000000-0000-0000-0000-000000000031");

        coordinator.acceptMatchFound(sessionId(),
                ClientTestFixtures.fleetPlacementGame(newGameId));

        assertEquals(ClientPhase.SHIP_PLACEMENT, coordinator.getState().getPhase());
        assertEquals(newGameId, coordinator.getState().getGameView().getGameId());
        assertFalse(coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.DECLINED)));
    }

    /** Confirms a G1 callback cannot affect Lobby or a later G2 Game Over activation. */
    @Test
    void oldCompletedGameCallbacksCannotAffectLaterLifecycle() {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        UUID oldGameId = ClientTestFixtures.finishedGame(true).getGameId();
        coordinator.beginReturnToLobby();
        assertFalse(coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.EXPIRED)));

        UUID newGameId = UUID.fromString("00000000-0000-0000-0000-000000000032");
        coordinator.acceptMatchFound(sessionId(), ClientTestFixtures.fleetPlacementGame(newGameId));
        coordinator.acceptGameStateChanged(sessionId(),
                ClientTestFixtures.finishedGame(newGameId, true));
        long revision = coordinator.getState().getRevision();

        assertFalse(coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(oldGameId,
                        ClientTestFixtures.opponentView(), RematchState.DECLINED)));
        assertEquals(revision, coordinator.getState().getRevision());
        assertEquals(newGameId, coordinator.getState().getGameView().getGameId());
    }

    /** Confirms exact callback session identity rejects S1 after the same account establishes S2. */
    @Test
    void oldEndpointSessionCannotMutateReplacementSessionForSamePlayer() {
        ClientStateCoordinator coordinator = registeredCoordinator(
                ClientTestFixtures.registeredSession());
        UUID firstSession = coordinator.getState().getSessionInfo().getSessionId();
        coordinator.acceptMatchFound(firstSession, ClientTestFixtures.fleetPlacementGame());
        coordinator.acceptGameStateChanged(firstSession, ClientTestFixtures.finishedGame(true));
        coordinator.beginReturnToLobby();
        LogoutReconciliation logout = coordinator.completeLogout(coordinator.beginLogout(),
                OperationResult.success());
        assertTrue(logout.isTerminal());

        establishRegisteredSession(coordinator, ClientTestFixtures.replacementRegisteredSession());
        UUID secondSession = coordinator.getState().getSessionInfo().getSessionId();
        UUID secondGameId = UUID.fromString("00000000-0000-0000-0000-000000000033");
        coordinator.acceptMatchFound(secondSession,
                ClientTestFixtures.fleetPlacementGame(secondGameId));
        coordinator.acceptGameStateChanged(secondSession,
                ClientTestFixtures.finishedGame(secondGameId, true));
        long revision = coordinator.getState().getRevision();

        assertFalse(coordinator.acceptRematchStatus(firstSession,
                ClientTestFixtures.rematchStatus(secondGameId,
                        ClientTestFixtures.opponentView(), RematchState.ACCEPTED)));
        assertThrows(IllegalArgumentException.class, () -> coordinator.acceptMatchFound(
                firstSession, ClientTestFixtures.fleetPlacementGame(UUID.randomUUID())));
        assertEquals(revision, coordinator.getState().getRevision());
        assertEquals(secondGameId, coordinator.getState().getGameView().getGameId());
    }

    /** Confirms endpoint binding captures and forwards the exact successful local session. */
    @Test
    void callbackEndpointRequiresAndUsesExactSessionBinding() throws RemoteException {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        ClientCallbackEndpoint endpoint = new ClientCallbackEndpoint(coordinator);
        assertThrows(RemoteException.class, () -> endpoint.onRematchStatusChanged(
                ClientTestFixtures.rematchStatus(RematchState.DECLINED)));

        endpoint.bindSession(sessionId());
        endpoint.onRematchStatusChanged(
                ClientTestFixtures.rematchStatus(RematchState.DECLINED));

        assertEquals(RematchState.DECLINED,
                rematch(coordinator).getAuthoritativeStatus().getState());
    }

    /** Creates one connected coordinator with a terminal authoritative game. */
    private static ClientStateCoordinator gameOverCoordinator() {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        OperationToken connection = coordinator.beginGuestConnection();
        coordinator.completeGuestConnection(connection,
                SessionResult.success(ClientTestFixtures.session()));
        coordinator.acceptMatchFound(sessionId(), ClientTestFixtures.fleetPlacementGame());
        coordinator.acceptGameStateChanged(sessionId(), ClientTestFixtures.finishedGame(true));
        return coordinator;
    }

    /** Creates Game Over with one current authoritative incoming rematch request. */
    private static ClientStateCoordinator incomingRequestCoordinator() {
        ClientStateCoordinator coordinator = gameOverCoordinator();
        coordinator.acceptRematchStatus(sessionId(),
                ClientTestFixtures.rematchStatus(RematchState.REQUESTED_BY_OPPONENT));
        return coordinator;
    }

    /** Creates one registered Lobby coordinator for exact session replacement tests. */
    private static ClientStateCoordinator registeredCoordinator(SessionInfo sessionInfo) {
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Runnable::run);
        establishRegisteredSession(coordinator, sessionInfo);
        return coordinator;
    }

    /** Establishes a selected registered session through normal result reconciliation. */
    private static void establishRegisteredSession(ClientStateCoordinator coordinator,
                                                   SessionInfo sessionInfo) {
        OperationToken token = coordinator.beginRegisteredConnection(false);
        coordinator.completeRegisteredConnection(token, SessionResult.success(sessionInfo), false);
    }

    /** Returns the current immutable rematch slice. */
    private static RematchClientState rematch(ClientStateCoordinator coordinator) {
        return coordinator.getState().getRematchState();
    }

    /** Returns the deterministic exact guest session. */
    private static UUID sessionId() {
        return ClientTestFixtures.session().getSessionId();
    }
}
