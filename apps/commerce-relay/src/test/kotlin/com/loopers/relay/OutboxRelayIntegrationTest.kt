package com.loopers.relay

import com.loopers.domain.outbox.OutboxEvent
import com.loopers.domain.outbox.OutboxEventRepository
import com.loopers.domain.outbox.OutboxStatus
import com.loopers.infrastructure.outbox.OutboxEventJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.kafka.config.TopicBuilder
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.Properties

@SpringBootTest
@TestPropertySource(
    properties = [
        "relay.batch-size=2",
    ],
)
class OutboxRelayIntegrationTest @Autowired constructor(
    private val outboxRelay: OutboxRelay,
    private val outboxEventRepository: OutboxEventRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    private fun pending(orderId: Long): OutboxEvent = outboxEventRepository.save(
        OutboxEvent(
            eventId = "order-confirmed:v1:$orderId",
            topic = TOPIC,
            eventType = "OrderConfirmed",
            schemaVersion = 1,
            payload = """{"eventId":"order-confirmed:v1:$orderId","orderId":$orderId}""",
            key = orderId.toString(),
        ),
    )

    private fun consumer() = KafkaConsumer<String, String>(
        Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKERS)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
            put(ConsumerConfig.GROUP_ID_CONFIG, "relay-probe")
        },
    )

    private fun endOffsets(): Map<TopicPartition, Long> = consumer().use { consumer ->
        val deadline = System.currentTimeMillis() + 10_000
        var partitions = consumer.partitionsFor(TOPIC).orEmpty()
        while (partitions.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            partitions = consumer.partitionsFor(TOPIC).orEmpty()
        }
        assertThat(partitions).isNotEmpty()

        consumer.endOffsets(partitions.map { TopicPartition(it.topic(), it.partition()) })
    }

    private fun recordsSince(offsets: Map<TopicPartition, Long>, expected: Int): Map<String, String> =
        consumer().use { consumer ->
            consumer.assign(offsets.keys)
            offsets.forEach { (partition, offset) -> consumer.seek(partition, offset) }

            val received = mutableMapOf<String, String>()
            val deadline = System.currentTimeMillis() + 10_000
            while (received.size < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach { received[it.key()] = it.value() }
            }

            received
        }

    @DisplayName("묶음 크기보다 많이 쌓여 있어도 한 주기에 PENDING 을 모두 보낸다.")
    @Test
    fun relaysEveryPendingRowAcrossBatches() {
        val before = endOffsets().values.sum()
        (1L..5L).forEach { pending(it) }

        val relayed = outboxRelay.relayAll()

        assertAll(
            { assertThat(relayed).isEqualTo(5) },
            { assertThat(endOffsets().values.sum() - before).isEqualTo(5) },
        )
    }

    @DisplayName("브로커가 받았다고 답한 행은 SENT 로 바꾼다.")
    @Test
    fun marksSentAfterAck() {
        pending(1L)

        outboxRelay.relayAll()

        assertThat(outboxEventJpaRepository.findAll().single().status).isEqualTo(OutboxStatus.SENT)
    }

    @DisplayName("페이로드를 다시 감싸지 않고 키와 함께 그대로 보낸다.")
    @Test
    fun sendsPayloadAsIsWithKey() {
        val before = endOffsets()
        val row = pending(700_001L)

        outboxRelay.relayAll()

        val received = recordsSince(before, expected = 1)
        assertThat(received).containsEntry(row.key, row.payload)
    }

    @DisplayName("SENT 인 행은 보내지 않는다.")
    @Test
    fun skipsSentRows() {
        val row = pending(1L)
        outboxEventRepository.markSent(row.eventId)
        val before = endOffsets().values.sum()

        val relayed = outboxRelay.relayAll()

        assertAll(
            { assertThat(relayed).isZero() },
            { assertThat(endOffsets().values.sum()).isEqualTo(before) },
        )
    }

    @TestConfiguration
    class ProbeTopic {
        @Bean
        fun relayProbeTopic(): NewTopic = TopicBuilder.name(TOPIC).partitions(3).replicas(3).build()
    }

    companion object {
        private const val BROKERS = "localhost:19092,localhost:19093,localhost:19094"
    }
}

private const val TOPIC = "order.events.relay-probe"
