package io.github.tomerg12.fleetlink.server.statistics;

import io.github.tomerg12.fleetlink.server.persistence.ParticipantResult;
import io.github.tomerg12.fleetlink.shared.protocol.GameEndReason;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.resource.jdbc.spi.PhysicalConnectionHandlingMode;

/**
 * Executes bounded database-side aggregate, history, and leaderboard queries.
 * Each public operation uses one held connection and one committed database snapshot for all of
 * its queries. It returns detached immutable query rows and never shares JPA entities with the
 * service or RMI boundary.
 */
public final class StatisticsRepository {

    /** H2's documented JDBC isolation value for SNAPSHOT transactions. */
    private static final int H2_SNAPSHOT_ISOLATION = 6;

    private static final String PERSONAL_AGGREGATE_QUERY = """
            select count(participant),
                   coalesce(sum(case when participant.result = :win then 1 else 0 end), 0),
                   coalesce(sum(case when participant.result = :loss then 1 else 0 end), 0),
                   coalesce(sum(participant.shotsFired), 0),
                   coalesce(sum(participant.hits), 0),
                   coalesce(sum(participant.shipsSunk), 0)
            from GameParticipantEntity participant
            where participant.player.id = :playerId
            """;

    private static final String HISTORY_QUERY = """
            select participant.game.id,
                   opponent.displayNameSnapshot,
                   opponent.guest,
                   participant.result,
                   participant.game.endReason,
                   participant.turnsTaken,
                   participant.game.startedAt,
                   participant.game.completedAt,
                   participant.shotsFired,
                   participant.hits,
                   participant.shipsSunk,
                   participant.ratingDelta
            from GameParticipantEntity participant, GameParticipantEntity opponent
            where participant.player.id = :playerId
              and opponent.game = participant.game
              and opponent.id <> participant.id
            order by participant.game.completedAt desc, participant.game.id desc
            """;

    private static final String LEADERBOARD_PLAYERS_QUERY = """
            select player.id, player.username, player.rating
            from PlayerEntity player
            order by player.rating desc, player.usernameKey asc, player.id asc
            """;

    private static final String LEADERBOARD_AGGREGATE_QUERY = """
            select participant.player.id,
                   count(participant),
                   coalesce(sum(case when participant.result = :win then 1 else 0 end), 0)
            from GameParticipantEntity participant
            where participant.player.id in :playerIds
            group by participant.player.id
            """;

    private final SessionFactory sessionFactory;
    private final QueryObserver queryObserver;

    /**
     * Creates a query repository using the existing process-wide persistence factory.
     *
     * @param entityManagerFactory factory used to create operation-scoped Hibernate sessions
     */
    public StatisticsRepository(EntityManagerFactory entityManagerFactory) {
        this(entityManagerFactory, QueryObserver.NO_OP);
    }

    /**
     * Creates a query repository with a package-visible deterministic interleaving observer.
     *
     * @param entityManagerFactory factory used to create operation-scoped Hibernate sessions
     * @param queryObserver observer invoked between the paired snapshot queries
     */
    StatisticsRepository(EntityManagerFactory entityManagerFactory, QueryObserver queryObserver) {
        this.sessionFactory = Objects.requireNonNull(entityManagerFactory,
                "entityManagerFactory").unwrap(SessionFactory.class);
        this.queryObserver = Objects.requireNonNull(queryObserver, "queryObserver");
    }

    /**
     * Loads database aggregates and one bounded, deterministic personal history page.
     * The history query asks for one extra row so the caller can expose stable has-more metadata.
     *
     * @param playerId persistent registered-player identifier
     * @param historyOffset nonnegative database offset
     * @param historyLimit positive requested page size
     * @return detached aggregate and page query data
     * @throws NullPointerException if playerId is null
     * @throws IllegalArgumentException if offset or limit is invalid
     */
    public PersonalStatisticsData loadPersonalStatistics(UUID playerId, int historyOffset,
                                                         int historyLimit) {
        Objects.requireNonNull(playerId, "playerId");
        if (historyOffset < 0 || historyLimit <= 0) {
            throw new IllegalArgumentException("history bounds must be positive and nonnegative");
        }
        return inCommittedSnapshot(session -> {
            Object[] aggregate = session.createQuery(PERSONAL_AGGREGATE_QUERY, Object[].class)
                    .setParameter("playerId", playerId)
                    .setParameter("win", ParticipantResult.WIN)
                    .setParameter("loss", ParticipantResult.LOSS)
                    .getSingleResult();
            queryObserver.afterPersonalAggregateRead();
            List<Object[]> rows = session.createQuery(HISTORY_QUERY, Object[].class)
                    .setParameter("playerId", playerId)
                    .setFirstResult(historyOffset)
                    .setMaxResults(historyLimit + 1)
                    .getResultList();
            boolean hasMore = rows.size() > historyLimit;
            int returnedSize = Math.min(rows.size(), historyLimit);
            List<HistoryRow> history = new ArrayList<>(returnedSize);
            for (int index = 0; index < returnedSize; index++) {
                history.add(toHistoryRow(rows.get(index)));
            }
            return new PersonalStatisticsData(number(aggregate[0]), number(aggregate[1]),
                    number(aggregate[2]), number(aggregate[3]), number(aggregate[4]),
                    number(aggregate[5]), history, hasMore);
        });
    }

    /**
     * Selects, orders, and limits registered players in the database, then aggregates only those
     * bounded identities in a second database query. Zero-game accounts remain in the result.
     *
     * @param limit positive maximum number of registered accounts to return
     * @return detached rows in final leaderboard order without assigned ranks
     * @throws IllegalArgumentException if limit is not positive
     */
    public List<LeaderboardRow> loadLeaderboard(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("leaderboard limit must be positive");
        }
        return inCommittedSnapshot(session -> {
            List<Object[]> selected = session.createQuery(
                            LEADERBOARD_PLAYERS_QUERY, Object[].class)
                    .setMaxResults(limit)
                    .getResultList();
            queryObserver.afterLeaderboardSelectionRead();
            if (selected.isEmpty()) {
                return List.of();
            }
            List<UUID> playerIds = selected.stream()
                    .map(row -> (UUID) row[0])
                    .toList();
            Map<UUID, WinAggregate> aggregates = new HashMap<>();
            for (Object[] row : session.createQuery(
                            LEADERBOARD_AGGREGATE_QUERY, Object[].class)
                    .setParameter("playerIds", playerIds)
                    .setParameter("win", ParticipantResult.WIN)
                    .getResultList()) {
                aggregates.put((UUID) row[0],
                        new WinAggregate(number(row[1]), number(row[2])));
            }
            List<LeaderboardRow> result = new ArrayList<>(selected.size());
            for (Object[] row : selected) {
                UUID playerId = (UUID) row[0];
                WinAggregate aggregate = aggregates.getOrDefault(playerId, WinAggregate.EMPTY);
                result.add(new LeaderboardRow(playerId, (String) row[1],
                        ((Number) row[2]).intValue(), aggregate.gamesPlayed, aggregate.wins));
            }
            return List.copyOf(result);
        });
    }

    /**
     * Executes a multi-query read against one committed H2 snapshot on one held connection.
     * The caller receives detached output only after commit, and the connection's prior isolation
     * is restored after the transaction ends so pooled connection state cannot leak.
     *
     * @param queryOperation repository queries to execute within the snapshot
     * @param <T> detached result type
     * @return detached operation result
     */
    private <T> T inCommittedSnapshot(Function<Session, T> queryOperation) {
        Objects.requireNonNull(queryOperation, "queryOperation");
        try (Session session = sessionFactory.withOptions()
                .connectionHandlingMode(
                        PhysicalConnectionHandlingMode.DELAYED_ACQUISITION_AND_HOLD)
                .openSession()) {
            int previousIsolation = session.doReturningWork(
                    connection -> connection.getTransactionIsolation());
            session.doWork(connection ->
                    connection.setTransactionIsolation(H2_SNAPSHOT_ISOLATION));
            Transaction transaction = null;
            RuntimeException operationFailure = null;
            try {
                transaction = session.beginTransaction();
                T result = queryOperation.apply(session);
                transaction.commit();
                return result;
            } catch (RuntimeException failure) {
                operationFailure = failure;
                rollback(transaction, failure);
                throw failure;
            } finally {
                restoreIsolation(session, previousIsolation, operationFailure);
            }
        }
    }

    /**
     * Rolls back an active snapshot transaction while preserving the original operation failure.
     *
     * @param transaction transaction that may still be active
     * @param operationFailure original query or commit failure
     */
    private static void rollback(Transaction transaction, RuntimeException operationFailure) {
        if (transaction == null || !transaction.isActive()) {
            return;
        }
        try {
            transaction.rollback();
        } catch (RuntimeException rollbackFailure) {
            operationFailure.addSuppressed(rollbackFailure);
        }
    }

    /**
     * Restores the held connection's original isolation after the snapshot transaction ends.
     * Restoration failure is suppressed onto an existing operation failure when necessary.
     *
     * @param session open operation session holding the physical connection
     * @param previousIsolation isolation captured before the snapshot transaction
     * @param operationFailure original operation failure, or null after success
     */
    private static void restoreIsolation(Session session, int previousIsolation,
                                         RuntimeException operationFailure) {
        try {
            session.doWork(connection ->
                    connection.setTransactionIsolation(previousIsolation));
        } catch (RuntimeException restorationFailure) {
            if (operationFailure != null) {
                operationFailure.addSuppressed(restorationFailure);
                return;
            }
            throw restorationFailure;
        }
    }

    /**
     * Converts one projected history tuple without traversing a lazy participant collection.
     *
     * @param row projected query tuple
     * @return detached immutable history row
     */
    private static HistoryRow toHistoryRow(Object[] row) {
        return new HistoryRow((UUID) row[0], (String) row[1], (Boolean) row[2],
                (ParticipantResult) row[3], (GameEndReason) row[4], number(row[5]),
                (Instant) row[6], (Instant) row[7], number(row[8]), number(row[9]),
                number(row[10]), ((Number) row[11]).intValue());
    }

    /**
     * Converts a provider numeric aggregate to the protocol's long count representation.
     *
     * @param value non-null aggregate number
     * @return long count value
     */
    private static long number(Object value) {
        return ((Number) Objects.requireNonNull(value, "aggregate value")).longValue();
    }

    /**
     * Supplies package-visible deterministic interleaving points between paired snapshot queries.
     * Production construction uses the no-op observer.
     */
    interface QueryObserver {

        /** No-op observer used outside deterministic repository tests. */
        QueryObserver NO_OP = new QueryObserver() { };

        /**
         * Runs after the personal aggregate is materialized and before history is queried.
         */
        default void afterPersonalAggregateRead() { }

        /**
         * Runs after leaderboard players are selected and before their aggregates are queried.
         */
        default void afterLeaderboardSelectionRead() { }
    }

    /**
     * Carries one personal aggregate and page from the repository to the query service.
     */
    public static final class PersonalStatisticsData {
        private final long totalGames;
        private final long wins;
        private final long losses;
        private final long totalShots;
        private final long hits;
        private final long shipsSunk;
        private final List<HistoryRow> history;
        private final boolean hasMore;

        /**
         * Stores immutable database query output.
         *
         * @param totalGames committed game count
         * @param wins committed win count
         * @param losses committed loss count
         * @param totalShots committed shot count
         * @param hits committed hit count
         * @param shipsSunk committed sunk ship count
         * @param history bounded history rows
         * @param hasMore whether an extra row followed the requested page
         */
        private PersonalStatisticsData(long totalGames, long wins, long losses,
                                       long totalShots, long hits, long shipsSunk,
                                       List<HistoryRow> history, boolean hasMore) {
            this.totalGames = totalGames;
            this.wins = wins;
            this.losses = losses;
            this.totalShots = totalShots;
            this.hits = hits;
            this.shipsSunk = shipsSunk;
            this.history = List.copyOf(history);
            this.hasMore = hasMore;
        }

        /**
         * Returns the number of committed games used by every personal aggregate ratio.
         *
         * @return committed game count
         */
        public long getTotalGames() { return totalGames; }

        /**
         * Returns the number of committed participant rows whose result is WIN.
         *
         * @return committed win count
         */
        public long getWins() { return wins; }

        /**
         * Returns the number of committed participant rows whose result is LOSS.
         *
         * @return committed loss count
         */
        public long getLosses() { return losses; }

        /**
         * Returns accepted shots aggregated by the database for accuracy calculation.
         *
         * @return committed accepted shot count
         */
        public long getTotalShots() { return totalShots; }

        /**
         * Returns accepted hits aggregated by the database for derived statistics.
         *
         * @return committed hit count
         */
        public long getHits() { return hits; }

        /**
         * Returns opponent ships sunk across the committed participant rows.
         *
         * @return committed sunk ship count
         */
        public long getShipsSunk() { return shipsSunk; }

        /**
         * Returns the bounded rows in the deterministic database history order.
         *
         * @return immutable ordered history rows
         */
        public List<HistoryRow> getHistory() { return history; }

        /**
         * Reports whether the repository observed an extra history row beyond this page.
         *
         * @return true when another history row follows the page
         */
        public boolean hasMore() { return hasMore; }
    }

    /**
     * Carries one projected personal history row without a JPA entity reference.
     */
    public static final class HistoryRow {
        private final UUID gameId;
        private final String opponentDisplayName;
        private final boolean opponentGuest;
        private final ParticipantResult result;
        private final GameEndReason endReason;
        private final long turnsTaken;
        private final Instant startedAt;
        private final Instant completedAt;
        private final long shotsFired;
        private final long hits;
        private final long shipsSunk;
        private final int ratingDelta;

        /**
         * Stores one detached history projection.
         *
         * @param gameId stable game identifier used as the secondary order key
         * @param opponentDisplayName immutable opponent snapshot name
         * @param opponentGuest immutable opponent guest flag
         * @param result persistent participant result
         * @param endReason terminal game reason
         * @param turnsTaken player turn count
         * @param startedAt authoritative start time
         * @param completedAt authoritative completion time
         * @param shotsFired accepted shots
         * @param hits accepted hits
         * @param shipsSunk sunk ship count
         * @param ratingDelta signed rating change
         */
        private HistoryRow(UUID gameId, String opponentDisplayName, boolean opponentGuest,
                           ParticipantResult result, GameEndReason endReason, long turnsTaken,
                           Instant startedAt, Instant completedAt, long shotsFired, long hits,
                           long shipsSunk, int ratingDelta) {
            this.gameId = gameId;
            this.opponentDisplayName = opponentDisplayName;
            this.opponentGuest = opponentGuest;
            this.result = result;
            this.endReason = endReason;
            this.turnsTaken = turnsTaken;
            this.startedAt = startedAt;
            this.completedAt = completedAt;
            this.shotsFired = shotsFired;
            this.hits = hits;
            this.shipsSunk = shipsSunk;
            this.ratingDelta = ratingDelta;
        }

        /**
         * Returns the stable identity used as the deterministic history tie key.
         *
         * @return game identifier
         */
        public UUID getGameId() { return gameId; }

        /**
         * Returns the immutable opponent name captured when the game completed.
         *
         * @return opponent snapshot name
         */
        public String getOpponentDisplayName() { return opponentDisplayName; }

        /**
         * Reports whether the opponent snapshot represents a temporary guest.
         *
         * @return true when the opponent was a guest
         */
        public boolean isOpponentGuest() { return opponentGuest; }

        /**
         * Returns the persistent participant outcome for transport mapping.
         *
         * @return persistent WIN or LOSS result
         */
        public ParticipantResult getResult() { return result; }

        /**
         * Returns the authoritative terminal reason captured for the completed game.
         *
         * @return terminal game reason
         */
        public GameEndReason getEndReason() { return endReason; }

        /**
         * Returns accepted shots plus expired turns consumed by this participant.
         *
         * @return player turn count
         */
        public long getTurnsTaken() { return turnsTaken; }

        /**
         * Returns the authoritative start used to derive match duration.
         *
         * @return authoritative start time
         */
        public Instant getStartedAt() { return startedAt; }

        /**
         * Returns the authoritative completion used for ordering and duration.
         *
         * @return authoritative completion time
         */
        public Instant getCompletedAt() { return completedAt; }

        /**
         * Returns accepted shots used to derive this history row's accuracy.
         *
         * @return accepted shot count
         */
        public long getShotsFired() { return shotsFired; }

        /**
         * Returns accepted hits used to derive this history row's accuracy.
         *
         * @return accepted hit count
         */
        public long getHits() { return hits; }

        /**
         * Returns opponent ships sunk by this participant in the completed game.
         *
         * @return sunk ship count
         */
        public long getShipsSunk() { return shipsSunk; }

        /**
         * Returns the persistent rating change associated with this participant.
         *
         * @return signed rating delta
         */
        public int getRatingDelta() { return ratingDelta; }
    }

    /**
     * Carries one already ordered, bounded registered-player leaderboard row.
     */
    public static final class LeaderboardRow {
        private final UUID playerId;
        private final String username;
        private final int rating;
        private final long gamesPlayed;
        private final long wins;

        /**
         * Stores one detached leaderboard result row.
         *
         * @param playerId stable persistent identity and final tie key
         * @param username case-preserving username
         * @param rating committed rating
         * @param gamesPlayed committed game count
         * @param wins committed win count
         */
        private LeaderboardRow(UUID playerId, String username, int rating,
                               long gamesPlayed, long wins) {
            this.playerId = playerId;
            this.username = username;
            this.rating = rating;
            this.gamesPlayed = gamesPlayed;
            this.wins = wins;
        }

        /**
         * Returns the persistent identity used as the final deterministic database tie key.
         *
         * @return persistent player identity
         */
        public UUID getPlayerId() { return playerId; }

        /**
         * Returns the case-preserving username intended for public display.
         *
         * @return case-preserving username
         */
        public String getUsername() { return username; }

        /**
         * Returns the committed rating that determined this row's database order.
         *
         * @return committed rating
         */
        public int getRating() { return rating; }

        /**
         * Returns the bounded aggregate count joined to this selected player.
         *
         * @return committed game count
         */
        public long getGamesPlayed() { return gamesPlayed; }

        /**
         * Returns the bounded WIN aggregate joined to this selected player.
         *
         * @return committed win count
         */
        public long getWins() { return wins; }
    }

    /**
     * Holds bounded leaderboard aggregates for one selected registered player.
     */
    private static final class WinAggregate {
        private static final WinAggregate EMPTY = new WinAggregate(0, 0);

        private final long gamesPlayed;
        private final long wins;

        /**
         * Stores game and win counts returned by the grouped database query.
         *
         * @param gamesPlayed committed game count
         * @param wins committed win count
         */
        private WinAggregate(long gamesPlayed, long wins) {
            this.gamesPlayed = gamesPlayed;
            this.wins = wins;
        }
    }
}
