package com.loopers.infrastructure.product

import com.loopers.domain.commerce.EntityState
import com.loopers.domain.product.ProductSort

data class ProductEntity(
    val brandId: Long,
    val name: String,
    val price: Long,
    val stock: Int,
    val state: EntityState,
)

interface ProductEntityRepository {
    fun save(entity: ProductEntity): ProductEntity

    fun saveAll(entities: Collection<ProductEntity>)

    fun findActive(id: Long): ProductEntity?

    fun findAny(id: Long): ProductEntity?

    fun findActiveByIds(ids: Collection<Long>): List<ProductEntity>

    fun existsActiveByBrandId(brandId: Long): Boolean

    fun findActivePage(brandId: Long?, offset: Int, limit: Int, sort: ProductSort): List<ProductEntity>

    fun countActive(brandId: Long?): Long
}
