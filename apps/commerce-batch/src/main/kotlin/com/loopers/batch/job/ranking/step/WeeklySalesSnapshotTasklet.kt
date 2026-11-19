package com.loopers.batch.job.ranking.step

import com.loopers.batch.job.ranking.SnapshotWeek
import com.loopers.batch.job.ranking.WeeklySalesSnapshotJobConfig
import org.springframework.batch.core.StepContribution
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@StepScope
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklySalesSnapshotJobConfig.JOB_NAME)
@Component
class WeeklySalesSnapshotTasklet(
    @param:Value("#{jobParameters['requestDate']}") private val requestDate: LocalDate,
    private val jdbcTemplate: JdbcTemplate,
) : Tasklet {
    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val week = SnapshotWeek.endedBefore(requestDate)
        val written = writeFirst(week) + writeLate(week)
        contribution.incrementWriteCount(written.toLong())
        return RepeatStatus.FINISHED
    }

    private fun writeFirst(week: SnapshotWeek): Int {
        if (metaExists(week.snapshotId)) return 0

        val nowUtc = LocalDateTime.now(ZoneOffset.UTC)
        val written = jdbcTemplate.update(
            INSERT_WEEKLY_PURCHASES,
            week.snapshotId,
            week.startUtc,
            week.endUtc,
            nowUtc,
            nowUtc,
            week.startUtc,
            week.endUtc,
            week.endUtc,
        )
        writeMeta(week, week.snapshotId, FIRST_REVISION, nowUtc)
        return written
    }

    private fun writeLate(week: SnapshotWeek): Int {
        if (metaExists(week.lateSnapshotId)) return 0

        val nowUtc = LocalDateTime.now(ZoneOffset.UTC)
        val written = jdbcTemplate.update(
            INSERT_LATE_WEEKLY_PURCHASES,
            week.lateSnapshotId,
            week.startUtc,
            week.endUtc,
            nowUtc,
            nowUtc,
            week.startUtc,
            week.endUtc,
            week.endUtc,
        )
        if (written == 0) return 0
        writeMeta(week, week.lateSnapshotId, LATE_REVISION, nowUtc)
        return written
    }

    private fun writeMeta(week: SnapshotWeek, snapshotId: String, revision: Int, frozenAt: LocalDateTime) {
        val digest = MessageDigest.getInstance("SHA-256")
        var rowCount = 0L
        jdbcTemplate.query(SELECT_FOR_CHECKSUM, { rs ->
            val line = listOf(
                rs.getLong("order_id"),
                rs.getLong("product_id"),
                rs.getInt("quantity"),
                rs.getObject("confirmed_at", LocalDateTime::class.java).format(CHECKSUM_TIME),
            ).joinToString("\t") + "\n"
            digest.update(line.toByteArray(Charsets.UTF_8))
            rowCount++
        }, snapshotId)
        jdbcTemplate.update(
            INSERT_META,
            snapshotId,
            week.startUtc,
            week.endUtc,
            revision,
            frozenAt,
            rowCount,
            digest.digest().joinToString("") { "%02x".format(it) },
            frozenAt,
            frozenAt,
        )
    }

    private fun metaExists(snapshotId: String): Boolean =
        jdbcTemplate.queryForList(
            "SELECT 1 FROM product_sales_snapshot_meta WHERE snapshot_id = ? LIMIT 1",
            snapshotId,
        ).isNotEmpty()

    companion object {
        private const val FIRST_REVISION = 1
        private const val LATE_REVISION = 2
        private val CHECKSUM_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")

        private val INSERT_WEEKLY_PURCHASES = """
            INSERT INTO purchase_activity_snapshot
                (snapshot_id, period_start, period_end, order_id, product_id, quantity, confirmed_at, created_at, updated_at)
            SELECT ?, ?, ?, order_id, product_id, quantity, confirmed_at, ?, ?
            FROM product_purchase_activity
            WHERE confirmed_at >= ? AND confirmed_at < ? AND deleted_at IS NULL
              AND created_at < ?
        """.trimIndent()

        private val INSERT_LATE_WEEKLY_PURCHASES = """
            INSERT INTO purchase_activity_snapshot
                (snapshot_id, period_start, period_end, order_id, product_id, quantity, confirmed_at, created_at, updated_at)
            SELECT ?, ?, ?, order_id, product_id, quantity, confirmed_at, ?, ?
            FROM product_purchase_activity
            WHERE confirmed_at >= ? AND confirmed_at < ? AND deleted_at IS NULL
              AND created_at >= ?
        """.trimIndent()

        private val SELECT_FOR_CHECKSUM = """
            SELECT order_id, product_id, quantity, confirmed_at
            FROM purchase_activity_snapshot
            WHERE snapshot_id = ?
            ORDER BY order_id, product_id
        """.trimIndent()

        private val INSERT_META = """
            INSERT INTO product_sales_snapshot_meta
                (snapshot_id, period_start, period_end, revision, status, frozen_at, row_count, checksum, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'FROZEN', ?, ?, ?, ?, ?)
        """.trimIndent()
    }
}
