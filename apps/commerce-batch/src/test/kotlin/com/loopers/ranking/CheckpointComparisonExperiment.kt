package com.loopers.ranking

import com.loopers.application.ranking.WeeklyRankingService
import com.loopers.domain.ranking.ProductScore
import com.loopers.domain.ranking.RankingCalculation
import com.loopers.domain.ranking.RankingChecksum
import com.loopers.domain.ranking.RankingRequest
import com.loopers.domain.ranking.RankingStore
import org.assertj.core.api.Assertions.assertThat
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

// This test-only Job never publishes. New instances for C are a comparison, not the weeklyRanking restart strategy.
class CheckpointComparisonExperiment(
    private val dataSource: DataSource,
    private val launcher: JobLauncher,
    private val explorer: JobExplorer,
    private val repository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val service: WeeklyRankingService,
    private val store: RankingStore,
) {
    private val calls = mutableListOf<Long>()
    private val prefix = "comparison-${UUID.randomUUID()}"

    private fun job(snapshot: String, failNine: Boolean, failAfterSql: Boolean = false): Job {
        val reader = JdbcCursorItemReaderBuilder<Long>().name("comparisonProducts").saveState(true).dataSource(dataSource)
            .sql("SELECT DISTINCT product_id FROM ranking_snapshot_inputs WHERE snapshot_id=? ORDER BY product_id")
            .preparedStatementSetter { it.setString(1, snapshot) }.rowMapper { rs, _ -> rs.getLong(1) }.build()
        val step = StepBuilder("compareProducts", repository).chunk<Long, ProductScore>(2, transactionManager).reader(reader)
            .processor(
                ItemProcessor<Long, ProductScore> {
                calls.add(it)
                if (failNine && it == 9L) error("comparison stops before 9")
                service.score(snapshot, it)
            },
            )
            .writer(
                ItemWriter<ProductScore> {
                service.write(snapshot, it.items)
                if (failAfterSql) error("comparison stops after SQL before commit")
            },
            ).build()

        return JobBuilder(prefix, repository).start(step).build()
    }

    private fun launch(snapshot: String, trial: String, failNine: Boolean = false, failAfterSql: Boolean = false): JobExecution {
        calls.clear()
        val result = launcher.run(
            job(snapshot, failNine, failAfterSql),
            JobParametersBuilder().addString("snapshotId", snapshot).addString("trial", trial).toJobParameters(),
        )
        val step = result.stepExecutions.single()
        val persisted = explorer.getStepExecution(result.id!!, step.id!!)!!
        println(
            "WEEK9_COMPARISON trial=$trial snapshot=$snapshot instance=${result.jobInstance.instanceId} execution=${result.id} " +
                "step=${step.id} status=${result.status} state=${persisted.executionContext} " +
                "calls=${calls.toList()} stage=${store.scores(snapshot)}",
        )

        return result
    }

    // A/B/C keep equal frozen contents; B/C retain their committed stage and repository between failure and next launch.
    fun run() {
        val jdbc = JdbcTemplate(dataSource)
        val period = LocalDate.parse("2026-08-17")
        val publishedBefore = jdbc.queryForList("SELECT * FROM ranking_publications")
        val requests = (1L..4L).map { RankingRequest(period, "$prefix-$it", it) }
        requests.forEach { service.freeze(it) }
        val a = requests[0].snapshotId
        val b = requests[1].snapshotId
        val c = requests[2].snapshotId
        val e = requests[3].snapshotId

        assertThat(launch(a, "A").status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(calls).containsExactly(7, 8, 9)
        val failedB = launch(b, "B", failNine = true)
        assertThat(failedB.status).isEqualTo(BatchStatus.FAILED)
        assertThat(store.scores(b)).containsExactly(ProductScore(7, 5), ProductScore(8, 1))
        val resumedB = launch(b, "B")
        assertThat(resumedB.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(resumedB.jobInstance.instanceId).isEqualTo(failedB.jobInstance.instanceId)
        assertThat(resumedB.id).isNotEqualTo(failedB.id)
        assertThat(calls).containsExactly(9)

        val failedC = launch(c, "C-failure", failNine = true)
        assertThat(failedC.status).isEqualTo(BatchStatus.FAILED)
        val repeatedC = launch(c, "C-full")
        assertThat(repeatedC.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(repeatedC.jobInstance.instanceId).isNotEqualTo(failedC.jobInstance.instanceId)
        assertThat(calls).containsExactly(7, 8, 9)
        listOf(a, b, c).forEach { id ->
            assertThat(store.scores(id)).containsExactly(ProductScore(7, 5), ProductScore(8, 1), ProductScore(9, 5))
            assertThat(RankingChecksum.inputs(store.frozenInputs(id))).isEqualTo(store.findSnapshot(a)!!.inputChecksum)
            assertThat(RankingChecksum.results(RankingCalculation.rank(store.scores(id))))
                .isEqualTo("745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2")
        }

        repeat(3) { service.write(c, listOf(ProductScore(7, 5))) }
        assertThat(store.scores(c)).containsExactly(ProductScore(7, 5), ProductScore(8, 1), ProductScore(9, 5))
        println("WEEK9_COMPARISON D repeatedWrites=3 product=7 rows=1 score=5 input=${store.findSnapshot(c)!!.inputChecksum}")
        val failedE = launch(e, "E", failAfterSql = true)
        assertThat(failedE.status).isEqualTo(BatchStatus.FAILED)
        assertThat(store.scores(e)).isEmpty()
        val step = failedE.stepExecutions.single()
        assertThat(
            explorer.getStepExecution(failedE.id!!, step.id!!)!!.executionContext.getInt("comparisonProducts.read.count", 0),
        ).isZero()
        assertThat(launch(e, "E").status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(calls).containsExactly(7, 8, 9)
        val publishedAfter = jdbc.queryForList("SELECT * FROM ranking_publications WHERE snapshot_id IS NOT NULL")
        assertThat(publishedAfter).isEqualTo(publishedBefore.filter { it["snapshot_id"] != null })
    }
}
