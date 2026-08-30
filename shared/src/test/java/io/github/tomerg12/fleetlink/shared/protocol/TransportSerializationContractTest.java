package io.github.tomerg12.fleetlink.shared.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies stable serialization declarations on class-based transport values.
 */
class TransportSerializationContractTest {

    /**
     * Requires a stable serial version for every serializable protocol class introduced by T1.
     */
    @Test
    void serializableTransportClassesDeclareStableVersion() {
        List<Class<? extends Serializable>> transportClasses = List.of(
                Coordinate.class,
                ShipPlacement.class,
                PlayerView.class,
                SessionInfo.class,
                RematchStatusView.class,
                OwnBoardView.class,
                OpponentBoardView.class,
                GameView.class,
                OperationResult.class,
                SessionResult.class,
                MatchmakingResult.class,
                FleetSubmissionResult.class,
                ShotResult.class,
                GameViewResult.class);

        for (Class<? extends Serializable> transportClass : transportClasses) {
            assertEquals(1L, ObjectStreamClass.lookup(transportClass).getSerialVersionUID(),
                    transportClass.getName());
        }
    }
}
