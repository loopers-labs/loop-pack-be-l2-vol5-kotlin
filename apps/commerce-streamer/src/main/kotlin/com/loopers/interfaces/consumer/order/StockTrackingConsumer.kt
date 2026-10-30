package com.loopers.interfaces.consumer.order

import com.loopers.config.kafka.KafkaConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

@Component
class StockTrackingConsumer(
    private val orderEventRecords: OrderEventRecords,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${order-event.topic}"],
        groupId = GROUP,
        containerFactory = KafkaConfig.BATCH_LISTENER,
    )
    fun consume(records: List<ConsumerRecord<String, ByteArray>>, acknowledgment: Acknowledgment) {
        orderEventRecords.confirmedOf(records)
            .forEach { log.info("[{}] orderId={} items={}", GROUP, it.orderId, it.items) }

        acknowledgment.acknowledge()
    }

    companion object {
        const val GROUP = "stock-tracking"
    }
}
