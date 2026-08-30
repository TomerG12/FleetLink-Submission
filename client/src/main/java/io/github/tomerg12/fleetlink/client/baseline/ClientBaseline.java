package io.github.tomerg12.fleetlink.client.baseline;

import io.github.tomerg12.fleetlink.shared.baseline.SharedBaseline;
import javafx.scene.control.Control;

/**
 * Exposes build markers for the client module and its compile-time dependencies.
 * It does not start JavaFX or create an application screen.
 */
public final class ClientBaseline {

    /**
     * Prevents construction because the baseline checks use only dependency metadata.
     */
    private ClientBaseline() {
    }

    /**
     * Reads the shared module marker to prove the client-to-shared dependency direction.
     *
     * @return the logical name of the shared module
     */
    public static String sharedModuleName() {
        return SharedBaseline.moduleName();
    }

    /**
     * Returns a JavaFX control type without starting the JavaFX runtime.
     *
     * @return the JavaFX Control base type
     */
    public static Class<Control> javaFxControlType() {
        return Control.class;
    }
}
