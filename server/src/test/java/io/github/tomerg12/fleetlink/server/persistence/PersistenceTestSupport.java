package io.github.tomerg12.fleetlink.server.persistence;

import java.util.Map;
import java.util.UUID;

/**
 * Opens isolated H2 persistence units for server tests.
 */
public final class PersistenceTestSupport {

    /**
     * Prevents construction because this test utility is static.
     */
    private PersistenceTestSupport() {
    }

    /**
     * Opens a unique in-memory database with schema creation and cleanup.
     *
     * @return isolated persistence owner
     */
    public static FleetLinkPersistence openMemory() {
        return FleetLinkPersistence.withProperties(Map.of(
                "jakarta.persistence.jdbc.url",
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "hibernate.hbm2ddl.auto", "create-drop",
                "hibernate.show_sql", "false"));
    }

    /**
     * Opens a caller-selected database URL and schema behavior for restart tests.
     *
     * @param jdbcUrl H2 JDBC URL
     * @param schemaAction Hibernate schema action
     * @return open persistence owner
     */
    public static FleetLinkPersistence open(String jdbcUrl, String schemaAction) {
        return FleetLinkPersistence.withProperties(Map.of(
                "jakarta.persistence.jdbc.url", jdbcUrl,
                "hibernate.hbm2ddl.auto", schemaAction,
                "hibernate.show_sql", "false"));
    }
}
