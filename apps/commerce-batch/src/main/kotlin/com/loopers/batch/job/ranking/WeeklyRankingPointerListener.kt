package com.loopers.batch.job.ranking

import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklySalesSnapshotJobConfig.JOB_NAME)
@Component
class WeeklyRankingPointerListener(
    private val jdbcTemplate: JdbcTemplate,
) : JobExecutionListener {
    override fun beforeJob(jobExecution: JobExecution) {
        val snapshotId = rankingSnapshotIdOf(jobExecution) ?: return
        jdbcTemplate.update(MARK_IN_PROGRESS, snapshotId)
    }

    override fun afterJob(jobExecution: JobExecution) {
        if (jobExecution.status == BatchStatus.COMPLETED) return
        val snapshotId = rankingSnapshotIdOf(jobExecution) ?: return
        jdbcTemplate.update(MARK_FAILED, snapshotId)
    }

    private fun rankingSnapshotIdOf(jobExecution: JobExecution): String? =
        jobExecution.jobParameters.getString("snapshotId")?.substringBefore("_r")

    companion object {
        private val MARK_IN_PROGRESS = """
            INSERT INTO product_ranking_pointer
                (snapshot_id, status, product_count, created_at, updated_at)
            VALUES
                (?, 'IN_PROGRESS', NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE
                updated_at = IF(status = 'PUBLISHED', updated_at, UTC_TIMESTAMP(6)),
                status = IF(status = 'PUBLISHED', status, 'IN_PROGRESS')
        """.trimIndent()

        private val MARK_FAILED = """
            UPDATE product_ranking_pointer
            SET status = 'FAILED', updated_at = UTC_TIMESTAMP(6)
            WHERE snapshot_id = ? AND status <> 'PUBLISHED'
        """.trimIndent()
    }
}
