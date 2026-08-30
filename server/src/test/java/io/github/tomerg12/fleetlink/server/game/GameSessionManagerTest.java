package io.github.tomerg12.fleetlink.server.game;

import static io.github.tomerg12.fleetlink.server.ServerTestFixtures.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import org.junit.jupiter.api.Test;

/**
 * Verifies direct multi-game indexing and terminal-finalization availability behavior.
 */
class GameSessionManagerTest {

    /**
     * Keeps independent games directly addressable by both game and player identifiers.
     */
    @Test
    void managesMultipleGamesConcurrently() {
        GameSessionManager manager = new GameSessionManager();
        PlayerView a = player("A", 1200);
        PlayerView b = player("B", 1200);
        PlayerView c = player("C", 1200);
        PlayerView d = player("D", 1200);

        GameSession first = manager.createGame(a, b, a.getPlayerId());
        GameSession second = manager.createGame(c, d, c.getPlayerId());

        assertEquals(first.getGameId(), manager.findByPlayerId(a.getPlayerId()).orElseThrow().getGameId());
        assertEquals(second.getGameId(), manager.findByPlayerId(d.getPlayerId()).orElseThrow().getGameId());
        assertTrue(manager.hasActiveGame(b.getPlayerId()));
        assertTrue(manager.hasActiveGame(c.getPlayerId()));
    }

    /**
     * Allows a participant from a finalized finished game to enter a newly created game.
     */
    @Test
    void finishedGameDoesNotBlockNewGame() {
        GameSessionManager manager = new GameSessionManager();
        PlayerView a = player("A", 1200);
        PlayerView b = player("B", 1200);
        PlayerView c = player("C", 1200);
        GameSession oldGame = manager.createGame(a, b, a.getPlayerId());
        oldGame.leave(b.getPlayerId());
        manager.markTerminalFinalizationComplete(oldGame.getGameId());

        GameSession newGame = manager.createGame(a, c, a.getPlayerId());

        assertFalse(manager.hasActiveGame(b.getPlayerId()));
        assertEquals(newGame.getGameId(), manager.findByPlayerId(a.getPlayerId()).orElseThrow().getGameId());
        assertTrue(manager.findByGameId(oldGame.getGameId()).isPresent());
    }

    /**
     * Retains both participant reservations and the finished game until authoritative terminal
     * finalization has been published.
     */
    @Test
    void finishedGameRemainsUnavailableUntilTerminalFinalizationCompletes() {
        GameSessionManager manager = new GameSessionManager();
        PlayerView a = player("A", 1000);
        PlayerView b = player("B", 1000);
        PlayerView c = player("C", 1000);
        GameSession oldGame = manager.createGame(a, b, a.getPlayerId());
        oldGame.leave(b.getPlayerId());

        assertTrue(manager.hasActiveGame(a.getPlayerId()));
        assertTrue(manager.hasActiveGame(b.getPlayerId()));
        assertFalse(manager.removeGame(oldGame.getGameId()));
        assertThrows(IllegalArgumentException.class,
                () -> manager.createGame(a, c, a.getPlayerId()));

        manager.markTerminalFinalizationComplete(oldGame.getGameId());

        assertFalse(manager.hasActiveGame(a.getPlayerId()));
        assertFalse(manager.hasActiveGame(b.getPlayerId()));
        assertTrue(manager.removeGame(oldGame.getGameId()));
    }
}
