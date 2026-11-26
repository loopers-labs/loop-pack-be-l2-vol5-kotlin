package com.loopers.infrastructure.payment

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "payment.metrics")
data class PaymentMetricsProperties(
    val stalePendingAfter: Duration,
)
