package io.github.tomerg12.fleetlink.client.integration;

/**
 * Schedules presentation work without exposing JavaFX controls to callback or networking code.
 */
@FunctionalInterface
public interface ClientUiDispatcher {

    /**
     * Schedules one already-prepared presentation action on the UI execution boundary.
     *
     * @param action presentation action to run later
     */
    void dispatch(Runnable action);
}
