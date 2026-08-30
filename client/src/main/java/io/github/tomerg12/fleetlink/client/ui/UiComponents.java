package io.github.tomerg12.fleetlink.client.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Creates reusable FleetLink controls and structural shell elements.
 */
public final class UiComponents {
    private static final String STYLESHEET = "/io/github/tomerg12/fleetlink/client/ui/fleetlink.css";

    /**
     * Prevents construction because this type exposes only UI factory methods.
     */
    private UiComponents() {
    }

    /**
     * Returns the classpath location of the shared FleetLink stylesheet.
     *
     * @return absolute classpath stylesheet path
     */
    public static String stylesheetPath() {
        return STYLESHEET;
    }

    /**
     * Wraps screen content in the reusable top application header.
     *
     * @param content screen-specific content
     * @return shell containing the shared header and supplied content
     */
    public static BorderPane applicationShell(Node content) {
        Label brand = new Label("FLEETLINK");
        brand.getStyleClass().add("brand-label");

        Label product = new Label("MULTIPLAYER BATTLESHIP");
        product.getStyleClass().add("header-caption");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(12, brand, product, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 24, 0, 24));
        header.getStyleClass().add("top-header");

        BorderPane shell = new BorderPane();
        shell.getStyleClass().add("app-shell");
        shell.setTop(header);
        shell.setCenter(content);
        return shell;
    }

    /**
     * Creates the reusable dashboard sidebar shared by Lobby and Player Statistics.
     *
     * @param navigator client navigation boundary
     * @param activeScreen currently active dashboard destination
     * @param displayName safe established session display name
     * @param guest whether the established session is temporary
     * @param logoutAction asynchronous logout request supplied by the application boundary
     * @return styled dashboard sidebar
     */
    public static VBox dashboardSidebar(ScreenNavigator navigator, ScreenId activeScreen,
                                        String displayName, boolean guest,
                                        Runnable logoutAction) {
        Label player = new Label(displayName);
        player.getStyleClass().add("section-title");
        Label sessionKind = new Label(sessionKindLabel(guest));
        sessionKind.getStyleClass().add("mono-caption");

        Button lobby = navigationButton("LOBBY", ScreenId.LOBBY, activeScreen, navigator);
        Button statistics = navigationButton("STATISTICS", ScreenId.PLAYER_STATISTICS, activeScreen, navigator);
        Button logout = secondaryButton("LOG OUT", logoutAction);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox sidebar = new VBox(12, player, sessionKind, lobby, statistics, spacer, logout);
        sidebar.setPadding(new Insets(24));
        sidebar.setPrefWidth(260);
        sidebar.getStyleClass().add("sidebar");
        return sidebar;
    }

    /**
     * Returns neutral session identity copy without guessing a leaderboard rank.
     *
     * @param guest whether the established session is temporary
     * @return guest or registered session label
     */
    static String sessionKindLabel(boolean guest) {
        return guest ? "GUEST SESSION" : "REGISTERED ACCOUNT";
    }

    /**
     * Creates a reusable tonal panel for grouped content.
     *
     * @param spacing vertical gap between child nodes
     * @param children panel contents
     * @return styled panel
     */
    public static VBox surfaceCard(double spacing, Node... children) {
        VBox card = new VBox(spacing, children);
        card.setPadding(new Insets(24));
        card.getStyleClass().add("surface-card");
        return card;
    }

    /**
     * Creates a reusable metric card for statistics and result summaries.
     *
     * @param label metric label
     * @param value metric value or placeholder
     * @return styled metric card
     */
    public static VBox metricCard(String label, String value) {
        Label metricLabel = new Label(label);
        metricLabel.getStyleClass().add("metric-label");
        Label metricValue = new Label(value);
        metricValue.getStyleClass().add("metric-value");
        VBox card = surfaceCard(8, metricLabel, metricValue);
        card.getStyleClass().add("metric-card");
        return card;
    }

    /**
     * Creates a primary action button with the FleetLink design-system style.
     *
     * @param text button label
     * @param action local action to invoke
     * @return styled primary button
     */
    public static Button primaryButton(String text, Runnable action) {
        return actionButton(text, action, "primary-button");
    }

    /**
     * Creates a secondary action button with the FleetLink design-system style.
     *
     * @param text button label
     * @param action local action to invoke
     * @return styled secondary button
     */
    public static Button secondaryButton(String text, Runnable action) {
        return actionButton(text, action, "secondary-button");
    }

    /**
     * Creates a destructive-action button for controls such as resigning a match.
     *
     * @param text button label
     * @param action local action to invoke
     * @return styled danger button
     */
    public static Button dangerButton(String text, Runnable action) {
        return actionButton(text, action, "danger-button");
    }

    /**
     * Creates a mono-style status chip for local presentation states.
     *
     * @param text displayed status
     * @param stateStyle additional state-specific CSS class
     * @return styled status label
     */
    public static Label statusChip(String text, String stateStyle) {
        Label chip = new Label(text);
        chip.getStyleClass().addAll("status-chip", stateStyle);
        return chip;
    }

    /**
     * Creates one dashboard navigation button and disables the current destination.
     *
     * @param text button label
     * @param destination destination screen
     * @param activeScreen current screen
     * @param navigator client navigation boundary
     * @return configured navigation button
     */
    private static Button navigationButton(String text, ScreenId destination, ScreenId activeScreen, ScreenNavigator navigator) {
        Button button = secondaryButton(text, () -> navigator.navigate(destination));
        if (destination == activeScreen) {
            button.setDisable(true);
            button.getStyleClass().add("navigation-active");
        }
        return button;
    }

    /**
     * Creates a button shared by the public action factories.
     *
     * @param text button label
     * @param action action invoked by the button
     * @param styleClass FleetLink button style class
     * @return configured button
     */
    private static Button actionButton(String text, Runnable action, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> action.run());
        return button;
    }
}
