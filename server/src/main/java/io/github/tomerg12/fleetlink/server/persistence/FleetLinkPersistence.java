package io.github.tomerg12.fleetlink.server.persistence;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.util.Map;
import java.util.Objects;

/**
 * Owns the single process-wide EntityManagerFactory used by server persistence services.
 */
public final class FleetLinkPersistence implements AutoCloseable {

    /** The stable persistence-unit name declared in persistence.xml. */
    public static final String PERSISTENCE_UNIT = "fleetlink";

    /** Optional process property used by isolated process-level tests. */
    public static final String JDBC_URL_PROPERTY = "fleetlink.persistence.jdbc.url";

    private final EntityManagerFactory entityManagerFactory;

    /**
     * Stores an already-created factory for lifecycle ownership.
     *
     * @param entityManagerFactory process-wide factory
     */
    private FleetLinkPersistence(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = Objects.requireNonNull(
                entityManagerFactory, "entityManagerFactory");
    }

    /**
     * Opens production persistence using the file-backed configuration in persistence.xml.
     *
     * @return open production persistence lifecycle
     */
    public static FleetLinkPersistence production() {
        String override = System.getProperty(JDBC_URL_PROPERTY);
        if (override == null || override.isBlank()) {
            return new FleetLinkPersistence(Persistence.createEntityManagerFactory(PERSISTENCE_UNIT));
        }
        return withProperties(Map.of("jakarta.persistence.jdbc.url", override));
    }

    /**
     * Opens persistence with explicit overrides for isolated tests and restart verification.
     *
     * @param properties JPA and Hibernate property overrides
     * @return open persistence lifecycle
     */
    public static FleetLinkPersistence withProperties(Map<String, ?> properties) {
        Objects.requireNonNull(properties, "properties");
        return new FleetLinkPersistence(
                Persistence.createEntityManagerFactory(PERSISTENCE_UNIT, properties));
    }

    /**
     * Returns the shared factory from which each operation creates its own EntityManager.
     *
     * @return open entity manager factory
     */
    public EntityManagerFactory getEntityManagerFactory() {
        return entityManagerFactory;
    }

    /**
     * Closes the process-wide factory after completion retry services have stopped.
     */
    @Override
    public void close() {
        if (entityManagerFactory.isOpen()) {
            entityManagerFactory.close();
        }
    }
}
