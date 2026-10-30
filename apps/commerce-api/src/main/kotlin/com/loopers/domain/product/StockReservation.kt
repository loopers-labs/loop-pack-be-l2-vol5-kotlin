package com.loopers.domain.product

import com.loopers.domain.BaseEntity
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "stock_reservation",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_stock_reservation_order_product",
            columnNames = ["order_id", "product_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_stock_reservation_order", columnList = "order_id"),
    ],
)
class StockReservation(
    orderId: Long,
    productId: Long,
    quantity: Int,
) : BaseEntity() {
    @Column(name = "order_id", nullable = false, updatable = false)
    var orderId: Long = orderId
        protected set

    @Column(name = "product_id", nullable = false, updatable = false)
    var productId: Long = productId
        protected set

    @Column(name = "quantity", nullable = false, updatable = false)
    var quantity: Int = quantity
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: StockReservationStatus = StockReservationStatus.RESERVED
        protected set

    init {
        if (quantity <= 0) throw CoreException(ErrorType.BAD_REQUEST, "예약 수량은 1 이상이어야 합니다.")
    }

    fun commit() {
        if (status == StockReservationStatus.COMMITTED) return
        if (status == StockReservationStatus.RELEASED) {
            throw CoreException(ErrorType.CONFLICT, "이미 해제된 예약은 확정할 수 없습니다.")
        }

        status = StockReservationStatus.COMMITTED
    }

    fun release(): Boolean {
        if (status == StockReservationStatus.RELEASED) return false

        status = StockReservationStatus.RELEASED

        return true
    }
}
