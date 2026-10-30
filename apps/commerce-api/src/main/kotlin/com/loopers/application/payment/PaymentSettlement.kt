package com.loopers.application.payment

import com.loopers.domain.order.OrderConfirmedEvent
import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.product.StockReservation
import com.loopers.domain.product.StockReservationService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class PaymentSettlement(
    private val stockReservationService: StockReservationService,
    private val events: ApplicationEventPublisher,
) {
    fun reflect(payment: Payment, status: PaymentStatus) {
        when (status) {
            PaymentStatus.SUCCESS -> succeed(payment)
            PaymentStatus.FAILED -> decline(payment)
            PaymentStatus.PENDING -> Unit
        }
    }

    private fun succeed(payment: Payment) {
        payment.markSuccess()
        val reserved = stockReservationService.commit(payment.orderId)

        events.publishEvent(confirmedEvent(payment, reserved))
    }

    private fun decline(payment: Payment) {
        payment.markFailed()
        stockReservationService.release(payment.orderId)
    }

    private fun confirmedEvent(payment: Payment, reserved: List<StockReservation>) = OrderConfirmedEvent(
        eventId = OrderConfirmedEvent.idOf(payment.orderId),
        orderId = payment.orderId,
        userId = payment.userId,
        confirmedAt = requireNotNull(payment.succeededAt) { "성공 시각 없이 확정 이벤트를 만들 수 없습니다." }
            .toInstant()
            .toString(),
        items = reserved.map { OrderConfirmedEvent.Item(productId = it.productId, quantity = it.quantity) },
    )
}
