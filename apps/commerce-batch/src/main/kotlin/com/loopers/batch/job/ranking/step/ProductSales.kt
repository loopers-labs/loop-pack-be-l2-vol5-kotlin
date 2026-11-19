package com.loopers.batch.job.ranking.step

import java.time.LocalDate

data class ProductSales(
    val snapshotId: String,
    val periodStart: LocalDate,
    val productId: Long,
    val salesQuantity: Long,
    val ranking: Int,
)
