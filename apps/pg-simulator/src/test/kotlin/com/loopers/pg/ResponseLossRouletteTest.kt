package com.loopers.pg

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ResponseLossRouletteTest {
    private fun roulette(rate: Double) = ResponseLossRoulette(
        PgSimulatorProperties(
            callbackUrl = "http://localhost:1/callback",
            responseLostRate = rate,
        ),
    )

    @DisplayName("확률이 0이면 응답을 잃지 않는다.")
    @Test
    fun neverLosesAtZero() {
        val roulette = roulette(0.0)

        assertThat((1..1_000).count { roulette.spin() }).isZero()
    }

    @DisplayName("확률이 1이면 항상 응답을 잃는다.")
    @Test
    fun alwaysLosesAtOne() {
        val roulette = roulette(1.0)

        assertThat((1..1_000).all { roulette.spin() }).isTrue()
    }

    @DisplayName("뽑은 값이 확률보다 작을 때만 응답을 잃는다.")
    @Test
    fun losesOnlyBelowRate() {
        val roulette = roulette(0.01)

        assertThat(roulette.spin(fixed(0.009))).isTrue()
        assertThat(roulette.spin(fixed(0.01))).isFalse()
    }

    @DisplayName("기본 설정 1%는 많이 뽑으면 1% 근처로 나온다.")
    @Test
    fun keepsDefaultRateNearOnePercent() {
        val roulette = roulette(0.01)

        val lost = (1..50_000).count { roulette.spin() }

        assertThat(lost.toDouble() / 50_000).isBetween(0.005, 0.015)
    }

    private fun fixed(value: Double) = object : Random() {
        override fun nextBits(bitCount: Int): Int = throw UnsupportedOperationException()

        override fun nextDouble(): Double = value
    }
}
