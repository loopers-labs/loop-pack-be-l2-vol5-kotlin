package com.loopers.connection

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.outbox.OutboxEvent
import com.loopers.domain.outbox.OutboxEventRepository
import com.loopers.relay.OutboxRelay
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.kafka.config.TopicBuilder
import java.time.Duration
import java.util.Properties

@SpringBootTest
class OrderConfirmedRelayConnectionTest @Autowired constructor(
    private val outboxRelay: OutboxRelay,
    private val outboxEventRepository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    @DisplayName("연결 2구간: 계약 JSON 이 든 아웃박스 행을 relay 가 보내면, Kafka 에 키와 payload 가 그대로 도착한다.")
    @Test
    fun relaysContractPayloadAsIs() {
        val before = endOffsets()
        val payloads = contract()
        payloads.forEach { payload ->
            val orderId = objectMapper.readTree(payload).get("orderId").asLong()
            outboxEventRepository.save(
                OutboxEvent(
                    eventId = "order-confirmed:v1:$orderId",
                    topic = TOPIC,
                    eventType = "OrderConfirmed",
                    schemaVersion = 1,
                    payload = payload,
                    key = orderId.toString(),
                ),
            )
        }

        val relayed = outboxRelay.relayAll()

        assertThat(relayed).isEqualTo(3)
        val received = recordsSince(before, expected = 3)
        assertThat(received).containsExactlyInAnyOrderEntriesOf(
            payloads.associateBy { objectMapper.readTree(it).get("orderId").asText() },
        )
    }

    private fun contract(): List<String> =
        requireNotNull(javaClass.getResource(CONTRACT)).readText().lines().filter { it.isNotBlank() }

    private fun consumer() = KafkaConsumer<String, String>(
        Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKERS)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
            put(ConsumerConfig.GROUP_ID_CONFIG, "connection-probe")
        },
    )

    private fun endOffsets(): Map<TopicPartition, Long> = consumer().use { consumer ->
        val deadline = System.currentTimeMillis() + 10_000
        var partitions = consumer.partitionsFor(TOPIC).orEmpty()
        while (partitions.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            partitions = consumer.partitionsFor(TOPIC).orEmpty()
        }
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

    @TestConfiguration
    class ConnectionTopic {
        @Bean
        fun connectionProbeTopic(): NewTopic = TopicBuilder.name(TOPIC).partitions(3).replicas(3).build()
    }

    companion object {
        private const val CONTRACT = "/contract/order-confirmed-2026-08-17.jsonl"
        private const val TOPIC = "order.events.connection-probe"
        private const val BROKERS = "localhost:19092,localhost:19093,localhost:19094"
    }
}
