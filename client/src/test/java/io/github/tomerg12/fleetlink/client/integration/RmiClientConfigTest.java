package io.github.tomerg12.fleetlink.client.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Verifies client registry configuration defaults and validation boundaries.
 */
class RmiClientConfigTest {

    /**
     * Confirms explicit lookup settings are trimmed and retained.
     */
    @Test
    void explicitConfigurationIsValidated() {
        RmiClientConfig config = new RmiClientConfig(" localhost ", 2099, " FleetLinkServer ");

        assertEquals("localhost", config.getHost());
        assertEquals(2099, config.getPort());
        assertEquals("FleetLinkServer", config.getBindingName());
    }

    /**
     * Confirms blank text and invalid ports fail before registry access.
     */
    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RmiClientConfig(" ", 1099, "FleetLinkServer"));
        assertThrows(IllegalArgumentException.class,
                () -> new RmiClientConfig("localhost", 0, "FleetLinkServer"));
        assertThrows(IllegalArgumentException.class,
                () -> new RmiClientConfig("localhost", 65536, "FleetLinkServer"));
        assertThrows(IllegalArgumentException.class,
                () -> new RmiClientConfig("localhost", 1099, " "));
    }

    /**
     * Confirms process-local property overrides are parsed without changing persistent settings.
     */
    @Test
    void systemPropertiesProvideProcessLocalOverrides() {
        String oldHost = System.getProperty(RmiClientConfig.HOST_PROPERTY);
        String oldPort = System.getProperty(RmiClientConfig.PORT_PROPERTY);
        String oldBinding = System.getProperty(RmiClientConfig.BINDING_PROPERTY);
        try {
            System.setProperty(RmiClientConfig.HOST_PROPERTY, "rmi-host");
            System.setProperty(RmiClientConfig.PORT_PROPERTY, "2099");
            System.setProperty(RmiClientConfig.BINDING_PROPERTY, "FleetLinkTest");

            RmiClientConfig config = RmiClientConfig.fromSystemProperties();

            assertEquals("rmi-host", config.getHost());
            assertEquals(2099, config.getPort());
            assertEquals("FleetLinkTest", config.getBindingName());

            System.setProperty(RmiClientConfig.PORT_PROPERTY, "invalid");
            assertThrows(IllegalArgumentException.class, RmiClientConfig::fromSystemProperties);
        } finally {
            restore(RmiClientConfig.HOST_PROPERTY, oldHost);
            restore(RmiClientConfig.PORT_PROPERTY, oldPort);
            restore(RmiClientConfig.BINDING_PROPERTY, oldBinding);
        }
    }

    /**
     * Restores one system property to its value before the test.
     *
     * @param name property name
     * @param value prior value, or null when previously absent
     */
    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
