package com.loopers.connection

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.order.OrderConfirmedEvent
import com.loopers.domain.order.OrderEventOutbox
import com.loopers.infrastructure.outbox.OutboxEventJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class OrderConfirmedOutboxConnectionTest @Autowired constructor(
    private val orderEventOutbox: OrderEventOutbox,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${order-event.topic}") private val topic: String,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    @DisplayName("연결 1구간: 주문 확정 이벤트를 아웃박스에 넣으면, payload 가 계약 JSON 과 같고 키는 주문 id 다.")
    @Test
    fun appendsContractPayload() {
        event(42L, "2026-08-18T10:00:00Z", 7L to 3, 8L to 1).let(orderEventOutbox::append)
        event(43L, "2026-08-19T10:00:00Z", 9L to 5).let(orderEventOutbox::append)
        event(44L, "2026-08-20T10:00:00Z", 7L to 2).let(orderEventOutbox::append)

        val rows = outboxEventJpaRepository.findAll().sortedBy { it.key.toLong() }

        assertThat(rows.map { it.key }).containsExactly("42", "43", "44")
        assertThat(rows.map { it.topic }.distinct()).containsExactly(topic)
        assertThat(rows.map { objectMapper.readTree(it.payload) }).containsExactlyElementsOf(contract())
    }

    private fun event(orderId: Long, confirmedAt: String, vararg items: Pair<Long, Int>) =
        OrderConfirmedEvent(
            eventId = OrderConfirmedEvent.idOf(orderId),
            orderId = orderId,
            userId = 1L,
            confirmedAt = confirmedAt,
            items = items.map { OrderConfirmedEvent.Item(productId = it.first, quantity = it.second) },
        )

    private fun contract() =
        requireNotNull(javaClass.getResource(CONTRACT)).readText().lines().filter { it.isNotBlank() }.map { objectMapper.readTree(it) }

    companion object {
        private const val CONTRACT = "/contract/order-confirmed-2026-08-17.jsonl"
    }
}
