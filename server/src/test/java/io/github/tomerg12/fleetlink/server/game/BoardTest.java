package io.github.tomerg12.fleetlink.server.game;

import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.validFleet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.ShipType;
import io.github.tomerg12.fleetlink.shared.protocol.ShotOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies direct board lookup, atomic fleet commit, duplicate shots, and sunk detection.
 */
class BoardTest {

    /**
     * Applies direct miss, hit, sunk, and duplicate-shot behavior after a validated fleet commit.
     */
    @Test
    void appliesDirectShotOutcomesAndRejectsDuplicate() {
        Board board = new Board();
        board.commitFleet(new FleetValidator().validate(validFleet()));

        assertEquals(ShotOutcome.MISS, board.fireAt(new Coordinate(9, 9)));
        assertEquals(ShotOutcome.HIT, board.fireAt(new Coordinate(0, 0)));
        assertEquals(ShotOutcome.HIT, board.fireAt(new Coordinate(0, 1)));
        assertEquals(ShotOutcome.HIT, board.fireAt(new Coordinate(0, 2)));
        assertEquals(ShotOutcome.HIT, board.fireAt(new Coordinate(0, 3)));
        assertEquals(ShotOutcome.SUNK, board.fireAt(new Coordinate(0, 4)));
        assertThrows(IllegalStateException.class,
                () -> board.fireAt(new Coordinate(0, 4)));
    }

    /**
     * Rechecks overlap before mutation so invalid internal fleet data cannot partially change board.
     */
    @Test
    void rejectsOverlappingInternalFleetWithoutPartialCommit() {
        Board board = new Board();
        ShipState destroyer = new ShipState(ShipType.DESTROYER,
                List.of(new Coordinate(0, 0), new Coordinate(0, 1)));
        ShipState submarine = new ShipState(ShipType.SUBMARINE,
                List.of(new Coordinate(0, 1), new Coordinate(0, 2), new Coordinate(0, 3)));

        assertThrows(IllegalArgumentException.class,
                () -> board.commitFleet(List.of(destroyer, submarine)));
        assertFalse(board.hasShipAt(new Coordinate(0, 0)));
        assertFalse(board.hasShipAt(new Coordinate(0, 1)));
    }

    /**
     * Keeps an empty board from being treated as defeated before a fleet is committed.
     */
    @Test
    void emptyBoardIsNotSunk() {
        assertFalse(new Board().areAllShipsSunk());
    }
}
