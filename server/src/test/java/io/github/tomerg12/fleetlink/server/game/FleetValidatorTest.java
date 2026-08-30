package io.github.tomerg12.fleetlink.server.game;

import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.validFleet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.Orientation;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShipType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies complete-fleet validation independently from GameSession state transitions.
 */
class FleetValidatorTest {

    /**
     * Accepts a complete mixed-orientation fleet placed against several board edges.
     */
    @Test
    void acceptsCompleteFleetAtBoardEdges() {
        FleetValidator validator = new FleetValidator();
        List<ShipPlacement> placements = List.of(
                new ShipPlacement(ShipType.CARRIER,
                        new Coordinate(0, 9), Orientation.VERTICAL),
                new ShipPlacement(ShipType.BATTLESHIP,
                        new Coordinate(9, 0), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.CRUISER,
                        new Coordinate(5, 8), Orientation.VERTICAL),
                new ShipPlacement(ShipType.SUBMARINE,
                        new Coordinate(8, 5), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.DESTROYER,
                        new Coordinate(7, 0), Orientation.HORIZONTAL));

        List<ShipState> fleet = validator.validate(placements);

        assertEquals(ShipType.values().length, fleet.size());
        assertEquals(new Coordinate(4, 9),
                fleet.get(0).getOccupiedCells().get(ShipType.CARRIER.getLength() - 1));
        assertEquals(new Coordinate(9, 3),
                fleet.get(1).getOccupiedCells().get(ShipType.BATTLESHIP.getLength() - 1));
    }

    /**
     * Allows adjacent ships because FleetLink does not define a no-touch placement rule.
     */
    @Test
    void allowsAdjacentShips() {
        FleetValidator validator = new FleetValidator();

        assertEquals(ShipType.values().length, validator.validate(validFleet()).size());
    }

    /**
     * Rejects a ship whose expanded cells leave the fixed board.
     */
    @Test
    void rejectsOutOfBoundsPlacement() {
        FleetValidator validator = new FleetValidator();
        List<ShipPlacement> placements = new ArrayList<>(validFleet());
        placements.set(0, new ShipPlacement(
                ShipType.CARRIER, new Coordinate(0, 6), Orientation.HORIZONTAL));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(placements));
    }

    /**
     * Rejects duplicate ship types even when the submitted list still contains five placements.
     */
    @Test
    void rejectsDuplicateShipType() {
        FleetValidator validator = new FleetValidator();
        List<ShipPlacement> placements = new ArrayList<>(validFleet());
        placements.set(4, new ShipPlacement(
                ShipType.CARRIER, new Coordinate(5, 0), Orientation.HORIZONTAL));

        assertThrows(IllegalArgumentException.class, () -> validator.validate(placements));
    }

    /**
     * Rejects null fleet input and null entries before any internal ship state is returned.
     */
    @Test
    void rejectsNullFleetData() {
        FleetValidator validator = new FleetValidator();
        List<ShipPlacement> placements = new ArrayList<>(validFleet());
        placements.set(2, null);

        assertThrows(IllegalArgumentException.class, () -> validator.validate(null));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(placements));
    }
}
