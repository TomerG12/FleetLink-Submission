package io.github.tomerg12.fleetlink.server.statistics;

import io.github.tomerg12.fleetlink.server.persistence.ParticipantResult;
import io.github.tomerg12.fleetlink.server.rating.RegisteredRatingRegistry;
import io.github.tomerg12.fleetlink.server.statistics.StatisticsRepository.HistoryRow;
import io.github.tomerg12.fleetlink.server.statistics.StatisticsRepository.LeaderboardRow;
import io.github.tomerg12.fleetlink.server.statistics.StatisticsRepository.PersonalStatisticsData;
import io.github.tomerg12.fleetlink.shared.protocol.LeaderboardEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchHistoryEntryView;
import io.github.tomerg12.fleetlink.shared.protocol.MatchOutcome;
import io.github.tomerg12.fleetlink.shared.protocol.PlayerStatisticsView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Combines live registered rating state with committed bounded database statistics queries.
 * It never waits for asynchronous completion recording and never overlays live ratings onto the
 * committed public leaderboard.
 */
public final class StatisticsQueryService {

    private final StatisticsRepository repository;
    private final RegisteredRatingRegistry ratings;

    /**
     * Creates the statistics service from the focused query repository and existing live registry.
     *
     * @param repository database filtering, aggregation, ordering, and pagination boundary
     * @param ratings existing process-live registered rating authority
     */
    public StatisticsQueryService(StatisticsRepository repository,
                                  RegisteredRatingRegistry ratings) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ratings = Objects.requireNonNull(ratings, "ratings");
    }

    /**
     * Returns current live rating with committed personal aggregates and one history page.
     *
     * @param playerId registered player identity resolved from the active session
     * @param historyOffset nonnegative database page offset
     * @param historyLimit positive requested page size
     * @return immutable transport-safe personal statistics view
     */
    public PlayerStatisticsView getPlayerStatistics(UUID playerId, int historyOffset,
                                                    int historyLimit) {
        Objects.requireNonNull(playerId, "playerId");
        int currentRating = ratings.current(playerId).getRating();
        PersonalStatisticsData data = repository.loadPersonalStatistics(
                playerId, historyOffset, historyLimit);
        List<MatchHistoryEntryView> history = data.getHistory().stream()
                .map(StatisticsQueryService::toHistoryView)
                .toList();
        return new PlayerStatisticsView(currentRating, data.getTotalGames(), data.getWins(),
                data.getLosses(), data.getShipsSunk(), data.getTotalShots(), data.getHits(),
                history, historyOffset, data.hasMore());
    }

    /**
     * Returns the committed public leaderboard and assigns sequential one-based ranks only after
     * the database has performed ordering, limiting, and bounded aggregation.
     *
     * @param limit positive maximum number of entries
     * @return immutable ordered leaderboard views
     */
    public List<LeaderboardEntryView> getLeaderboard(int limit) {
        List<LeaderboardRow> rows = repository.loadLeaderboard(limit);
        List<LeaderboardEntryView> entries = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            LeaderboardRow row = rows.get(index);
            entries.add(new LeaderboardEntryView(index + 1, row.getUsername(), row.getRating(),
                    row.getGamesPlayed(), row.getWins()));
        }
        return List.copyOf(entries);
    }

    /**
     * Maps one detached persistence projection to a transport history view.
     *
     * @param row detached repository history row
     * @return immutable transport history entry
     */
    private static MatchHistoryEntryView toHistoryView(HistoryRow row) {
        MatchOutcome outcome = row.getResult() == ParticipantResult.WIN
                ? MatchOutcome.WIN : MatchOutcome.LOSS;
        Duration duration = Duration.between(row.getStartedAt(), row.getCompletedAt());
        return new MatchHistoryEntryView(row.getOpponentDisplayName(), row.isOpponentGuest(),
                outcome, row.getEndReason(), row.getTurnsTaken(), duration,
                row.getShotsFired(), row.getHits(), row.getShipsSunk(), row.getRatingDelta(),
                row.getCompletedAt());
    }
}
