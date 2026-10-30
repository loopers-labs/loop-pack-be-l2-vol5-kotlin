package com.loopers.config.kafka

import org.apache.kafka.clients.consumer.ConsumerRecord

interface DeadLetterAlert {
    val groupId: String
    val label: String

    fun describe(record: ConsumerRecord<*, *>): String
}
