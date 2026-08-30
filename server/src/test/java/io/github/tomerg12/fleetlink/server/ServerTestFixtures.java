package io.github.tomerg12.fleetlink.server;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.Orientation;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShipType;
import java.util.List;
import java.util.UUID;

/**
 * Provides concise valid players and fleet requests for focused server tests.
 */
public final class ServerTestFixtures {

    /**
     * Prevents construction because test fixtures are stateless.
     */
    private ServerTestFixtures() {
    }

    /**
     * Creates one registered-style safe player view.
     *
     * @param name the display name
     * @param rating the server-owned rating
     * @return the new player view
     */
    public static PlayerView player(String name, int rating) {
        return new PlayerView(UUID.randomUUID(), name, rating, false);
    }

    /**
     * Creates a valid fleet with one horizontal ship of every required type.
     *
     * @return the complete valid fleet request
     */
    public static List<ShipPlacement> validFleet() {
        return List.of(
                new ShipPlacement(ShipType.CARRIER,
                        new Coordinate(0, 0), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.BATTLESHIP,
                        new Coordinate(1, 0), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.CRUISER,
                        new Coordinate(2, 0), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.SUBMARINE,
                        new Coordinate(3, 0), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.DESTROYER,
                        new Coordinate(4, 0), Orientation.HORIZONTAL));
    }

    /**
     * Creates a complete unique-type fleet whose carrier and battleship overlap.
     *
     * @return the invalid overlapping fleet request
     */
    public static List<ShipPlacement> overlappingFleet() {
        return List.of(
                new ShipPlacement(ShipType.CARRIER,
                        new Coordinate(0, 0), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.BATTLESHIP,
                        new Coordinate(0, 3), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.CRUISER,
                        new Coordinate(2, 0), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.SUBMARINE,
                        new Coordinate(3, 0), Orientation.HORIZONTAL),
                new ShipPlacement(ShipType.DESTROYER,
                        new Coordinate(4, 0), Orientation.HORIZONTAL));
    }
}
