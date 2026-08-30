package io.github.tomerg12.fleetlink.server.baseline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

/**
 * Verifies the server module dependency boundary without creating persistence state.
 */
class ServerBaselineTest {

    /**
     * Confirms that shared, Jakarta Persistence, Hibernate, and H2 are available to the server.
     *
     * @throws ClassNotFoundException if the configured H2 runtime driver is unavailable
     */
    @Test
    void exposesServerDependencies() throws ClassNotFoundException {
        assertEquals("shared", ServerBaseline.sharedModuleName());
        assertEquals("6.6.54.Final", ServerBaseline.persistenceProviderVersion());
        assertEquals(EntityManager.class, ServerBaseline.persistenceApiType());
        assertEquals("org.h2.Driver", Class.forName("org.h2.Driver").getName());
    }
}
