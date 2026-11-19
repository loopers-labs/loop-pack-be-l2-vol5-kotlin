package com.loopers.batch.job.ranking.step

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

@StepScope
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklySalesSnapshotJobConfig.JOB_NAME)
@Component
class WeeklyRankingPublishTasklet(
    @param:Value("#{jobParameters['snapshotId']}") private val snapshotId: String,
    private val jdbcTemplate: JdbcTemplate,
) : Tasklet {
    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val rankingSnapshotId = snapshotId.substringBefore("_r")
        val snapshotCount = jdbcTemplate.queryForObject(
            COUNT_SNAPSHOT_PRODUCTS,
            Long::class.java,
            snapshotId,
            "${rankingSnapshotId}_r2",
        ) ?: 0L
        val rankingCount = count(COUNT_RANKING, rankingSnapshotId)
        check(snapshotCount == rankingCount) {
            "스냅샷과 랭킹의 상품 수가 다릅니다. [스냅샷 = $snapshotId, 스냅샷 상품 수 = $snapshotCount, 랭킹 상품 수 = $rankingCount]"
        }
        jdbcTemplate.update(PUBLISH, rankingCount, rankingSnapshotId)
        return RepeatStatus.FINISHED
    }

    private fun count(sql: String, snapshotId: String): Long =
        jdbcTemplate.queryForObject(sql, Long::class.java, snapshotId) ?: 0L

    companion object {
        private const val COUNT_SNAPSHOT_PRODUCTS =
            "SELECT COUNT(DISTINCT product_id) FROM purchase_activity_snapshot WHERE snapshot_id IN (?, ?)"
        private const val COUNT_RANKING = "SELECT COUNT(*) FROM product_ranking WHERE snapshot_id = ?"

        private val PUBLISH = """
            UPDATE product_ranking_pointer
            SET status = 'PUBLISHED', product_count = ?, updated_at = UTC_TIMESTAMP(6)
            WHERE snapshot_id = ? AND status <> 'PUBLISHED'
        """.trimIndent()
    }
}
