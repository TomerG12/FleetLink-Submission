package io.github.tomerg12.fleetlink.shared.rmi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.shared.protocol.Coordinate;
import io.github.tomerg12.fleetlink.shared.protocol.FleetSubmissionResult;
import io.github.tomerg12.fleetlink.shared.protocol.GamePhase;
import io.github.tomerg12.fleetlink.shared.protocol.GameView;
import io.github.tomerg12.fleetlink.shared.protocol.GameViewResult;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingResult;
import io.github.tomerg12.fleetlink.shared.protocol.MatchmakingState;
import io.github.tomerg12.fleetlink.shared.protocol.OperationResult;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerView;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsResult;
import io.github.tomerg12.fleetlink.shared.protocol.RematchStatusView;
import io.github.tomerg12.fleetlink.shared.protocol.SessionResult;
import io.github.tomerg12.fleetlink.shared.protocol.ShipPlacement;
import io.github.tomerg12.fleetlink.shared.protocol.ShotOutcome;
import io.github.tomerg12.fleetlink.shared.protocol.ShotResult;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Verifies the standard Java RMI shape and server-authoritative method signatures.
 */
class RemoteContractTest {

    /**
     * Requires both public protocol boundaries to be standard RMI remote interfaces.
     */
    @Test
    void remoteInterfacesExtendRemote() {
        assertTrue(Remote.class.isAssignableFrom(FleetLinkServerRemote.class));
        assertTrue(Remote.class.isAssignableFrom(FleetLinkClientCallback.class));
        assertTrue(FleetLinkServerRemote.class.isInterface());
        assertTrue(FleetLinkClientCallback.class.isInterface());
    }

    /**
     * Requires every server operation and callback to declare only the standard RMI exception.
     */
    @Test
    void everyRemoteMethodDeclaresRemoteException() {
        assertRemoteExceptionContract(FleetLinkServerRemote.class);
        assertRemoteExceptionContract(FleetLinkClientCallback.class);
    }

    /**
     * Locks down the complete server operation set through T6.2.
     */
    @Test
    void serverInterfaceDefinesOnlyPlannedOperations() {
        Set<String> names = Arrays.stream(FleetLinkServerRemote.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("login", "register", "connectAsGuest", "logout",
                "joinMatchmaking", "cancelMatchmaking", "submitFleet", "fire",
                "getCurrentGame", "getPlayerStatistics", "getLeaderboard", "leaveGame",
                "requestRematch", "respondToRematch"), names);
    }

    /**
     * Locks down the explicit callback responsibilities from the protocol plan.
     */
    @Test
    void callbackDefinesOnlyPlannedNotifications() {
        Set<String> names = Arrays.stream(FleetLinkClientCallback.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("onMatchFound", "onGameStateChanged",
                "onRematchRequested", "onRematchStatusChanged"), names);
    }

    /**
     * Requires every post-connection operation to identify the caller by session first.
     */
    @Test
    void authenticatedOperationsAreSessionBased() {
        Set<String> connectionMethods = Set.of("login", "register", "connectAsGuest");

        for (Method method : FleetLinkServerRemote.class.getDeclaredMethods()) {
            if (!connectionMethods.contains(method.getName())) {
                assertEquals(UUID.class, method.getParameterTypes()[0], method.getName());
            }
        }
    }

    /**
     * Requires one remote call to carry the complete typed fleet list.
     *
     * @throws NoSuchMethodException if the planned operation is missing
     */
    @Test
    void fleetSubmissionIsOneCompleteOperation() throws NoSuchMethodException {
        Method submitFleet = FleetLinkServerRemote.class.getMethod(
                "submitFleet", UUID.class, List.class);
        Type listType = submitFleet.getGenericParameterTypes()[1];

        assertTrue(listType instanceof ParameterizedType);
        ParameterizedType placements = (ParameterizedType) listType;
        assertEquals(List.class, placements.getRawType());
        assertArrayEquals(new Type[]{ShipPlacement.class}, placements.getActualTypeArguments());
        assertEquals(FleetSubmissionResult.class, submitFleet.getReturnType());
    }

    /**
     * Prevents client requests from supplying authoritative rating, identity, or game snapshots.
     */
    @Test
    void clientInputsExcludeServerOwnedState() {
        Set<Class<?>> forbiddenInputs = Set.of(PlayerView.class, GameView.class, GamePhase.class,
                MatchmakingState.class, ShotOutcome.class, Integer.class);

        for (Method method : FleetLinkServerRemote.class.getDeclaredMethods()) {
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertFalse(forbiddenInputs.contains(parameterType), method.toString());
            }
        }
    }

    /**
     * Allows primitive integers only as bounded statistics pagination inputs.
     */
    @Test
    void primitiveIntegerInputsAreOnlyStatisticsBounds() {
        Set<String> allowed = Set.of("getPlayerStatistics", "getLeaderboard");

        for (Method method : FleetLinkServerRemote.class.getDeclaredMethods()) {
            boolean containsInteger = Arrays.stream(method.getParameterTypes())
                    .anyMatch(type -> type == int.class);
            assertEquals(allowed.contains(method.getName()), containsInteger, method.toString());
        }
    }

    /**
     * Requires the exact session-first T6.2 signatures, limits, and result types.
     *
     * @throws NoSuchMethodException if a statistics operation is missing
     */
    @Test
    void statisticsOperationsUseOnlySessionAndBounds() throws NoSuchMethodException {
        Method statistics = FleetLinkServerRemote.class.getMethod(
                "getPlayerStatistics", UUID.class, int.class, int.class);
        Method leaderboard = FleetLinkServerRemote.class.getMethod(
                "getLeaderboard", UUID.class, int.class);

        assertEquals(PlayerStatisticsResult.class, statistics.getReturnType());
        assertEquals(LeaderboardResult.class, leaderboard.getReturnType());
        assertEquals(50, FleetLinkServerRemote.MAX_HISTORY_LIMIT);
        assertEquals(100, FleetLinkServerRemote.MAX_LEADERBOARD_LIMIT);
    }

    /**
     * Locks the frozen session-only rematch signatures and proves no public withdrawal operation was
     * added.
     *
     * @throws NoSuchMethodException if an approved rematch operation is missing
     */
    @Test
    void rematchOperationsKeepFrozenSessionOnlyContract() throws NoSuchMethodException {
        Method request = FleetLinkServerRemote.class.getMethod("requestRematch", UUID.class);
        Method respond = FleetLinkServerRemote.class.getMethod(
                "respondToRematch", UUID.class, boolean.class);

        assertEquals(OperationResult.class, request.getReturnType());
        assertEquals(OperationResult.class, respond.getReturnType());
        assertFalse(Arrays.stream(FleetLinkServerRemote.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("withdrawRematch")));
    }

    /**
     * Requires explicit result DTOs for expected server-operation failures.
     */
    @Test
    void serverOperationsReturnExplicitResultTypes() {
        Set<Class<?>> resultTypes = Set.of(SessionResult.class, OperationResult.class,
                MatchmakingResult.class, FleetSubmissionResult.class,
                ShotResult.class, GameViewResult.class, PlayerStatisticsResult.class,
                LeaderboardResult.class);

        for (Method method : FleetLinkServerRemote.class.getDeclaredMethods()) {
            assertTrue(resultTypes.contains(method.getReturnType()), method.toString());
        }
    }

    /**
     * Requires safe game snapshots and rematch views on every callback parameter.
     */
    @Test
    void callbacksCarryOnlySafeSnapshotTypes() {
        for (Method method : FleetLinkClientCallback.class.getDeclaredMethods()) {
            assertEquals(1, method.getParameterCount(), method.toString());
            Class<?> parameterType = method.getParameterTypes()[0];
            assertTrue(parameterType == GameView.class || parameterType == RematchStatusView.class,
                    method.toString());
        }
    }

    /**
     * Confirms the fire request contains only session identity and a validated coordinate.
     *
     * @throws NoSuchMethodException if the planned operation is missing
     */
    @Test
    void fireDoesNotAcceptClientTurnOrGameState() throws NoSuchMethodException {
        Method fire = FleetLinkServerRemote.class.getMethod(
                "fire", UUID.class, Coordinate.class);

        assertEquals(ShotResult.class, fire.getReturnType());
    }

    /**
     * Checks one remote interface for the exact checked exception contract.
     *
     * @param remoteType the remote interface to inspect
     */
    private static void assertRemoteExceptionContract(Class<?> remoteType) {
        for (Method method : remoteType.getDeclaredMethods()) {
            assertArrayEquals(new Class<?>[]{RemoteException.class},
                    method.getExceptionTypes(), method.toString());
        }
    }
}
