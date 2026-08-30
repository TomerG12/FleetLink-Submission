package io.github.tomerg12.fleetlink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.client.integration.ClientPhase;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import org.junit.jupiter.api.Test;

/** Verifies the complete toolkit-free Ship Placement lifecycle-control matrix. */
class ShipPlacementControlStateTest {

    /** Enables local editing, conditional confirmation, and Forfeit only in active placement. */
    @Test
    void activePlacementUsesLocalFleetCompleteness() {
        ShipPlacementControlState incomplete = evaluate(ClientPhase.SHIP_PLACEMENT, false);
        ShipPlacementControlState complete = evaluate(ClientPhase.SHIP_PLACEMENT, true);

        assertTrue(incomplete.isEditingEnabled());
        assertFalse(incomplete.isConfirmEnabled());
        assertTrue(incomplete.isForfeitEnabled());
        assertTrue(complete.isEditingEnabled());
        assertTrue(complete.isConfirmEnabled());
        assertTrue(complete.isForfeitEnabled());
        assertEquals(ShipPlacementControlState.Presentation.PLACING,
                complete.getPresentation());
    }

    /** Disables all mutations during fleet submission. */
    @Test
    void fleetSubmissionDisablesEditingConfirmationAndForfeit() {
        ShipPlacementControlState state = evaluate(ClientPhase.SUBMITTING_FLEET, true);

        assertAllDisabled(state);
        assertEquals(ShipPlacementControlState.Presentation.SUBMISSION_PENDING,
                state.getPresentation());
    }

    /** Keeps only voluntary Forfeit available while waiting for the opponent fleet. */
    @Test
    void waitingForBattleAllowsOnlyForfeit() {
        ShipPlacementControlState state = evaluate(ClientPhase.WAITING_FOR_BATTLE, true);

        assertFalse(state.isEditingEnabled());
        assertFalse(state.isConfirmEnabled());
        assertTrue(state.isForfeitEnabled());
        assertEquals(ShipPlacementControlState.Presentation.WAITING_FOR_OPPONENT,
                state.getPresentation());
    }

    /** Disables all competing mutations while placement Forfeit is unresolved. */
    @Test
    void pendingForfeitDisablesEveryPlacementMutation() {
        ShipPlacementControlState state = evaluate(ClientPhase.LEAVING_GAME, true);

        assertAllDisabled(state);
        assertEquals(ShipPlacementControlState.Presentation.FORFEIT_PENDING,
                state.getPresentation());
    }

    /** Rejects Battle and mismatched authoritative game phases as not placement-owned. */
    @Test
    void nonPlacementLifecycleIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ShipPlacementControlState.evaluate(
                        ClientPhase.BATTLE, GamePhase.BATTLE, true));
        assertThrows(IllegalArgumentException.class,
                () -> ShipPlacementControlState.evaluate(
                        ClientPhase.LEAVING_GAME, GamePhase.BATTLE, true));
    }

    /**
     * Evaluates one placement lifecycle row.
     *
     * @param phase client lifecycle phase
     * @param complete local fleet completeness
     * @return derived immutable control state
     */
    private static ShipPlacementControlState evaluate(ClientPhase phase, boolean complete) {
        return ShipPlacementControlState.evaluate(phase, GamePhase.FLEET_PLACEMENT, complete);
    }

    /**
     * Verifies every mutation is unavailable.
     *
     * @param state derived control state
     */
    private static void assertAllDisabled(ShipPlacementControlState state) {
        assertFalse(state.isEditingEnabled());
        assertFalse(state.isConfirmEnabled());
        assertFalse(state.isForfeitEnabled());
    }
}
