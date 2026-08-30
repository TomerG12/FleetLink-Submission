package io.github.tomerg12.fleetlink.shared.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Verifies coordinate validation, value equality, and wire compatibility.
 */
class CoordinateTest {

    /**
     * Accepts both corners of the zero-based board.
     */
    @Test
    void acceptsBoardBoundaries() {
        Coordinate first = new Coordinate(0, 0);
        Coordinate last = new Coordinate(9, 9);

        assertEquals(0, first.getRow());
        assertEquals(0, first.getColumn());
        assertEquals(9, last.getRow());
        assertEquals(9, last.getColumn());
    }

    /**
     * Rejects negative row and column indexes.
     */
    @Test
    void rejectsNegativeIndexes() {
        assertThrows(IllegalArgumentException.class, () -> new Coordinate(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Coordinate(0, -1));
    }

    /**
     * Rejects row and column indexes beyond the last board cell.
     */
    @Test
    void rejectsIndexesGreaterThanNine() {
        assertThrows(IllegalArgumentException.class, () -> new Coordinate(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new Coordinate(0, 10));
    }

    /**
     * Uses both indexes for equality and hash behavior.
     */
    @Test
    void implementsCoordinateEquality() {
        Coordinate coordinate = new Coordinate(4, 7);
        Coordinate equal = new Coordinate(4, 7);

        assertEquals(coordinate, coordinate);
        assertEquals(coordinate, equal);
        assertEquals(coordinate.hashCode(), equal.hashCode());
        assertNotEquals(coordinate, new Coordinate(4, 6));
        assertNotEquals(coordinate, "E5");
        assertNotEquals(coordinate, null);
    }

    /**
     * Preserves a coordinate through standard Java serialization.
     *
     * @throws IOException if the test cannot serialize the coordinate
     * @throws ClassNotFoundException if the test cannot deserialize its type
     */
    @Test
    void survivesSerializationRoundTrip() throws IOException, ClassNotFoundException {
        Coordinate coordinate = new Coordinate(3, 8);

        assertEquals(coordinate,
                SerializationTestSupport.roundTrip(coordinate, Coordinate.class));
    }
}
