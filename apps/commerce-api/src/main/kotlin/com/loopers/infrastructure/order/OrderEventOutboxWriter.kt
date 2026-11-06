package com.loopers.infrastructure.order

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.order.OrderConfirmedEvent
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.domain.outbox.OutboxEvent
import com.loopers.domain.outbox.OutboxEventRepository
import org.springframework.stereotype.Component

@Component
class OrderEventOutboxWriter(
    private val outboxEventRepository: OutboxEventRepository,
    private val properties: OrderEventProperties,
    private val objectMapper: ObjectMapper,
) : OrderEventOutbox {
    override fun append(event: OrderConfirmedEvent) {
        val stamped = event.copy(schemaVersion = properties.schemaVersion)

        outboxEventRepository.save(
            OutboxEvent(
                eventId = stamped.eventId,
                topic = properties.topic,
                eventType = stamped.eventType,
                schemaVersion = stamped.schemaVersion,
                payload = objectMapper.writeValueAsString(stamped),
                key = stamped.orderId.toString(),
            ),
        )
    }
}
