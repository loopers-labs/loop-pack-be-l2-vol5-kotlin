package com.loopers.interfaces.consumer.order

import com.loopers.config.kafka.KafkaConfig
import com.loopers.domain.purchase.PurchaseActivityCommand
import com.loopers.domain.purchase.PurchaseActivityService
import com.loopers.domain.purchase.PurchaseConflictException
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@Component
class PurchaseActivityConsumer(
    private val orderEventRecords: OrderEventRecords,
    private val purchaseActivityService: PurchaseActivityService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${order-event.topic}"],
        groupId = GROUP,
        containerFactory = KafkaConfig.RECORD_LISTENER,
    )
    fun consume(record: ConsumerRecord<String, ByteArray>, acknowledgment: Acknowledgment) {
        reflect(commandOf(orderEventRecords.confirmedOf(record)))

        acknowledgment.acknowledge()
    }

    private fun reflect(command: PurchaseActivityCommand) {
        try {
            purchaseActivityService.record(command)
        } catch (_: DataIntegrityViolationException) {
            if (purchaseActivityService.conflictsWith(command)) {
                throw PurchaseConflictException(command.eventId)
            }

            log.info("[{}] 이미 반영한 이벤트라 넘어간다. eventId={}", GROUP, command.eventId)
        }
    }

    private fun commandOf(event: OrderConfirmedEvent) = PurchaseActivityCommand(
        eventId = event.eventId,
        orderId = event.orderId,
        userId = event.userId,
        confirmedAt = confirmedAtOf(event.confirmedAt),
        items = event.items.map { PurchaseActivityCommand.Item(it.productId, it.quantity) },
    )

    private fun confirmedAtOf(raw: String): ZonedDateTime =
        ZonedDateTime.ofInstant(Instant.parse(raw), ZoneId.systemDefault())

    companion object {
        const val GROUP = "purchase-activity"
    }
}
