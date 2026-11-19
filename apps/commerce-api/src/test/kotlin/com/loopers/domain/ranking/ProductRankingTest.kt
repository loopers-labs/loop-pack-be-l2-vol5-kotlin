package com.loopers.domain.ranking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate

class ProductRankingTest {
    private val monday = LocalDate.parse("2026-11-09")

    private fun ranking(
        snapshotId: String = "2026_11_2",
        periodStart: LocalDate = monday,
        score: Long = 5,
        ranking: Int = 1,
    ) = ProductRanking(
        snapshotId = snapshotId,
        periodStart = periodStart,
        productId = 1L,
        score = score,
        ranking = ranking,
    )

    @DisplayName("랭킹을 생성할 때, ")
    @Nested
    inner class Create {
        @DisplayName("값이 모두 올바르면, 생성된다.")
        @Test
        fun createsRanking_whenAllValuesAreValid() {
            val ranking = ranking()

            assertThat(ranking.snapshotId).isEqualTo("2026_11_2")
            assertThat(ranking.periodStart).isEqualTo(monday)
            assertThat(ranking.productId).isEqualTo(1L)
            assertThat(ranking.score).isEqualTo(5L)
            assertThat(ranking.ranking).isEqualTo(1)
        }

        @DisplayName("스냅샷 식별자가 연도_두 자리 월_주차 형식이면, 생성된다.")
        @ParameterizedTest
        @ValueSource(strings = ["2026_01_1", "2026_12_5", "2026_03_2"])
        fun createsRanking_whenSnapshotIdMatchesFormat(snapshotId: String) {
            assertThat(ranking(snapshotId = snapshotId).snapshotId).isEqualTo(snapshotId)
        }

        @DisplayName("스냅샷 식별자가 형식에 맞지 않거나 리비전이 붙어 있으면, IllegalArgumentException 이 발생한다.")
        @ParameterizedTest
        @ValueSource(strings = ["", "2026_11_2_r1", "2026_3_1", "2026_00_1", "2026_13_1", "2026_11_0", "2026_11_6"])
        fun throwsException_whenSnapshotIdDoesNotMatchFormat(snapshotId: String) {
            assertThrows<IllegalArgumentException> { ranking(snapshotId = snapshotId) }
        }

        @DisplayName("주 시작일이 월요일이 아니면, IllegalArgumentException 이 발생한다.")
        @ParameterizedTest
        @ValueSource(strings = ["2026-11-10", "2026-11-15"])
        fun throwsException_whenPeriodStartIsNotMonday(periodStart: String) {
            assertThrows<IllegalArgumentException> { ranking(periodStart = LocalDate.parse(periodStart)) }
        }

        @DisplayName("점수가 1 보다 작으면, IllegalArgumentException 이 발생한다.")
        @ParameterizedTest
        @ValueSource(longs = [0, -1])
        fun throwsException_whenScoreIsLessThanOne(score: Long) {
            assertThrows<IllegalArgumentException> { ranking(score = score) }
        }

        @DisplayName("랭킹이 1 보다 작으면, IllegalArgumentException 이 발생한다.")
        @ParameterizedTest
        @ValueSource(ints = [0, -1])
        fun throwsException_whenRankingIsLessThanOne(ranking: Int) {
            assertThrows<IllegalArgumentException> { ranking(ranking = ranking) }
        }
    }
}
