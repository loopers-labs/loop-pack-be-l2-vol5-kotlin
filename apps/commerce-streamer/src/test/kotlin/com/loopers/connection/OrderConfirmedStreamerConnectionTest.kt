package com.loopers.connection

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.purchase.PurchaseActivityRepository
import com.loopers.interfaces.consumer.order.PurchaseActivityConsumer
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import java.time.Instant

@SpringBootTest
class OrderConfirmedStreamerConnectionTest @Autowired constructor(
    private val kafkaTemplate: KafkaTemplate<Any, Any>,
    private val purchaseActivityRepository: PurchaseActivityRepository,
    private val registry: KafkaListenerEndpointRegistry,
    private val objectMapper: ObjectMapper,
    @Value("\${order-event.topic}") private val topic: String,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @BeforeEach
    fun awaitAssignment() {
        val deadline = System.nanoTime() + ASSIGNMENT_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val assigned = registry.listenerContainers
                .filter { it.groupId == PurchaseActivityConsumer.GROUP }
                .sumOf { it.assignedPartitions?.size ?: 0 }
            if (assigned >= PARTITIONS) return
            Thread.sleep(POLL_MS)
        }
        throw IllegalStateException("구매 활동 컨슈머가 파티션을 모두 배정받지 못했다.")
    }

    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    @DisplayName("연결 3구간: 계약 JSON 을 받으면 품목마다 구매 활동이 남고, 같은 이벤트를 다시 받아도 늘지 않는다.")
    @Test
    fun recordsContractActivitiesOnce() {
        publishContract()

        assertThat(awaitActivities(expected = 4)).containsExactlyInAnyOrderElementsOf(EXPECTED_ACTIVITIES)

        publishContract()
        Thread.sleep(REDELIVERY_SETTLE_MS)

        assertThat(activities()).containsExactlyInAnyOrderElementsOf(EXPECTED_ACTIVITIES)
    }

    private fun publishContract() {
        requireNotNull(javaClass.getResource(CONTRACT)).readText().lines().filter { it.isNotBlank() }.forEach { line ->
            val event = objectMapper.readTree(line)
            kafkaTemplate.send(topic, event.get("orderId").asText(), event).get()
        }
    }

    private fun activities(): List<Activity> =
        listOf(42L, 43L, 44L).flatMap { orderId ->
            purchaseActivityRepository.findAllByOrderId(orderId).map {
                Activity(it.orderId, it.productId, it.quantity, it.userId, it.confirmedAt.toInstant())
            }
        }

    private fun awaitActivities(expected: Int): List<Activity> {
        val deadline = System.nanoTime() + DELIVERY_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val found = activities()
            if (found.size >= expected) return found
            Thread.sleep(POLL_MS)
        }
        return activities()
    }

    private data class Activity(
        val orderId: Long,
        val productId: Long,
        val quantity: Int,
        val userId: Long,
        val confirmedAt: Instant,
    )

    companion object {
        private const val CONTRACT = "/contract/order-confirmed-2026-08-17.jsonl"
        private const val PARTITIONS = 3
        private const val POLL_MS = 200L
        private const val REDELIVERY_SETTLE_MS = 12_000L
        private const val ASSIGNMENT_TIMEOUT_NANOS = 60_000_000_000L
        private const val DELIVERY_TIMEOUT_NANOS = 30_000_000_000L

        private val EXPECTED_ACTIVITIES = listOf(
            Activity(42L, 7L, 3, 1L, Instant.parse("2026-08-18T10:00:00Z")),
            Activity(42L, 8L, 1, 1L, Instant.parse("2026-08-18T10:00:00Z")),
            Activity(43L, 9L, 5, 1L, Instant.parse("2026-08-19T10:00:00Z")),
            Activity(44L, 7L, 2, 1L, Instant.parse("2026-08-20T10:00:00Z")),
        )
    }
}
