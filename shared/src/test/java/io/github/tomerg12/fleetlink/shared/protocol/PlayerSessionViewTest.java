package io.github.tomerg12.fleetlink.shared.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies safe player, session, and rematch transport data.
 */
class PlayerSessionViewTest {

    /**
     * Exposes server-owned player information without credentials.
     */
    @Test
    void exposesSafePlayerInformation() {
        UUID playerId = UUID.randomUUID();
        PlayerView player = new PlayerView(playerId, "Ada", 1420, false);

        assertEquals(playerId, player.getPlayerId());
        assertEquals("Ada", player.getDisplayName());
        assertEquals(1420, player.getRating());
        assertFalse(player.isGuest());
    }

    /**
     * Preserves a server-supplied rating without imposing the undecided rating policy.
     */
    @Test
    void doesNotInventRatingValidation() {
        PlayerView player = new PlayerView(UUID.randomUUID(), "Guest", -5, true);

        assertEquals(-5, player.getRating());
        assertTrue(player.isGuest());
    }

    /**
     * Rejects missing identifiers and unusable display names.
     */
    @Test
    void rejectsInvalidPlayerFields() {
        assertThrows(NullPointerException.class,
                () -> new PlayerView(null, "Ada", 1200, false));
        assertThrows(NullPointerException.class,
                () -> new PlayerView(UUID.randomUUID(), null, 1200, false));
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerView(UUID.randomUUID(), "   ", 1200, false));
    }

    /**
     * Associates an opaque session identifier with one safe player view.
     */
    @Test
    void exposesSessionInformation() {
        UUID sessionId = UUID.randomUUID();
        PlayerView player = player("Grace", false);
        SessionInfo session = new SessionInfo(sessionId, player);

        assertEquals(sessionId, session.getSessionId());
        assertEquals(player, session.getPlayer());
        assertThrows(NullPointerException.class, () -> new SessionInfo(null, player));
        assertThrows(NullPointerException.class,
                () -> new SessionInfo(sessionId, null));
    }

    /**
     * Associates rematch status with a completed game and opponent.
     */
    @Test
    void exposesRematchStatus() {
        UUID gameId = UUID.randomUUID();
        PlayerView opponent = player("Linus", false);
        RematchStatusView status = new RematchStatusView(
                gameId, opponent, RematchState.REQUESTED_BY_OPPONENT);

        assertEquals(gameId, status.getCompletedGameId());
        assertEquals(opponent, status.getOpponent());
        assertEquals(RematchState.REQUESTED_BY_OPPONENT, status.getState());
        assertThrows(NullPointerException.class,
                () -> new RematchStatusView(null, opponent, RematchState.AVAILABLE));
        assertThrows(NullPointerException.class,
                () -> new RematchStatusView(gameId, null, RematchState.AVAILABLE));
        assertThrows(NullPointerException.class,
                () -> new RematchStatusView(gameId, opponent, null));
    }

    /**
     * Preserves nested session and rematch data through Java serialization.
     *
     * @throws IOException if the test cannot serialize the DTOs
     * @throws ClassNotFoundException if the test cannot deserialize their types
     */
    @Test
    void viewsSurviveSerializationRoundTrip() throws IOException, ClassNotFoundException {
        SessionInfo session = new SessionInfo(UUID.randomUUID(), player("Ada", false));
        RematchStatusView status = new RematchStatusView(
                UUID.randomUUID(), player("Guest", true), RematchState.AVAILABLE);

        SessionInfo sessionCopy = SerializationTestSupport.roundTrip(session, SessionInfo.class);
        RematchStatusView statusCopy = SerializationTestSupport.roundTrip(
                status, RematchStatusView.class);

        assertEquals(session.getSessionId(), sessionCopy.getSessionId());
        assertEquals(session.getPlayer().getPlayerId(), sessionCopy.getPlayer().getPlayerId());
        assertEquals(session.getPlayer().getDisplayName(),
                sessionCopy.getPlayer().getDisplayName());
        assertEquals(status.getCompletedGameId(), statusCopy.getCompletedGameId());
        assertEquals(status.getOpponent().getDisplayName(),
                statusCopy.getOpponent().getDisplayName());
        assertEquals(status.getState(), statusCopy.getState());
    }

    /**
     * Creates a concise safe player view for DTO tests.
     *
     * @param name the display name
     * @param guest whether the player is temporary
     * @return the new player view
     */
    private static PlayerView player(String name, boolean guest) {
        return new PlayerView(UUID.randomUUID(), name, 1200, guest);
    }
}
