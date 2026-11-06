package com.loopers.relay

import com.loopers.config.relay.RelayKafkaConfig
import com.loopers.domain.outbox.OutboxEvent
import com.loopers.domain.outbox.OutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class OutboxRelay(
    private val outboxEventRepository: OutboxEventRepository,
    @Qualifier(RelayKafkaConfig.RELAY_KAFKA_TEMPLATE)
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val properties: RelayProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun relayAll(): Int {
        var relayed = 0
        var lastId = 0L

        while (true) {
            val batch = outboxEventRepository.findPendingAfter(lastId, properties.batchSize)
            if (batch.isEmpty()) return relayed

            relayed += sendAll(batch)
            lastId = batch.last().id
        }
    }

    private fun sendAll(batch: List<OutboxEvent>): Int {
        val sending = batch.map { event -> event to runCatching { kafkaTemplate.send(event.topic, event.key, event.payload) } }

        return sending.count { (event, future) ->
            future
                .mapCatching { it.get(properties.sendTimeout.toMillis(), TimeUnit.MILLISECONDS) }
                .onSuccess { outboxEventRepository.markSent(event.eventId) }
                .onFailure { log(event, it) }
                .isSuccess
        }
    }

    private fun log(event: OutboxEvent, failure: Throwable) {
        log.error(
            "아웃박스 릴레이 실패. 다음 주기에 다시 보낸다. id={} topic={} type={} eventId={}",
            event.id,
            event.topic,
            event.eventType,
            event.eventId,
            failure,
        )
    }
}
