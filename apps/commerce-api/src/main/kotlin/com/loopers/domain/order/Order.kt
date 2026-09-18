package com.loopers.domain.order

import com.loopers.domain.commerce.CommerceException
import com.loopers.domain.commerce.CommerceFailure
import com.loopers.domain.commerce.EntityState
import com.loopers.domain.product.Money
import java.time.ZonedDateTime

enum class OrderStatus { DRAFT, CONFIRMED }

enum class PaymentResult { SUCCESS }

data class OrderLine(val productId: Long, val quantity: Int, val unitPrice: Long, val id: Long = 0)

class Order private constructor(
    val userId: Long,
    lines: List<OrderLine>,
    state: EntityState,
    initialStatus: OrderStatus,
    initialPaymentAmount: Long?,
    initialPaymentResult: PaymentResult?,
    initialConfirmedAt: ZonedDateTime?,
) {
    constructor(userId: Long, lines: List<OrderLine>) :
        this(userId, lines, EntityState(), OrderStatus.DRAFT, null, null, null)

    val id: Long = state.id
    val createdAt: ZonedDateTime = state.createdAt
    val updatedAt: ZonedDateTime = state.updatedAt

    var status: OrderStatus = initialStatus
        private set

    private val orderItems: List<OrderItem> = lines.sortedBy { it.productId }.map(::OrderItem)

    val items: List<OrderItem>
        get() = orderItems.toList()

    val totalAmount: Money = orderItems.fold(Money(0)) { total, item -> total.add(item.lineAmount) }

    var paymentAmount: Money? = initialPaymentAmount?.let(::Money)
        private set

    var paymentResult: PaymentResult? = initialPaymentResult
        private set

    var confirmedAt: ZonedDateTime? = initialConfirmedAt
        private set

    init {
        if (userId <= 0 || lines.isEmpty()) throw CommerceException(CommerceFailure.INVALID_REQUEST)
        if (lines.map { it.productId }.toSet().size != lines.size) throw CommerceException(CommerceFailure.INVALID_REQUEST)
    }

    fun assertDraft() {
        if (status != OrderStatus.DRAFT) throw CommerceException(CommerceFailure.ORDER_ALREADY_CONFIRMED)
    }

    fun confirm() {
        assertDraft()
        paymentAmount = totalAmount
        paymentResult = PaymentResult.SUCCESS
        confirmedAt = ZonedDateTime.now()
        status = OrderStatus.CONFIRMED
    }

    companion object {
        fun reconstitute(
            userId: Long,
            lines: List<OrderLine>,
            state: EntityState,
            status: OrderStatus,
            paymentAmount: Long?,
            paymentResult: PaymentResult?,
            confirmedAt: ZonedDateTime?,
        ): Order = Order(userId, lines, state, status, paymentAmount, paymentResult, confirmedAt)
    }
}
