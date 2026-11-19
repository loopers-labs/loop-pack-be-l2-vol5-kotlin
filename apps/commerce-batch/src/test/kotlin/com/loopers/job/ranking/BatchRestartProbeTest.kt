package com.loopers.job.ranking

import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.batch.test.JobRepositoryTestUtils
import org.springframework.batch.test.context.SpringBatchTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDateTime
import javax.sql.DataSource

@SpringBootTest(properties = ["spring.batch.job.enabled=false"])
@SpringBatchTest
class BatchRestartProbeTest @Autowired constructor(
    private val jobRepository: JobRepository,
    private val jobLauncher: JobLauncher,
    private val jobExplorer: JobExplorer,
    private val transactionManager: PlatformTransactionManager,
    @param:Qualifier("mySqlMainDataSource") private val dataSource: DataSource,
    private val jobRepositoryTestUtils: JobRepositoryTestUtils,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    private val jdbcTemplate = JdbcTemplate(dataSource)

    @BeforeEach
    fun setUp() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS batch_restart_probe (
                snapshot_id VARCHAR(64) NOT NULL,
                product_id BIGINT NOT NULL,
                PRIMARY KEY (snapshot_id, product_id)
            ) ENGINE=InnoDB
            """.trimIndent(),
        )
        listOf(7L to 3L, 8L to 2L, 9L to 5L).forEach { (productId, salesQuantity) ->
            snapshot(SNAPSHOT_ID, productId, salesQuantity)
        }
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS batch_restart_probe")
        databaseCleanUp.truncateAllTables()
        jobRepositoryTestUtils.removeJobExecutions()
    }

    @DisplayName("두 번째 청크에서 실패하면, 같은 파라미터로 다시 실행할 때 커밋된 청크 다음부터 이어 간다.")
    @Test
    fun resumesFromLastCommittedChunk_whenRelaunchedAfterFailure() {
        val params = JobParametersBuilder().addString("snapshotId", SNAPSHOT_ID, true).toJobParameters()

        val first = jobLauncher.run(probe(params, failOnNine = true), params)

        assertThat(first.status).isEqualTo(BatchStatus.FAILED)
        assertThat(probedProductIds()).containsExactly(7L, 8L)
        val failedStep = first.stepExecutions.single()
        val savedContext = jobExplorer.getStepExecution(first.id, failedStep.id)!!.executionContext
        assertThat(savedContext.getInt("purchaseProductProbe.read.count")).isEqualTo(2)

        val second = jobLauncher.run(probe(params, failOnNine = false), params)

        assertThat(second.jobInstance.id).isEqualTo(first.jobInstance.id)
        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(second.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(probedProductIds()).containsExactly(7L, 8L, 9L)
    }

    private fun probe(params: JobParameters, failOnNine: Boolean): Job {
        val snapshot = requireNotNull(params.getString("snapshotId"))
        val reader = JdbcCursorItemReaderBuilder<Long>()
            .name("purchaseProductProbe").saveState(true)
            .dataSource(dataSource)
            .sql("SELECT product_id FROM purchase_activity_snapshot WHERE snapshot_id = ? ORDER BY product_id")
            .preparedStatementSetter { ps -> ps.setString(1, snapshot) }
            .rowMapper { rs, _ -> rs.getLong("product_id") }
            .build()
        val step = StepBuilder("probeProducts", jobRepository)
            .chunk<Long, Long>(2, transactionManager)
            .reader(reader)
            .processor(
                ItemProcessor<Long, Long> { id ->
                    check(!(failOnNine && id == 9L)) { "before third product" }
                    id
                },
            )
            .writer { items ->
                items.forEach { id ->
                    jdbcTemplate.update(
                        "INSERT INTO batch_restart_probe (snapshot_id, product_id) VALUES (?, ?)",
                        snapshot,
                        id,
                    )
                }
            }
            .build()
        return JobBuilder("purchaseInputProbe", jobRepository).start(step).build()
    }

    private fun probedProductIds(): List<Long> =
        jdbcTemplate.queryForList(
            "SELECT product_id FROM batch_restart_probe WHERE snapshot_id = ? ORDER BY product_id",
            Long::class.java,
            SNAPSHOT_ID,
        )

    private fun snapshot(snapshotId: String, productId: Long, salesQuantity: Long) {
        val now = LocalDateTime.of(2026, 11, 16, 0, 0)
        jdbcTemplate.update(
            """
            INSERT INTO purchase_activity_snapshot
                (snapshot_id, period_start, period_end, order_id, product_id, quantity, confirmed_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            snapshotId,
            now.minusWeeks(1),
            now,
            productId,
            productId,
            salesQuantity,
            now.minusDays(3),
            now,
            now,
        )
    }

    companion object {
        private const val SNAPSHOT_ID = "probe-1"
    }
}
