package com.loopers.interfaces.consumer.order

import com.loopers.config.kafka.DeadLetterAlert
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.stereotype.Component

@Component
class PurchaseActivityDeadLetterAlert(
    private val orderEventRecords: OrderEventRecords,
) : DeadLetterAlert {
    override val groupId = PurchaseActivityConsumer.GROUP
    override val label = "구매활동"

    @Suppress("UNCHECKED_CAST")
    override fun describe(record: ConsumerRecord<*, *>): String {
        val event = orderEventRecords.confirmedOf(record as ConsumerRecord<String, ByteArray>)

        return "${event.eventType} · ${event.eventId} · orderId=${event.orderId}"
    }
}
