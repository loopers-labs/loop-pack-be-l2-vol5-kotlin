package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.ProductRanking
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.time.ZonedDateTime

interface ProductRankingJpaRepository : JpaRepository<ProductRanking, Long> {
    @Query("SELECT MIN(r.createdAt) FROM ProductRanking r WHERE r.periodStart = :periodStart AND r.deletedAt IS NULL")
    fun findFirstCreatedAt(periodStart: LocalDate): ZonedDateTime?

    fun findAllByPeriodStartAndDeletedAtIsNullOrderByRankingAscProductIdAsc(
        periodStart: LocalDate,
        pageable: Pageable,
    ): List<ProductRanking>
}
