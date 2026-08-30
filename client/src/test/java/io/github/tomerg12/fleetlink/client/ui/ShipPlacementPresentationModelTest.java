package io.github.tomerg12.fleetlink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.Orientation;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShipType;
import org.junit.jupiter.api.Test;

/**
 * Verifies responsive local fleet arrangement without treating it as server authority.
 */
class ShipPlacementPresentationModelTest {

    /**
     * Confirms selection and rotation alter only the local presentation state.
     */
    @Test
    void selectionAndRotationAreLocalAndDeterministic() {
        ShipPlacementPresentationModel model = new ShipPlacementPresentationModel();

        assertEquals(ShipType.CARRIER, model.getSelectedShip());
        assertEquals(Orientation.HORIZONTAL, model.getOrientation());

        model.selectShip(ShipType.DESTROYER);
        model.rotateSelected();

        assertEquals(ShipType.DESTROYER, model.getSelectedShip());
        assertEquals(Orientation.VERTICAL, model.getOrientation());
    }

    /**
     * Confirms local placement prevents obvious overlap and board overflow for UI feedback.
     */
    @Test
    void placementRejectsOverlapAndBoardOverflow() {
        ShipPlacementPresentationModel model = new ShipPlacementPresentationModel();
        assertTrue(model.placeSelected(new Coordinate(0, 0)));

        model.selectShip(ShipType.BATTLESHIP);
        assertFalse(model.placeSelected(new Coordinate(0, 2)));
        assertFalse(model.placeSelected(new Coordinate(9, 8)));
        assertTrue(model.placeSelected(new Coordinate(2, 0)));
    }

    /**
     * Confirms the ready presentation becomes valid only after every shared ship has a local preview.
     */
    @Test
    void fleetCompletionRequiresEveryShipType() {
        ShipPlacementPresentationModel model = new ShipPlacementPresentationModel();
        assertFalse(model.isFleetComplete());

        place(model, ShipType.CARRIER, 0, 0);
        place(model, ShipType.BATTLESHIP, 1, 0);
        place(model, ShipType.CRUISER, 2, 0);
        place(model, ShipType.SUBMARINE, 3, 0);
        place(model, ShipType.DESTROYER, 4, 0);

        assertTrue(model.isFleetComplete());
        assertEquals(17, model.occupiedCells().size());

        model.clearPlacements();
        assertFalse(model.isFleetComplete());
        assertTrue(model.occupiedCells().isEmpty());
    }

    /**
     * Confirms complete submission preserves each placement orientation after later rotations.
     */
    @Test
    void completeFleetCreatesOneTransportPlacementPerShip() {
        ShipPlacementPresentationModel model = new ShipPlacementPresentationModel();
        assertThrows(IllegalStateException.class, model::createFleetSubmission);

        model.selectShip(ShipType.DESTROYER);
        model.rotateSelected();
        assertTrue(model.placeSelected(new Coordinate(0, 9)));
        model.rotateSelected();
        place(model, ShipType.CARRIER, 0, 0);
        place(model, ShipType.BATTLESHIP, 1, 0);
        place(model, ShipType.CRUISER, 2, 0);
        place(model, ShipType.SUBMARINE, 3, 0);

        java.util.List<ShipPlacement> submission = model.createFleetSubmission();

        assertEquals(ShipType.values().length, submission.size());
        ShipPlacement destroyer = submission.stream()
                .filter(placement -> placement.getShipType() == ShipType.DESTROYER)
                .findFirst()
                .orElseThrow();
        assertEquals(new Coordinate(0, 9), destroyer.getStart());
        assertEquals(Orientation.VERTICAL, destroyer.getOrientation());
    }

    /**
     * Places one ship horizontally in a non-overlapping test row.
     *
     * @param model model under test
     * @param shipType ship to select
     * @param row start row
     * @param column start column
     */
    private void place(ShipPlacementPresentationModel model, ShipType shipType, int row, int column) {
        model.selectShip(shipType);
        assertTrue(model.placeSelected(new Coordinate(row, column)));
    }
}
