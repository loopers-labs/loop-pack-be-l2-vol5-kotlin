package com.loopers.domain.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class OutboxEventTest {
    @DisplayName("아웃박스 이벤트는 PENDING 으로 시작한다.")
    @Test
    fun startsAsPending() {
        val event = OutboxEvent(
            eventId = "order-confirmed:v1:1",
            topic = "order-events",
            eventType = "OrderConfirmed",
            schemaVersion = 1,
            payload = "{}",
            key = "1",
        )

        assertThat(event.status).isEqualTo(OutboxStatus.PENDING)
    }
}
