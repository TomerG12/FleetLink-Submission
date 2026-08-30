package io.github.tomerg12.fleetlink.shared.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies player-specific game snapshot invariants and serialization.
 */
class GameViewTest {

    /**
     * Exposes active authoritative state through role-specific boards.
     */
    @Test
    void exposesActiveGameSnapshot() {
        PlayerView player = player("Ada");
        PlayerView opponent = player("Grace");
        OwnBoardView ownBoard = ownBoard();
        OpponentBoardView opponentBoard = opponentBoard();
        UUID gameId = UUID.randomUUID();
        GameView game = new GameView(gameId, GamePhase.BATTLE, player, opponent, true,
                ownBoard, opponentBoard, null, null);

        assertEquals(gameId, game.getGameId());
        assertEquals(GamePhase.BATTLE, game.getPhase());
        assertSame(player, game.getPlayer());
        assertSame(opponent, game.getOpponent());
        assertTrue(game.isYourTurn());
        assertSame(ownBoard, game.getOwnBoard());
        assertSame(opponentBoard, game.getOpponentBoard());
        assertNull(game.getWinner());
        assertNull(game.getEndReason());
    }

    /**
     * Rejects terminal data before the game reaches its finished phase.
     */
    @Test
    void rejectsOutcomeForUnfinishedGame() {
        PlayerView player = player("Ada");
        PlayerView opponent = player("Grace");

        assertThrows(IllegalArgumentException.class,
                () -> game(GamePhase.BATTLE, false, player, opponent, player, null));
        assertThrows(IllegalArgumentException.class,
                () -> game(GamePhase.FLEET_PLACEMENT, false, player, opponent,
                        null, GameEndReason.DISCONNECT));
        assertThrows(IllegalArgumentException.class,
                () -> game(GamePhase.BATTLE, false, player, opponent,
                        player, GameEndReason.ALL_SHIPS_SUNK));
    }

    /**
     * Requires a winner and end reason and disables turns for finished snapshots.
     */
    @Test
    void validatesFinishedGameState() {
        PlayerView player = player("Ada");
        PlayerView opponent = player("Grace");

        assertThrows(IllegalArgumentException.class,
                () -> game(GamePhase.FINISHED, false, player, opponent, player, null));
        assertThrows(IllegalArgumentException.class,
                () -> game(GamePhase.FINISHED, false, player, opponent,
                        null, GameEndReason.ALL_SHIPS_SUNK));
        assertThrows(IllegalArgumentException.class,
                () -> game(GamePhase.FINISHED, true, player, opponent,
                        player, GameEndReason.ALL_SHIPS_SUNK));

        GameView finished = game(GamePhase.FINISHED, false, player, opponent,
                player, GameEndReason.ALL_SHIPS_SUNK);
        assertFalse(finished.isYourTurn());
        assertSame(player, finished.getWinner());
        assertEquals(GameEndReason.ALL_SHIPS_SUNK, finished.getEndReason());
    }

    /**
     * Rejects snapshots that assign both participant roles to the same player identity.
     */
    @Test
    void rejectsMatchingParticipantIdentities() {
        PlayerView player = player("Ada");
        PlayerView duplicateIdentity = new PlayerView(player.getPlayerId(), "Grace", 1200, false);

        assertThrows(IllegalArgumentException.class,
                () -> game(GamePhase.BATTLE, false, player, duplicateIdentity, null, null));
    }

    /**
     * Rejects an active turn while players are still placing fleets.
     */
    @Test
    void rejectsTurnBeforeBattle() {
        PlayerView player = player("Ada");
        PlayerView opponent = player("Grace");

        assertThrows(IllegalArgumentException.class,
                () -> game(GamePhase.FLEET_PLACEMENT, true, player, opponent, null, null));
    }

    /**
     * Rejects a winner who is not one of the snapshot participants.
     */
    @Test
    void rejectsUnknownWinner() {
        PlayerView player = player("Ada");
        PlayerView opponent = player("Grace");
        PlayerView stranger = player("Linus");

        assertThrows(IllegalArgumentException.class,
                () -> game(GamePhase.FINISHED, false, player, opponent,
                        stranger, GameEndReason.RESIGNATION));
    }

    /**
     * Rejects each missing required snapshot component.
     */
    @Test
    void rejectsMissingRequiredComponents() {
        PlayerView player = player("Ada");
        PlayerView opponent = player("Grace");
        OwnBoardView ownBoard = ownBoard();
        OpponentBoardView opponentBoard = opponentBoard();
        UUID gameId = UUID.randomUUID();

        assertThrows(NullPointerException.class, () -> new GameView(null, GamePhase.BATTLE,
                player, opponent, false, ownBoard, opponentBoard, null, null));
        assertThrows(NullPointerException.class, () -> new GameView(gameId, null,
                player, opponent, false, ownBoard, opponentBoard, null, null));
        assertThrows(NullPointerException.class, () -> new GameView(gameId, GamePhase.BATTLE,
                null, opponent, false, ownBoard, opponentBoard, null, null));
        assertThrows(NullPointerException.class, () -> new GameView(gameId, GamePhase.BATTLE,
                player, null, false, ownBoard, opponentBoard, null, null));
        assertThrows(NullPointerException.class, () -> new GameView(gameId, GamePhase.BATTLE,
                player, opponent, false, null, opponentBoard, null, null));
        assertThrows(NullPointerException.class, () -> new GameView(gameId, GamePhase.BATTLE,
                player, opponent, false, ownBoard, null, null, null));
    }

    /**
     * Preserves a safe snapshot through standard Java serialization.
     *
     * @throws IOException if the test cannot serialize the snapshot
     * @throws ClassNotFoundException if the test cannot deserialize its type
     */
    @Test
    void survivesSerializationRoundTrip() throws IOException, ClassNotFoundException {
        PlayerView player = player("Ada");
        PlayerView opponent = player("Grace");
        GameView game = game(GamePhase.BATTLE, true, player, opponent, null, null);

        GameView copy = SerializationTestSupport.roundTrip(game, GameView.class);

        assertEquals(game.getGameId(), copy.getGameId());
        assertEquals(game.getPhase(), copy.getPhase());
        assertEquals(game.getPlayer().getPlayerId(), copy.getPlayer().getPlayerId());
        assertEquals(game.getOpponent().getPlayerId(), copy.getOpponent().getPlayerId());
        assertEquals(game.isYourTurn(), copy.isYourTurn());
        assertEquals(OwnCellView.WATER, copy.getOwnBoard().getCell(new Coordinate(0, 0)));
        assertEquals(OpponentCellView.UNKNOWN,
                copy.getOpponentBoard().getCell(new Coordinate(0, 0)));
    }

    /**
     * Creates a snapshot with standard safe boards for invariant tests.
     *
     * @param phase the snapshot phase
     * @param yourTurn the turn flag
     * @param player the receiving player
     * @param opponent the opponent
     * @param winner the optional winner
     * @param endReason the optional end reason
     * @return the new game snapshot
     */
    private static GameView game(GamePhase phase, boolean yourTurn, PlayerView player,
                                 PlayerView opponent, PlayerView winner,
                                 GameEndReason endReason) {
        return new GameView(UUID.randomUUID(), phase, player, opponent, yourTurn,
                ownBoard(), opponentBoard(), winner, endReason);
    }

    /**
     * Creates a safe player view for snapshot tests.
     *
     * @param name the display name
     * @return the player view
     */
    private static PlayerView player(String name) {
        return new PlayerView(UUID.randomUUID(), name, 1200, false);
    }

    /**
     * Creates an empty own-board snapshot.
     *
     * @return the own-board view
     */
    private static OwnBoardView ownBoard() {
        return new OwnBoardView(BoardViewTest.ownCells(OwnCellView.WATER));
    }

    /**
     * Creates an unknown opponent-board snapshot.
     *
     * @return the opponent-board view
     */
    private static OpponentBoardView opponentBoard() {
        return new OpponentBoardView(
                BoardViewTest.opponentCells(OpponentCellView.UNKNOWN));
    }
}
