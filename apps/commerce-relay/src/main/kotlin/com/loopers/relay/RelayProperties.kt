package com.loopers.relay

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "relay")
data class RelayProperties(
    val batchSize: Int,
    val sendTimeout: Duration,
)
