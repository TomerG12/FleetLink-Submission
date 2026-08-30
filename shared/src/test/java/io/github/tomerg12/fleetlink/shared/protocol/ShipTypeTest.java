package io.github.tomerg12.fleetlink.shared.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies the fixed ship-length metadata shared by protocol consumers.
 */
class ShipTypeTest {

    /**
     * Defines the carrier length as five board cells.
     */
    @Test
    void definesCarrierLength() {
        assertEquals(5, ShipType.CARRIER.getLength());
    }

    /**
     * Defines the battleship length as four board cells.
     */
    @Test
    void definesBattleshipLength() {
        assertEquals(4, ShipType.BATTLESHIP.getLength());
    }

    /**
     * Defines the cruiser length as three board cells.
     */
    @Test
    void definesCruiserLength() {
        assertEquals(3, ShipType.CRUISER.getLength());
    }

    /**
     * Defines the submarine length as three board cells.
     */
    @Test
    void definesSubmarineLength() {
        assertEquals(3, ShipType.SUBMARINE.getLength());
    }

    /**
     * Defines the destroyer length as two board cells.
     */
    @Test
    void definesDestroyerLength() {
        assertEquals(2, ShipType.DESTROYER.getLength());
    }
}
