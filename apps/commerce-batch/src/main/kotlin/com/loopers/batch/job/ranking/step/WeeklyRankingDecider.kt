package com.loopers.batch.job.ranking.step

import com.loopers.batch.job.ranking.WeeklySalesSnapshotJobConfig
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.job.flow.FlowExecutionStatus
import org.springframework.batch.core.job.flow.JobExecutionDecider
import org.springframework.batch.core.repository.JobRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklySalesSnapshotJobConfig.JOB_NAME)
@Component
class WeeklyRankingDecider(
    private val jdbcTemplate: JdbcTemplate,
    private val jobRepository: JobRepository,
) : JobExecutionDecider {
    override fun decide(jobExecution: JobExecution, stepExecution: StepExecution?): FlowExecutionStatus {
        if (isResumingRankingStep(jobExecution)) return FlowExecutionStatus(CONTINUE)

        val snapshotId = requireNotNull(jobExecution.jobParameters.getString("snapshotId")) {
            "랭킹을 매길 스냅샷 식별자가 없습니다."
        }
        return if (rankingExists(snapshotId)) FlowExecutionStatus(RANKING_EXISTS) else FlowExecutionStatus(CONTINUE)
    }

    private fun isResumingRankingStep(jobExecution: JobExecution): Boolean {
        val last = jobRepository.getLastStepExecution(jobExecution.jobInstance, WeeklySalesSnapshotJobConfig.RANKING_STEP_NAME)
        return last != null && last.status != BatchStatus.COMPLETED
    }

    private fun rankingExists(snapshotId: String): Boolean =
        jdbcTemplate.queryForList(
            "SELECT 1 FROM product_ranking WHERE snapshot_id = SUBSTRING_INDEX(?, '_r', 1) LIMIT 1",
            snapshotId,
        ).isNotEmpty()

    companion object {
        const val RANKING_EXISTS = "RANKING_EXISTS"
        private const val CONTINUE = "CONTINUE"
    }
}
