package com.loopers.interfaces.api.ranking

import com.loopers.application.ranking.WeeklyRankingInfo
import com.loopers.application.ranking.WeeklyRankingStatus
import java.time.LocalDate
import java.time.ZonedDateTime

class RankingV1Dto {
    data class WeeklyResponse(
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
        )

        companion object {
            fun from(info: WeeklyRankingInfo): WeeklyResponse =
                WeeklyResponse(
                    status = info.status,
                    periodStart = info.periodStart,
                    periodEnd = info.periodEnd,
                    snapshotId = info.snapshotId,
                    inputFrozenAt = info.inputFrozenAt,
                    items = info.items.map { Item(productId = it.productId, score = it.score, ranking = it.ranking) },
                )
        }
    }
}
