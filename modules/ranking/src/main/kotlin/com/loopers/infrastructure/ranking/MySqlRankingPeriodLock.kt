package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.RankingPeriodLock
import org.springframework.stereotype.Component
import java.time.LocalDate
import javax.sql.DataSource

@Component
class MySqlRankingPeriodLock(private val dataSource: DataSource) : RankingPeriodLock {
    // A dedicated connection owns the advisory lock across all Job steps and their separate transactions.
    override fun acquire(periodStart: LocalDate): AutoCloseable {
        val connection = dataSource.connection
        val name = "weeklyRanking:$periodStart"
        try {
            connection.prepareStatement("SELECT GET_LOCK(?,0)").use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { rs ->
                    check(rs.next() && rs.getInt(1) == 1) { "weekly ranking already running for $periodStart" }
                }
            }
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }

        return AutoCloseable {
            try {
                connection.prepareStatement("SELECT RELEASE_LOCK(?)").use { statement ->
                    statement.setString(1, name)
                    statement.executeQuery().close()
                }
            } finally {
                connection.close()
            }
        }
    }
}
