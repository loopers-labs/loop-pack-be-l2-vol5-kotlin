package com.loopers.infrastructure.purchase

import com.loopers.domain.purchase.ProductPurchaseActivity
import org.springframework.data.jpa.repository.JpaRepository

interface PurchaseActivityJpaRepository : JpaRepository<ProductPurchaseActivity, Long> {
    fun findAllByOrderIdOrderByIdAsc(orderId: Long): List<ProductPurchaseActivity>
}
