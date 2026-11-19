package com.loopers.domain.ranking

import java.time.LocalDate
import java.time.ZonedDateTime

interface ProductRankingRepository {
    fun findFirstWrittenAt(periodStart: LocalDate): ZonedDateTime?
    fun findPage(periodStart: LocalDate, page: Int, size: Int): List<ProductRanking>
}
