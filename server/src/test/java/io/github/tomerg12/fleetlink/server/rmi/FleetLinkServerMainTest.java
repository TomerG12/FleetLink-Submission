package io.github.tomerg12.fleetlink.server.rmi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.rmi.registry.Registry;
import org.junit.jupiter.api.Test;

/**
 * Verifies the non-network construction boundary and stable registry metadata of the RMI bootstrap.
 */
class FleetLinkServerMainTest {

    /**
     * Creates a complete core adapter without exporting sockets and preserves the standard binding.
     */
    @Test
    void createsCoreServerWithStableRegistryMetadata() {
        assertNotNull(FleetLinkServerMain.createCoreServer());
        assertEquals(Registry.REGISTRY_PORT, FleetLinkServerMain.DEFAULT_REGISTRY_PORT);
        assertEquals("FleetLinkServer", FleetLinkServerMain.BINDING_NAME);
    }
}
