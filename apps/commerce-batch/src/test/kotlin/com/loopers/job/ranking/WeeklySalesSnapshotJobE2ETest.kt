package com.loopers.job.ranking

import com.loopers.batch.job.ranking.SnapshotWeek
import com.loopers.batch.job.ranking.WeeklySalesSnapshotJobConfig
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException
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
@TestPropertySource(properties = ["spring.batch.job.name=${WeeklySalesSnapshotJobConfig.JOB_NAME}"])
class WeeklySalesSnapshotJobE2ETest @Autowired constructor(
    private val jobLauncherTestUtils: JobLauncherTestUtils,
    private val jobRepositoryTestUtils: JobRepositoryTestUtils,
    @param:Qualifier(WeeklySalesSnapshotJobConfig.JOB_NAME) private val job: Job,
    private val jdbcTemplate: JdbcTemplate,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    private var nextOrderId = 1L

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

    @DisplayName("직전 주에 확정된 수량을 상품별로 합쳐 스냅샷으로 남긴다.")
    @Test
    fun sumsQuantityPerProductWithinLastWeek() {
        purchase(productId = 1L, quantity = 2, confirmedAt = "2026-11-09T00:00:00")
        purchase(productId = 1L, quantity = 3, confirmedAt = "2026-11-12T10:00:00")
        purchase(productId = 2L, quantity = 1, confirmedAt = "2026-11-15T23:59:59.999999")

        val exitStatus = launch("2026-11-16")

        assertThat(exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode)
        assertThat(snapshotRows()).containsExactlyInAnyOrder(
            SnapshotRow("2026_11_2_r1", 1L, 5L, "2026-11-09T00:00", "2026-11-16T00:00"),
            SnapshotRow("2026_11_2_r1", 2L, 1L, "2026-11-09T00:00", "2026-11-16T00:00"),
        )
    }

    @DisplayName("구간 밖에서 확정된 수량은 넣지 않는다.")
    @Test
    fun excludesPurchasesOutsideWeek() {
        purchase(productId = 1L, quantity = 4, confirmedAt = "2026-11-08T23:59:59.999999")
        purchase(productId = 1L, quantity = 7, confirmedAt = "2026-11-16T00:00:00")
        purchase(productId = 2L, quantity = 1, confirmedAt = "2026-11-10T00:00:00")

        launch("2026-11-16")

        assertThat(quantitiesOf("2026_11_2_r1")).containsExactly(2L to 1L)
    }

    @DisplayName("성공한 주를 날짜만 바꿔 다시 실행하면, 같은 JobInstance 라 실행되지 않고 스냅샷을 그대로 둔다.")
    @Test
    fun rejectsRerun_whenWeekAlreadyCompleted() {
        purchase(productId = 1L, quantity = 2, confirmedAt = "2026-11-10T00:00:00")
        launch("2026-11-16")
        purchase(productId = 1L, quantity = 9, confirmedAt = "2026-11-11T00:00:00")

        assertThrows<JobInstanceAlreadyCompleteException> { launch("2026-11-18") }
        assertThat(snapshotRows().map { it.productId to it.salesQuantity }).containsExactly(1L to 2L)
    }

    @DisplayName("직전 주에 확정된 구매가 없으면, 행 없이 성공한다.")
    @Test
    fun completesWithoutRows_whenNoPurchaseInWeek() {
        val exitStatus = launch("2026-11-16")

        assertThat(exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode)
        assertThat(snapshotRows()).isEmpty()
    }

    @DisplayName("requestDate 가 없으면, 실패한다.")
    @Test
    fun fails_whenRequestDateIsMissing() {
        val execution = jobLauncherTestUtils.launchJob()

        assertThat(execution.exitStatus.exitCode).isEqualTo(ExitStatus.FAILED.exitCode)
    }

    @DisplayName("직전 주 스냅샷으로 판매 수량 순위를 매겨 랭킹으로 남긴다.")
    @Test
    fun writesRankingFromLastWeekSnapshot() {
        purchase(productId = 1L, quantity = 3, confirmedAt = "2026-11-10T00:00:00")
        purchase(productId = 2L, quantity = 9, confirmedAt = "2026-11-11T00:00:00")
        purchase(productId = 3L, quantity = 3, confirmedAt = "2026-11-12T00:00:00")
        purchase(productId = 4L, quantity = 1, confirmedAt = "2026-11-13T00:00:00")

        val exitStatus = launch("2026-11-16")

        assertThat(exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode)
        assertThat(rankingRows()).containsExactly(
            RankingRow("2026_11_2", "2026-11-09", 2L, 9L, 1),
            RankingRow("2026_11_2", "2026-11-09", 1L, 3L, 2),
            RankingRow("2026_11_2", "2026-11-09", 3L, 3L, 3),
            RankingRow("2026_11_2", "2026-11-09", 4L, 1L, 4),
        )
    }

    @DisplayName("같은 주의 랭킹이 이미 있으면, 랭킹을 다시 쓰지 않는다.")
    @Test
    fun skipsRanking_whenWeekAlreadyRanked() {
        purchase(productId = 1L, quantity = 3, confirmedAt = "2026-11-10T00:00:00")
        jdbcTemplate.update(
            """
            INSERT INTO product_ranking
                (snapshot_id, period_start, product_id, score, ranking, created_at, updated_at)
            VALUES ('2026_11_2', '2026-11-09', 99, 1, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """.trimIndent(),
        )

        val exitStatus = launch("2026-11-16")

        assertThat(exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode)
        assertThat(rankingRows().map { it.productId }).containsExactly(99L)
    }

    @DisplayName("랭킹을 다 쓰고 상품 수가 스냅샷과 같으면, 포인터를 PUBLISHED 와 상품 수로 남긴다.")
    @Test
    fun publishesPointer_whenRankingIsComplete() {
        purchase(productId = 1L, quantity = 3, confirmedAt = "2026-11-10T00:00:00")
        purchase(productId = 2L, quantity = 9, confirmedAt = "2026-11-11T00:00:00")

        launch("2026-11-16")

        assertThat(pointerRows()).containsExactly(PointerRow("2026_11_2", "PUBLISHED", 2L))
    }

    @DisplayName("랭킹 스텝이 실패하면, 포인터를 FAILED 로 남기고 상품 수는 비워 둔다.")
    @Test
    fun marksPointerFailed_whenRankingStepFails() {
        purchase(productId = 1L, quantity = 3, confirmedAt = "2026-11-10T00:00:00")
        jdbcTemplate.execute("ALTER TABLE product_ranking ADD COLUMN must_fill int NOT NULL")

        val exitStatus = launch("2026-11-16")

        assertThat(exitStatus.exitCode).isEqualTo(ExitStatus.FAILED.exitCode)
        assertThat(pointerRows()).containsExactly(PointerRow("2026_11_2", "FAILED", null))
    }

    @DisplayName("실패한 주를 날짜만 바꿔 다시 실행하면, 같은 JobInstance 로 이어서 랭킹을 쓰고 PUBLISHED 로 남긴다.")
    @Test
    fun resumesSameInstanceAndPublishes_whenFailedWeekIsRerun() {
        purchase(productId = 1L, quantity = 3, confirmedAt = "2026-11-10T00:00:00")
        purchase(productId = 2L, quantity = 9, confirmedAt = "2026-11-11T00:00:00")
        jdbcTemplate.execute("ALTER TABLE product_ranking ADD COLUMN must_fill int NOT NULL")
        val failed = launchForInstance("2026-11-16")
        jdbcTemplate.execute("ALTER TABLE product_ranking DROP COLUMN must_fill")

        val resumed = launchForInstance("2026-11-17")

        assertThat(resumed.exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode)
        assertThat(resumed.instanceId).isEqualTo(failed.instanceId)
        assertThat(rankingRows().map { it.productId }).containsExactly(2L, 1L)
        assertThat(pointerRows()).containsExactly(PointerRow("2026_11_2", "PUBLISHED", 2L))
    }

    @DisplayName("이미 PUBLISHED 인 주를 다시 실행해도, 포인터의 상태와 상품 수를 그대로 둔다.")
    @Test
    fun keepsPublishedPointer_whenPublishedWeekIsRerun() {
        purchase(productId = 1L, quantity = 3, confirmedAt = "2026-11-10T00:00:00")
        jdbcTemplate.update(
            """
            INSERT INTO product_ranking_pointer (snapshot_id, status, product_count, created_at, updated_at)
            VALUES ('2026_11_2', 'PUBLISHED', 5, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """.trimIndent(),
        )

        launch("2026-11-16")

        assertThat(pointerRows()).containsExactly(PointerRow("2026_11_2", "PUBLISHED", 5L))
    }

    @DisplayName("구매가 없는 주는, 포인터를 PUBLISHED 와 상품 수 0 으로 남긴다.")
    @Test
    fun publishesPointerWithZero_whenNoPurchaseInWeek() {
        launch("2026-11-16")

        assertThat(pointerRows()).containsExactly(PointerRow("2026_11_2", "PUBLISHED", 0L))
    }

    @DisplayName("스냅샷과 랭킹의 상품 수가 다르면, 공개하지 않고 포인터를 FAILED 로 남긴다.")
    @Test
    fun marksPointerFailed_whenCountsDiffer() {
        purchase(productId = 1L, quantity = 3, confirmedAt = "2026-11-10T00:00:00")
        purchase(productId = 2L, quantity = 9, confirmedAt = "2026-11-11T00:00:00")
        jdbcTemplate.update(
            """
            INSERT INTO product_ranking
                (snapshot_id, period_start, product_id, score, ranking, created_at, updated_at)
            VALUES ('2026_11_2', '2026-11-09', 99, 1, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """.trimIndent(),
        )

        val exitStatus = launch("2026-11-16")

        assertThat(exitStatus.exitCode).isEqualTo(ExitStatus.FAILED.exitCode)
        assertThat(pointerRows()).containsExactly(PointerRow("2026_11_2", "FAILED", null))
    }

    @DisplayName("직전 주에 확정된 구매 중 월요일 00:00 전에 저장된 건 r1, 00:00 이후에 저장된 건 r2 로 함께 남긴다.")
    @Test
    fun splitsFirstAndSecond_byCreatedBeforeOrAfterWeekEnd() {
        purchase(productId = 1L, quantity = 2, confirmedAt = "2026-11-10T00:00:00")
        purchase(productId = 2L, quantity = 1, confirmedAt = "2026-11-15T23:00:00", createdAt = at("2026-11-15T23:59:59.999999"))
        purchase(productId = 1L, quantity = 3, confirmedAt = "2026-11-12T00:00:00", createdAt = at("2026-11-16T00:00:00"))
        purchase(productId = 2L, quantity = 4, confirmedAt = "2026-11-15T23:00:00", createdAt = at("2026-11-16T01:00:00"))
        purchase(productId = 3L, quantity = 9, confirmedAt = "2026-11-16T00:00:00", createdAt = at("2026-11-16T00:10:00"))

        val exitStatus = launch("2026-11-16")

        assertThat(exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode)
        assertThat(quantitiesOf("2026_11_2_r1")).containsExactlyInAnyOrder(1L to 2L, 2L to 1L)
        assertThat(quantitiesOf("2026_11_2_r2")).containsExactlyInAnyOrder(1L to 3L, 2L to 4L)
    }

    @DisplayName("월요일 00:00 이후에 저장된 구매가 없으면, r2 를 만들지 않는다.")
    @Test
    fun writesNoLateSnapshot_whenNoPurchaseCreatedAfterWeekEnd() {
        purchase(productId = 1L, quantity = 2, confirmedAt = "2026-11-10T00:00:00")

        launch("2026-11-16")

        assertThat(quantitiesOf("2026_11_2_r2")).isEmpty()
        assertThat(metaOf("2026_11_2_r2")).isNull()
    }

    @DisplayName("r1 과 r2 를 더해 랭킹을 매긴다.")
    @Test
    fun ranksWithFirstPlusSecond_inSameRun() {
        purchase(productId = 1L, quantity = 2, confirmedAt = "2026-11-10T00:00:00")
        purchase(productId = 2L, quantity = 3, confirmedAt = "2026-11-11T00:00:00")
        purchase(productId = 1L, quantity = 4, confirmedAt = "2026-11-12T00:00:00", createdAt = at("2026-11-16T00:30:00"))

        launch("2026-11-16")

        assertThat(rankingRows().map { Triple(it.productId, it.score, it.ranking) })
            .containsExactly(Triple(1L, 6L, 1), Triple(2L, 3L, 2))
        assertThat(pointerRows()).containsExactly(PointerRow("2026_11_2", "PUBLISHED", 2L))
    }

    @DisplayName("그 주 r2 가 있으면, r1 과 r2 를 더해 랭킹을 쓰고 합친 상품 수와 비교해 PUBLISHED 로 남긴다.")
    @Test
    fun ranksAndPublishesWithFirstPlusSecond_whenSecondExists() {
        snapshot("2026_11_2_r1", productId = 1L, salesQuantity = 2L)
        snapshot("2026_11_2_r2", productId = 1L, salesQuantity = 3L)
        snapshot("2026_11_2_r2", productId = 2L, salesQuantity = 4L)

        val exitStatus = launch("2026-11-16")

        assertThat(exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode)
        assertThat(rankingRows().map { Triple(it.productId, it.score, it.ranking) })
            .containsExactly(Triple(1L, 5L, 1), Triple(2L, 4L, 2))
        assertThat(pointerRows()).containsExactly(PointerRow("2026_11_2", "PUBLISHED", 2L))
    }

    private fun snapshot(snapshotId: String, productId: Long, salesQuantity: Long) {
        jdbcTemplate.update(
            """
            INSERT INTO purchase_activity_snapshot
                (snapshot_id, period_start, period_end, order_id, product_id, quantity, confirmed_at, created_at, updated_at)
            VALUES (?, '2026-11-09 00:00:00', '2026-11-16 00:00:00', ?, ?, ?, '2026-11-10 00:00:00', '2026-11-16 01:30:00', '2026-11-16 01:30:00')
            """.trimIndent(),
            snapshotId,
            nextOrderId++,
            productId,
            salesQuantity,
        )
    }

    @DisplayName("T0. 상품별 r1 과 r2 를 더하면 그 주 구매 활동 합계와 같다.")
    @Test
    fun t0_firstPlusSecondEqualsWeeklyPurchases() {
        purchase(productId = 1L, quantity = 3, confirmedAt = "2026-11-10T00:00:00")
        purchase(productId = 2L, quantity = 5, confirmedAt = "2026-11-11T00:00:00")
        purchase(productId = 1L, quantity = 2, confirmedAt = "2026-11-12T00:00:00", createdAt = at("2026-11-16T00:20:00"))
        purchase(productId = 3L, quantity = 4, confirmedAt = "2026-11-13T00:00:00", createdAt = at("2026-11-16T01:00:00"))

        launch("2026-11-16")

        assertThat(quantitiesOf("2026_11_2_r1")).containsExactlyInAnyOrder(1L to 3L, 2L to 5L)
        assertThat(quantitiesOf("2026_11_2_r2")).containsExactlyInAnyOrder(1L to 2L, 3L to 4L)
        assertThat(firstPlusSecondOf("2026_11_2")).isEqualTo(weeklyPurchasesOf(WEEK_START, WEEK_END))
        assertThat(firstPlusSecondOf("2026_11_2")).containsExactlyInAnyOrderEntriesOf(mapOf(1L to 5L, 2L to 5L, 3L to 4L))
    }

    @DisplayName("T1a. 랭킹 스텝이 실패한 뒤 r2 가 생기고 재시작해도, 첫 실행과 같은 입력(r1)으로 매긴다.")
    @Test
    fun t1a_restartAfterSecondAppears_ranksWithFirstOnly() {
        purchaseRankedProducts()
        failRankingInsertAt(productId = 120L)
        val failed = launchForInstance("2026-11-16")
        assertThat(failed.exitStatus.exitCode).isEqualTo(ExitStatus.FAILED.exitCode)
        assertThat(rankingRows()).hasSize(100)
        assertThat(pointerRows()).containsExactly(PointerRow("2026_11_2", "FAILED", null))
        val afterFirst = LocalDateTime.now(ZoneOffset.UTC).plusHours(1)
        purchase(productId = 50L, quantity = 5000, confirmedAt = "2026-11-14T00:00:00", createdAt = afterFirst)
        purchase(productId = 130L, quantity = 3, confirmedAt = "2026-11-14T00:00:00", createdAt = afterFirst)
        launch("2026-11-23")
        stopFailingRankingInsert()

        val resumed = launchForInstance("2026-11-17")

        assertThat(resumed.exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode)
        assertThat(resumed.instanceId).isEqualTo(failed.instanceId)
        assertThat(rankingOf(50L)).isEqualTo(1500L to 50)
        assertThat(rankingOf(130L)).isEqualTo(700L to 130)
        assertThat(pointerRows()).containsExactly(PointerRow("2026_11_2", "PUBLISHED", 150L))
    }

    @DisplayName("T1b. 늦은 구매로 첫 청크 경계가 밀려도, 재시작하면 r1 만으로 150 행을 모두 매긴다.")
    @Test
    fun t1b_restartAfterChunkBoundaryShifts_ranksAllWithFirstOnly() {
        purchaseRankedProducts()
        failRankingInsertAt(productId = 120L)
        val failed = launchForInstance("2026-11-16")
        assertThat(failed.exitStatus.exitCode).isEqualTo(ExitStatus.FAILED.exitCode)
        val afterFirst = LocalDateTime.now(ZoneOffset.UTC).plusHours(1)
        purchase(productId = 150L, quantity = 5000, confirmedAt = "2026-11-14T00:00:00", createdAt = afterFirst)
        launch("2026-11-23")
        stopFailingRankingInsert()

        val resumed = launchForInstance("2026-11-17")

        assertThat(resumed.exitStatus.exitCode).isEqualTo(ExitStatus.COMPLETED.exitCode)
        assertThat(rankingRows()).hasSize(150)
        assertThat(rankingRows().map { Triple(it.productId, it.score, it.ranking) })
            .containsExactlyElementsOf((1L..150L).map { Triple(it, (200L - it) * 10, it.toInt()) })
        assertThat(pointerRows()).containsExactly(PointerRow("2026_11_2", "PUBLISHED", 150L))
    }

    @DisplayName("T2a. r1 을 만들기 전에 저장됐는데 created_at 이 r1 시각보다 늦어도, r2 에 두 번 세지 않는다.")
    @Test
    fun t2a_purchaseInFirstIsNotCountedAgainInSecond() {
        val ahead = LocalDateTime.now(ZoneOffset.UTC).plusHours(1)
        purchase(productId = 1L, quantity = 3, confirmedAt = "2026-11-10T00:00:00", createdAt = ahead)
        launch("2026-11-16")

        launch("2026-11-23")

        assertThat(quantitiesOf("2026_11_2_r1")).containsExactly(1L to 3L)
        assertThat(quantitiesOf("2026_11_2_r2")).isEmpty()
        assertThat(firstPlusSecondOf("2026_11_2")).isEqualTo(weeklyPurchasesOf(WEEK_START, WEEK_END))
    }

    @DisplayName("T2b. r1 을 만든 뒤 저장됐는데 created_at 이 r1 시각보다 이르면, r2 에서 빠지지 않는다.")
    @Test
    fun t2b_purchaseMissingFromFirstIsInSecond() {
        purchase(productId = 1L, quantity = 3, confirmedAt = "2026-11-10T00:00:00")
        launch("2026-11-16")
        val firstSnapshotAt = jdbcTemplate.queryForObject(
            "SELECT frozen_at FROM product_sales_snapshot_meta WHERE snapshot_id = '2026_11_2_r1'",
            LocalDateTime::class.java,
        )!!
        purchase(productId = 2L, quantity = 4, confirmedAt = "2026-11-11T00:00:00", createdAt = firstSnapshotAt.minusSeconds(1))

        launch("2026-11-23")

        assertThat(quantitiesOf("2026_11_2_r1")).containsExactly(1L to 3L)
        assertThat(quantitiesOf("2026_11_2_r2")).containsExactly(2L to 4L)
        assertThat(firstPlusSecondOf("2026_11_2")).isEqualTo(weeklyPurchasesOf(WEEK_START, WEEK_END))
    }

    @DisplayName("T3. r2 를 만든 뒤에 도착한 구매는 r2 에도, 다른 주 스냅샷에도 들어가지 않는다.")
    @Test
    fun t3_purchaseAfterSecondIsNotReflected() {
        purchase(productId = 1L, quantity = 3, confirmedAt = "2026-11-10T00:00:00")
        purchase(productId = 1L, quantity = 2, confirmedAt = "2026-11-11T00:00:00", createdAt = at("2026-11-16T00:30:00"))
        launch("2026-11-16")
        purchase(productId = 1L, quantity = 4, confirmedAt = "2026-11-12T00:00:00", createdAt = at("2026-11-16T02:00:00"))

        launch("2026-11-23")

        assertThat(quantitiesOf("2026_11_2_r1")).containsExactly(1L to 3L)
        assertThat(quantitiesOf("2026_11_2_r2")).containsExactly(1L to 2L)
        assertThat(quantitiesOf("2026_11_2_r3")).isEmpty()
        assertThat(firstPlusSecondOf("2026_11_2")).containsExactlyEntriesOf(mapOf(1L to 5L))
        assertThat(weeklyPurchasesOf(WEEK_START, WEEK_END)).containsExactlyEntriesOf(mapOf(1L to 9L))
        assertThat(quantitiesOf("2026_11_3_r2")).isEmpty()
    }

    private fun purchaseRankedProducts() {
        (1L..150L).forEach { purchase(productId = it, quantity = ((200 - it) * 10).toInt(), confirmedAt = "2026-11-10T00:00:00") }
    }

    private fun failRankingInsertAt(productId: Long) {
        jdbcTemplate.execute("ALTER TABLE product_ranking ADD CONSTRAINT chk_fail_ranking_insert CHECK (product_id <> $productId)")
    }

    private fun stopFailingRankingInsert() {
        jdbcTemplate.execute("ALTER TABLE product_ranking DROP CHECK chk_fail_ranking_insert")
    }

    private fun rankingOf(productId: Long): Pair<Long, Int>? =
        rankingRows().firstOrNull { it.productId == productId }?.let { it.score to it.ranking }

    private fun firstPlusSecondOf(week: String): Map<Long, Long> =
        jdbcTemplate.query(
            "SELECT product_id, SUM(quantity) q FROM purchase_activity_snapshot WHERE snapshot_id IN (?, ?) GROUP BY product_id",
            { rs, _ -> rs.getLong("product_id") to rs.getLong("q") },
            "${week}_r1",
            "${week}_r2",
        ).toMap()

    private fun weeklyPurchasesOf(start: String, end: String): Map<Long, Long> =
        jdbcTemplate.query(
            """
            SELECT product_id, SUM(quantity) q FROM product_purchase_activity
            WHERE confirmed_at >= ? AND confirmed_at < ? AND deleted_at IS NULL
            GROUP BY product_id
            """.trimIndent(),
            { rs, _ -> rs.getLong("product_id") to rs.getLong("q") },
            start,
            end,
        ).toMap()

    @DisplayName("r1 을 만들면, 주문·상품 행을 그대로 사본에 남기고 정보 행에 건수와 checksum 을 남긴다.")
    @Test
    fun writesRowsAndMeta_whenFirstSnapshotIsMade() {
        purchase(productId = 7L, quantity = 2, confirmedAt = "2026-11-10T00:00:00")
        purchase(productId = 9L, quantity = 5, confirmedAt = "2026-11-11T12:30:00")

        launch("2026-11-16")

        assertThat(
            jdbcTemplate.queryForList(
                "SELECT order_id, product_id, quantity FROM purchase_activity_snapshot WHERE snapshot_id = '2026_11_2_r1' ORDER BY order_id",
            ).map { Triple(it["order_id"], it["product_id"], it["quantity"]) },
        ).containsExactly(Triple(1L, 7L, 2), Triple(2L, 9L, 5))
        val meta = metaOf("2026_11_2_r1")!!
        assertThat(meta.revision).isEqualTo(1)
        assertThat(meta.status).isEqualTo("FROZEN")
        assertThat(meta.rowCount).isEqualTo(2L)
        assertThat(meta.checksum).isEqualTo(
            sha256("1\t7\t2\t2026-11-10T00:00:00.000000Z\n2\t9\t5\t2026-11-11T12:30:00.000000Z\n"),
        )
    }

    @DisplayName("r1 에 들 구매가 없어도 r1 정보 행을 남기고, r2 는 따로 정보 행을 남긴다.")
    @Test
    fun writesEmptyFirstMeta_andSecondMeta_whenOnlyLatePurchasesExist() {
        purchase(productId = 1L, quantity = 2, confirmedAt = "2026-11-10T00:00:00", createdAt = at("2026-11-16T00:30:00"))

        launch("2026-11-16")

        assertThat(metaOf("2026_11_2_r1")!!.rowCount).isEqualTo(0L)
        assertThat(quantitiesOf("2026_11_2_r2")).containsExactly(1L to 2L)
        assertThat(metaOf("2026_11_2_r2")!!.revision).isEqualTo(2)
        assertThat(metaOf("2026_11_2_r2")!!.rowCount).isEqualTo(1L)
    }

    private fun at(text: String): LocalDateTime = LocalDateTime.parse(text)

    private fun metaOf(snapshotId: String): MetaRow? =
        jdbcTemplate.query(
            "SELECT revision, status, row_count, checksum FROM product_sales_snapshot_meta WHERE snapshot_id = ?",
            { rs, _ -> MetaRow(rs.getInt("revision"), rs.getString("status"), rs.getLong("row_count"), rs.getString("checksum")) },
            snapshotId,
        ).firstOrNull()

    private data class MetaRow(
        val revision: Int,
        val status: String,
        val rowCount: Long,
        val checksum: String,
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun quantitiesOf(snapshotId: String): List<Pair<Long, Long>> =
        snapshotRows().filter { it.snapshotId == snapshotId }.map { it.productId to it.salesQuantity }

    private fun launch(requestDate: String): ExitStatus = launchForInstance(requestDate).exitStatus

    private fun launchForInstance(requestDate: String): Launched {
        val date = LocalDate.parse(requestDate)
        val execution = jobLauncherTestUtils.launchJob(
            JobParametersBuilder()
                .addLocalDate("requestDate", date, false)
                .addString("snapshotId", SnapshotWeek.endedBefore(date).snapshotId)
                .toJobParameters(),
        )
        return Launched(execution.exitStatus, execution.jobInstance.instanceId)
    }

    private data class Launched(
        val exitStatus: ExitStatus,
        val instanceId: Long,
    )

    private fun pointerRows(): List<PointerRow> =
        jdbcTemplate.query("SELECT snapshot_id, status, product_count FROM product_ranking_pointer") { rs, _ ->
            PointerRow(
                snapshotId = rs.getString("snapshot_id"),
                status = rs.getString("status"),
                productCount = rs.getLong("product_count").takeUnless { rs.wasNull() },
            )
        }

    private data class PointerRow(
        val snapshotId: String,
        val status: String,
        val productCount: Long?,
    )

    private fun rankingRows(): List<RankingRow> =
        jdbcTemplate.query(
            "SELECT snapshot_id, period_start, product_id, score, ranking FROM product_ranking ORDER BY ranking, product_id",
        ) { rs, _ ->
            RankingRow(
                snapshotId = rs.getString("snapshot_id"),
                periodStart = rs.getObject("period_start", LocalDate::class.java).toString(),
                productId = rs.getLong("product_id"),
                score = rs.getLong("score"),
                ranking = rs.getInt("ranking"),
            )
        }

    private data class RankingRow(
        val snapshotId: String,
        val periodStart: String,
        val productId: Long,
        val score: Long,
        val ranking: Int,
    )

    private fun purchase(productId: Long, quantity: Int, confirmedAt: String, createdAt: LocalDateTime? = null) {
        val at = LocalDateTime.parse(confirmedAt)
        val created = createdAt ?: at
        jdbcTemplate.update(
            """
            INSERT INTO product_purchase_activity
                (created_at, updated_at, order_id, product_id, quantity, user_id, confirmed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            created,
            created,
            nextOrderId++,
            productId,
            quantity,
            1L,
            at,
        )
    }

    private fun snapshotRows(): List<SnapshotRow> =
        jdbcTemplate.query(
            """
            SELECT snapshot_id, product_id, SUM(quantity) sales_quantity, MIN(period_start) period_start, MIN(period_end) period_end
            FROM purchase_activity_snapshot
            GROUP BY snapshot_id, product_id
            """.trimIndent(),
        ) { rs, _ ->
            SnapshotRow(
                snapshotId = rs.getString("snapshot_id"),
                productId = rs.getLong("product_id"),
                salesQuantity = rs.getLong("sales_quantity"),
                periodStart = rs.getObject("period_start", LocalDateTime::class.java).toString(),
                periodEnd = rs.getObject("period_end", LocalDateTime::class.java).toString(),
            )
        }

    private data class SnapshotRow(
        val snapshotId: String,
        val productId: Long,
        val salesQuantity: Long,
        val periodStart: String,
        val periodEnd: String,
    )

    companion object {
        private const val WEEK_START = "2026-11-09 00:00:00"
        private const val WEEK_END = "2026-11-16 00:00:00"

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
