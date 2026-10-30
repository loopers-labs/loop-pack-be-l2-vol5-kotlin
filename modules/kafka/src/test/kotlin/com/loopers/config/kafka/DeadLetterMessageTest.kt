package com.loopers.config.kafka

import com.fasterxml.jackson.core.JsonParseException
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.kafka.listener.ListenerExecutionFailedException
import java.sql.SQLSyntaxErrorException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Optional

class DeadLetterMessageTest {
    private val record = ConsumerRecord(
        TOPIC, 1, 48213L, PUBLISHED_AT, TimestampType.CREATE_TIME, -1, -1, KEY, "value",
        RecordHeaders(), Optional.empty<Int>(),
    )

    private val purchaseActivity = object : DeadLetterAlert {
        override val groupId = GROUP
        override val label = "구매활동"
        override fun describe(record: ConsumerRecord<*, *>) =
            "OrderConfirmed · order-confirmed:v1:777001 · orderId=777001"
    }

    private fun messageOf(cause: Throwable, alert: DeadLetterAlert? = purchaseActivity) = deadLetterMessage(
        record = record,
        exception = ListenerExecutionFailedException("리스너 실패", GROUP, cause),
        group = GROUP,
        alert = alert,
        landed = LANDED,
        now = Instant.ofEpochMilli(PUBLISHED_AT + 7_000),
    )

    @DisplayName("저장이 깨지면 저장하지 못했다고 말하고, 이벤트를 읽어서 보여준다.")
    @Test
    fun namesTheStoreStage() {
        val message = messageOf(SQLSyntaxErrorException("Unknown column 'quantity' in 'field list'"))

        assertAll(
            { assertThat(message).startsWith("'구매활동' 도중 저장하지 못해 DLT로 격리하였다.") },
            { assertThat(message).contains("이벤트  OrderConfirmed · order-confirmed:v1:777001 · orderId=777001") },
            { assertThat(message).contains("원인    SQLSyntaxErrorException: Unknown column 'quantity' in 'field list'") },
            { assertThat(message).contains("원본    $TOPIC-1@48213") },
            { assertThat(message).contains("DLT     $LANDED") },
            { assertThat(message).contains("그룹    $GROUP") },
        )
    }

    @DisplayName("파싱이 깨지면 해석하지 못했다고 말한다.")
    @Test
    fun namesTheParseStage() {
        val message = messageOf(DateTimeParseException("Text '썩은시각' could not be parsed", "썩은시각", 0))

        assertThat(message).startsWith("'구매활동' 도중 이벤트를 해석하지 못해 DLT로 격리하였다.")
    }

    @DisplayName("분류할 수 없는 원인은 처리하지 못했다고만 말한다.")
    @Test
    fun fallsBackToGenericStage() {
        val message = messageOf(IllegalStateException("무슨 일인지 모른다"))

        assertThat(message).startsWith("'구매활동' 도중 처리하지 못해 DLT로 격리하였다.")
    }

    @DisplayName("이벤트를 읽을 수 없으면 해석 불가로 두고 키만 남긴다.")
    @Test
    fun keepsKeyWhenUnreadable() {
        val broken = object : DeadLetterAlert {
            override val groupId = GROUP
            override val label = "구매활동"
            override fun describe(record: ConsumerRecord<*, *>): String = throw JsonParseException("깨진 본문")
        }

        val message = messageOf(JsonParseException("깨진 본문"), alert = broken)

        assertAll(
            { assertThat(message).startsWith("'구매활동' 도중 이벤트를 해석하지 못해 DLT로 격리하였다.") },
            { assertThat(message).contains("이벤트  해석 불가 (key=$KEY)") },
        )
    }

    @DisplayName("등록된 설명이 없으면 그룹 이름으로 대신한다.")
    @Test
    fun fallsBackToGroupName() {
        val message = messageOf(SQLSyntaxErrorException("..."), alert = null)

        assertThat(message).startsWith("'$GROUP' 도중 저장하지 못해 DLT로 격리하였다.")
    }

    @DisplayName("발행 시각과 실패까지 걸린 시간을 남긴다.")
    @Test
    fun recordsWhenItHappened() {
        val message = messageOf(SQLSyntaxErrorException("..."))

        assertAll(
            { assertThat(message).contains("시각    발행 ") },
            { assertThat(message).contains("· 실패까지 7초") },
        )
    }

    @DisplayName("원인 메시지가 여러 줄이면 첫 줄만 남겨 틀을 지킨다.")
    @Test
    fun keepsOneLinePerField() {
        val message = messageOf(JsonParseException("Invalid UTF-8 start byte 0xb9\n at [Source: REDACTED]"))

        assertAll(
            { assertThat(message.lines()).hasSize(7) },
            { assertThat(message).contains("원인    JsonParseException: Invalid UTF-8 start byte 0xb9") },
            { assertThat(message).doesNotContain("REDACTED") },
            { assertThat(message.lines()).noneMatch { it.startsWith(" ") } },
        )
    }

    companion object {
        private const val TOPIC = "order.events.v1"
        private const val GROUP = "purchase-activity"
        private const val KEY = "777001"
        private const val LANDED = "order.events.v1-dlt-1@7"
        private const val PUBLISHED_AT = 1793400000000L
    }
}
