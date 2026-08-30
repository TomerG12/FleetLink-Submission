package io.github.tomerg12.fleetlink.client.ui;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import io.github.tomerg12.fleetlink.client.integration.ClientOperationService;
import io.github.tomerg12.fleetlink.client.integration.ClientState;
import io.github.tomerg12.fleetlink.client.integration.RematchClientState;
import io.github.tomerg12.fleetlink.shared.protocol.RematchState;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Presents the authoritative completed match and returns through the client state boundary. */
public final class GameOverScreen {
    private final GameOverPresentationModel model;
    private final ClientOperationService operations;
    private final RematchClientState rematchState;

    /**
     * Creates the game-over screen from latest reconciled terminal state.
     *
     * @param state reconciled state containing terminal snapshot
     * @param operations asynchronous client operation boundary
     * @throws NullPointerException if state or operations is null
     * @throws IllegalArgumentException if state has no terminal game snapshot
     */
    public GameOverScreen(ClientState state, ClientOperationService operations) {
        Objects.requireNonNull(state, "state");
        if (state.getGameView() == null) {
            throw new IllegalArgumentException("Game Over state requires a game snapshot");
        }
        model = new GameOverPresentationModel(state.getGameView());
        this.operations = Objects.requireNonNull(operations, "operations");
        rematchState = Objects.requireNonNull(state.getRematchState(), "state.rematchState");
    }

    /**
     * Returns the navigation identifier for this screen type.
     *
     * @return game-over screen identifier
     */
    public static ScreenId screenId() {
        return ScreenId.GAME_OVER;
    }

    /**
     * Builds the complete terminal result presentation from authoritative server data.
     * `NO_CONTEST` is rendered safely with no winner dereference.
     *
     * @return fully constructed result screen
     */
    public Parent createView() {
        Label title = new Label("GAME OVER");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("AUTHORITATIVE MATCH RESULT");
        subtitle.getStyleClass().add("mono-caption");
        Label result = new Label(model.resultTitle());
        result.getStyleClass().add("result-title");
        Label resultDetail = new Label(model.resultDetail());
        resultDetail.getStyleClass().add("muted-text");
        resultDetail.setWrapText(true);
        String winnerName = model.getGameView().getWinner() == null
                ? "NONE" : model.getGameView().getWinner().getDisplayName();
        VBox resultCard = UiComponents.surfaceCard(14, result, resultDetail,
                UiComponents.metricCard("PLAYER", model.getGameView().getPlayer().getDisplayName()),
                UiComponents.metricCard("OPPONENT", model.getGameView().getOpponent().getDisplayName()),
                UiComponents.metricCard("WINNER", winnerName));
        resultCard.setPrefWidth(380);

        Label summaryTitle = new Label("MATCH SUMMARY");
        summaryTitle.getStyleClass().add("section-title");
        Label summary = new Label("Result reason: "
                + model.getGameView().getEndReason().name().replace('_', ' '));
        summary.getStyleClass().add("muted-text");
        summary.setWrapText(true);
        Label actionStatus = new Label(rematchMessage(rematchState));
        actionStatus.getStyleClass().add("muted-text");
        actionStatus.setWrapText(true);
        VBox summaryCard = UiComponents.surfaceCard(16, summaryTitle, summary, actionStatus);
        HBox.setHgrow(summaryCard, Priority.ALWAYS);
        Button rematch = UiComponents.primaryButton("REQUEST REMATCH", () -> { });
        Button accept = UiComponents.primaryButton("ACCEPT REMATCH", () -> { });
        Button decline = UiComponents.secondaryButton("DECLINE REMATCH", () -> { });
        Button lobby = UiComponents.secondaryButton("RETURN TO LOBBY", () -> { });

        boolean incomingVisible = isIncomingInteractionVisible(rematchState);
        rematch.setVisible(!incomingVisible);
        rematch.setManaged(!incomingVisible);
        rematch.setDisable(!rematchState.canRequest());
        accept.setVisible(incomingVisible);
        accept.setManaged(incomingVisible);
        accept.setDisable(!rematchState.canAccept());
        decline.setVisible(incomingVisible);
        decline.setManaged(incomingVisible);
        decline.setDisable(!rematchState.canDecline());
        lobby.setDisable(!rematchState.canReturnToLobby());

        rematch.setOnAction(event -> startRematchAction(rematch, accept, decline, actionStatus,
                operations::requestRematch));
        accept.setOnAction(event -> startRematchAction(rematch, accept, decline, actionStatus,
                () -> operations.respondToRematch(true)));
        decline.setOnAction(event -> startRematchAction(rematch, accept, decline, actionStatus,
                () -> operations.respondToRematch(false)));
        lobby.setOnAction(event -> returnToLobby(lobby, actionStatus));
        summaryCard.getChildren().add(new VBox(10, rematch, accept, decline, lobby));
        HBox cards = new HBox(24, resultCard, summaryCard);
        VBox content = new VBox(24, new VBox(4, title, subtitle), cards);
        content.setPadding(new Insets(32));
        BorderPane body = new BorderPane(content);
        body.getStyleClass().add("game-over-layout");
        return UiComponents.applicationShell(body);
    }

    /**
     * Clears completed-game client state through the asynchronous operation boundary.
     *
     * @param lobby return button disabled while the local transition is pending
     * @param status label receiving pending or failure feedback
     */
    private void returnToLobby(Button lobby, Label status) {
        lobby.setDisable(true);
        status.setText("Returning to Lobby...");
        operations.returnToLobby().whenComplete((state, failure) -> {
            if (failure != null) {
                Platform.runLater(() -> status.setText("This return action is no longer valid."));
            }
        });
    }

    /**
     * Immediately disables duplicate mutation controls and delegates remote work asynchronously.
     * Coordinator revalidation remains authoritative if this screen instance is already stale.
     *
     * @param request Request button from the current immutable screen snapshot
     * @param accept Accept button from the current immutable screen snapshot
     * @param decline Decline button from the current immutable screen snapshot
     * @param status status label receiving local stale-action feedback
     * @param action asynchronous operation supplier
     */
    private void startRematchAction(Button request, Button accept, Button decline, Label status,
                                    Supplier<CompletableFuture<ClientState>> action) {
        request.setDisable(true);
        accept.setDisable(true);
        decline.setDisable(true);
        action.get().whenComplete((state, failure) -> {
            if (failure != null) {
                Platform.runLater(() -> status.setText(
                        "This rematch action is no longer valid."));
            }
        });
    }

    /**
     * Keeps incoming response controls visible while a related mutation still owns the stream.
     *
     * @param rematch current immutable rematch slice
     * @return true when Accept and Decline occupy the action area
     */
    private static boolean isIncomingInteractionVisible(RematchClientState rematch) {
        if (rematch.getAuthoritativeStatus() != null
                && rematch.getAuthoritativeStatus().getState()
                == RematchState.REQUESTED_BY_OPPONENT) {
            return true;
        }
        return rematch.getInFlightAction() == RematchClientState.InFlightAction.ACCEPT
                || rematch.getInFlightAction() == RematchClientState.InFlightAction.DECLINE;
    }

    /**
     * Derives rematch interaction copy without changing completed-match result semantics.
     *
     * @param rematch current immutable rematch slice
     * @return player-facing interaction status
     */
    static String rematchMessage(RematchClientState rematch) {
        Objects.requireNonNull(rematch, "rematch");
        return switch (rematch.getPresentation()) {
            case INITIAL -> "Request a rematch or return to Lobby.";
            case REQUEST_IN_FLIGHT -> "Requesting a rematch...";
            case REQUEST_ACKNOWLEDGED -> "Rematch requested. Waiting for your opponent.";
            case REQUESTED_BY_YOU -> "Rematch requested. Waiting for your opponent.";
            case REQUESTED_BY_OPPONENT -> "Your opponent requested a rematch.";
            case ACCEPT_IN_FLIGHT -> "Accepting the rematch...";
            case DECLINE_IN_FLIGHT -> "Declining the rematch...";
            case WITHDRAW_IN_FLIGHT -> "Withdrawing the rematch request...";
            case DECLINED -> "The rematch was declined.";
            case EXPIRED -> "The rematch opportunity expired.";
            case RECOVERABLE_FAILURE -> rematch.getFeedbackMessage();
            case AWAITING_NEW_GAME -> "Rematch accepted. Starting new game...";
        };
    }
}
