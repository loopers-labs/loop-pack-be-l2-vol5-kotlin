package com.loopers.infrastructure.outbox

import com.loopers.domain.outbox.OutboxEvent
import com.loopers.domain.outbox.OutboxEventRepository
import com.loopers.domain.outbox.OutboxStatus
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class OutboxEventRepositoryIntegrationTest @Autowired constructor(
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() {
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("저장하면 메시지를 다시 만드는 데 필요한 값이 그대로 남고, 생성 시각이 찍힌다.")
    @Test
    fun keepsMessageFieldsAndStampsCreatedAt() {
        val payload = """{"eventId":"order-confirmed:v1:700001","orderId":700001}"""
        val saved = outboxEventRepository.save(
            OutboxEvent(
                eventId = "order-confirmed:v1:700001",
                topic = "order-events",
                eventType = "OrderConfirmed",
                schemaVersion = 1,
                payload = payload,
                key = "700001",
            ),
        )

        val found = outboxEventJpaRepository.findById(saved.id).orElseThrow()

        assertAll(
            { assertThat(found.eventId).isEqualTo("order-confirmed:v1:700001") },
            { assertThat(found.topic).isEqualTo("order-events") },
            { assertThat(found.eventType).isEqualTo("OrderConfirmed") },
            { assertThat(found.schemaVersion).isEqualTo(1) },
            { assertThat(found.payload).isEqualTo(payload) },
            { assertThat(found.key).isEqualTo("700001") },
            { assertThat(found.status).isEqualTo(OutboxStatus.PENDING) },
            { assertThat(found.createdAt).isNotNull() },
        )
    }
}
