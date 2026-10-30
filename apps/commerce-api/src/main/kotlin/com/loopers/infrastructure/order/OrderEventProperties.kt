package com.loopers.infrastructure.order

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "order-event")
data class OrderEventProperties(
    val topic: String,
    val schemaVersion: Int,
)
