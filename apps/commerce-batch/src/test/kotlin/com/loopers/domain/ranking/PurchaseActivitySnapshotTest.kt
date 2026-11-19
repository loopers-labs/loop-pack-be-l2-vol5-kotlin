package com.loopers.domain.ranking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.ZonedDateTime

class PurchaseActivitySnapshotTest {
    private val start = ZonedDateTime.parse("2026-11-09T00:00:00Z")
    private val end = start.plusWeeks(1)

    private fun snapshot(
        snapshotId: String = "2026_11_2_r1",
        periodStart: ZonedDateTime = start,
        periodEnd: ZonedDateTime = end,
        quantity: Int = 3,
        confirmedAt: ZonedDateTime = start.plusDays(1),
    ) = PurchaseActivitySnapshot(
        snapshotId = snapshotId,
        periodStart = periodStart,
        periodEnd = periodEnd,
        orderId = 42L,
        productId = 7L,
        quantity = quantity,
        confirmedAt = confirmedAt,
    )

    @DisplayName("구매 사본 행을 생성할 때, ")
    @Nested
    inner class Create {
        @DisplayName("값이 모두 올바르면, 생성된다.")
        @Test
        fun createsSnapshot_whenAllValuesAreValid() {
            val snapshot = snapshot()

            assertThat(snapshot.snapshotId).isEqualTo("2026_11_2_r1")
            assertThat(snapshot.orderId).isEqualTo(42L)
            assertThat(snapshot.productId).isEqualTo(7L)
            assertThat(snapshot.quantity).isEqualTo(3)
            assertThat(snapshot.confirmedAt).isEqualTo(start.plusDays(1))
        }

        @DisplayName("스냅샷 식별자가 연도_두 자리 월_주차_r버전 형식이 아니면, IllegalArgumentException 이 발생한다.")
        @ParameterizedTest
        @ValueSource(strings = ["", "2026_3_1_r1", "2026_13_1_r1", "2026_11_6_r1", "2026_11_1_r0", "2026_11_1"])
        fun throws_whenSnapshotIdIsInvalid(snapshotId: String) {
            assertThrows<IllegalArgumentException> { snapshot(snapshotId = snapshotId) }
        }

        @DisplayName("구간 시작이 끝보다 앞서지 않으면, IllegalArgumentException 이 발생한다.")
        @Test
        fun throws_whenPeriodIsNotOrdered() {
            assertThrows<IllegalArgumentException> { snapshot(periodStart = end, periodEnd = start) }
        }

        @DisplayName("수량이 0 이하면, IllegalArgumentException 이 발생한다.")
        @ParameterizedTest
        @ValueSource(ints = [0, -1])
        fun throws_whenQuantityIsNotPositive(quantity: Int) {
            assertThrows<IllegalArgumentException> { snapshot(quantity = quantity) }
        }

        @DisplayName("확정 시각이 구간 시작과 같으면, 생성된다.")
        @Test
        fun createsSnapshot_whenConfirmedAtIsPeriodStart() {
            assertThat(snapshot(confirmedAt = start).confirmedAt).isEqualTo(start)
        }

        @DisplayName("확정 시각이 구간 밖이면, IllegalArgumentException 이 발생한다.")
        @Test
        fun throws_whenConfirmedAtIsOutsidePeriod() {
            assertThrows<IllegalArgumentException> { snapshot(confirmedAt = start.minusNanos(1000)) }
            assertThrows<IllegalArgumentException> { snapshot(confirmedAt = end) }
        }
    }
}
