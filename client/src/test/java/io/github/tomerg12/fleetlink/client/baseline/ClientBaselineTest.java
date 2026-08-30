package io.github.tomerg12.fleetlink.client.baseline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javafx.scene.control.Control;
import org.junit.jupiter.api.Test;

/**
 * Verifies the client module dependency boundary without starting JavaFX.
 */
class ClientBaselineTest {

    /**
     * Confirms that the shared module and JavaFX controls are visible to the client module.
     */
    @Test
    void exposesClientDependencies() {
        assertEquals("shared", ClientBaseline.sharedModuleName());
        assertEquals(Control.class, ClientBaseline.javaFxControlType());
    }
}
