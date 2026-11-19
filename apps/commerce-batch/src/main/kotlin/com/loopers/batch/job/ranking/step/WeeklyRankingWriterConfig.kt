package com.loopers.batch.job.ranking.step

import org.springframework.batch.item.database.JdbcBatchItemWriter
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

@Configuration
class WeeklyRankingWriterConfig(
    private val dataSource: DataSource,
) {
    @Bean(WRITER_NAME)
    fun weeklyRankingWriter(): JdbcBatchItemWriter<ProductSales> =
        JdbcBatchItemWriterBuilder<ProductSales>()
            .dataSource(dataSource)
            .sql(INSERT_RANKING)
            .beanMapped()
            .build()

    companion object {
        const val WRITER_NAME = "weeklyRankingWriter"

        private val INSERT_RANKING = """
            INSERT INTO product_ranking
                (snapshot_id, period_start, product_id, score, ranking, created_at, updated_at)
            VALUES
                (:snapshotId, :periodStart, :productId, :salesQuantity, :ranking, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            AS new
            ON DUPLICATE KEY UPDATE
                score = new.score,
                ranking = new.ranking,
                updated_at = new.updated_at
        """.trimIndent()
    }
}
