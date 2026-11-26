package com.loopers.job.ranking

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.batch.job.ranking.SnapshotWeek
import com.loopers.batch.job.ranking.WeeklySalesSnapshotJobConfig
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.test.JobLauncherTestUtils
import org.springframework.batch.test.JobRepositoryTestUtils
import org.springframework.batch.test.context.SpringBatchTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.Instant
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
class IncidentWeeklyRankingConnectionTest @Autowired constructor(
    private val jobLauncherTestUtils: JobLauncherTestUtils,
    private val jobRepositoryTestUtils: JobRepositoryTestUtils,
    @param:Qualifier(WeeklySalesSnapshotJobConfig.JOB_NAME) private val job: Job,
    private val jdbcTemplate: JdbcTemplate,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        jobLauncherTestUtils.job = job
        jdbcTemplate.execute(CREATE_PURCHASE_ACTIVITY)
        jdbcTemplate.execute(CREATE_PRODUCT_RANKING)
        jdbcTemplate.execute(CREATE_PRODUCT_RANKING_POINTER)
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE product_purchase_activity")
        jdbcTemplate.execute("DROP TABLE product_ranking")
        jdbcTemplate.execute("TRUNCATE TABLE product_ranking_pointer")
        databaseCleanUp.truncateAllTables()
        jobRepositoryTestUtils.removeJobExecutions()
    }

    @DisplayName("사고 연결 4구간: 정상 10건과 복구한 장애 10건의 구매 활동을 주간 배치로 고정하면, 20건이 모두 입력되고 상품 점수 20 으로 1위다.")
    @Test
    fun ranksIncidentActivities() {
        purchasesFromIncidentContract()

        val execution = jobLauncherTestUtils.launchJob(
            JobParametersBuilder()
                .addLocalDate("requestDate", REQUEST_DATE, false)
                .addString("snapshotId", SnapshotWeek.endedBefore(REQUEST_DATE).snapshotId)
                .toJobParameters(),
        )

        assertThat(execution.status).isEqualTo(BatchStatus.COMPLETED)
        assertThat(snapshotOrderIds()).containsExactlyElementsOf((1L..20L).toList())
        assertThat(rankings()).containsExactly(Ranking(INCIDENT_PRODUCT_ID, 20L, 1))
        assertThat(pointerStatus()).isEqualTo("PUBLISHED")
    }

    private fun purchasesFromIncidentContract() {
        requireNotNull(javaClass.getResource(INCIDENT_CONTRACT)).readText().lines().filter { it.isNotBlank() }.forEach { line ->
            val event = objectMapper.readTree(line)
            val at = LocalDateTime.ofInstant(Instant.parse(event.get("confirmedAt").asText()), ZoneOffset.UTC)
            event.get("items").forEach { item ->
                jdbcTemplate.update(
                    """
                    INSERT INTO product_purchase_activity
                        (created_at, updated_at, order_id, product_id, quantity, user_id, confirmed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    at,
                    at,
                    event.get("orderId").asLong(),
                    item.get("productId").asLong(),
                    item.get("quantity").asInt(),
                    event.get("userId").asLong(),
                    at,
                )
            }
        }
    }

    private fun snapshotOrderIds(): List<Long> =
        jdbcTemplate.queryForList(
            "SELECT order_id FROM purchase_activity_snapshot WHERE snapshot_id = ? ORDER BY order_id",
            Long::class.java,
            "${WEEK}_r1",
        )

    private fun rankings(): List<Ranking> =
        jdbcTemplate.query(
            "SELECT product_id, score, ranking FROM product_ranking WHERE snapshot_id = ? ORDER BY ranking",
            { rs, _ -> Ranking(rs.getLong("product_id"), rs.getLong("score"), rs.getInt("ranking")) },
            "${WEEK}_r1",
        )

    private fun pointerStatus(): String? =
        jdbcTemplate.queryForList(
            "SELECT status FROM product_ranking_pointer WHERE snapshot_id = ?",
            String::class.java,
            "${WEEK}_r1",
        ).firstOrNull()

    private data class Ranking(
        val productId: Long,
        val score: Long,
        val ranking: Int,
    )

    companion object {
        private const val INCIDENT_CONTRACT = "/contract/order-confirmed-incident-2026-08-17.jsonl"
        private const val INCIDENT_PRODUCT_ID = 1L
        private val REQUEST_DATE = LocalDate.parse("2026-08-24")
        private const val WEEK = "2026_08_3"

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
