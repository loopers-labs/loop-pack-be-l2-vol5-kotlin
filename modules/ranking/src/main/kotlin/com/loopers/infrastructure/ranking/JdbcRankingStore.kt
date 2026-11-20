package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.ProductScore
import com.loopers.domain.ranking.RankedProduct
import com.loopers.domain.ranking.RankingInput
import com.loopers.domain.ranking.RankingPeriod
import com.loopers.domain.ranking.RankingRequest
import com.loopers.domain.ranking.RankingSnapshot
import com.loopers.domain.ranking.RankingStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.Calendar
import java.util.TimeZone

@Repository
class JdbcRankingStore(private val jdbc: JdbcTemplate) : RankingStore {
    private fun utc() = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

    private fun input(rs: ResultSet): RankingInput = RankingInput(
        rs.getLong("order_id"),
        rs.getLong("product_id"),
        rs.getLong("quantity"),
        rs.getTimestamp("confirmed_at", utc()).toInstant(),
    )

    override fun lockPeriod(periodStart: LocalDate): String? {
        jdbc.update("INSERT IGNORE INTO ranking_publications(period_start) VALUES (?)", periodStart)

        return jdbc.query("SELECT snapshot_id FROM ranking_publications WHERE period_start=? FOR UPDATE", { rs, _ ->
            rs.getString(1)
        }, periodStart).single()
    }

    override fun findSnapshot(snapshotId: String, forUpdate: Boolean): RankingSnapshot? = jdbc.query(
        "SELECT * FROM ranking_snapshots WHERE snapshot_id=?" + if (forUpdate) " FOR UPDATE" else "",
        { rs, _ ->
            RankingSnapshot(
                RankingRequest(rs.getDate("period_start").toLocalDate(), rs.getString("snapshot_id"), rs.getLong("revision")),
                rs.getTimestamp("frozen_at", utc()).toInstant(),
                rs.getLong("input_count"),
                rs.getLong("product_count"),
                rs.getString("input_checksum"),
                rs.getString("status"),
                rs.getString("result_checksum"),
            )
        },
        snapshotId,
    ).singleOrNull()

    override fun databaseTime(): Instant = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", { rs, _ ->
        rs.getTimestamp(1, utc()).toInstant()
    })!!

    override fun liveInputs(period: RankingPeriod): List<RankingInput> = jdbc.query(
        "SELECT order_id,product_id,quantity,confirmed_at FROM purchase_activities " +
            "WHERE confirmed_at>=? AND confirmed_at<? ORDER BY order_id,product_id",
        org.springframework.jdbc.core.PreparedStatementSetter { ps ->
            ps.setTimestamp(1, Timestamp.from(period.startAt), utc())
            ps.setTimestamp(2, Timestamp.from(period.endAt), utc())
        },
        { rs, _ -> input(rs) },
    )

    override fun frozenInputs(snapshotId: String): List<RankingInput> = jdbc.query(
        "SELECT * FROM ranking_snapshot_inputs WHERE snapshot_id=? ORDER BY order_id,product_id",
        { rs, _ -> input(rs) },
        snapshotId,
    )

    override fun productInputs(snapshotId: String, productId: Long): List<RankingInput> = jdbc.query(
        "SELECT * FROM ranking_snapshot_inputs WHERE snapshot_id=? AND product_id=? ORDER BY order_id",
        { rs, _ -> input(rs) },
        snapshotId,
        productId,
    )

    // The caller owns the freeze transaction; manifest and all frozen rows either commit together or roll back together.
    override fun insertSnapshot(snapshot: RankingSnapshot, inputs: List<RankingInput>) {
        jdbc.update(
            "INSERT INTO ranking_snapshots " +
                "(snapshot_id,period_start,period_end,revision,status,frozen_at,input_count,product_count,input_checksum) " +
                "VALUES (?,?,?,?,'FROZEN',?,?,?,?)",
            { ps ->
                ps.setString(1, snapshot.request.snapshotId)
                ps.setObject(2, snapshot.request.periodStart)
                ps.setObject(3, RankingPeriod(snapshot.request.periodStart).end)
                ps.setLong(4, snapshot.request.revision)
                ps.setTimestamp(5, Timestamp.from(snapshot.frozenAt), utc())
                ps.setLong(6, snapshot.inputCount)
                ps.setLong(7, snapshot.productCount)
                ps.setString(8, snapshot.inputChecksum)
            },
        )

        jdbc.batchUpdate(
            "INSERT INTO ranking_snapshot_inputs(snapshot_id,order_id,product_id,quantity,confirmed_at) VALUES (?,?,?,?,?)",
            inputs,
            500,
        ) { ps, row ->
            ps.setString(1, snapshot.request.snapshotId)
            ps.setLong(2, row.orderId)
            ps.setLong(3, row.productId)
            ps.setLong(4, row.quantity)
            ps.setTimestamp(5, Timestamp.from(row.confirmedAt), utc())
        }
    }

    override fun writeScores(snapshotId: String, scores: List<ProductScore>) {
        jdbc.batchUpdate(
            "INSERT INTO ranking_score_stages(snapshot_id,product_id,score) VALUES (?,?,?) " +
                "ON DUPLICATE KEY UPDATE score=VALUES(score)",
                    scores,
            500,
        ) { ps, row ->
            ps.setString(1, snapshotId)
            ps.setLong(2, row.productId)
            ps.setLong(3, row.score)
        }
    }

    override fun scores(snapshotId: String): List<ProductScore> = jdbc.query(
        "SELECT product_id,score FROM ranking_score_stages WHERE snapshot_id=? ORDER BY product_id",
        { rs, _ -> ProductScore(rs.getLong(1), rs.getLong(2)) },
        snapshotId,
    )

    override fun results(snapshotId: String): List<RankedProduct> = jdbc.query(
        "SELECT product_id,score,calculated_rank FROM ranking_results WHERE snapshot_id=? ORDER BY calculated_rank",
        { rs, _ -> RankedProduct(rs.getLong(1), rs.getLong(2), rs.getLong(3)) },
        snapshotId,
    )

    // Called only after stage validation and manifest locking in the publication transaction.
    override fun saveResults(snapshotId: String, results: List<RankedProduct>, checksum: String) {
        jdbc.batchUpdate(
            "INSERT INTO ranking_results(snapshot_id,product_id,score,calculated_rank) VALUES (?,?,?,?)",
            results,
            500,
        ) { ps, row ->
            ps.setString(1, snapshotId)
            ps.setLong(2, row.productId)
            ps.setLong(3, row.score)
            ps.setLong(4, row.rank)
        }

        check(
            jdbc.update(
            "UPDATE ranking_snapshots SET status='COMPLETE',result_checksum=? WHERE snapshot_id=? AND status='FROZEN'",
            checksum, snapshotId,
        ) == 1,
        )
    }

    override fun pointTo(periodStart: LocalDate, snapshotId: String) {
        check(jdbc.update("UPDATE ranking_publications SET snapshot_id=? WHERE period_start=?", snapshotId, periodStart) == 1)
    }
}
