package com.loopers.ranking

import com.loopers.application.ranking.WeeklyRankingService
import com.loopers.domain.ranking.ProductScore
import com.loopers.domain.ranking.RankedProduct
import com.loopers.domain.ranking.RankingChecksum
import com.loopers.domain.ranking.RankingPeriodLock
import com.loopers.domain.ranking.RankingRequest
import com.loopers.domain.ranking.RankingStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.reset
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest(
    classes = [RankingTestApplication::class],
    properties = [
        "spring.batch.job.enabled=false", "spring.batch.job.name=weeklyRanking",
        "spring.batch.jdbc.initialize-schema=always", "spring.jpa.hibernate.ddl-auto=none", "spring.jpa.show-sql=false",
    ],
)
@Testcontainers
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation::class)
class RankingJobRestartTest @Autowired constructor(
    private val dataSource: DataSource,
    private val jdbc: JdbcTemplate,
    private val launcher: JobLauncher,
    private val explorer: JobExplorer,
    private val repository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val store: RankingStore,
    private val periodLock: RankingPeriodLock,
    @param:Qualifier("weeklyRanking") private val job: Job,
) {
    companion object {
        @Container
        @JvmStatic
        val mysql = MySQLContainer("mysql:8.0")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("datasource.mysql-jpa.main.jdbc-url", mysql::getJdbcUrl)
            registry.add("datasource.mysql-jpa.main.username", mysql::getUsername)
            registry.add("datasource.mysql-jpa.main.password", mysql::getPassword)
            registry.add("datasource.mysql-jpa.main.minimum-idle") { 1 }
            registry.add("datasource.mysql-jpa.main.maximum-pool-size") { 8 }
        }
    }

    @MockitoSpyBean
    private lateinit var service: WeeklyRankingService
    private val period = LocalDate.parse("2026-08-17")
    private val processed = mutableListOf<Long>()
    private val tx get() = TransactionTemplate(transactionManager)

    @BeforeEach
    fun prepare() {
        dataSource.connection.use { ScriptUtils.executeSqlScript(it, ClassPathResource("ranking-schema.sql")) }
        jdbc.execute(
            "CREATE TABLE IF NOT EXISTS purchase_activities(" +
                "order_id BIGINT NOT NULL,product_id BIGINT NOT NULL,quantity BIGINT NOT NULL,confirmed_at DATETIME(6) NOT NULL," +
                "PRIMARY KEY(order_id,product_id), CHECK(quantity>0)) ENGINE=InnoDB",
        )
        jdbc.update("DELETE FROM ranking_publications")
        listOf("ranking_results", "ranking_score_stages", "ranking_snapshot_inputs", "ranking_snapshots", "purchase_activities").forEach {
            jdbc.update("DELETE FROM $it")
        }
        jdbc.update(
            "INSERT INTO purchase_activities VALUES " +
                "(42,7,2,'2026-08-17 01:00:00'),(42,8,1,'2026-08-17 01:00:00')," +
                "(43,7,3,'2026-08-18 01:00:00'),(44,9,5,'2026-08-19 01:00:00')",
        )
        processed.clear()
        reset(service)
    }

    private fun request(revision: Long = 1) = RankingRequest(period, "w9-${UUID.randomUUID()}", revision)
    private fun params(request: RankingRequest): JobParameters = JobParametersBuilder()
        .addString("periodStart", request.periodStart.toString()).addString("snapshotId", request.snapshotId)
        .addLong("revision", request.revision).toJobParameters()

    private fun calls(failAtNine: Boolean) {
        doAnswer { invocation ->
            val product = invocation.getArgument<Long>(1)
            processed.add(product)
            if (failAtNine && product == 9L) error("test stop before product 9")
            invocation.callRealMethod()
        }.`when`(service).score(any(), any())
    }

    private fun persisted(execution: JobExecution, name: String): StepExecution {
        val step = execution.stepExecutions.single { it.stepName == name }

        return checkNotNull(explorer.getStepExecution(execution.id!!, step.id!!))
    }

    private fun evidence(label: String, execution: JobExecution, stepName: String, readerName: String) {
        val step = persisted(execution, stepName)
        println(
            "WEEK9 $label instance=${execution.jobInstance.instanceId} execution=${execution.id} step=${step.id} " +
                "status=${execution.status} state=${step.executionContext} reader=$readerName calls=$processed",
        )
    }

    private fun assertExpected(id: String) {
        assertThat(store.results(id)).containsExactly(RankedProduct(7, 5, 1), RankedProduct(9, 5, 2), RankedProduct(8, 1, 3))
        assertThat(store.findSnapshot(id)!!.resultChecksum).isEqualTo("745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2")
        assertThat(store.findSnapshot(id)!!.inputCount).isEqualTo(4)
        assertThat(store.findSnapshot(id)!!.inputChecksum).isEqualTo("3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298")
    }

    // The two launches keep the same DB, frozen input and persistent Batch repository. INSERT exposes accidental replay.
    @org.junit.jupiter.api.Order(1)
    @Test
    fun `actual DB connection probe restores a new reader at committed position two`() {
        val request = request().copy(snapshotId = "probe-1")
        service.freeze(request)
        jdbc.execute("CREATE TABLE IF NOT EXISTS batch_restart_probe(snapshot_id VARCHAR(64),product_id BIGINT,PRIMARY KEY(snapshot_id,product_id))")
        jdbc.update("DELETE FROM batch_restart_probe")
        val parameters = JobParametersBuilder().addString("snapshotId", "probe-1").toJobParameters()
        fun probe(fail: Boolean): Job {
            val reader = JdbcCursorItemReaderBuilder<Long>().name("purchaseProductProbe").saveState(true).dataSource(dataSource)
                .sql("SELECT DISTINCT product_id FROM ranking_snapshot_inputs WHERE snapshot_id=? ORDER BY product_id")
                .preparedStatementSetter { it.setString(1, request.snapshotId) }.rowMapper { rs, _ -> rs.getLong(1) }.build()
            val step = StepBuilder("probeProducts", repository).chunk<Long, Long>(2, transactionManager).reader(reader)
                .processor(
                    ItemProcessor<Long, Long> { id ->
                    processed.add(id)
                    if (fail && id == 9L) error("probe failure before product 9")
                    id
                },
                )
                .writer(
                    ItemWriter<Long> { chunk ->
                    chunk.forEach { jdbc.update("INSERT INTO batch_restart_probe VALUES ('probe-1',?)", it) }
                },
                ).build()

            return JobBuilder("purchaseInputProbe", repository).start(step).build()
        }
        val failed = launcher.run(probe(true), parameters)

        assertThat(failed.status).isEqualTo(BatchStatus.FAILED)
        assertThat(persisted(failed, "probeProducts").executionContext.getInt("purchaseProductProbe.read.count")).isEqualTo(2)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT product_id FROM batch_restart_probe ORDER BY product_id").use { rs ->
                    val ids = mutableListOf<Long>()
                    while (rs.next()) ids.add(rs.getLong(1))
                    assertThat(ids).containsExactly(7, 8)
                }
            }
        }
        evidence("PROBE_FAILED", failed, "probeProducts", "purchaseProductProbe")
        processed.clear()

        val completed = launcher.run(probe(false), parameters)

        assertThat(completed.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(completed.jobInstance.instanceId).isEqualTo(failed.jobInstance.instanceId)
        assertThat(completed.id).isNotEqualTo(failed.id)
        assertThat(processed).containsExactly(9)
        assertThat(jdbc.queryForList("SELECT product_id FROM batch_restart_probe ORDER BY product_id", Long::class.java)).containsExactly(7, 8, 9)
        evidence("PROBE_RESTARTED", completed, "probeProducts", "purchaseProductProbe")
    }

    // Keep the old pointer and committed chunk across a late arrival; restart must only calculate the remaining frozen product.
    @Test
    fun `weekly job keeps old publication on failure and restarts only product nine from frozen input`() {
        val old = request()
        assertThat(launcher.run(job, params(old)).status).isEqualTo(BatchStatus.COMPLETED)
        val request = request(2)
        calls(failAtNine = true)

        val failed = launcher.run(job, params(request))

        assertThat(failed.status).isEqualTo(BatchStatus.FAILED)
        assertThat(store.scores(request.snapshotId)).containsExactly(ProductScore(7, 5), ProductScore(8, 1))
        assertThat(persisted(failed, "scoreRankingProducts").executionContext.getInt("weeklyRankingProducts.read.count")).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM ranking_publications WHERE period_start=?", String::class.java, period)).isEqualTo(old.snapshotId)
        evidence("JOB_FAILED", failed, "scoreRankingProducts", "weeklyRankingProducts")
        val original = store.findSnapshot(request.snapshotId)!!
        jdbc.update("INSERT INTO purchase_activities VALUES(45,7,10,'2026-08-20 01:00:00')")
        processed.clear()
        calls(failAtNine = false)

        val completed = launcher.run(job, params(request))

        assertThat(completed.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(completed.jobInstance.instanceId).isEqualTo(failed.jobInstance.instanceId)
        assertThat(completed.id).isNotEqualTo(failed.id)
        assertThat(processed).containsExactly(9)
        assertThat(completed.stepExecutions.map { it.stepName }).doesNotContain("freezeRankingInput")
        assertThat(store.findSnapshot(request.snapshotId)!!.inputChecksum).isEqualTo(original.inputChecksum)
        assertExpected(request.snapshotId)
        assertThat(store.results(request.snapshotId)).isEqualTo(store.results(old.snapshotId))
        evidence("JOB_RESTARTED", completed, "scoreRankingProducts", "weeklyRankingProducts")
        println("WEEK9 SNAPSHOT id=${request.snapshotId} revision=2 input=${original.inputChecksum} result=${store.findSnapshot(request.snapshotId)!!.resultChecksum}")

        val next = request(3)
        assertThat(launcher.run(job, params(next)).status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(store.results(next.snapshotId).first()).isEqualTo(RankedProduct(7, 15, 1))
        assertThrows<IllegalStateException> { service.publish(request) }
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM ranking_publications WHERE period_start=?", String::class.java, period)).isEqualTo(next.snapshotId)
    }

    @Test
    fun `writer and checkpoint both roll back if SQL executes before chunk failure`() {
        val request = request()
        doAnswer { invocation ->
            invocation.callRealMethod()
            error("test stop after SQL before chunk commit")
        }.`when`(service).write(eq(request.snapshotId), any())

        val failed = launcher.run(job, params(request))

        assertThat(failed.status).isEqualTo(BatchStatus.FAILED)
        assertThat(store.scores(request.snapshotId)).isEmpty()
        assertThat(persisted(failed, "scoreRankingProducts").executionContext.getInt("weeklyRankingProducts.read.count", 0)).isZero()
        reset(service)
        calls(false)
        assertThat(launcher.run(job, params(request)).status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(processed).containsExactly(7, 8, 9)
        assertExpected(request.snapshotId)
    }

    // Exercise identity conflicts and an explicit publication rollback before repeating the same completed publication.
    @Test
    fun `freeze conflict rollback publication rollback repeated publish and period exclusion`() {
        val request = request()
        service.freeze(request)
        val snapshot = store.findSnapshot(request.snapshotId)!!
        jdbc.update("INSERT INTO purchase_activities VALUES(99,7,1,'2026-08-24 00:00:00'),(98,7,1,'2026-08-17 00:00:00')")
        assertThat(service.freeze(request)).isEqualTo(snapshot)
        assertThrows<IllegalStateException> { service.freeze(request.copy(revision = 2)) }
        assertThrows<RuntimeException> { service.freeze(request.copy(snapshotId = "another-${UUID.randomUUID()}")) }
        listOf(ProductScore(7, 5), ProductScore(8, 1), ProductScore(9, 5)).forEach { service.write(request.snapshotId, listOf(it)) }

        assertThrows<IllegalStateException> {
            tx.executeWithoutResult {
                service.publish(request)
                error("rollback publication including results and COMPLETE")
            }
        }
        assertThat(store.results(request.snapshotId)).isEmpty()
        assertThat(store.findSnapshot(request.snapshotId)!!.status).isEqualTo("FROZEN")
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM ranking_publications WHERE period_start=?", String::class.java, period)).isNull()
        service.publish(request)
        service.publish(request)
        assertExpected(request.snapshotId)
        val next = request(2)
        service.freeze(next)
        assertThat(store.findSnapshot(next.snapshotId)!!.inputCount).isEqualTo(5)
        assertThat(store.frozenInputs(next.snapshotId).map { it.orderId }).contains(98).doesNotContain(99)
    }

    // Fail the real repository final metadata update after business commit, then inspect UNKNOWN without altering metadata.
    @Test
    fun `actual final metadata write failure preserves committed publication and blocks unsafe UNKNOWN restart`() {
        val request = request()
        service.freeze(request)
        service.write(request.snapshotId, listOf(ProductScore(7, 5), ProductScore(8, 1), ProductScore(9, 5)))
        var injected = false
        val failingRepository = object : JobRepository by repository {
            override fun update(stepExecution: StepExecution) {
                if (!injected && stepExecution.status == BatchStatus.COMPLETED && stepExecution.endTime != null) {
                    injected = true
                    error("test final StepExecution metadata write failed after business commit")
                }
                repository.update(stepExecution)
            }
        }
        val publish = StepBuilder("publishGap", failingRepository).tasklet({ _, _ ->
            service.publish(request)
            org.springframework.batch.repeat.RepeatStatus.FINISHED
        }, transactionManager).build()
        val gapJob = JobBuilder("publishGap-${UUID.randomUUID()}", failingRepository).start(publish).build()

        val failed = launcher.run(gapJob, params(request))

        assertThat(injected).isTrue()
        assertThat(failed.status).isEqualTo(BatchStatus.UNKNOWN)
        assertThat(failed.stepExecutions.single().status).isEqualTo(BatchStatus.UNKNOWN)
        assertThat(persisted(failed, "publishGap").status).isEqualTo(BatchStatus.STARTED)
        assertThat(store.findSnapshot(request.snapshotId)!!.status).isEqualTo("COMPLETE")
        assertExpected(request.snapshotId)
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM ranking_publications WHERE period_start=?", String::class.java, period))
            .isEqualTo(request.snapshotId)
        assertThrows<org.springframework.batch.core.repository.JobExecutionAlreadyRunningException> { launcher.run(gapJob, params(request)) }
        service.publish(request)
        service.publish(request)
        assertExpected(request.snapshotId)
        println(
            "WEEK9 PUBLICATION_METADATA_FAILURE execution=${failed.id} jobStatus=${failed.status} inMemoryStepStatus=UNKNOWN persistedStepStatus=STARTED " +
            "snapshot=${request.snapshotId} manifest=COMPLETE sameParameterRestart=BLOCKED publicationReplay=verified",
        )
    }

    @Test
    fun `empty weekly Job publishes real zero count snapshot`() {
        jdbc.update("DELETE FROM purchase_activities")
        val request = request()

        assertThat(launcher.run(job, params(request)).status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(store.findSnapshot(request.snapshotId)!!.inputCount).isZero()
        assertThat(store.findSnapshot(request.snapshotId)!!.status).isEqualTo("COMPLETE")
        assertThat(store.results(request.snapshotId)).isEmpty()
        assertThat(store.findSnapshot(request.snapshotId)!!.resultChecksum).isEqualTo(RankingChecksum.results(emptyList()))
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM ranking_publications WHERE period_start=?", String::class.java, period))
            .isEqualTo(request.snapshotId)
    }

    @Test
    fun `freeze failure after SQL rolls back all frozen rows and no partial input is consumed`() {
        val request = request()
        doAnswer { invocation ->
            invocation.callRealMethod()
            error("freeze step stop after insert before commit")
        }.`when`(service).freeze(eq(request))

        val failed = launcher.run(job, params(request))

        assertThat(failed.status).isEqualTo(BatchStatus.FAILED)
        assertThat(store.findSnapshot(request.snapshotId)).isNull()
        assertThat(store.frozenInputs(request.snapshotId)).isEmpty()
        assertThat(store.scores(request.snapshotId)).isEmpty()
        reset(service)
        assertThat(launcher.run(job, params(request)).status).isEqualTo(BatchStatus.COMPLETED)
        assertExpected(request.snapshotId)
    }

    @Test
    fun `DB constraints reject duplicate input nonpositive score duplicate rank and cross week pointer`() {
        val request = request()
        service.freeze(request)
        assertThrows<org.springframework.dao.DataIntegrityViolationException> {
            jdbc.update("INSERT INTO ranking_snapshot_inputs VALUES (?,42,7,2,'2026-08-17 01:00:00')", request.snapshotId)
        }
        val invalidScore = assertThrows<org.springframework.jdbc.UncategorizedSQLException> {
            jdbc.update("INSERT INTO ranking_score_stages VALUES (?,7,0)", request.snapshotId)
        }
        assertThat(invalidScore.sqlException!!.errorCode).isEqualTo(3819)
        assertThat(invalidScore.sqlException!!.message).contains("ck_ranking_stage")
        service.write(request.snapshotId, listOf(ProductScore(7, 5), ProductScore(8, 1), ProductScore(9, 5)))
        service.publish(request)
        assertThrows<org.springframework.dao.DataIntegrityViolationException> {
            jdbc.update("INSERT INTO ranking_results VALUES (?,10,1,1)", request.snapshotId)
        }
        assertThrows<org.springframework.dao.DataIntegrityViolationException> {
            jdbc.update("INSERT INTO ranking_publications VALUES (?,?)", period.plusWeeks(1), request.snapshotId)
        }
        assertExpected(request.snapshotId)
    }

    @Test
    fun `stage validation rejects missing and incorrect scores without publishing`() {
        val request = request()
        service.freeze(request)
        service.write(request.snapshotId, listOf(ProductScore(7, 5), ProductScore(8, 1)))
        assertThrows<IllegalStateException> { service.publish(request) }
        assertThat(store.results(request.snapshotId)).isEmpty()
        service.write(request.snapshotId, listOf(ProductScore(9, 6)))
        assertThrows<IllegalStateException> { service.publish(request) }
        assertThat(store.findSnapshot(request.snapshotId)!!.status).isEqualTo("FROZEN")
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM ranking_publications WHERE period_start=?", String::class.java, period)).isNull()
        service.write(request.snapshotId, listOf(ProductScore(9, 5)))
        service.publish(request)
        assertExpected(request.snapshotId)
    }

    @Test
    fun `blog candidate one compares checkpoint full replay repeated writer and commit rollback`() {
        CheckpointComparisonExperiment(dataSource, launcher, explorer, repository, transactionManager, service, store).run()
    }

    @Test
    fun `period lock excludes another same week connection and releases for next execution`() {
        periodLock.acquire(period).use {
            assertThrows<IllegalStateException> { periodLock.acquire(period) }
            periodLock.acquire(period.plusWeeks(1)).close()
        }
        periodLock.acquire(period).close()
    }
}
