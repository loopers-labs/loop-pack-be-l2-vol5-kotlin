package com.loopers.infrastructure.ranking

import com.loopers.domain.ranking.ProductRankingPointer
import com.loopers.domain.ranking.ProductRankingPointerRepository
import org.springframework.stereotype.Component

@Component
class ProductRankingPointerRepositoryImpl(
    private val productRankingPointerJpaRepository: ProductRankingPointerJpaRepository,
) : ProductRankingPointerRepository {
    override fun findBySnapshotId(snapshotId: String): ProductRankingPointer? =
        productRankingPointerJpaRepository.findBySnapshotIdAndDeletedAtIsNull(snapshotId)
}
