package io.github.tomerg12.fleetlink.client.ui;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Owns client-side screen selection without coupling controllers to a JavaFX stage.
 */
public final class ScreenNavigator {
    private ScreenId currentScreen;
    private Consumer<ScreenId> screenListener = ignored -> { };

    /**
     * Creates a navigator with no active screen and a no-op presentation listener.
     */
    public ScreenNavigator() {
    }

    /**
     * Registers the presentation callback that replaces the visible screen.
     *
     * @param listener callback invoked after a successful navigation change
     */
    public void setScreenListener(Consumer<ScreenId> listener) {
        screenListener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Changes the active screen and notifies the presentation boundary once.
     * Repeating the current destination is ignored to avoid rebuilding a visible screen unnecessarily.
     *
     * @param destination destination screen
     */
    public void navigate(ScreenId destination) {
        ScreenId validatedDestination = Objects.requireNonNull(destination, "destination");
        if (validatedDestination == currentScreen) {
            return;
        }
        currentScreen = validatedDestination;
        screenListener.accept(validatedDestination);
    }

    /**
     * Returns the most recently selected screen, or null before initial navigation.
     *
     * @return current screen, or null when no screen has been selected yet
     */
    public ScreenId getCurrentScreen() {
        return currentScreen;
    }
}
