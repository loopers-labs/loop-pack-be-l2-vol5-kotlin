package com.loopers.domain.brand

interface BrandRepository {
    fun save(brand: Brand): Brand

    fun findActive(id: Long): Brand?

    fun findActiveByIds(ids: Collection<Long>): List<Brand>

    fun findActivePage(offset: Int, limit: Int): List<Brand>

    fun countActive(): Long
}
