package com.loopers.interfaces.consumer.order

import com.loopers.domain.purchase.ProductPurchaseActivity
import com.loopers.domain.purchase.PurchaseActivityRepository
import com.loopers.utils.DatabaseCleanUp
import com.ninjasquad.springmockk.SpykBean
import io.mockk.verify
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import java.time.Instant

@SpringBootTest
class OrderEventConsumerGroupIntegrationTest @Autowired constructor(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val purchaseActivityRepository: PurchaseActivityRepository,
    private val registry: KafkaListenerEndpointRegistry,
    @Value("\${order-event.topic}") private val topic: String,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @SpykBean
    private lateinit var purchaseActivityConsumer: PurchaseActivityConsumer

    @SpykBean
    private lateinit var stockTrackingConsumer: StockTrackingConsumer

    @BeforeEach
    fun awaitAssignment() {
        val deadline = System.nanoTime() + ASSIGNMENT_TIMEOUT_NANOS
        val groups = setOf(PurchaseActivityConsumer.GROUP, StockTrackingConsumer.GROUP)

        while (System.nanoTime() < deadline) {
            val assigned = groups.associateWith { group ->
                registry.listenerContainers
                    .filter { it.groupId == group }
                    .sumOf { it.assignedPartitions?.size ?: 0 }
            }

            if (assigned.values.all { it >= PARTITIONS }) return

            Thread.sleep(POLL_MS)
        }

        throw IllegalStateException("컨슈머 그룹이 파티션을 모두 배정받지 못했다.")
    }

    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    private fun eventOf(orderId: Long, vararg items: Pair<Long, Int>) = OrderConfirmedEvent(
        eventId = "order-confirmed:v1:$orderId",
        orderId = orderId,
        userId = BUYER,
        confirmedAt = CONFIRMED_AT,
        items = items.map { OrderConfirmedEvent.Item(productId = it.first, quantity = it.second) },
        eventType = "OrderConfirmed",
        schemaVersion = SCHEMA_VERSION,
    )

    private fun activitiesOf(orderId: Long): List<ProductPurchaseActivity> {
        val deadline = System.nanoTime() + DELIVERY_TIMEOUT_NANOS

        while (System.nanoTime() < deadline) {
            val found = purchaseActivityRepository.findAllByOrderId(orderId)
            if (found.isNotEmpty()) return found

            Thread.sleep(POLL_MS)
        }

        return emptyList()
    }

    @DisplayName("한 토픽의 같은 메시지를 두 컨슈머 그룹이 각자 받는다.")
    @Test
    fun bothGroupsReceiveSameRecord() {
        val orderId = System.currentTimeMillis()
        val purchased = mutableListOf<ConsumerRecord<String, ByteArray>>()
        val tracked = mutableListOf<List<ConsumerRecord<String, ByteArray>>>()

        kafkaTemplate.send(topic, orderId.toString(), eventOf(orderId, 7L to 2)).get()

        verify(timeout = DELIVERY_TIMEOUT_MS, atLeast = 1) {
            purchaseActivityConsumer.consume(capture(purchased), any<Acknowledgment>())
        }
        verify(timeout = DELIVERY_TIMEOUT_MS, atLeast = 1) {
            stockTrackingConsumer.consume(capture(tracked), any<Acknowledgment>())
        }
        assertAll(
            { assertThat(purchased.map { it.key() }).contains(orderId.toString()) },
            { assertThat(tracked.flatten().map { it.key() }).contains(orderId.toString()) },
            { assertThat(purchased.map { it.topic() }.distinct()).containsExactly(topic) },
        )
    }

    @DisplayName("같은 이벤트를 두 번 받아도 구매 활동은 늘지 않는다.")
    @Test
    fun staysSameOnRedelivery() {
        val orderId = System.currentTimeMillis()
        val event = eventOf(orderId, 7L to 2, 8L to 1)

        kafkaTemplate.send(topic, orderId.toString(), event).get()
        assertThat(activitiesOf(orderId)).hasSize(2)

        kafkaTemplate.send(topic, orderId.toString(), event).get()
        Thread.sleep(REDELIVERY_SETTLE_MS)

        assertThat(purchaseActivityRepository.findAllByOrderId(orderId)).hasSize(2)
    }

    @DisplayName("주문 확정 이벤트를 받으면 품목마다 구매 활동 한 행을 남긴다.")
    @Test
    fun recordsOneRowPerItem() {
        val orderId = System.currentTimeMillis()

        kafkaTemplate.send(topic, orderId.toString(), eventOf(orderId, 7L to 2, 8L to 1)).get()

        val activities = activitiesOf(orderId)
        assertAll(
            { assertThat(activities).hasSize(2) },
            { assertThat(activities.map { it.productId }).containsExactly(7L, 8L) },
            { assertThat(activities.map { it.quantity }).containsExactly(2, 1) },
            { assertThat(activities).allMatch { it.orderId == orderId } },
            { assertThat(activities).allMatch { it.userId == BUYER } },
            { assertThat(activities).allMatch { it.confirmedAt.toInstant() == Instant.parse(CONFIRMED_AT) } },
        )
    }

    companion object {
        private const val BUYER = 42L
        private const val SCHEMA_VERSION = 1
        private const val PARTITIONS = 3
        private const val REDELIVERY_SETTLE_MS = 12_000L
        private const val CONFIRMED_AT = "2026-09-07T01:00:00Z"
        private const val POLL_MS = 200L
        private const val ASSIGNMENT_TIMEOUT_NANOS = 60_000_000_000L
        private const val DELIVERY_TIMEOUT_NANOS = 30_000_000_000L
        private const val DELIVERY_TIMEOUT_MS = 30_000L
    }
}
