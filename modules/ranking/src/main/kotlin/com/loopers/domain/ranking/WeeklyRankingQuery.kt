package com.loopers.domain.ranking

import java.time.Instant
import java.time.LocalDate

data class WeeklyRankingView(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val status: String,
    val snapshotId: String?,
    val frozenAt: Instant?,
    val items: List<WeeklyRankingItem>,
)

data class WeeklyRankingItem(val productId: Long, val name: String, val score: Long, val rank: Long)

interface WeeklyRankingQuery {
    fun find(period: RankingPeriod, limit: Int): WeeklyRankingView
}
