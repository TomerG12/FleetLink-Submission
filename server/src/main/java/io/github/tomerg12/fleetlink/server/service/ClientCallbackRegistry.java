package io.github.tomerg12.fleetlink.server.service;

import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.rmi.FleetLinkClientCallback;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the latest exported client callback associated with each connected player identity.
 * Authentication and session ownership remain outside this registry.
 */
public final class ClientCallbackRegistry {

    private final ConcurrentHashMap<UUID, FleetLinkClientCallback> callbacks =
            new ConcurrentHashMap<>();

    /**
     * Creates an empty callback registry.
     */
    public ClientCallbackRegistry() {
    }

    /**
     * Registers or replaces the callback for one connected player.
     *
     * @param player the safe connected player identity
     * @param callback the exported callback reference used for server notifications
     */
    public void register(PlayerView player, FleetLinkClientCallback callback) {
        Objects.requireNonNull(player, "player");
        callbacks.put(player.getPlayerId(), Objects.requireNonNull(callback, "callback"));
    }

    /**
     * Returns the currently registered callback for one player.
     *
     * @param playerId the player identifier
     * @return the callback when registered
     */
    public Optional<FleetLinkClientCallback> find(UUID playerId) {
        return Optional.ofNullable(playerId == null ? null : callbacks.get(playerId));
    }

    /**
     * Removes the callback for one disconnected or logged-out player.
     *
     * @param playerId the player identifier
     */
    public void unregister(UUID playerId) {
        if (playerId != null) {
            callbacks.remove(playerId);
        }
    }

    /**
     * Removes a callback only when it is still the callback owned by an ending session.
     *
     * @param playerId player identity whose callback may be removed
     * @param expectedCallback callback captured for the ending session
     * @return true when the matching callback was removed
     */
    public boolean unregister(UUID playerId, FleetLinkClientCallback expectedCallback) {
        if (playerId == null || expectedCallback == null) {
            return false;
        }
        return callbacks.remove(playerId, expectedCallback);
    }
}
