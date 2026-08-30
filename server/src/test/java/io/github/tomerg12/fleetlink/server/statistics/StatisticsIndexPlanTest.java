package io.github.tomerg12.fleetlink.server.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tomerg12.fleetlink.server.persistence.FleetLinkPersistence;
import io.github.tomerg12.fleetlink.server.persistence.PersistenceTestSupport;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks down the one H2-plan-justified statistics index and avoids speculative query indexes.
 */
class StatisticsIndexPlanTest {

    /**
     * Verifies schema column directions and index-sorted access for the final leaderboard query.
     */
    @SuppressWarnings("unchecked")
    @Test
    void leaderboardIndexMatchesFinalOrderingAndAvoidsTableScanSort() {
        try (FleetLinkPersistence persistence = PersistenceTestSupport.openMemory();
             EntityManager entityManager = persistence.getEntityManagerFactory()
                     .createEntityManager()) {
            List<Object[]> columns = entityManager.createNativeQuery("""
                    select column_name, ordering_specification
                    from information_schema.index_columns
                    where table_schema = 'PUBLIC'
                      and table_name = 'PLAYERS'
                      and index_name = 'IDX_PLAYERS_LEADERBOARD'
                    order by ordinal_position
                    """, Object[].class).getResultList();
            String plan = (String) entityManager.createNativeQuery("""
                    explain select player.id, player.username, player.rating
                    from players player
                    order by player.rating desc, player.username_key asc, player.id asc
                    fetch first 100 rows only
                    """).getSingleResult();

            assertEquals(List.of("RATING:DESC", "USERNAME_KEY:ASC", "ID:ASC"),
                    columns.stream().map(row -> row[0] + ":" + row[1]).toList());
            assertTrue(plan.contains("IDX_PLAYERS_LEADERBOARD"), plan);
            assertTrue(plan.contains("index sorted"), plan);
        }
    }
}
