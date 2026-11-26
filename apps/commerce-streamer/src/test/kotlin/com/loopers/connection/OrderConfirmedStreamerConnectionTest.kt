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

    @DisplayName("사고 연결 3구간: 정상 10건과 복구한 장애 10건을 받으면 주문마다 구매 활동이 한 번씩 20건 남고, 다시 받아도 늘지 않는다.")
    @Test
    fun recordsIncidentActivitiesOnce() {
        publishContract(INCIDENT_CONTRACT)

        assertThat(awaitActivities(expected = 20, orderIds = INCIDENT_ORDER_IDS)).containsExactlyInAnyOrderElementsOf(INCIDENT_ACTIVITIES)

        publishContract(INCIDENT_CONTRACT)
        Thread.sleep(REDELIVERY_SETTLE_MS)

        assertThat(activities(INCIDENT_ORDER_IDS)).containsExactlyInAnyOrderElementsOf(INCIDENT_ACTIVITIES)
    }

    private fun publishContract(path: String = CONTRACT) {
        requireNotNull(javaClass.getResource(path)).readText().lines().filter { it.isNotBlank() }.forEach { line ->
            val event = objectMapper.readTree(line)
            kafkaTemplate.send(topic, event.get("orderId").asText(), event).get()
        }
    }

    private fun activities(orderIds: List<Long> = listOf(42L, 43L, 44L)): List<Activity> =
        orderIds.flatMap { orderId ->
            purchaseActivityRepository.findAllByOrderId(orderId).map {
                Activity(it.orderId, it.productId, it.quantity, it.userId, it.confirmedAt.toInstant())
            }
        }

    private fun awaitActivities(expected: Int, orderIds: List<Long> = listOf(42L, 43L, 44L)): List<Activity> {
        val deadline = System.nanoTime() + DELIVERY_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val found = activities(orderIds)
            if (found.size >= expected) return found
            Thread.sleep(POLL_MS)
        }
        return activities(orderIds)
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
        private const val INCIDENT_CONTRACT = "/contract/order-confirmed-incident-2026-08-17.jsonl"
        private const val INCIDENT_PRODUCT_ID = 1L
        private val INCIDENT_ORDER_IDS = (1L..20L).toList()
        private val RECOVERED_ORDER_IDS = setOf(12L, 15L, 18L)
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

        private val INCIDENT_ACTIVITIES = INCIDENT_ORDER_IDS.map { orderId ->
            when {
                orderId <= 10L -> Activity(orderId, INCIDENT_PRODUCT_ID, 1, 1_000L + orderId, Instant.parse("2026-08-18T10:00:00Z"))
                orderId in RECOVERED_ORDER_IDS -> Activity(orderId, INCIDENT_PRODUCT_ID, 1, 2_990L + orderId, Instant.parse("2026-08-20T10:00:00Z"))
                else -> Activity(orderId, INCIDENT_PRODUCT_ID, 1, 2_990L + orderId, Instant.parse("2026-08-19T10:00:00Z"))
            }
        }
    }
}
