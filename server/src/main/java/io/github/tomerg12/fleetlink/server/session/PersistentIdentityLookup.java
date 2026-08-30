package io.github.tomerg12.fleetlink.server.session;

import java.util.UUID;

/**
 * Checks persistent player identity ownership without coupling session state to JPA.
 */
@FunctionalInterface
public interface PersistentIdentityLookup {

    /**
     * Reports whether an identifier already belongs to a persistent player account.
     *
     * @param playerId identifier to inspect
     * @return true when a persistent account owns the identifier
     */
    boolean exists(UUID playerId);
}
