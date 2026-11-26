package com.loopers.pg

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "pg")
data class PgSimulatorProperties(
    val callbackUrl: String,
    val callbackDelay: Duration = Duration.ofSeconds(3),
    val slowCardDelay: Duration = Duration.ofSeconds(10),
    val responseDelay: Duration = Duration.ZERO,
    val callbackDropRate: Double = 0.0,
    val responseLostRate: Double = 0.0,
    val decline: Map<DeclineReason, Double> = emptyMap(),
)
