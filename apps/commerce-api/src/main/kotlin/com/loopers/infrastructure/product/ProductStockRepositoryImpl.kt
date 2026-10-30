package com.loopers.infrastructure.product

import com.loopers.domain.product.ProductStock
import com.loopers.domain.product.ProductStockRepository
import org.springframework.stereotype.Component

@Component
class ProductStockRepositoryImpl(
    private val productStockJpaRepository: ProductStockJpaRepository,
) : ProductStockRepository {
    override fun upsert(productId: Long, quantity: Int) =
        productStockJpaRepository.upsert(productId, quantity)

    override fun findByProductId(productId: Long): ProductStock? =
        productStockJpaRepository.findByProductId(productId)

    override fun findAllByProductIds(productIds: List<Long>): List<ProductStock> =
        if (productIds.isEmpty()) emptyList() else productStockJpaRepository.findAllByProductIdIn(productIds)
}
