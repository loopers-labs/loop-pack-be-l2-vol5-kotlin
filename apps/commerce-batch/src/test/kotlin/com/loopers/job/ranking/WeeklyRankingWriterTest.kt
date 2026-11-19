package com.loopers.job.ranking

import com.loopers.batch.job.ranking.step.ProductSales
import com.loopers.batch.job.ranking.step.WeeklyRankingWriterConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.database.JdbcBatchItemWriter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.time.LocalDateTime

@SpringBootTest(properties = ["spring.batch.job.enabled=false"])
class WeeklyRankingWriterTest @Autowired constructor(
    @param:Qualifier(WeeklyRankingWriterConfig.WRITER_NAME) private val writer: JdbcBatchItemWriter<ProductSales>,
    private val jdbcTemplate: JdbcTemplate,
) {
    @BeforeEach
    fun setUp() {
        jdbcTemplate.execute(
            """
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
            """.trimIndent(),
        )
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE product_ranking")
    }

    @DisplayName("같은 주에 상품이 없으면, 새 행으로 넣는다.")
    @Test
    fun insertsRow_whenProductIsNotRanked() {
        writer.write(Chunk(sales(productId = 1L, salesQuantity = 9L, ranking = 1)))

        assertThat(rows()).containsExactly(Row(productId = 1L, score = 9L, ranking = 1))
    }

    @DisplayName("같은 주에 상품이 이미 있으면, 점수와 순위를 바꾸고 행을 늘리지 않는다.")
    @Test
    fun updatesScoreAndRanking_whenProductIsAlreadyRanked() {
        val createdAt = LocalDateTime.of(2026, 11, 16, 1, 30)
        jdbcTemplate.update(
            """
            INSERT INTO product_ranking (snapshot_id, period_start, product_id, score, ranking, created_at, updated_at)
            VALUES ('2026_11_2', '2026-11-09', 1, 2, 3, ?, ?)
            """.trimIndent(),
            createdAt,
            createdAt,
        )

        writer.write(Chunk(sales(productId = 1L, salesQuantity = 5L, ranking = 1)))

        assertThat(rows()).containsExactly(Row(productId = 1L, score = 5L, ranking = 1))
        assertThat(
            jdbcTemplate.queryForObject("SELECT created_at FROM product_ranking", LocalDateTime::class.java),
        ).isEqualTo(createdAt)
        assertThat(
            jdbcTemplate.queryForObject("SELECT updated_at FROM product_ranking", LocalDateTime::class.java),
        ).isAfter(createdAt)
    }

    private fun sales(productId: Long, salesQuantity: Long, ranking: Int) =
        ProductSales(
            snapshotId = "2026_11_2",
            periodStart = LocalDate.parse("2026-11-09"),
            productId = productId,
            salesQuantity = salesQuantity,
            ranking = ranking,
        )

    private fun rows(): List<Row> =
        jdbcTemplate.query("SELECT product_id, score, ranking FROM product_ranking ORDER BY product_id") { rs, _ ->
            Row(productId = rs.getLong("product_id"), score = rs.getLong("score"), ranking = rs.getInt("ranking"))
        }

    private data class Row(
        val productId: Long,
        val score: Long,
        val ranking: Int,
    )
}
