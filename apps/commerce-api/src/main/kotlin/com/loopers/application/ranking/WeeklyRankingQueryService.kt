package com.loopers.application.ranking

import com.loopers.domain.ranking.RankingPeriod
import com.loopers.domain.ranking.WeeklyRankingQuery
import org.springframework.stereotype.Service
import java.time.LocalDate

@Service
class WeeklyRankingQueryService(private val query: WeeklyRankingQuery) {
    fun find(periodStart: LocalDate, limit: Int) = query.find(RankingPeriod(periodStart), limit.also { require(it in 1..100) })
}
