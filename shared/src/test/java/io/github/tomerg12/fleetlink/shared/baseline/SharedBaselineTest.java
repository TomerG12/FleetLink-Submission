package io.github.tomerg12.fleetlink.shared.baseline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies that Maven compiles and executes tests in the shared module.
 */
class SharedBaselineTest {

    /**
     * Confirms that the shared baseline marker is available to its own test source set.
     */
    @Test
    void exposesSharedModuleName() {
        assertEquals("shared", SharedBaseline.moduleName());
    }
}
