package com.loopers.batch.job.ranking.step

import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.item.database.JdbcCursorItemReader
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.LocalDate
import javax.sql.DataSource

@Configuration
class WeeklySalesReaderConfig(
    private val dataSource: DataSource,
) {
    @StepScope
    @Bean(READER_NAME)
    fun weeklySalesReader(
        @Value("#{jobParameters['snapshotId']}") snapshotId: String,
    ): JdbcCursorItemReader<ProductSales> =
        JdbcCursorItemReaderBuilder<ProductSales>()
            .name(READER_NAME)
            .dataSource(dataSource)
            .sql(SELECT_WEEKLY_SALES)
            .queryArguments(snapshotId, lateSnapshotIdOf(snapshotId))
            .rowMapper { rs, _ ->
                ProductSales(
                    snapshotId = rs.getString("snapshot_id"),
                    periodStart = rs.getObject("period_start", LocalDate::class.java),
                    productId = rs.getLong("product_id"),
                    salesQuantity = rs.getLong("sales_quantity"),
                    ranking = rs.getInt("ranking"),
                )
            }
            .build()

    companion object {
        const val READER_NAME = "weeklySalesReader"

        private fun lateSnapshotIdOf(snapshotId: String): String = "${snapshotId.substringBefore("_r")}_r2"

        private val SELECT_WEEKLY_SALES = """
            SELECT SUBSTRING_INDEX(MIN(snapshot_id), '_r', 1) AS snapshot_id,
                   DATE(MIN(period_start)) AS period_start,
                   product_id,
                   SUM(quantity) AS sales_quantity,
                   ROW_NUMBER() OVER (ORDER BY SUM(quantity) DESC, product_id) AS ranking
            FROM purchase_activity_snapshot
            WHERE snapshot_id IN (?, ?)
            GROUP BY product_id
            ORDER BY sales_quantity DESC, product_id
        """.trimIndent()
    }
}
