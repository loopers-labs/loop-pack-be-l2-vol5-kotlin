package com.loopers.config.relay

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate

@Configuration
class RelayKafkaConfig {
    @Bean(RELAY_KAFKA_TEMPLATE)
    fun relayKafkaTemplate(kafkaProperties: KafkaProperties): KafkaTemplate<String, String> {
        val props = HashMap(kafkaProperties.buildProducerProperties()).apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java)
        }

        return KafkaTemplate(DefaultKafkaProducerFactory(props))
    }

    companion object {
        const val RELAY_KAFKA_TEMPLATE = "RELAY_KAFKA_TEMPLATE"
    }
}
