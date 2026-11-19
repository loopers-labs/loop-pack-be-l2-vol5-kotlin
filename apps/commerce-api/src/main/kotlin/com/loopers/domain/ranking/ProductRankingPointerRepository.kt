package com.loopers.domain.ranking

interface ProductRankingPointerRepository {
    fun findBySnapshotId(snapshotId: String): ProductRankingPointer?
}
