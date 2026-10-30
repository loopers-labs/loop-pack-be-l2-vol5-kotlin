package com.loopers.config.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.support.converter.BatchMessagingMessageConverter
import org.springframework.kafka.support.converter.ByteArrayJsonMessageConverter
import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.ExponentialBackOff
import java.util.HashMap

@EnableKafka
@Configuration
class KafkaConfig {
    companion object {
        const val BATCH_LISTENER = "BATCH_LISTENER_DEFAULT"
        const val RECORD_LISTENER = "RECORD_LISTENER_DEFAULT"
        const val DEAD_LETTER_TEMPLATE = "DEAD_LETTER_KAFKA_TEMPLATE"

        private const val CONCURRENCY = 3
        private const val MAX_POLLING_SIZE = 3000 // read 3000 msg
        private const val FETCH_MIN_BYTES = (1024 * 1024) // 1mb
        private const val FETCH_MAX_WAIT_MS = 5 * 1000 // broker waiting time = 5s
        private const val SESSION_TIMEOUT_MS = 60 * 1000 // session timeout = 1m
        private const val HEARTBEAT_INTERVAL_MS = 20 * 1000 // heartbeat interval = 20s ( 1/3 of session_timeout )
        private const val MAX_POLL_INTERVAL_MS = 2 * 60 * 1000 // max poll interval = 2m

        private const val RETRY_INITIAL_INTERVAL_MS = 1_000L
        private const val RETRY_MULTIPLIER = 2.0
        private const val RETRY_MAX_INTERVAL_MS = 8_000L
        private const val RETRY_MAX_ATTEMPTS = 4
    }

    @Bean
    fun producerFactory(
        kafkaProperties: KafkaProperties,
    ): ProducerFactory<Any, Any> {
        val props: Map<String, Any> = HashMap(kafkaProperties.buildProducerProperties())
        return DefaultKafkaProducerFactory(props)
    }

    @Bean
    fun consumerFactory(
        kafkaProperties: KafkaProperties,
    ): ConsumerFactory<Any, Any> {
        val props: Map<String, Any> = HashMap(kafkaProperties.buildConsumerProperties())
        return DefaultKafkaConsumerFactory(props)
    }

    @Bean
    @Primary
    fun kafkaTemplate(producerFactory: ProducerFactory<Any, Any>): KafkaTemplate<Any, Any> {
        return KafkaTemplate(producerFactory)
    }

    @Bean(DEAD_LETTER_TEMPLATE)
    fun deadLetterKafkaTemplate(kafkaProperties: KafkaProperties): KafkaTemplate<Any, Any> {
        val props = HashMap(kafkaProperties.buildProducerProperties()).apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java)
        }

        return KafkaTemplate(DefaultKafkaProducerFactory(props))
    }

    @Bean
    fun jsonMessageConverter(objectMapper: ObjectMapper): ByteArrayJsonMessageConverter {
        return ByteArrayJsonMessageConverter(objectMapper)
    }

    @Bean(BATCH_LISTENER)
    fun defaultBatchListenerContainerFactory(
        kafkaProperties: KafkaProperties,
        converter: ByteArrayJsonMessageConverter,
    ): ConcurrentKafkaListenerContainerFactory<*, *> {
        return ConcurrentKafkaListenerContainerFactory<Any, Any>().apply {
            consumerFactory = DefaultKafkaConsumerFactory(tunedConsumerConfig(kafkaProperties))
            containerProperties.ackMode = ContainerProperties.AckMode.MANUAL
            setBatchMessageConverter(BatchMessagingMessageConverter(converter))
            setConcurrency(kafkaProperties.listener.concurrency ?: CONCURRENCY)
            setAutoStartup(kafkaProperties.listener.isAutoStartup)
            isBatchListener = true
        }
    }

    @Bean(RECORD_LISTENER)
    fun defaultRecordListenerContainerFactory(
        kafkaProperties: KafkaProperties,
        @Qualifier(DEAD_LETTER_TEMPLATE) deadLetterKafkaTemplate: KafkaTemplate<Any, Any>,
        alerts: ObjectProvider<DeadLetterAlert>,
    ): ConcurrentKafkaListenerContainerFactory<*, *> {
        return ConcurrentKafkaListenerContainerFactory<Any, Any>().apply {
            consumerFactory = DefaultKafkaConsumerFactory(tunedConsumerConfig(kafkaProperties))
            containerProperties.ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            setConcurrency(kafkaProperties.listener.concurrency ?: CONCURRENCY)
            setAutoStartup(kafkaProperties.listener.isAutoStartup)
            setCommonErrorHandler(
                RetryThenDeadLetterErrorHandler(
                    deadLetterKafkaTemplate,
                    retryBackOff(),
                    alerts.orderedStream().toList(),
                ),
            )
        }
    }

    private fun tunedConsumerConfig(kafkaProperties: KafkaProperties): Map<String, Any> =
        HashMap(kafkaProperties.buildConsumerProperties()).apply {
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLLING_SIZE)
            put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, FETCH_MIN_BYTES)
            put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, FETCH_MAX_WAIT_MS)
            put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, SESSION_TIMEOUT_MS)
            put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, HEARTBEAT_INTERVAL_MS)
            put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, MAX_POLL_INTERVAL_MS)
        }

    private fun retryBackOff(): BackOff = ExponentialBackOff().apply {
        initialInterval = RETRY_INITIAL_INTERVAL_MS
        multiplier = RETRY_MULTIPLIER
        maxInterval = RETRY_MAX_INTERVAL_MS
        maxAttempts = RETRY_MAX_ATTEMPTS
    }
}
