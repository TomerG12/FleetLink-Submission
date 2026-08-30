package io.github.tomerg12.fleetlink.shared.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Verifies fixed board shape, defensive copying, serialization, and opponent secrecy.
 */
class BoardViewTest {

    /**
     * Reads valid cells from both role-specific board representations.
     */
    @Test
    void readsValidBoardCells() {
        OwnCellView[][] ownCells = ownCells(OwnCellView.WATER);
        ownCells[2][4] = OwnCellView.SHIP;
        OpponentCellView[][] opponentCells = opponentCells(OpponentCellView.UNKNOWN);
        opponentCells[7][1] = OpponentCellView.HIT;

        assertEquals(OwnCellView.SHIP,
                new OwnBoardView(ownCells).getCell(new Coordinate(2, 4)));
        assertEquals(OpponentCellView.HIT,
                new OpponentBoardView(opponentCells).getCell(new Coordinate(7, 1)));
    }

    /**
     * Protects board snapshots from mutations to constructor inputs and returned arrays.
     */
    @Test
    void makesDefensiveCopies() {
        OwnCellView[][] ownCells = ownCells(OwnCellView.WATER);
        OwnBoardView ownBoard = new OwnBoardView(ownCells);
        ownCells[0][0] = OwnCellView.SHIP;
        OwnCellView[][] ownCopy = ownBoard.toArray();
        ownCopy[0][1] = OwnCellView.SHIP;

        OpponentCellView[][] opponentCells = opponentCells(OpponentCellView.UNKNOWN);
        OpponentBoardView opponentBoard = new OpponentBoardView(opponentCells);
        opponentCells[0][0] = OpponentCellView.HIT;
        OpponentCellView[][] opponentCopy = opponentBoard.toArray();
        opponentCopy[0][1] = OpponentCellView.MISS;

        assertEquals(OwnCellView.WATER, ownBoard.getCell(new Coordinate(0, 0)));
        assertEquals(OwnCellView.WATER, ownBoard.getCell(new Coordinate(0, 1)));
        assertEquals(OpponentCellView.UNKNOWN,
                opponentBoard.getCell(new Coordinate(0, 0)));
        assertEquals(OpponentCellView.UNKNOWN,
                opponentBoard.getCell(new Coordinate(0, 1)));
    }

    /**
     * Rejects null outer arrays and incorrect row counts.
     */
    @Test
    void rejectsInvalidOuterBoardShape() {
        assertThrows(NullPointerException.class, () -> new OwnBoardView(null));
        assertThrows(NullPointerException.class, () -> new OpponentBoardView(null));
        assertThrows(IllegalArgumentException.class,
                () -> new OwnBoardView(new OwnCellView[9][10]));
        assertThrows(IllegalArgumentException.class,
                () -> new OpponentBoardView(new OpponentCellView[11][10]));
    }

    /**
     * Rejects null rows and rows with an incorrect cell count.
     */
    @Test
    void rejectsInvalidRows() {
        OwnCellView[][] ownCells = ownCells(OwnCellView.WATER);
        ownCells[3] = null;
        OpponentCellView[][] opponentCells = opponentCells(OpponentCellView.UNKNOWN);
        opponentCells[4] = new OpponentCellView[9];

        assertThrows(IllegalArgumentException.class, () -> new OwnBoardView(ownCells));
        assertThrows(IllegalArgumentException.class,
                () -> new OpponentBoardView(opponentCells));
    }

    /**
     * Rejects null cells from either board representation.
     */
    @Test
    void rejectsNullCells() {
        OwnCellView[][] ownCells = ownCells(OwnCellView.WATER);
        ownCells[5][5] = null;
        OpponentCellView[][] opponentCells = opponentCells(OpponentCellView.UNKNOWN);
        opponentCells[5][5] = null;

        assertThrows(IllegalArgumentException.class, () -> new OwnBoardView(ownCells));
        assertThrows(IllegalArgumentException.class,
                () -> new OpponentBoardView(opponentCells));
    }

    /**
     * Rejects a missing coordinate rather than performing an ambiguous board read.
     */
    @Test
    void rejectsNullCoordinate() {
        OwnBoardView ownBoard = new OwnBoardView(ownCells(OwnCellView.WATER));
        OpponentBoardView opponentBoard =
                new OpponentBoardView(opponentCells(OpponentCellView.UNKNOWN));

        assertThrows(NullPointerException.class, () -> ownBoard.getCell(null));
        assertThrows(NullPointerException.class, () -> opponentBoard.getCell(null));
    }

    /**
     * Makes an undiscovered ship state impossible in the opponent cell vocabulary.
     */
    @Test
    void opponentCellsCannotRepresentUndiscoveredShips() {
        assertFalse(Arrays.stream(OpponentCellView.values())
                .map(Enum::name)
                .anyMatch(name -> name.contains("SHIP")));
    }

    /**
     * Preserves complete boards through standard Java serialization.
     *
     * @throws IOException if the test cannot serialize the boards
     * @throws ClassNotFoundException if the test cannot deserialize their types
     */
    @Test
    void boardsSurviveSerializationRoundTrip() throws IOException, ClassNotFoundException {
        OwnCellView[][] ownCells = ownCells(OwnCellView.WATER);
        ownCells[9][9] = OwnCellView.HIT;
        OpponentCellView[][] opponentCells = opponentCells(OpponentCellView.UNKNOWN);
        opponentCells[0][9] = OpponentCellView.MISS;

        OwnBoardView ownCopy = SerializationTestSupport.roundTrip(
                new OwnBoardView(ownCells), OwnBoardView.class);
        OpponentBoardView opponentCopy = SerializationTestSupport.roundTrip(
                new OpponentBoardView(opponentCells), OpponentBoardView.class);

        for (int row = 0; row < Coordinate.BOARD_SIZE; row++) {
            assertArrayEquals(ownCells[row], ownCopy.toArray()[row]);
            assertArrayEquals(opponentCells[row], opponentCopy.toArray()[row]);
        }
    }

    /**
     * Creates a complete own-board array filled with one state.
     *
     * @param state the state assigned to every cell
     * @return the populated 10x10 array
     */
    static OwnCellView[][] ownCells(OwnCellView state) {
        OwnCellView[][] cells = new OwnCellView[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
        for (OwnCellView[] row : cells) {
            Arrays.fill(row, state);
        }
        return cells;
    }

    /**
     * Creates a complete opponent-board array filled with one state.
     *
     * @param state the state assigned to every cell
     * @return the populated 10x10 array
     */
    static OpponentCellView[][] opponentCells(OpponentCellView state) {
        OpponentCellView[][] cells =
                new OpponentCellView[Coordinate.BOARD_SIZE][Coordinate.BOARD_SIZE];
        for (OpponentCellView[] row : cells) {
            Arrays.fill(row, state);
        }
        return cells;
    }
}
