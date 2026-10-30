package com.loopers.domain.purchase

interface PurchaseActivityRepository {
    fun saveAll(activities: List<ProductPurchaseActivity>): List<ProductPurchaseActivity>

    fun findAllByOrderId(orderId: Long): List<ProductPurchaseActivity>
}
