package com.loopers.job.ranking

import com.loopers.batch.job.ranking.step.ProductSales
import com.loopers.batch.job.ranking.step.WeeklySalesReaderConfig
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.item.ExecutionContext
import org.springframework.batch.item.database.JdbcCursorItemReader
import org.springframework.batch.test.MetaDataInstanceFactory
import org.springframework.batch.test.StepScopeTestUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.LocalDateTime

@SpringBootTest(properties = ["spring.batch.job.enabled=false"])
class WeeklySalesReaderTest @Autowired constructor(
    @param:Qualifier(WeeklySalesReaderConfig.READER_NAME) private val reader: JdbcCursorItemReader<ProductSales>,
    private val jdbcTemplate: JdbcTemplate,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("지정한 주의 스냅샷만 읽는다.")
    @Test
    fun readsOnlyGivenSnapshot() {
        snapshot("2026_11_1_r1", productId = 1L, salesQuantity = 20L)
        snapshot("2026_11_2_r1", productId = 2L, salesQuantity = 90L)
        snapshot("2026_11_3_r1", productId = 3L, salesQuantity = 70L)

        assertThat(readAll("2026_11_2_r1")).containsExactly(sales(productId = 2L, salesQuantity = 90L, ranking = 1))
    }

    @DisplayName("판매 수량이 많은 순으로 읽고, 수량이 같으면 상품 id 가 작은 순으로 읽는다.")
    @Test
    fun readsBySalesQuantityDescThenProductIdAsc() {
        snapshot("2026_11_2_r1", productId = 3L, salesQuantity = 40L)
        snapshot("2026_11_2_r1", productId = 1L, salesQuantity = 40L)
        snapshot("2026_11_2_r1", productId = 2L, salesQuantity = 90L)
        snapshot("2026_11_2_r1", productId = 4L, salesQuantity = 5L)

        assertThat(readAll("2026_11_2_r1")).containsExactly(
            sales(productId = 2L, salesQuantity = 90L, ranking = 1),
            sales(productId = 1L, salesQuantity = 40L, ranking = 2),
            sales(productId = 3L, salesQuantity = 40L, ranking = 3),
            sales(productId = 4L, salesQuantity = 5L, ranking = 4),
        )
    }

    @DisplayName("스냅샷 식별자는 리비전을 떼고, 주 시작일은 날짜로 읽는다.")
    @Test
    fun readsSnapshotIdWithoutRevisionAndPeriodStartAsDate() {
        snapshot("2026_11_2_r1", productId = 1L, salesQuantity = 20L)

        val read = readAll("2026_11_2_r1").single()

        assertThat(read.snapshotId).isEqualTo("2026_11_2")
        assertThat(read.periodStart).isEqualTo(LocalDate.parse("2026-11-09"))
    }

    @DisplayName("판매 수량이 같으면 상품 id 가 작은 쪽에 앞 순위를 준다.")
    @Test
    fun ranksTiesByProductIdAsc() {
        snapshot("2026_11_2_r1", productId = 1L, salesQuantity = 30L)
        snapshot("2026_11_2_r1", productId = 2L, salesQuantity = 30L)
        snapshot("2026_11_2_r1", productId = 3L, salesQuantity = 30L)
        snapshot("2026_11_2_r1", productId = 4L, salesQuantity = 10L)

        assertThat(readAll("2026_11_2_r1").map { it.productId to it.ranking }).containsExactly(
            1L to 1,
            2L to 2,
            3L to 3,
            4L to 4,
        )
    }

    @DisplayName("그 주 r2 가 있으면, r1 과 r2 를 상품별로 더해 순위를 매긴다.")
    @Test
    fun sumsFirstAndSecondRevisionPerProduct_whenSecondExists() {
        snapshot("2026_11_2_r1", productId = 1L, salesQuantity = 20L)
        snapshot("2026_11_2_r1", productId = 2L, salesQuantity = 22L)
        snapshot("2026_11_2_r2", productId = 1L, salesQuantity = 5L)
        snapshot("2026_11_2_r2", productId = 3L, salesQuantity = 30L)

        assertThat(readAll("2026_11_2_r1")).containsExactly(
            sales(productId = 3L, salesQuantity = 30L, ranking = 1),
            sales(productId = 1L, salesQuantity = 25L, ranking = 2),
            sales(productId = 2L, salesQuantity = 22L, ranking = 3),
        )
    }

    @DisplayName("다른 주에 r2 가 있어도, 지정한 주의 r1 을 읽는다.")
    @Test
    fun readsFirstRevision_whenOnlyOtherWeekHasSecond() {
        snapshot("2026_11_2_r1", productId = 1L, salesQuantity = 20L)
        snapshot("2026_11_1_r2", productId = 2L, salesQuantity = 30L)

        assertThat(readAll("2026_11_2_r1")).containsExactly(sales(productId = 1L, salesQuantity = 20L, ranking = 1))
    }

    @DisplayName("지정한 주의 스냅샷이 없으면, 아무것도 읽지 않는다.")
    @Test
    fun readsNothing_whenSnapshotIsMissing() {
        snapshot("2026_11_1_r1", productId = 1L, salesQuantity = 20L)

        assertThat(readAll("2026_11_2_r1")).isEmpty()
    }

    private fun sales(productId: Long, salesQuantity: Long, ranking: Int) =
        ProductSales(
            snapshotId = "2026_11_2",
            periodStart = LocalDate.parse("2026-11-09"),
            productId = productId,
            salesQuantity = salesQuantity,
            ranking = ranking,
        )

    private fun readAll(snapshotId: String): List<ProductSales> {
        val stepExecution = MetaDataInstanceFactory.createStepExecution(
            JobParametersBuilder().addString("snapshotId", snapshotId).toJobParameters(),
        )
        return StepScopeTestUtils.doInStepScope(stepExecution) {
            reader.open(ExecutionContext())
            try {
                generateSequence { reader.read() }.toList()
            } finally {
                reader.close()
            }
        }
    }

    private var nextOrderId = 1L

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
            nextOrderId++,
            productId,
            salesQuantity,
            now.minusDays(3),
            now,
            now,
        )
    }
}
