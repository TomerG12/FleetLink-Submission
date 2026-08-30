package io.github.tomerg12.fleetlink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.util.UUID;

import io.github.tomerg12.fleetlink.client.integration.RematchClientState;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.RematchState;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.ResultCode;
import org.junit.jupiter.api.Test;

/** Verifies toolkit-free Game Over rematch copy and control decisions. */
class GameOverRematchPresentationTest {
    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID GAME_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final PlayerView OPPONENT = new PlayerView(
            UUID.fromString("00000000-0000-0000-0000-000000000103"),
            "Opponent", 1000, true);

    /** Confirms the initial completed screen exposes Request and Return without response controls. */
    @Test
    void initialPresentationExposesRequestAndLobby() throws Exception {
        RematchClientState state = state(null, RematchClientState.InFlightAction.NONE,
                false, false, false, null, false, "");

        assertEquals("Request a rematch or return to Lobby.",
                GameOverScreen.rematchMessage(state));
        assertTrue(state.canRequest());
        assertFalse(state.canAccept());
        assertFalse(state.canDecline());
        assertTrue(state.canReturnToLobby());
    }

    /** Confirms synchronous Request success has distinct copy without inventing a server status. */
    @Test
    void acknowledgedRequestUsesWaitingCopyAndSafeControls() throws Exception {
        RematchClientState state = state(null, RematchClientState.InFlightAction.NONE,
                true, false, false, null, false, "");

        assertEquals(RematchClientState.Presentation.REQUEST_ACKNOWLEDGED,
                state.getPresentation());
        assertEquals("Rematch requested. Waiting for your opponent.",
                GameOverScreen.rematchMessage(state));
        assertFalse("Request a rematch or return to Lobby."
                .equals(GameOverScreen.rematchMessage(state)));
        assertFalse(state.canRequest());
        assertFalse(state.canAccept());
        assertFalse(state.canDecline());
        assertTrue(state.canReturnToLobby());
    }

    /** Confirms incoming, busy, and recoverable-failure states use the approved control matrix. */
    @Test
    void incomingAndFailurePresentationFollowSafeControls() throws Exception {
        RematchStatusView incoming = status(RematchState.REQUESTED_BY_OPPONENT);
        RematchClientState ready = state(incoming, RematchClientState.InFlightAction.NONE,
                false, false, false, null, false, "");
        assertEquals("Your opponent requested a rematch.",
                GameOverScreen.rematchMessage(ready));
        assertTrue(ready.canAccept());
        assertTrue(ready.canDecline());

        RematchClientState busy = state(incoming, RematchClientState.InFlightAction.ACCEPT,
                false, false, false, null, false, "");
        assertEquals("Accepting the rematch...", GameOverScreen.rematchMessage(busy));
        assertFalse(busy.canAccept());
        assertFalse(busy.canDecline());
        assertTrue(busy.canReturnToLobby());

        RematchClientState failure = state(incoming, RematchClientState.InFlightAction.NONE,
                false, false, false, null, true, "Connection uncertain.");
        assertEquals("Connection uncertain.", GameOverScreen.rematchMessage(failure));
        assertTrue(failure.canAccept());
        assertTrue(failure.canDecline());
    }

    /** Confirms accepted disables every mutation and Lobby while terminal failures retain Lobby. */
    @Test
    void terminalPresentationsKeepOnlySemanticallySafeControls() throws Exception {
        RematchClientState accepted = state(status(RematchState.ACCEPTED),
                RematchClientState.InFlightAction.NONE, false, false, true,
                null, false, "");
        assertEquals("Rematch accepted. Starting new game...",
                GameOverScreen.rematchMessage(accepted));
        assertFalse(accepted.canRequest());
        assertFalse(accepted.canAccept());
        assertFalse(accepted.canDecline());
        assertFalse(accepted.canReturnToLobby());

        RematchClientState declined = state(status(RematchState.DECLINED),
                RematchClientState.InFlightAction.NONE, false, false, false,
                null, false, "");
        RematchClientState expired = state(status(RematchState.EXPIRED),
                RematchClientState.InFlightAction.NONE, false, false, false,
                null, false, "");
        assertEquals("The rematch was declined.", GameOverScreen.rematchMessage(declined));
        assertEquals("The rematch opportunity expired.", GameOverScreen.rematchMessage(expired));
        assertTrue(declined.canReturnToLobby());
        assertTrue(expired.canReturnToLobby());
    }

    /** Creates one transport status for the selected authoritative state. */
    private static RematchStatusView status(RematchState state) {
        return new RematchStatusView(GAME_ID, OPPONENT, state);
    }

    /**
     * Constructs coordinator-owned immutable state reflectively so the UI test does not make its
     * package-private mutation constructor part of production presentation API.
     */
    private static RematchClientState state(RematchStatusView status,
                                            RematchClientState.InFlightAction action,
                                            boolean requestAcknowledged,
                                            boolean declineAcknowledged,
                                            boolean creationCommitted,
                                            ResultCode feedbackCode,
                                            boolean transportFailure,
                                            String feedbackMessage) throws Exception {
        Constructor<RematchClientState> constructor = RematchClientState.class
                .getDeclaredConstructor(UUID.class, UUID.class, RematchStatusView.class,
                        RematchClientState.InFlightAction.class, boolean.class, boolean.class,
                        boolean.class, ResultCode.class, boolean.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(SESSION_ID, GAME_ID, status, action,
                requestAcknowledged, declineAcknowledged, creationCommitted,
                feedbackCode, transportFailure, feedbackMessage);
    }
}
