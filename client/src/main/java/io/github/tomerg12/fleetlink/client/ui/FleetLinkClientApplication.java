package io.github.tomerg12.fleetlink.client.ui;

import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.tomerg12.fleetlink.client.integration.ClientOperationService;
import io.github.tomerg12.fleetlink.client.integration.ClientPhase;
import io.github.tomerg12.fleetlink.client.integration.ClientState;
import io.github.tomerg12.fleetlink.client.integration.ClientStateCoordinator;
import io.github.tomerg12.fleetlink.client.integration.RmiClientConfig;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Starts the FleetLink JavaFX client shell and owns stage-level navigation. */
public final class FleetLinkClientApplication extends Application {
    private static final double DEFAULT_WIDTH = 1180;
    private static final double DEFAULT_HEIGHT = 760;
    private ClientOperationService operationService;
    private LobbyScreen activeLobbyScreen;
    private PlayerStatisticsScreen activeStatisticsScreen;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();

    /** Describes the complete stage-level response to one reconciled destination. */
    enum NavigationAction {
        /** Changes the navigator destination and lets its listener construct the new screen. */
        NAVIGATE,
        /** Reconstructs the current destination from its newest authoritative state. */
        REBUILD,
        /** Keeps the currently attached screen and its local presentation ownership. */
        RETAIN
    }

    /** Creates the JavaFX application entry point. */
    public FleetLinkClientApplication() {
    }

    /**
     * Returns the screen shown before a session is established.
     *
     * @return login as the initial screen
     */
    public static ScreenId initialScreen() {
        return ScreenId.LOGIN;
    }

    /**
     * Configures the primary JavaFX stage, asynchronous operation service, and navigation listeners.
     *
     * @param stage primary JavaFX stage
     */
    @Override
    public void start(Stage stage) {
        Platform.setImplicitExit(false);
        ScreenNavigator navigator = new ScreenNavigator();
        ClientStateCoordinator coordinator = new ClientStateCoordinator(Platform::runLater);
        operationService = ClientOperationService.forRmi(coordinator,
                RmiClientConfig.fromSystemProperties());
        navigator.setScreenListener(screen -> showScreen(
                stage, navigator, coordinator, operationService, screen));
        coordinator.setStateListener(state -> applyNavigationState(
                stage, navigator, coordinator, operationService, state));
        stage.setTitle("FleetLink");
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        stage.setOnCloseRequest(event -> {
            event.consume();
            beginWindowShutdown(stage);
        });
        navigator.navigate(initialScreen());
        stage.show();
    }

    /** Ensures JavaFX cleanup reuses graceful remote shutdown. */
    @Override
    public void stop() {
        if (operationService != null) {
            operationService.shutdownGracefully();
        }
    }

    /**
     * Replaces the stage scene with a fully built screen and the shared production stylesheet.
     *
     * @param stage stage receiving the new scene
     * @param navigator navigation owner
     * @param coordinator reconciled client-state owner
     * @param operations asynchronous remote-operation boundary
     * @param screen destination screen
     * @throws IllegalStateException if the production stylesheet is missing
     */
    private void showScreen(Stage stage, ScreenNavigator navigator,
                            ClientStateCoordinator coordinator,
                            ClientOperationService operations, ScreenId screen) {
        deactivateLobbyScreen();
        deactivateStatisticsScreen();
        Parent root = createScreen(navigator, coordinator, operations, screen);
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        URL stylesheet = FleetLinkClientApplication.class.getResource(UiComponents.stylesheetPath());
        if (stylesheet == null) {
            throw new IllegalStateException("FleetLink stylesheet is missing from client resources");
        }
        scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(scene);
        if (activeLobbyScreen != null) {
            activeLobbyScreen.activate();
        }
        if (activeStatisticsScreen != null) {
            activeStatisticsScreen.activate();
        }
    }

    /**
     * Builds one destination screen from the latest reconciled state and shared operation service.
     *
     * @param navigator navigation owner used by screens that request local navigation
     * @param coordinator reconciled client-state owner
     * @param operations asynchronous remote-operation boundary
     * @param screen destination screen identifier
     * @return fully constructed destination root
     */
    private Parent createScreen(ScreenNavigator navigator, ClientStateCoordinator coordinator,
                                ClientOperationService operations, ScreenId screen) {
        ClientState state = coordinator.getState();
        return switch (screen) {
            case LOGIN -> new LoginScreen(operations).createView();
            case LOBBY -> createLobbyScreen(navigator, operations, coordinator, state);
            case SHIP_PLACEMENT -> new ShipPlacementScreen(state, operations).createView();
            case BATTLE -> new BattleScreen(state, operations).createView();
            case GAME_OVER -> new GameOverScreen(state, operations).createView();
            case PLAYER_STATISTICS -> createStatisticsScreen(navigator, operations, coordinator);
        };
    }

    /**
     * Builds the Lobby stable shell and retains its preview lifecycle owner for post-attach loading.
     *
     * @param navigator established application navigator
     * @param operations asynchronous remote operation boundary
     * @param coordinator session-bound dashboard state owner
     * @param state current reconciled state containing safe session identity
     * @return stable Lobby shell
     */
    private Parent createLobbyScreen(ScreenNavigator navigator,
                                     ClientOperationService operations,
                                     ClientStateCoordinator coordinator, ClientState state) {
        activeLobbyScreen = new LobbyScreen(navigator, operations, coordinator, state);
        return activeLobbyScreen.createView();
    }

    /**
     * Builds the existing statistics destination and retains its lifecycle owner for activation
     * only after the stable shell has been attached to the stage.
     *
     * @param navigator established application navigator
     * @param operations asynchronous remote operation boundary
     * @param coordinator session-bound dashboard state owner
     * @return stable statistics dashboard shell
     */
    private Parent createStatisticsScreen(ScreenNavigator navigator,
                                          ClientOperationService operations,
                                          ClientStateCoordinator coordinator) {
        activeStatisticsScreen = new PlayerStatisticsScreen(navigator, operations, coordinator);
        return activeStatisticsScreen.createView();
    }

    /** Detaches the prior statistics listener before any application screen is replaced. */
    private void deactivateStatisticsScreen() {
        if (activeStatisticsScreen != null) {
            activeStatisticsScreen.deactivate();
            activeStatisticsScreen = null;
        }
    }

    /** Detaches the prior Lobby preview listener before its application screen is replaced. */
    private void deactivateLobbyScreen() {
        if (activeLobbyScreen != null) {
            activeLobbyScreen.deactivate();
            activeLobbyScreen = null;
        }
    }

    /**
     * Applies reconciled state by navigating or rebuilding stateful screens from authoritative data.
     *
     * @param stage active JavaFX stage
     * @param navigator navigation owner
     * @param coordinator reconciled client-state owner
     * @param operations asynchronous remote-operation boundary
     * @param state latest reconciled client state
     */
    private void applyNavigationState(Stage stage, ScreenNavigator navigator,
                                      ClientStateCoordinator coordinator,
                                      ClientOperationService operations, ClientState state) {
        if (state.getPhase() == ClientPhase.LOGGING_OUT) {
            deactivateLobbyScreen();
        }
        ScreenId destination = screenFor(state);
        NavigationAction action = navigationAction(navigator.getCurrentScreen(), destination,
                activeLobbyScreen != null, state.getPhase());
        switch (action) {
            case NAVIGATE -> navigator.navigate(destination);
            case REBUILD -> showScreen(stage, navigator, coordinator, operations, destination);
            case RETAIN -> {
            }
        }
    }

    /**
     * Chooses the complete stage-level navigation response without depending on the JavaFX toolkit.
     * Cross-destination changes navigate, authoritative Battle and Game Over updates rebuild, and
     * Ship Placement retains its local fleet owner. Lobby rebuilds only when its lifecycle owner is
     * unexpectedly absent outside logout.
     *
     * @param currentScreen currently selected navigator destination, or null before initial routing
     * @param destination destination derived from the newest reconciled state
     * @param lobbyLifecycleOwnerPresent whether the attached Lobby retains its lifecycle owner
     * @param phase newest reconciled client phase
     * @return complete navigation action for the supplied application state
     */
    static NavigationAction navigationAction(ScreenId currentScreen, ScreenId destination,
                                             boolean lobbyLifecycleOwnerPresent,
                                             ClientPhase phase) {
        if (currentScreen != destination) {
            return NavigationAction.NAVIGATE;
        }
        if (destination == ScreenId.BATTLE || destination == ScreenId.GAME_OVER) {
            return NavigationAction.REBUILD;
        }
        if (destination == ScreenId.LOBBY && !lobbyLifecycleOwnerPresent
                && phase != ClientPhase.LOGGING_OUT) {
            return NavigationAction.REBUILD;
        }
        return NavigationAction.RETAIN;
    }

    /**
     * Begins best-effort asynchronous logout and exits JavaFX only after local client cleanup.
     *
     * @param stage stage hidden immediately to prevent further user operations
     */
    private void beginWindowShutdown(Stage stage) {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }
        deactivateLobbyScreen();
        deactivateStatisticsScreen();
        stage.hide();
        operationService.shutdownGracefully().whenComplete((ignored, failure) ->
                Platform.runLater(Platform::exit));
    }

    /**
     * Maps one reconciled client phase to its existing screen destination.
     *
     * @param phase reconciled client lifecycle phase
     * @return existing screen destination for the supplied phase
     */
    static ScreenId screenFor(ClientPhase phase) {
        return switch (phase) {
            case LOGIN, CONNECTING -> ScreenId.LOGIN;
            case LOBBY, MATCHMAKING, LOGGING_OUT -> ScreenId.LOBBY;
            case SHIP_PLACEMENT, SUBMITTING_FLEET, WAITING_FOR_BATTLE -> ScreenId.SHIP_PLACEMENT;
            case BATTLE, FIRING -> ScreenId.BATTLE;
            case GAME_OVER -> ScreenId.GAME_OVER;
            case LEAVING_GAME -> throw new IllegalArgumentException(
                    "LEAVING_GAME screen selection requires authoritative game state");
        };
    }

    /**
     * Maps the latest reconciled state to an existing destination. Pending leave presentation uses
     * the newest authoritative game phase rather than an operation token or inferred source.
     *
     * @param state newest reconciled client state
     * @return existing screen destination
     * @throws IllegalArgumentException if pending leave has no valid nonterminal game snapshot
     */
    static ScreenId screenFor(ClientState state) {
        if (state.getPhase() != ClientPhase.LEAVING_GAME) {
            return screenFor(state.getPhase());
        }
        if (state.getGameView() == null) {
            throw new IllegalArgumentException("LEAVING_GAME requires an authoritative game view");
        }
        return switch (state.getGameView().getPhase()) {
            case FLEET_PLACEMENT -> ScreenId.SHIP_PLACEMENT;
            case BATTLE -> ScreenId.BATTLE;
            case FINISHED -> throw new IllegalArgumentException(
                    "Finished games must reconcile to GAME_OVER before screen selection");
        };
    }

    /**
     * Exposes the state-aware pending-leave mapping to toolkit-free UI contract tests.
     *
     * @param phase reconciled client phase
     * @param gamePhase newest authoritative game phase
     * @return existing screen destination
     */
    static ScreenId screenFor(ClientPhase phase, GamePhase gamePhase) {
        if (phase != ClientPhase.LEAVING_GAME) {
            return screenFor(phase);
        }
        return switch (gamePhase) {
            case FLEET_PLACEMENT -> ScreenId.SHIP_PLACEMENT;
            case BATTLE -> ScreenId.BATTLE;
            case FINISHED -> throw new IllegalArgumentException(
                    "Finished games must reconcile to GAME_OVER before screen selection");
        };
    }

    /**
     * Launches the JavaFX client process.
     *
     * @param args JavaFX launch arguments
     */
    public static void main(String[] args) {
        launch(args);
    }
}
