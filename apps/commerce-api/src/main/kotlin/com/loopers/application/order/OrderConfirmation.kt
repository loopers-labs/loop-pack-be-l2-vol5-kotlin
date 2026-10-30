package com.loopers.application.order

import com.loopers.application.payment.PaymentProcessor
import com.loopers.application.payment.PaymentSettlement
import com.loopers.domain.order.OrderService
import com.loopers.domain.payment.CardInfo
import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PaymentCommand
import com.loopers.domain.payment.PaymentMethod
import com.loopers.domain.payment.PaymentRepository
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.product.StockReservationCommand
import com.loopers.domain.product.StockReservationService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OrderConfirmation(
    private val orderService: OrderService,
    private val paymentRepository: PaymentRepository,
    private val paymentProcessor: PaymentProcessor,
    private val paymentSettlement: PaymentSettlement,
    private val stockReservationService: StockReservationService,
) {
    @Transactional
    fun start(userId: Long, orderId: Long, method: PaymentMethod, card: CardInfo?): Started {
        val order = orderService.get(userId, orderId)
        order.requireDraft()

        val command = PaymentCommand(
            userId = userId,
            orderId = order.id,
            amount = order.totalAmount,
            method = method,
            card = card,
        )
        paymentProcessor.require(command)

        stockReservationService.reserve(
            order.id,
            order.lines.map { StockReservationCommand(productId = it.productId, quantity = it.quantity) },
        )

        val payment = paymentRepository.save(Payment(userId = userId, orderId = order.id, amount = order.totalAmount))
        val info = OrderInfo.from(orderService.confirm(order))

        if (!paymentProcessor.isExternal(method)) {
            paymentSettlement.reflect(payment, paymentProcessor.pay(command).status)
        }

        return Started(info = info, paymentId = payment.id, command = command)
    }

    @Transactional
    fun settle(paymentId: Long, status: PaymentStatus) {
        paymentRepository.find(paymentId)?.let { paymentSettlement.reflect(it, status) }
    }

    data class Started(
        val info: OrderInfo,
        val paymentId: Long,
        val command: PaymentCommand,
    )
}
