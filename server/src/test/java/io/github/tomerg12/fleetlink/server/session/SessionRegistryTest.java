package io.github.tomerg12.fleetlink.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.SessionInfo;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Verifies cross-domain UUID collision avoidance and two-phase identity reservation.
 */
class SessionRegistryTest {

    /**
     * Retries guest identifiers that collide with active and offline persistent players.
     */
    @Test
    void guestIdentityAvoidsActiveAndPersistentCollisions() {
        UUID activeId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID persistentId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        UUID acceptedId = UUID.fromString("00000000-0000-0000-0000-000000000103");
        SessionRegistry sessions = new SessionRegistry(new Uuids(
                UUID.randomUUID(), UUID.randomUUID()), new Uuids(
                activeId, activeId, persistentId, acceptedId), persistentId::equals);
        assertTrue(sessions.claimRegistered(
                new PlayerView(activeId, "Registered", 1000, false)).isPresent());

        SessionInfo guest = sessions.createGuest("Guest");

        assertEquals(acceptedId, guest.getPlayer().getPlayerId());
        assertTrue(guest.getPlayer().isGuest());
        assertEquals(1000, guest.getPlayer().getRating());
    }

    /**
     * Retries a generated guest identifier that belongs to another active guest session.
     */
    @Test
    void secondGuestRetriesActiveGuestIdentifierCollision() {
        UUID firstPlayerId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        UUID secondPlayerId = UUID.fromString("00000000-0000-0000-0000-000000000112");
        UUID firstSessionId = UUID.fromString("00000000-0000-0000-0000-000000000211");
        UUID secondSessionId = UUID.fromString("00000000-0000-0000-0000-000000000212");
        SessionRegistry sessions = new SessionRegistry(
                new Uuids(firstSessionId, secondSessionId),
                new Uuids(firstPlayerId, firstPlayerId, secondPlayerId), ignored -> false);

        SessionInfo first = sessions.createGuest("First Guest");
        SessionInfo second = sessions.createGuest("Second Guest");

        assertEquals(firstPlayerId, first.getPlayer().getPlayerId());
        assertEquals(secondPlayerId, second.getPlayer().getPlayerId());
        assertTrue(sessions.resolvePlayer(first.getSessionId()).isPresent());
        assertTrue(sessions.resolvePlayer(second.getSessionId()).isPresent());
    }

    /**
     * Invalidates request authority immediately but releases identity only after cleanup completion.
     */
    @Test
    void terminationKeepsIdentityReservedUntilCompletion() {
        UUID playerId = UUID.randomUUID();
        SessionRegistry sessions = new SessionRegistry();
        SessionInfo session = sessions.claimRegistered(
                new PlayerView(playerId, "Account", 1000, false)).orElseThrow();

        SessionRegistry.Termination termination = sessions
                .beginTermination(session.getSessionId()).orElseThrow();

        assertTrue(sessions.resolvePlayer(session.getSessionId()).isEmpty());
        assertTrue(sessions.hasPlayer(playerId));
        assertTrue(sessions.claimRegistered(session.getPlayer()).isEmpty());
        assertTrue(sessions.completeTermination(termination));
        assertFalse(sessions.hasPlayer(playerId));
        assertTrue(sessions.claimRegistered(session.getPlayer()).isPresent());
        assertFalse(sessions.completeTermination(termination));
    }

    /**
     * Keeps an invalidated session identifier reserved until its termination cleanup completes.
     */
    @Test
    void terminatingSessionIdentifierCannotBeReused() {
        UUID oldSessionId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID replacementSessionId = UUID.fromString("00000000-0000-0000-0000-000000000202");
        SessionRegistry sessions = new SessionRegistry(
                new Uuids(oldSessionId, oldSessionId, replacementSessionId),
                UUID::randomUUID, ignored -> false);
        SessionInfo first = sessions.claimRegistered(new PlayerView(
                UUID.randomUUID(), "First", 1000, false)).orElseThrow();
        sessions.beginTermination(first.getSessionId()).orElseThrow();

        SessionInfo second = sessions.claimRegistered(new PlayerView(
                UUID.randomUUID(), "Second", 1000, false)).orElseThrow();

        assertEquals(replacementSessionId, second.getSessionId());
    }

    /**
     * Supplies a deterministic finite sequence of UUID values.
     */
    private static final class Uuids implements Supplier<UUID> {
        private final Queue<UUID> values;

        /**
         * Creates a supplier from the requested deterministic sequence.
         *
         * @param values UUID values in return order
         */
        private Uuids(UUID... values) {
            this.values = new ArrayDeque<>(java.util.List.of(values));
        }

        /**
         * Returns the next deterministic identifier.
         *
         * @return next UUID
         */
        @Override
        public UUID get() {
            return values.remove();
        }
    }
}
