package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.ProductRankingPointer
import org.springframework.data.jpa.repository.JpaRepository

interface ProductRankingPointerJpaRepository : JpaRepository<ProductRankingPointer, Long> {
    fun findBySnapshotIdAndDeletedAtIsNull(snapshotId: String): ProductRankingPointer?
}
