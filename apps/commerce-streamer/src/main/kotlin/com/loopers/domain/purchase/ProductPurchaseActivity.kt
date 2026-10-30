package com.loopers.domain.purchase

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.ZonedDateTime

@Entity
@Table(
    name = "product_purchase_activity",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_purchase_activity_order_product",
            columnNames = ["order_id", "product_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_purchase_activity_order", columnList = "order_id"),
        Index(name = "idx_purchase_activity_user", columnList = "user_id"),
        Index(name = "idx_purchase_activity_product", columnList = "product_id"),
    ],
)
class ProductPurchaseActivity(
    userId: Long,
    orderId: Long,
    productId: Long,
    quantity: Int,
    confirmedAt: ZonedDateTime,
) : BaseEntity() {
    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: Long = userId
        protected set

    @Column(name = "order_id", nullable = false, updatable = false)
    var orderId: Long = orderId
        protected set

    @Column(name = "product_id", nullable = false, updatable = false)
    var productId: Long = productId
        protected set

    @Column(name = "quantity", nullable = false, updatable = false)
    var quantity: Int = quantity
        protected set

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    var confirmedAt: ZonedDateTime = confirmedAt
        protected set

    init {
        require(quantity > 0) { "구매 수량은 1 이상이어야 합니다. [quantity = $quantity]" }
    }
}
