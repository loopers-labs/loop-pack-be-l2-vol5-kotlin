package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.RankingPeriod
import com.loopers.domain.ranking.WeeklyRankingItem
import com.loopers.domain.ranking.WeeklyRankingQuery
import com.loopers.domain.ranking.WeeklyRankingView
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.stereotype.Repository
import java.util.Calendar
import java.util.TimeZone

@Repository
class JdbcWeeklyRankingQuery(private val jdbc: JdbcTemplate) : WeeklyRankingQuery {
    // One statement reads pointer, manifest and visible items in the same DB read view, including empty published weeks.
    override fun find(period: RankingPeriod, limit: Int): WeeklyRankingView = jdbc.query(
        """
        SELECT s.snapshot_id,s.frozen_at,v.product_id,v.name,v.score,v.calculated_rank
        FROM (SELECT CAST(? AS DATE) AS period_start) requested
        LEFT JOIN ranking_publications p ON p.period_start=requested.period_start
        LEFT JOIN ranking_snapshots s ON s.snapshot_id=p.snapshot_id AND s.period_start=p.period_start AND s.status='COMPLETE'
        LEFT JOIN (
            SELECT r.snapshot_id,r.product_id,g.name,r.score,r.calculated_rank
            FROM ranking_results r
            JOIN products g ON g.id=r.product_id AND g.deleted_at IS NULL
            JOIN brands b ON b.id=g.brand_id AND b.deleted_at IS NULL
        ) v ON v.snapshot_id=s.snapshot_id
        ORDER BY v.calculated_rank
        LIMIT ?
        """.trimIndent(),
        ResultSetExtractor { rs ->
            check(rs.next())
            val snapshotId = rs.getString("snapshot_id")
            val frozenAt = rs.getTimestamp("frozen_at", Calendar.getInstance(TimeZone.getTimeZone("UTC")))?.toInstant()
            val items = mutableListOf<WeeklyRankingItem>()
            do {
                val productId = rs.getLong("product_id")
                if (!rs.wasNull()) {
                    items.add(
                        WeeklyRankingItem(productId, rs.getString("name"), rs.getLong("score"), rs.getLong("calculated_rank")),
                    )
                }
            } while (rs.next())

            WeeklyRankingView(
                period.start,
                period.end,
                if (snapshotId == null) "NOT_PUBLISHED" else "PUBLISHED",
                snapshotId,
                frozenAt,
                items,
            )
        },
        period.start,
        limit,
    )!!
}
