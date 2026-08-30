package io.github.tomerg12.fleetlink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Verifies navigation state independently from the JavaFX stage.
 */
class ScreenNavigatorTest {

    /**
     * Confirms that navigation publishes each actual screen change once.
     */
    @Test
    void navigationPublishesOnlyActualChanges() {
        ScreenNavigator navigator = new ScreenNavigator();
        List<ScreenId> visited = new ArrayList<>();
        navigator.setScreenListener(visited::add);

        assertNull(navigator.getCurrentScreen());
        navigator.navigate(ScreenId.LOGIN);
        navigator.navigate(ScreenId.LOGIN);
        navigator.navigate(ScreenId.LOBBY);

        assertEquals(List.of(ScreenId.LOGIN, ScreenId.LOBBY), visited);
        assertEquals(ScreenId.LOBBY, navigator.getCurrentScreen());
    }

    /**
     * Confirms that null navigation inputs are rejected at the boundary.
     */
    @Test
    void nullInputsAreRejected() {
        ScreenNavigator navigator = new ScreenNavigator();
        assertThrows(NullPointerException.class, () -> navigator.setScreenListener(null));
        assertThrows(NullPointerException.class, () -> navigator.navigate(null));
    }
}
