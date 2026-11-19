package com.loopers.job.ranking

import com.loopers.batch.job.ranking.SnapshotWeek
import com.loopers.batch.job.ranking.WeeklySalesSnapshotJobConfig
import com.loopers.batch.job.ranking.step.WeeklySalesReaderConfig
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.test.JobLauncherTestUtils
import org.springframework.batch.test.JobRepositoryTestUtils
import org.springframework.batch.test.context.SpringBatchTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@SpringBootTest
@SpringBatchTest
@TestPropertySource(
    properties = [
        "spring.batch.job.name=${WeeklySalesSnapshotJobConfig.JOB_NAME}",
        "${WeeklySalesSnapshotJobConfig.CHUNK_SIZE_PROPERTY}=2",
    ],
)
class WeeklyRankingRestartComparisonTest @Autowired constructor(
    private val jobLauncherTestUtils: JobLauncherTestUtils,
    private val jobRepositoryTestUtils: JobRepositoryTestUtils,
    private val jobExplorer: JobExplorer,
    @param:Qualifier(WeeklySalesSnapshotJobConfig.JOB_NAME) private val job: Job,
    private val jdbcTemplate: JdbcTemplate,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @BeforeEach
    fun setUp() {
        jobLauncherTestUtils.job = job
        jdbcTemplate.execute(CREATE_PURCHASE_ACTIVITY)
        jdbcTemplate.execute(CREATE_PRODUCT_RANKING)
        jdbcTemplate.execute(CREATE_PRODUCT_RANKING_POINTER)
        purchaseFixture()
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE product_purchase_activity")
        jdbcTemplate.execute("DROP TABLE product_ranking")
        jdbcTemplate.execute("TRUNCATE TABLE product_ranking_pointer")
        databaseCleanUp.truncateAllTables()
        jobRepositoryTestUtils.removeJobExecutions()
    }

    @DisplayName("정상 실행: 한 번에 끝나면 기대 점수·순위·입력 checksum·결과 checksum 이 나온다.")
    @Test
    fun normalRun_matchesExpected() {
        val execution = launch()

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(rankings()).containsExactlyElementsOf(EXPECTED_RANKINGS)
        assertThat(inputChecksum()).isEqualTo(EXPECTED_INPUT_CHECKSUM)
        assertThat(resultChecksum()).isEqualTo(EXPECTED_RESULT_CHECKSUM)
    }

    @DisplayName("재시작: 두 번째 청크에서 실패한 뒤 같은 파라미터로 다시 실행하면, 정상 실행과 같은 점수·순위·입력 checksum·결과 checksum 이 나온다.")
    @Test
    fun restartedRun_matchesNormalRun() {
        failRankingInsertAt(productId = 8L)

        val failed = launch()

        assertThat(failed.status).isEqualTo(BatchStatus.FAILED)
        assertThat(rankings()).containsExactly(Ranking(7L, 5L, 1), Ranking(9L, 5L, 2))
        assertThat(savedReadCount(failed.instanceId)).isEqualTo(2)
        assertThat(pointerStatus()).isNotEqualTo("PUBLISHED")
        stopFailingRankingInsert()

        val resumed = launch()

        assertThat(resumed.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(resumed.instanceId).isEqualTo(failed.instanceId)
        assertThat(resumed.executionId).isNotEqualTo(failed.executionId)
        assertThat(rankings()).containsExactlyElementsOf(EXPECTED_RANKINGS)
        assertThat(inputChecksum()).isEqualTo(EXPECTED_INPUT_CHECKSUM)
        assertThat(resultChecksum()).isEqualTo(EXPECTED_RESULT_CHECKSUM)
        assertThat(pointerStatus()).isEqualTo("PUBLISHED")
    }

    @DisplayName("연결 4구간: 고정한 뒤 같은 주의 늦은 구매가 들어와도, r1 입력 checksum 과 랭킹은 그대로다.")
    @Test
    fun lateArrivalAfterFreeze_keepsFirstInputAndRanking() {
        launch()
        jdbcTemplate.update(
            """
            INSERT INTO product_purchase_activity
                (created_at, updated_at, order_id, product_id, quantity, user_id, confirmed_at)
            VALUES (?, ?, 45, 8, 10, 1, '2026-08-21 10:00:00')
            """.trimIndent(),
            LocalDateTime.now(ZoneOffset.UTC).plusHours(1),
            LocalDateTime.now(ZoneOffset.UTC).plusHours(1),
        )

        jobLauncherTestUtils.launchJob(
            JobParametersBuilder()
                .addLocalDate("requestDate", REQUEST_DATE.plusWeeks(1), false)
                .addString("snapshotId", SnapshotWeek.endedBefore(REQUEST_DATE.plusWeeks(1)).snapshotId)
                .toJobParameters(),
        )

        assertThat(inputChecksum()).isEqualTo(EXPECTED_INPUT_CHECKSUM)
        assertThat(rankings()).containsExactlyElementsOf(EXPECTED_RANKINGS)
        assertThat(resultChecksum()).isEqualTo(EXPECTED_RESULT_CHECKSUM)
    }

    private fun launch(): Launched {
        val execution = jobLauncherTestUtils.launchJob(
            JobParametersBuilder()
                .addLocalDate("requestDate", REQUEST_DATE, false)
                .addString("snapshotId", SnapshotWeek.endedBefore(REQUEST_DATE).snapshotId)
                .toJobParameters(),
        )
        return Launched(execution.status, execution.jobInstance.instanceId, execution.id)
    }

    private data class Launched(
        val status: BatchStatus,
        val instanceId: Long,
        val executionId: Long,
    )

    private fun savedReadCount(instanceId: Long): Int {
        val instance = requireNotNull(jobExplorer.getJobInstance(instanceId))
        val rankingStep = jobExplorer.getJobExecutions(instance)
            .flatMap { it.stepExecutions }
            .single { it.stepName == WeeklySalesSnapshotJobConfig.RANKING_STEP_NAME }
        return rankingStep.executionContext.getInt("${WeeklySalesReaderConfig.READER_NAME}.read.count")
    }

    private fun purchaseFixture() {
        purchase(orderId = 42L, productId = 7L, quantity = 3, confirmedAt = "2026-08-18T10:00:00")
        purchase(orderId = 42L, productId = 8L, quantity = 1, confirmedAt = "2026-08-18T10:00:00")
        purchase(orderId = 43L, productId = 9L, quantity = 5, confirmedAt = "2026-08-19T10:00:00")
        purchase(orderId = 44L, productId = 7L, quantity = 2, confirmedAt = "2026-08-20T10:00:00")
    }

    private fun purchase(orderId: Long, productId: Long, quantity: Int, confirmedAt: String) {
        val at = LocalDateTime.parse(confirmedAt)
        jdbcTemplate.update(
            """
            INSERT INTO product_purchase_activity
                (created_at, updated_at, order_id, product_id, quantity, user_id, confirmed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            at,
            at,
            orderId,
            productId,
            quantity,
            1L,
            at,
        )
    }

    private fun failRankingInsertAt(productId: Long) {
        jdbcTemplate.execute("ALTER TABLE product_ranking ADD CONSTRAINT chk_fail_ranking_insert CHECK (product_id <> $productId)")
    }

    private fun stopFailingRankingInsert() {
        jdbcTemplate.execute("ALTER TABLE product_ranking DROP CHECK chk_fail_ranking_insert")
    }

    private fun rankings(): List<Ranking> =
        jdbcTemplate.query(
            "SELECT product_id, score, ranking FROM product_ranking WHERE snapshot_id = ? ORDER BY ranking",
            { rs, _ -> Ranking(rs.getLong("product_id"), rs.getLong("score"), rs.getInt("ranking")) },
            WEEK,
        )

    private data class Ranking(
        val productId: Long,
        val score: Long,
        val ranking: Int,
    )

    private fun inputChecksum(): String? =
        jdbcTemplate.queryForList(
            "SELECT checksum FROM product_sales_snapshot_meta WHERE snapshot_id = ?",
            String::class.java,
            "${WEEK}_r1",
        ).firstOrNull()

    private fun resultChecksum(): String = sha256(rankings().joinToString("") { "${it.productId}\t${it.score}\t${it.ranking}\n" })

    private fun pointerStatus(): String? =
        jdbcTemplate.queryForList(
            "SELECT status FROM product_ranking_pointer WHERE snapshot_id = ?",
            String::class.java,
            WEEK,
        ).firstOrNull()

    companion object {
        private val REQUEST_DATE = LocalDate.parse("2026-08-24")
        private const val WEEK = "2026_08_3"

        private val EXPECTED_RANKINGS = listOf(Ranking(7L, 5L, 1), Ranking(9L, 5L, 2), Ranking(8L, 1L, 3))

        private val EXPECTED_INPUT_CHECKSUM = sha256(
            "42\t7\t3\t2026-08-18T10:00:00.000000Z\n" +
                "42\t8\t1\t2026-08-18T10:00:00.000000Z\n" +
                "43\t9\t5\t2026-08-19T10:00:00.000000Z\n" +
                "44\t7\t2\t2026-08-20T10:00:00.000000Z\n",
        )

        private val EXPECTED_RESULT_CHECKSUM = sha256("7\t5\t1\n9\t5\t2\n8\t1\t3\n")

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private val CREATE_PURCHASE_ACTIVITY = """
            CREATE TABLE IF NOT EXISTS product_purchase_activity (
                id bigint NOT NULL AUTO_INCREMENT,
                created_at datetime(6) NOT NULL,
                deleted_at datetime(6) DEFAULT NULL,
                updated_at datetime(6) NOT NULL,
                order_id bigint NOT NULL,
                product_id bigint NOT NULL,
                quantity int NOT NULL,
                user_id bigint NOT NULL,
                confirmed_at datetime(6) NOT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_purchase_activity_order_product (order_id, product_id)
            )
        """.trimIndent()

        private val CREATE_PRODUCT_RANKING = """
            CREATE TABLE IF NOT EXISTS product_ranking (
                id bigint NOT NULL AUTO_INCREMENT,
                created_at datetime(6) NOT NULL,
                deleted_at datetime(6) DEFAULT NULL,
                updated_at datetime(6) NOT NULL,
                snapshot_id varchar(30) NOT NULL,
                period_start date NOT NULL,
                product_id bigint NOT NULL,
                score bigint NOT NULL,
                ranking int NOT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_product_ranking_snapshot_product (snapshot_id, product_id)
            )
        """.trimIndent()

        private val CREATE_PRODUCT_RANKING_POINTER = """
            CREATE TABLE IF NOT EXISTS product_ranking_pointer (
                id bigint NOT NULL AUTO_INCREMENT,
                created_at datetime(6) NOT NULL,
                deleted_at datetime(6) DEFAULT NULL,
                updated_at datetime(6) NOT NULL,
                snapshot_id varchar(30) NOT NULL,
                status varchar(20) NOT NULL,
                product_count bigint DEFAULT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_product_ranking_pointer_snapshot (snapshot_id)
            )
        """.trimIndent()
    }
}
