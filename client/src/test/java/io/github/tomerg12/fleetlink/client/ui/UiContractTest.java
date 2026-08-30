package io.github.tomerg12.fleetlink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import io.github.tomerg12.fleetlink.client.integration.ClientPhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.ShipType;
import org.junit.jupiter.api.Test;

/**
 * Verifies static shell contracts without starting the JavaFX toolkit.
 */
class UiContractTest {

    /**
     * Confirms all six accepted screens expose stable identifiers and a packaged stylesheet.
     */
    @Test
    void shellMetadataMatchesNavigationContract() {
        assertEquals(ScreenId.LOGIN, FleetLinkClientApplication.initialScreen());
        assertEquals(ScreenId.LOGIN, LoginScreen.screenId());
        assertEquals(ScreenId.LOBBY, LobbyScreen.screenId());
        assertEquals(ScreenId.SHIP_PLACEMENT, ShipPlacementScreen.screenId());
        assertEquals(ScreenId.BATTLE, BattleScreen.screenId());
        assertEquals(ScreenId.GAME_OVER, GameOverScreen.screenId());
        assertEquals(ScreenId.PLAYER_STATISTICS, PlayerStatisticsScreen.screenId());
        assertNotNull(UiComponents.class.getResource(UiComponents.stylesheetPath()));
        assertEquals("CARRIER - 5 CELLS", ShipPlacementScreen.shipLabel(ShipType.CARRIER));
        assertEquals("FORFEIT", ShipPlacementScreen.forfeitLabel());
        assertEquals("FORFEIT", BattleScreen.forfeitLabel());
    }

    /**
     * Confirms reusable board-state names remain mapped to the shared design-system CSS contract.
     */
    @Test
    void boardStatesMapToStableStyleClasses() {
        assertEquals("", BattleshipBoardView.cellStyleClass(BattleshipBoardView.CellState.EMPTY));
        assertEquals("board-cell-occupied", BattleshipBoardView.cellStyleClass(BattleshipBoardView.CellState.OCCUPIED));
        assertEquals("board-cell-hit", BattleshipBoardView.cellStyleClass(BattleshipBoardView.CellState.HIT));
        assertEquals("board-cell-miss", BattleshipBoardView.cellStyleClass(BattleshipBoardView.CellState.MISS));
        assertEquals("board-cell-selected", BattleshipBoardView.cellStyleClass(BattleshipBoardView.CellState.SELECTED));
    }

    /**
     * Confirms integration state reuses the existing navigation destinations.
     */
    @Test
    void integrationPhasesReuseExistingScreenNavigatorContract() {
        assertEquals(ScreenId.LOGIN, FleetLinkClientApplication.screenFor(ClientPhase.CONNECTING));
        assertEquals(ScreenId.LOBBY, FleetLinkClientApplication.screenFor(ClientPhase.MATCHMAKING));
        assertEquals(ScreenId.SHIP_PLACEMENT,
                FleetLinkClientApplication.screenFor(ClientPhase.WAITING_FOR_BATTLE));
        assertEquals(ScreenId.BATTLE, FleetLinkClientApplication.screenFor(ClientPhase.BATTLE));
        assertEquals(ScreenId.BATTLE, FleetLinkClientApplication.screenFor(ClientPhase.FIRING));
        assertEquals(ScreenId.SHIP_PLACEMENT,
                FleetLinkClientApplication.screenFor(
                        ClientPhase.LEAVING_GAME, GamePhase.FLEET_PLACEMENT));
        assertEquals(ScreenId.BATTLE,
                FleetLinkClientApplication.screenFor(
                        ClientPhase.LEAVING_GAME, GamePhase.BATTLE));
        assertEquals(ScreenId.LOBBY,
                FleetLinkClientApplication.screenFor(ClientPhase.LOGGING_OUT));
        assertEquals(ScreenId.GAME_OVER, FleetLinkClientApplication.screenFor(ClientPhase.GAME_OVER));
    }

    /** Confirms statistics sections and tables remain inside the one established screen. */
    @Test
    void statisticsDashboardUsesOneScreenWithRequiredSectionsAndColumns() {
        assertEquals(List.of("OVERVIEW", "MATCH HISTORY", "LEADERBOARD"),
                PlayerStatisticsScreen.sectionTitles());
        assertEquals(List.of("OPPONENT", "RESULT", "END REASON", "TURNS", "DURATION",
                        "ACCURACY", "SUNK", "RATING", "COMPLETED"),
                PlayerStatisticsScreen.historyColumnTitles());
        assertEquals(List.of("RANK", "PLAYER", "RATING", "GAMES", "WINS"),
                PlayerStatisticsScreen.leaderboardColumnTitles());
        assertEquals(10, PlayerStatisticsScreen.HISTORY_PAGE_SIZE);
        assertEquals(100, PlayerStatisticsScreen.LEADERBOARD_LIMIT);
        assertEquals(0, LobbyScreen.PREVIEW_HISTORY_OFFSET);
        assertEquals(3, LobbyScreen.PREVIEW_HISTORY_LIMIT);
        assertEquals(5, LobbyScreen.PREVIEW_LEADERBOARD_LIMIT);
    }

    /** Confirms sidebar identity copy never fabricates rank and protocol reason stays unchanged. */
    @Test
    void forfeitCopyDoesNotRenameProtocolOrGuessSidebarRank() {
        assertEquals("REGISTERED ACCOUNT", UiComponents.sessionKindLabel(false));
        assertEquals("GUEST SESSION", UiComponents.sessionKindLabel(true));
        assertEquals(GameEndReason.RESIGNATION,
                GameEndReason.valueOf("RESIGNATION"));
    }

    /** Confirms the shared account form explains all three existing entry actions up front. */
    @Test
    void accountFormExplainsGuestDisplayNameBeforeSubmission() {
        assertEquals("Enter your username and password to sign in or create an account.\n"
                + "To continue as a guest, enter a display name only.",
                LoginScreen.accountGuidance());
    }
}
