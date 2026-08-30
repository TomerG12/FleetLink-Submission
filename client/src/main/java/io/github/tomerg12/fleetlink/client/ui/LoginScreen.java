package io.github.tomerg12.fleetlink.client.ui;

import java.util.Objects;

import io.github.tomerg12.fleetlink.client.integration.ClientOperationService;
import io.github.tomerg12.fleetlink.client.integration.ClientPhase;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Builds the static login shell and local form presentation.
 */
public final class LoginScreen {
    private final ClientOperationService operations;

    /**
     * Creates the login screen with the asynchronous remote operation boundary.
     *
     * @param operations asynchronous client operations
     */
    public LoginScreen(ClientOperationService operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    /**
     * Returns the navigation identifier represented by this screen.
     *
     * @return login screen identifier
     */
    public static ScreenId screenId() {
        return ScreenId.LOGIN;
    }

    /**
     * Creates the complete login scene graph before remote work is requested.
     *
     * @return fully constructed login shell
     */
    public Parent createView() {
        Label title = new Label("FLEETLINK");
        title.getStyleClass().add("hero-title");
        Label subtitle = new Label("MULTIPLAYER BATTLESHIP GAME");
        subtitle.getStyleClass().add("hero-subtitle");
        Label description = new Label("Connect, find an opponent, and command your fleet.");
        description.getStyleClass().add("muted-text");
        description.setWrapText(true);

        VBox identity = new VBox(12, title, subtitle, description);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.setMaxWidth(430);
        HBox.setHgrow(identity, Priority.ALWAYS);

        Label formTitle = new Label("ACCOUNT");
        formTitle.getStyleClass().add("section-title");
        Label guidance = new Label(accountGuidance());
        guidance.getStyleClass().add("muted-text");
        guidance.setWrapText(true);
        TextField username = new TextField();
        username.setPromptText("Username");
        username.getStyleClass().add("fleet-input");
        PasswordField password = new PasswordField();
        password.setPromptText("Password");
        password.getStyleClass().add("fleet-input");

        Label status = new Label("Ready.");
        status.getStyleClass().add("form-status");
        status.setWrapText(true);

        Button signIn = UiComponents.primaryButton("SIGN IN", () -> { });
        Button guest = UiComponents.secondaryButton("CONTINUE AS GUEST", () -> { });
        Button createAccount = UiComponents.secondaryButton("CREATE ACCOUNT", () -> { });
        signIn.setOnAction(event -> handleRegisteredConnection(false, username, password,
                status, signIn, guest, createAccount));
        createAccount.setOnAction(event -> handleRegisteredConnection(true, username, password,
                status, signIn, guest, createAccount));
        guest.setOnAction(event -> handleGuestConnection(
                username, status, signIn, guest, createAccount));

        VBox form = UiComponents.surfaceCard(14, formTitle, guidance, username, password, status,
                signIn, guest, createAccount);
        form.setMaxWidth(420);
        HBox.setHgrow(form, Priority.ALWAYS);

        Region gap = new Region();
        gap.setMinWidth(48);
        HBox layout = new HBox(identity, gap, form);
        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(48, 72, 48, 72));
        layout.getStyleClass().add("login-layout");
        return UiComponents.applicationShell(layout);
    }

    /**
     * Returns concise guidance for the shared account and guest fields.
     *
     * @return explanatory login, registration, and guest copy
     */
    static String accountGuidance() {
        return "Enter your username and password to sign in or create an account.\n"
                + "To continue as a guest, enter a display name only.";
    }

    /**
     * Validates registered credentials locally and starts asynchronous login or registration.
     *
     * @param registration true for Create Account and false for Sign In
     * @param username username field
     * @param password password field
     * @param status status label for local validation feedback
     * @param signIn registered sign-in control
     * @param guest guest connection control
     * @param createAccount registration control
     */
    private void handleRegisteredConnection(boolean registration, TextField username,
                                            PasswordField password, Label status,
                                            Button signIn, Button guest, Button createAccount) {
        if (username.getText().isBlank() || password.getText().isEmpty()) {
            showError(status, "Username and password are required.");
            return;
        }
        setSubmissionDisabled(true, signIn, guest, createAccount);
        status.getStyleClass().remove("error-text");
        status.setText(registration ? "Creating account..." : "Signing in...");
        java.util.concurrent.CompletableFuture<io.github.tomerg12.fleetlink.client.integration.ClientState>
                request = registration
                ? operations.register(username.getText(), password.getText())
                : operations.login(username.getText(), password.getText());
        request.whenComplete((state, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                showError(status, "Account request could not be started.");
                setSubmissionDisabled(false, signIn, guest, createAccount);
            } else if (state.getPhase() == ClientPhase.LOGIN) {
                showError(status, state.getStatusMessage());
                setSubmissionDisabled(false, signIn, guest, createAccount);
            }
        }));
    }

    /**
     * Validates the guest display name locally and starts the asynchronous RMI connection.
     *
     * @param username existing username field used as guest display name
     * @param status status label for connection feedback
     * @param signIn registered sign-in control
     * @param guest guest connection control
     * @param createAccount registration control
     */
    private void handleGuestConnection(TextField username, Label status, Button signIn,
                                       Button guest, Button createAccount) {
        String displayName = username.getText().trim();
        if (displayName.isBlank()) {
            showError(status, "Guest display name is required.");
            return;
        }
        setSubmissionDisabled(true, signIn, guest, createAccount);
        status.getStyleClass().remove("error-text");
        status.setText("Connecting to FleetLink...");
        operations.connectAsGuest(displayName).whenComplete((state, failure) ->
                Platform.runLater(() -> {
                    if (failure != null) {
                        showError(status, "Guest connection could not be started.");
                        setSubmissionDisabled(false, signIn, guest, createAccount);
                    } else if (state.getPhase() == ClientPhase.LOGIN) {
                        showError(status, state.getStatusMessage());
                        setSubmissionDisabled(false, signIn, guest, createAccount);
                    }
                }));
    }

    /**
     * Updates the form status with reusable error styling.
     *
     * @param status status label to update
     * @param message player-facing validation or transport message
     */
    private static void showError(Label status, String message) {
        status.setText(message);
        if (!status.getStyleClass().contains("error-text")) {
            status.getStyleClass().add("error-text");
        }
    }

    /**
     * Enables or disables all competing session submission controls together.
     *
     * @param disabled true while one submission is pending
     * @param buttons controls to update
     */
    private static void setSubmissionDisabled(boolean disabled, Button... buttons) {
        for (Button button : buttons) {
            button.setDisable(disabled);
        }
    }
}
