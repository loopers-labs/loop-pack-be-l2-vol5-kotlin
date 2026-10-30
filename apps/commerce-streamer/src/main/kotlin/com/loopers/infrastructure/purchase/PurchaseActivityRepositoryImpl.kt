package com.loopers.infrastructure.purchase

import com.loopers.domain.purchase.ProductPurchaseActivity
import com.loopers.domain.purchase.PurchaseActivityRepository
import org.springframework.stereotype.Component

@Component
class PurchaseActivityRepositoryImpl(
    private val purchaseActivityJpaRepository: PurchaseActivityJpaRepository,
) : PurchaseActivityRepository {
    override fun saveAll(activities: List<ProductPurchaseActivity>): List<ProductPurchaseActivity> =
        purchaseActivityJpaRepository.saveAllAndFlush(activities)

    override fun findAllByOrderId(orderId: Long): List<ProductPurchaseActivity> =
        purchaseActivityJpaRepository.findAllByOrderIdOrderByIdAsc(orderId)
}
