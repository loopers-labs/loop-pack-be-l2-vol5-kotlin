package com.loopers.infrastructure.order

import com.loopers.domain.order.OrderConfirmedEvent
import com.loopers.domain.order.OrderEventPublisher
import com.loopers.domain.outbox.OutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
class OrderEventKafkaPublisher(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val properties: OrderEventProperties,
    private val outboxEventRepository: OutboxEventRepository,
) : OrderEventPublisher {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(event: OrderConfirmedEvent) {
        try {
            kafkaTemplate.send(properties.topic, event.orderId.toString(), stamped(event))
                .whenComplete { _, failure ->
                    if (failure == null) {
                        outboxEventRepository.markSent(event.eventId)
                    } else {
                        log(event, failure)
                    }
                }
        } catch (blocked: Exception) {
            log(event, blocked)
        }
    }

    private fun stamped(event: OrderConfirmedEvent) = event.copy(schemaVersion = properties.schemaVersion)

    private fun log(event: OrderConfirmedEvent, failure: Throwable) {
        log.error(
            "주문 이벤트 발행 실패. topic={} type={} orderId={}",
            properties.topic,
            event.eventType,
            event.orderId,
            failure,
        )
    }
}
