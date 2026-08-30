package io.github.tomerg12.fleetlink.server.baseline;

import io.github.tomerg12.fleetlink.shared.baseline.SharedBaseline;
import jakarta.persistence.EntityManager;
import org.hibernate.Version;

/**
 * Exposes build markers for the server module and its compile-time dependencies.
 * It does not create persistence state or implement server behavior.
 */
public final class ServerBaseline {

    /**
     * Prevents construction because the baseline checks use only dependency metadata.
     */
    private ServerBaseline() {
    }

    /**
     * Reads the shared module marker to prove the server-to-shared dependency direction.
     *
     * @return the logical name of the shared module
     */
    public static String sharedModuleName() {
        return SharedBaseline.moduleName();
    }

    /**
     * Returns the Hibernate version visible to the server module.
     *
     * @return the configured Hibernate provider version
     */
    public static String persistenceProviderVersion() {
        return Version.getVersionString();
    }

    /**
     * Returns the standard persistence API type visible to the server module.
     *
     * @return the Jakarta Persistence EntityManager type
     */
    public static Class<EntityManager> persistenceApiType() {
        return EntityManager.class;
    }
}
