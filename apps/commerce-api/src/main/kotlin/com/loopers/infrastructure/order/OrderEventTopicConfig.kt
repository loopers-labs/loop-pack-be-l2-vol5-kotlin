package com.loopers.infrastructure.order

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class OrderEventTopicConfig(
    private val properties: OrderEventProperties,
) {
    @Bean
    fun orderEventTopic(): NewTopic =
        TopicBuilder.name(properties.topic)
            .partitions(PARTITIONS)
            .replicas(REPLICAS)
            .build()

    companion object {
        const val PARTITIONS = 3
        const val REPLICAS = 3
    }
}
