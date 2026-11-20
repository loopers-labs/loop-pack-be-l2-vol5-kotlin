package com.loopers.domain.ranking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate

class RankingTest {
    private val time = Instant.parse("2026-08-17T01:00:00Z")

    @Test
    fun `UTC interval includes Monday start and excludes next start`() {
        val period = RankingPeriod(LocalDate.parse("2026-08-17"))

        assertThat(period.contains(Instant.parse("2026-08-17T00:00:00Z"))).isTrue()
        assertThat(period.contains(Instant.parse("2026-08-23T23:59:59.999999Z"))).isTrue()
        assertThat(period.contains(Instant.parse("2026-08-24T00:00:00Z"))).isFalse()
        assertThrows<IllegalArgumentException> { RankingPeriod(LocalDate.parse("2026-08-18")) }
    }

    @Test
    fun `sum quantity rather than activities and sort score then product id`() {
        assertThat(RankingCalculation.score(listOf(RankingInput(42, 7, 2, time), RankingInput(43, 7, 3, time))))
            .isEqualTo(5)
        assertThat(RankingCalculation.rank(listOf(ProductScore(9, 5), ProductScore(8, 1), ProductScore(7, 5))))
            .containsExactly(RankedProduct(7, 5, 1), RankedProduct(9, 5, 2), RankedProduct(8, 1, 3))
    }

    @Test
    fun `positive values microsecond precision and overflow are enforced`() {
        assertThrows<IllegalArgumentException> { RankingInput(42, 7, 0, time) }
        assertThrows<IllegalArgumentException> { RankingInput(42, 7, 1, time.plusNanos(1)) }
        assertThrows<ArithmeticException> {
            RankingCalculation.score(listOf(RankingInput(42, 7, Long.MAX_VALUE, time), RankingInput(43, 7, 1, time)))
        }
    }

    @Test
    fun `canonical checksums ignore input order and have fixed independent expected values`() {
        val rows = listOf(RankingInput(42, 7, 2, time), RankingInput(42, 8, 1, time))

        assertThat(RankingChecksum.inputs(rows.reversed())).isEqualTo(RankingChecksum.inputs(rows))
        assertThat(RankingChecksum.results(listOf(RankedProduct(7, 5, 1), RankedProduct(9, 5, 2), RankedProduct(8, 1, 3))))
            .isEqualTo("745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2")
        assertThat(RankingChecksum.inputs(emptyList())).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }
}
