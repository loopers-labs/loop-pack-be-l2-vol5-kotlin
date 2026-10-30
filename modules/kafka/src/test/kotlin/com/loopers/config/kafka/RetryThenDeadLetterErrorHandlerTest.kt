package com.loopers.config.kafka

import com.fasterxml.jackson.core.JsonParseException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.kafka.support.SendResult
import org.springframework.util.backoff.ExponentialBackOff
import java.util.concurrent.CompletableFuture

class RetryThenDeadLetterErrorHandlerTest {
    private val container = mockk<MessageListenerContainer>(relaxed = true)
    private val consumer = mockk<Consumer<Any, Any>>(relaxed = true)
    private val deadLetters = mockk<KafkaOperations<Any, Any>>(relaxed = true)
    private val sent = slot<ProducerRecord<Any, Any>>()

    private val handler = RetryThenDeadLetterErrorHandler(
        deadLetters,
        ExponentialBackOff().apply {
            initialInterval = 1L
            multiplier = 2.0
            maxAttempts = RETRIES
        },
    )

    init {
        every { deadLetters.send(capture(sent)) } answers { accepted(sent.captured) }
    }

    @DisplayName("재시도가 남아 있으면 DLT 로 보내지 않는다.")
    @Test
    fun holdsWhileRetriesRemain() {
        assertThatThrownBy { handle() }.isNotInstanceOf(KafkaException::class.java)

        verify(exactly = 0) { deadLetters.send(any<ProducerRecord<Any, Any>>()) }
    }

    @DisplayName("재시도를 모두 쓰면 DLT 로 보내고 컨슈머는 계속 돈다.")
    @Test
    fun movesToDeadLetterAndKeepsRunning() {
        repeat(RETRIES) { runCatching { handle() } }

        handle()

        assertAll(
            { assertThat(sent.captured.topic()).isEqualTo("order.events.v1-dlt") },
            { assertThat(sent.captured.partition()).isEqualTo(PARTITION) },
            { assertThat(sent.captured.key()).isEqualTo(KEY) },
            { verify(exactly = 0) { container.stopAbnormally(any()) } },
        )
    }

    @DisplayName("다시 해봐도 안 되는 실패는 재시도를 건너뛰고 바로 DLT 로 보낸다.")
    @Test
    fun skipsRetriesWhenHopeless() {
        handler.handleRemaining(
            ListenerExecutionFailedException("리스너 실패", JsonParseException("깨진 본문")),
            mutableListOf<ConsumerRecord<*, *>>(ConsumerRecord(TOPIC, PARTITION, OFFSET, KEY, "value")),
            consumer,
            container,
        )

        assertAll(
            { assertThat(sent.captured.topic()).isEqualTo("order.events.v1-dlt") },
            { verify(exactly = 0) { container.stopAbnormally(any()) } },
        )
    }

    private fun handle() = handler.handleRemaining(
        RuntimeException("반영 실패"),
        mutableListOf<ConsumerRecord<*, *>>(ConsumerRecord(TOPIC, PARTITION, OFFSET, KEY, "value")),
        consumer,
        container,
    )

    private fun accepted(record: ProducerRecord<Any, Any>): CompletableFuture<SendResult<Any, Any>> {
        val partition = TopicPartition(record.topic(), record.partition() ?: 0)
        val metadata = RecordMetadata(partition, 0L, 0, 0L, 0, 0)

        return CompletableFuture.completedFuture(SendResult(record, metadata))
    }

    companion object {
        private const val TOPIC = "order.events.v1"
        private const val PARTITION = 2
        private const val OFFSET = 7L
        private const val KEY = "77001"
        private const val RETRIES = 2
    }
}
