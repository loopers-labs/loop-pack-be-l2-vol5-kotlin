package com.loopers.interfaces.api.order

import com.loopers.application.order.OrderResult
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.order.PaymentResult
import java.time.ZonedDateTime

data class OrderItemResponse(val productId: Long, val quantity: Int, val unitPrice: Long, val lineAmount: Long)

data class OrderResponse(
    val id: Long,
    val userId: Long,
    val status: OrderStatus,
    val items: List<OrderItemResponse>,
    val totalAmount: Long,
    val paymentAmount: Long?,
    val paymentResult: PaymentResult?,
    val createdAt: ZonedDateTime,
    val confirmedAt: ZonedDateTime?,
) {
    companion object {
        fun from(result: OrderResult) = OrderResponse(
            result.id,
            result.userId,
            result.status,
            result.items.map { OrderItemResponse(it.productId, it.quantity, it.unitPrice, it.lineAmount) },
            result.totalAmount,
            result.paymentAmount,
            result.paymentResult,
            result.createdAt,
            result.confirmedAt,
        )
    }
}
