package io.github.tomerg12.fleetlink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import org.junit.jupiter.api.Test;

/**
 * Verifies conversion from the shared coordinate contract to board display labels.
 */
class BoardLabelsTest {

    /**
     * Confirms zero-based shared coordinates map to the required A-J and 1-10 labels.
     */
    @Test
    void coordinatesExposeBattleshipLabels() {
        assertEquals("A1", BoardLabels.displayLabel(new Coordinate(0, 0)));
        assertEquals("J10", BoardLabels.displayLabel(new Coordinate(9, 9)));
        assertEquals("F", BoardLabels.columnLabel(5));
    }
}
