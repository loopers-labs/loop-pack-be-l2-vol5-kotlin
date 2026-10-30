package com.loopers.interfaces.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.stereotype.Component

@Component
class OrderEventRecords(
    private val objectMapper: ObjectMapper,
) {
    fun confirmedOf(record: ConsumerRecord<String, ByteArray>): OrderConfirmedEvent =
        objectMapper.readValue(record.value(), OrderConfirmedEvent::class.java)

    fun confirmedOf(records: List<ConsumerRecord<String, ByteArray>>): List<OrderConfirmedEvent> =
        records.map { confirmedOf(it) }
}
