package io.github.tomerg12.fleetlink.client.integration;

import java.rmi.registry.Registry;
import java.util.Objects;

/**
 * Stores validated RMI registry lookup settings for one client process.
 */
public final class RmiClientConfig {
    /** System property that overrides the registry host. */
    public static final String HOST_PROPERTY = "fleetlink.rmi.host";

    /** System property that overrides the registry port. */
    public static final String PORT_PROPERTY = "fleetlink.rmi.port";

    /** System property that overrides the registry binding name. */
    public static final String BINDING_PROPERTY = "fleetlink.rmi.binding";

    /** Default registry host used for a local FleetLink server. */
    public static final String DEFAULT_HOST = "localhost";

    /** Default registry binding published by the FleetLink server. */
    public static final String DEFAULT_BINDING = "FleetLinkServer";

    private final String host;
    private final int port;
    private final String bindingName;

    /**
     * Creates validated registry lookup settings.
     *
     * @param host non-blank registry host
     * @param port registry port from 1 through 65535
     * @param bindingName non-blank registry binding name
     * @throws NullPointerException if host or binding name is null
     * @throws IllegalArgumentException if text is blank or the port is out of range
     */
    public RmiClientConfig(String host, int port, String bindingName) {
        this.host = requireText(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.port = port;
        this.bindingName = requireText(bindingName, "bindingName");
    }

    /**
     * Reads process-local system properties while preserving standard local defaults.
     *
     * @return validated configuration for the current client process
     * @throws IllegalArgumentException if a configured port is not an integer or is out of range
     */
    public static RmiClientConfig fromSystemProperties() {
        String host = System.getProperty(HOST_PROPERTY, DEFAULT_HOST);
        String binding = System.getProperty(BINDING_PROPERTY, DEFAULT_BINDING);
        String portText = System.getProperty(PORT_PROPERTY, Integer.toString(Registry.REGISTRY_PORT));
        final int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("RMI registry port must be an integer", exception);
        }
        return new RmiClientConfig(host, port, binding);
    }

    /**
     * Returns the registry host.
     *
     * @return non-blank registry host
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the registry port.
     *
     * @return validated port from 1 through 65535
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the remote binding name.
     *
     * @return non-blank binding name
     */
    public String getBindingName() {
        return bindingName;
    }

    /**
     * Validates one required lookup string.
     *
     * @param value supplied string
     * @param name argument name used in validation failures
     * @return trimmed non-blank string
     */
    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
