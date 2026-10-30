package com.loopers.domain.product

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ProductStockService(
    private val productStockRepository: ProductStockRepository,
) {
    @Transactional
    fun sync(productId: Long, quantity: Int) = productStockRepository.upsert(productId, quantity)

    @Transactional(readOnly = true)
    fun find(productId: Long): ProductStock? = productStockRepository.findByProductId(productId)

    @Transactional(readOnly = true)
    fun findAll(productIds: List<Long>): List<ProductStock> =
        productStockRepository.findAllByProductIds(productIds)
}
