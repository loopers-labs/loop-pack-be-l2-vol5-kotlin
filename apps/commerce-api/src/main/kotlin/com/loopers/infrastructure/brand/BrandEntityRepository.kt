package com.loopers.infrastructure.brand

import com.loopers.domain.commerce.EntityState

data class BrandEntity(val name: String, val state: EntityState)

interface BrandEntityRepository {
    fun save(entity: BrandEntity): BrandEntity

    fun findActive(id: Long): BrandEntity?

    fun findActiveByIds(ids: Collection<Long>): List<BrandEntity>

    fun findActivePage(offset: Int, limit: Int): List<BrandEntity>

    fun countActive(): Long
}
