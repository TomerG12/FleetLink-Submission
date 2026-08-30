package io.github.tomerg12.fleetlink.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;

import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import io.github.tomerg12.fleetlink.shared.protocol.MatchOutcome;
import org.junit.jupiter.api.Test;

/** Verifies stable formatting of authoritative statistics and history values. */
class StatisticsPresentationTest {

    /** Confirms ratio and average formatting uses fixed locale-independent precision. */
    @Test
    void formatsRatiosAndAveragesWithoutRecalculatingProtocolValues() {
        assertEquals("62.5%", StatisticsPresentation.percentage(0.625));
        assertEquals("0.0%", StatisticsPresentation.percentage(0.0));
        assertEquals("3.50", StatisticsPresentation.average(3.5));
    }

    /** Confirms short and long durations remain compact and deterministic. */
    @Test
    void formatsMatchDurationBoundaries() {
        assertEquals("0:00", StatisticsPresentation.duration(Duration.ZERO));
        assertEquals("2:05", StatisticsPresentation.duration(Duration.ofSeconds(125)));
        assertEquals("1:02:03", StatisticsPresentation.duration(Duration.ofSeconds(3723)));
    }

    /** Confirms completion time, result, end reason, and rating delta have stable labels. */
    @Test
    void formatsHistoryLabels() {
        assertEquals("2026-08-23 17:39 UTC", StatisticsPresentation.completedAt(
                Instant.parse("2026-08-23T17:39:00Z")));
        assertEquals("WIN", StatisticsPresentation.outcome(MatchOutcome.WIN));
        assertEquals("All Ships Sunk",
                StatisticsPresentation.endReason(GameEndReason.ALL_SHIPS_SUNK));
        assertEquals("+16", StatisticsPresentation.ratingDelta(16));
        assertEquals("-16", StatisticsPresentation.ratingDelta(-16));
        assertEquals("0", StatisticsPresentation.ratingDelta(0));
    }

    /** Confirms guest identity is marked without changing the captured opponent name. */
    @Test
    void formatsGuestOpponentIdentity() {
        assertEquals("Guest Alpha (Guest)",
                StatisticsPresentation.opponent("Guest Alpha", true));
        assertEquals("AccountUser", StatisticsPresentation.opponent("AccountUser", false));
    }
}
