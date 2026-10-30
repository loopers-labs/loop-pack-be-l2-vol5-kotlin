package com.loopers.interfaces.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.config.jackson.JacksonConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

class OrderEventRecordsTest {
    private val objectMapper = Jackson2ObjectMapperBuilder.json()
        .also { JacksonConfig().jacksonCustomizer().customize(it) }
        .build<ObjectMapper>()

    private val orderEventRecords = OrderEventRecords(objectMapper)

    private fun recordOf(json: String) =
        ConsumerRecord("order.events.v1", 0, 0L, "777001", json.toByteArray())

    @DisplayName("모든 필드가 있으면 읽는다.")
    @Test
    fun readsFullEvent() {
        val event = orderEventRecords.confirmedOf(recordOf(FULL))

        assertAll(
            { assertThat(event.orderId).isEqualTo(777001L) },
            { assertThat(event.items).hasSize(1) },
            { assertThat(event.schemaVersion).isEqualTo(1) },
        )
    }

    @DisplayName("items 가 없으면 빈 구매로 읽지 않고 거부한다.")
    @Test
    fun refusesMissingItems() {
        val missing = FULL.replace(""""items":[{"productId":7,"quantity":5}],""", "")

        assertThatThrownBy { orderEventRecords.confirmedOf(recordOf(missing)) }
            .hasMessageContaining("items")
    }

    @DisplayName("orderId 가 없으면 0 번 주문으로 읽지 않고 거부한다.")
    @Test
    fun refusesMissingOrderId() {
        val missing = FULL.replace(""""orderId":777001,""", "")

        assertThatThrownBy { orderEventRecords.confirmedOf(recordOf(missing)) }
            .hasMessageContaining("orderId")
    }

    @DisplayName("schemaVersion 이 없으면 0 으로 읽지 않고 거부한다.")
    @Test
    fun refusesMissingSchemaVersion() {
        val missing = FULL.replace(""","schemaVersion":1""", "")

        assertThatThrownBy { orderEventRecords.confirmedOf(recordOf(missing)) }
            .hasMessageContaining("schemaVersion")
    }

    @DisplayName("모르는 필드는 그냥 버린다.")
    @Test
    fun ignoresUnknownField() {
        val extra = FULL.replace("""{"eventId\"""", """{"couponId":555,"eventId\"""")

        assertThat(orderEventRecords.confirmedOf(recordOf(extra)).orderId).isEqualTo(777001L)
    }

    companion object {
        private const val FULL = """{"eventId":"order-confirmed:v1:777001","orderId":777001,""" +
            """"userId":42,"confirmedAt":"2026-10-29T05:00:00Z",""" +
            """"items":[{"productId":7,"quantity":5}],""" +
            """"eventType":"OrderConfirmed","schemaVersion":1}"""
    }
}
