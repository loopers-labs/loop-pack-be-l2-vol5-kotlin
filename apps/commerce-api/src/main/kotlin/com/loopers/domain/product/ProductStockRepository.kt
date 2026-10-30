package com.loopers.domain.product

interface ProductStockRepository {
    fun upsert(productId: Long, quantity: Int)

    fun findByProductId(productId: Long): ProductStock?

    fun findAllByProductIds(productIds: List<Long>): List<ProductStock>
}
