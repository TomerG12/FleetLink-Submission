package io.github.tomerg12.fleetlink.shared.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Verifies ship placement invariants, value equality, and wire compatibility.
 */
class ShipPlacementTest {

    /**
     * Exposes all fields needed for later authoritative fleet validation.
     */
    @Test
    void exposesCompletePlacementRequest() {
        Coordinate start = new Coordinate(2, 3);
        ShipPlacement placement = new ShipPlacement(
                ShipType.CRUISER, start, Orientation.VERTICAL);

        assertEquals(ShipType.CRUISER, placement.getShipType());
        assertEquals(start, placement.getStart());
        assertEquals(Orientation.VERTICAL, placement.getOrientation());
    }

    /**
     * Rejects a placement with any missing required value.
     */
    @Test
    void rejectsMissingValues() {
        Coordinate start = new Coordinate(0, 0);

        assertThrows(NullPointerException.class,
                () -> new ShipPlacement(null, start, Orientation.HORIZONTAL));
        assertThrows(NullPointerException.class,
                () -> new ShipPlacement(ShipType.CARRIER, null, Orientation.HORIZONTAL));
        assertThrows(NullPointerException.class,
                () -> new ShipPlacement(ShipType.CARRIER, start, null));
    }

    /**
     * Uses every placement field for equality and hash behavior.
     */
    @Test
    void implementsPlacementEquality() {
        ShipPlacement placement = new ShipPlacement(
                ShipType.DESTROYER, new Coordinate(5, 4), Orientation.HORIZONTAL);
        ShipPlacement equal = new ShipPlacement(
                ShipType.DESTROYER, new Coordinate(5, 4), Orientation.HORIZONTAL);

        assertEquals(placement, placement);
        assertEquals(placement, equal);
        assertEquals(placement.hashCode(), equal.hashCode());
        assertNotEquals(placement, new ShipPlacement(
                ShipType.SUBMARINE, new Coordinate(5, 4), Orientation.HORIZONTAL));
        assertNotEquals(placement, new ShipPlacement(
                ShipType.DESTROYER, new Coordinate(5, 5), Orientation.HORIZONTAL));
        assertNotEquals(placement, new ShipPlacement(
                ShipType.DESTROYER, new Coordinate(5, 4), Orientation.VERTICAL));
        assertNotEquals(placement, null);
        assertNotEquals(placement, "D5 horizontal");
    }

    /**
     * Preserves a complete placement through standard Java serialization.
     *
     * @throws IOException if the test cannot serialize the placement
     * @throws ClassNotFoundException if the test cannot deserialize its type
     */
    @Test
    void survivesSerializationRoundTrip() throws IOException, ClassNotFoundException {
        ShipPlacement placement = new ShipPlacement(
                ShipType.BATTLESHIP, new Coordinate(1, 6), Orientation.VERTICAL);

        assertEquals(placement,
                SerializationTestSupport.roundTrip(placement, ShipPlacement.class));
    }
}
