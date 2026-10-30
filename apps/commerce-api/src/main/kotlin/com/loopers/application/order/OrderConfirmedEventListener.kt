package com.loopers.application.order

import com.loopers.domain.order.OrderConfirmedEvent
import com.loopers.domain.order.OrderEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class OrderConfirmedEventListener(
    private val orderEventPublisher: OrderEventPublisher,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(event: OrderConfirmedEvent) = orderEventPublisher.publish(event)
}
