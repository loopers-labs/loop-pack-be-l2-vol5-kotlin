package com.loopers.relay

import com.loopers.domain.outbox.OutboxEvent
import com.loopers.domain.outbox.OutboxEventRepository
import com.loopers.domain.outbox.OutboxStatus
import com.loopers.infrastructure.outbox.OutboxEventJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.kafka.producer.bootstrap-servers=localhost:1",
        "spring.kafka.producer.properties.max.block.ms=500",
        "spring.kafka.producer.properties.request.timeout.ms=500",
        "spring.kafka.producer.properties.delivery.timeout.ms=1000",
    ],
)
class OutboxRelayUnreachableIntegrationTest @Autowired constructor(
    private val outboxRelay: OutboxRelay,
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    @DisplayName("브로커에 닿지 못해도 멈추지 않고 한 바퀴를 끝내며, 행은 다음 주기를 위해 PENDING 으로 남는다.")
    @Test
    fun keepsPendingWithoutAck() {
        outboxEventRepository.save(
            OutboxEvent(
                eventId = "order-confirmed:v1:1",
                topic = "order.events.relay-unreachable",
                eventType = "OrderConfirmed",
                schemaVersion = 1,
                payload = """{"eventId":"order-confirmed:v1:1","orderId":1}""",
                key = "1",
            ),
        )

        val relayed = outboxRelay.relayAll()

        assertAll(
            { assertThat(relayed).isZero() },
            { assertThat(outboxEventJpaRepository.findAll().single().status).isEqualTo(OutboxStatus.PENDING) },
        )
    }
}
