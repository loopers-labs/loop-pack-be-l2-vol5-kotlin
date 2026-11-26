package com.loopers.pg

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Duration
import kotlin.random.Random

class DeclineRouletteTest {
    private fun roulette(decline: Map<DeclineReason, Double>) = DeclineRoulette(
        PgSimulatorProperties(
            callbackUrl = "http://localhost:1/callback",
            callbackDelay = Duration.ofMillis(1),
            decline = decline,
        ),
    )

    @DisplayName("설정이 비어 있으면 아무것도 거절하지 않는다.")
    @Test
    fun approvesByDefault() {
        val roulette = roulette(emptyMap())

        assertThat((1..1_000).mapNotNull { roulette.spin() }).isEmpty()
    }

    @DisplayName("확률이 1인 사유가 있으면 그 사유로 거절한다.")
    @Test
    fun alwaysDeclines() {
        assertThat(roulette(mapOf(DeclineReason.LOST_OR_STOLEN to 1.0)).spin())
            .isEqualTo(DeclineReason.LOST_OR_STOLEN)
    }

    @DisplayName("사유들의 확률은 서로 겹치지 않고 나뉘어 쓰인다.")
    @Test
    fun splitsProbabilityAcrossReasons() {
        val roulette = roulette(
            mapOf(
                DeclineReason.LIMIT_EXCEEDED to 0.5,
                DeclineReason.INSUFFICIENT_BALANCE to 0.5,
            ),
        )

        assertAll(
            { assertThat(roulette.spin(Random(seedFor(0.2)))).isEqualTo(DeclineReason.LIMIT_EXCEEDED) },
            { assertThat((1..2_000).mapNotNull { roulette.spin() }).hasSize(2_000) },
        )
    }

    @DisplayName("기본 설정의 거절률은 5% 근처다.")
    @Test
    fun keepsDefaultRateNearFivePercent() {
        val roulette = roulette(
            mapOf(
                DeclineReason.LIMIT_EXCEEDED to 0.0227,
                DeclineReason.INSUFFICIENT_BALANCE to 0.0227,
                DeclineReason.LOST_OR_STOLEN to 0.0046,
            ),
        )

        val declined = (1..20_000).count { roulette.spin() != null }

        assertThat(declined.toDouble() / 20_000).isBetween(0.04, 0.06)
    }

    private fun seedFor(firstDouble: Double): Int {
        var seed = 0
        while (Random(seed).nextDouble() > firstDouble && seed < 10_000) seed++

        return seed
    }
}
