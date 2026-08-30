package io.github.tomerg12.fleetlink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.tomerg12.fleetlink.client.integration.ClientPhase;
import io.github.tomerg12.fleetlink.client.ui.FleetLinkClientApplication.NavigationAction;
import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShipType;
import org.junit.jupiter.api.Test;

/** Verifies state-aware navigation without starting the JavaFX toolkit. */
class FleetLinkClientApplicationTest {

    /** Retains Ship Placement throughout its three ordinary local lifecycle phases. */
    @Test
    void shipPlacementLifecycleDoesNotRebuildSameDestination() {
        for (ClientPhase phase : List.of(ClientPhase.SHIP_PLACEMENT,
                ClientPhase.SUBMITTING_FLEET, ClientPhase.WAITING_FOR_BATTLE)) {
            ScreenId destination = FleetLinkClientApplication.screenFor(phase);

            assertEquals(ScreenId.SHIP_PLACEMENT, destination);
            assertEquals(NavigationAction.RETAIN,
                    FleetLinkClientApplication.navigationAction(
                            ScreenId.SHIP_PLACEMENT, destination, false, phase));
        }
    }

    /** Retains placement during pending Forfeit but still navigates to authoritative Battle. */
    @Test
    void pendingForfeitUsesAuthoritativeCrossDestinationNavigation() {
        ScreenId placement = FleetLinkClientApplication.screenFor(
                ClientPhase.LEAVING_GAME, GamePhase.FLEET_PLACEMENT);
        ScreenId battle = FleetLinkClientApplication.screenFor(
                ClientPhase.LEAVING_GAME, GamePhase.BATTLE);

        assertEquals(ScreenId.SHIP_PLACEMENT, placement);
        assertEquals(NavigationAction.RETAIN,
                FleetLinkClientApplication.navigationAction(ScreenId.SHIP_PLACEMENT,
                        placement, false, ClientPhase.LEAVING_GAME));
        assertEquals(ScreenId.BATTLE, battle);
        assertEquals(NavigationAction.NAVIGATE,
                FleetLinkClientApplication.navigationAction(ScreenId.SHIP_PLACEMENT,
                        battle, false, ClientPhase.LEAVING_GAME));
    }

    /** Preserves same-destination refresh for authoritative Battle and Game Over views. */
    @Test
    void authoritativeScreensStillRebuildAtSameDestination() {
        assertEquals(NavigationAction.REBUILD,
                FleetLinkClientApplication.navigationAction(
                        ScreenId.BATTLE, ScreenId.BATTLE, false, ClientPhase.BATTLE));
        assertEquals(ScreenId.GAME_OVER,
                FleetLinkClientApplication.screenFor(ClientPhase.GAME_OVER));
        assertEquals(NavigationAction.REBUILD,
                FleetLinkClientApplication.navigationAction(
                        ScreenId.GAME_OVER, ScreenId.GAME_OVER,
                        false, ClientPhase.GAME_OVER));
    }

    /** Covers every Lobby lifecycle-owner and logout branch in the production policy. */
    @Test
    void lobbyLifecycleMatrixUsesOneCompletePolicy() {
        assertEquals(NavigationAction.RETAIN,
                FleetLinkClientApplication.navigationAction(
                        ScreenId.LOBBY, ScreenId.LOBBY, true, ClientPhase.LOBBY));
        assertEquals(NavigationAction.REBUILD,
                FleetLinkClientApplication.navigationAction(
                        ScreenId.LOBBY, ScreenId.LOBBY, false, ClientPhase.LOBBY));
        assertEquals(NavigationAction.RETAIN,
                FleetLinkClientApplication.navigationAction(
                        ScreenId.LOBBY, ScreenId.LOBBY, false, ClientPhase.LOGGING_OUT));
    }

    /** Navigates for both an ordinary destination change and initial routing from no screen. */
    @Test
    void differentOrMissingCurrentDestinationNavigates() {
        assertEquals(NavigationAction.NAVIGATE,
                FleetLinkClientApplication.navigationAction(
                        ScreenId.LOBBY, ScreenId.SHIP_PLACEMENT,
                        true, ClientPhase.SHIP_PLACEMENT));
        assertEquals(NavigationAction.NAVIGATE,
                FleetLinkClientApplication.navigationAction(
                        null, ScreenId.LOGIN, false, ClientPhase.LOGIN));
    }

    /** Retains valid same-destination screens that have no special rebuild requirement. */
    @Test
    void ordinarySameDestinationRetainsCurrentScreen() {
        assertEquals(NavigationAction.RETAIN,
                FleetLinkClientApplication.navigationAction(
                        ScreenId.LOGIN, ScreenId.LOGIN, false, ClientPhase.LOGIN));
    }

    /** Keeps one complete local model and payload through submit-pending and waiting publications. */
    @Test
    void completeArrangementSurvivesSubmissionAndWaitingPolicies() {
        ShipPlacementPresentationModel model = completeFleet();
        List<ShipPlacement> payload = model.createFleetSubmission();

        ShipPlacementPresentationModel submitting = retainedModel(model,
                ClientPhase.SUBMITTING_FLEET, GamePhase.FLEET_PLACEMENT);
        ShipPlacementPresentationModel waiting = retainedModel(submitting,
                ClientPhase.WAITING_FOR_BATTLE, GamePhase.FLEET_PLACEMENT);

        assertSame(model, submitting);
        assertSame(model, waiting);
        assertEquals(payload, waiting.createFleetSubmission());
    }

    /** Preserves the original arrangement when a fleet submission returns to active placement. */
    @Test
    void recoverableSubmissionFailureRetainsArrangementAndPayload() {
        ShipPlacementPresentationModel model = completeFleet();
        List<ShipPlacement> payload = model.createFleetSubmission();

        ShipPlacementPresentationModel submitting = retainedModel(model,
                ClientPhase.SUBMITTING_FLEET, GamePhase.FLEET_PLACEMENT);
        ShipPlacementPresentationModel restored = retainedModel(submitting,
                ClientPhase.SHIP_PLACEMENT, GamePhase.FLEET_PLACEMENT);

        assertSame(model, restored);
        assertTrue(restored.isFleetComplete());
        assertEquals(payload, restored.createFleetSubmission());
    }

    /** Preserves one arrangement across pending and failed Forfeit from both placement sources. */
    @Test
    void recoverableForfeitFailureRetainsBothPlacementSourceModels() {
        for (ClientPhase source : List.of(
                ClientPhase.SHIP_PLACEMENT, ClientPhase.WAITING_FOR_BATTLE)) {
            ShipPlacementPresentationModel model = completeFleet();
            List<ShipPlacement> payload = model.createFleetSubmission();

            ShipPlacementPresentationModel leaving = retainedModel(model,
                    ClientPhase.LEAVING_GAME, GamePhase.FLEET_PLACEMENT);
            ShipPlacementPresentationModel restored = retainedModel(leaving, source,
                    GamePhase.FLEET_PLACEMENT);

            assertSame(model, leaving);
            assertSame(model, restored);
            assertEquals(payload, restored.createFleetSubmission());
        }
    }

    /**
     * Applies the exact complete production navigation policy to a local model reference.
     *
     * @param current currently active Ship Placement model
     * @param phase reconciled client phase
     * @param gamePhase newest authoritative game phase for pending leave
     * @return retained model, or a replacement only when production requests a rebuild
     */
    private static ShipPlacementPresentationModel retainedModel(
            ShipPlacementPresentationModel current, ClientPhase phase, GamePhase gamePhase) {
        ScreenId destination = phase == ClientPhase.LEAVING_GAME
                ? FleetLinkClientApplication.screenFor(phase, gamePhase)
                : FleetLinkClientApplication.screenFor(phase);
        NavigationAction action = FleetLinkClientApplication.navigationAction(
                ScreenId.SHIP_PLACEMENT, destination, false, phase);
        return switch (action) {
            case REBUILD -> new ShipPlacementPresentationModel();
            case RETAIN -> current;
            case NAVIGATE -> throw new IllegalArgumentException(
                    "Arrangement retention requires a Ship Placement destination");
        };
    }

    /**
     * Creates one deterministic complete local fleet.
     *
     * @return complete mutable local presentation model
     */
    private static ShipPlacementPresentationModel completeFleet() {
        ShipPlacementPresentationModel model = new ShipPlacementPresentationModel();
        place(model, ShipType.CARRIER, 0);
        place(model, ShipType.BATTLESHIP, 1);
        place(model, ShipType.CRUISER, 2);
        place(model, ShipType.SUBMARINE, 3);
        place(model, ShipType.DESTROYER, 4);
        return model;
    }

    /**
     * Places one selected ship in a dedicated horizontal row.
     *
     * @param model local model under test
     * @param shipType ship selected for placement
     * @param row dedicated non-overlapping row
     */
    private static void place(ShipPlacementPresentationModel model,
                              ShipType shipType, int row) {
        model.selectShip(shipType);
        assertTrue(model.placeSelected(new Coordinate(row, 0)));
    }
}
