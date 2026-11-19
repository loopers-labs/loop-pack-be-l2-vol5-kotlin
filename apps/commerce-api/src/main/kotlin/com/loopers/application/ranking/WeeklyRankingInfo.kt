package com.loopers.application.ranking

import com.loopers.domain.ranking.ProductRanking
import java.time.LocalDate
import java.time.ZonedDateTime

enum class WeeklyRankingStatus {
    PUBLISHED,
    IN_PROGRESS,
    NOT_PUBLISHED,
}

data class WeeklyRankingInfo(
    val status: WeeklyRankingStatus,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val snapshotId: String?,
    val inputFrozenAt: ZonedDateTime?,
    val items: List<Item>,
) {
    data class Item(
        val productId: Long,
        val score: Long,
        val ranking: Int,
    ) {
        companion object {
            fun from(ranking: ProductRanking): Item =
                Item(productId = ranking.productId, score = ranking.score, ranking = ranking.ranking)
        }
    }

    companion object {
        fun notPublished(periodStart: LocalDate): WeeklyRankingInfo =
            WeeklyRankingInfo(
                status = WeeklyRankingStatus.NOT_PUBLISHED,
                periodStart = periodStart,
                periodEnd = periodEndOf(periodStart),
                snapshotId = null,
                inputFrozenAt = null,
                items = emptyList(),
            )

        fun of(
            status: WeeklyRankingStatus,
            periodStart: LocalDate,
            snapshotId: String,
            inputFrozenAt: ZonedDateTime?,
            items: List<Item>,
        ): WeeklyRankingInfo =
            WeeklyRankingInfo(
                status = status,
                periodStart = periodStart,
                periodEnd = periodEndOf(periodStart),
                snapshotId = snapshotId,
                inputFrozenAt = inputFrozenAt,
                items = items,
            )

        private fun periodEndOf(periodStart: LocalDate): LocalDate = periodStart.plusDays(6)
    }
}
