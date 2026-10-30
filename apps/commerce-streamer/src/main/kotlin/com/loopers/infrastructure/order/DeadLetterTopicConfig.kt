package com.loopers.infrastructure.order

import com.loopers.config.kafka.RetryThenDeadLetterErrorHandler
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class DeadLetterTopicConfig(
    @Value("\${order-event.topic}") private val topic: String,
) {
    @Bean
    fun orderEventDeadLetterTopic(): NewTopic =
        TopicBuilder.name(topic + RetryThenDeadLetterErrorHandler.TOPIC_SUFFIX)
            .partitions(PARTITIONS)
            .replicas(REPLICAS)
            .build()

    companion object {
        const val PARTITIONS = 3
        const val REPLICAS = 3
    }
}
