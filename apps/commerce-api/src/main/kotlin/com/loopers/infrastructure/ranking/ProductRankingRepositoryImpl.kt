package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.ProductRanking
import com.loopers.domain.ranking.ProductRankingRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZonedDateTime

@Component
class ProductRankingRepositoryImpl(
    private val productRankingJpaRepository: ProductRankingJpaRepository,
) : ProductRankingRepository {
    override fun findFirstWrittenAt(periodStart: LocalDate): ZonedDateTime? =
        productRankingJpaRepository.findFirstCreatedAt(periodStart)

    override fun findPage(periodStart: LocalDate, page: Int, size: Int): List<ProductRanking> =
        productRankingJpaRepository.findAllByPeriodStartAndDeletedAtIsNullOrderByRankingAscProductIdAsc(
            periodStart,
            PageRequest.of(page, size),
        )
}
