package com.loopers.pg

import org.springframework.stereotype.Component
import kotlin.random.Random

@Component
class ResponseLossRoulette(
    private val properties: PgSimulatorProperties,
) {
    fun spin(random: Random = Random): Boolean = random.nextDouble() < properties.responseLostRate
}
