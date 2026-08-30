package io.github.tomerg12.fleetlink.shared.baseline;

/**
 * Supplies a stable marker that lets the initial build verify access to the shared module.
 * This class contains no game protocol or domain behavior.
 */
public final class SharedBaseline {

    /**
     * Prevents construction because the baseline marker has no instance state.
     */
    private SharedBaseline() {
    }

    /**
     * Returns the logical name used to identify the shared module in smoke tests.
     *
     * @return the shared module name
     */
    public static String moduleName() {
        return "shared";
    }
}
