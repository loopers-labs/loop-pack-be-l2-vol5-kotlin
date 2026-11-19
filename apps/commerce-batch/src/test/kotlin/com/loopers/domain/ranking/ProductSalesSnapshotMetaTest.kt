package com.loopers.domain.ranking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.ZonedDateTime

class ProductSalesSnapshotMetaTest {
    private val start = ZonedDateTime.parse("2026-11-09T00:00:00Z")
    private val checksum = "a".repeat(64)

    private fun meta(
        snapshotId: String = "2026_11_2_r1",
        periodStart: ZonedDateTime = start,
        periodEnd: ZonedDateTime = start.plusWeeks(1),
        rowCount: Long = 2,
        checksum: String = this.checksum,
    ) = ProductSalesSnapshotMeta(
        snapshotId = snapshotId,
        periodStart = periodStart,
        periodEnd = periodEnd,
        status = SnapshotMetaStatus.FROZEN,
        frozenAt = start.plusWeeks(1).plusMinutes(90),
        rowCount = rowCount,
        checksum = checksum,
    )

    @DisplayName("스냅샷 정보 행을 생성할 때, ")
    @Nested
    inner class Create {
        @DisplayName("revision 은 스냅샷 식별자의 r 뒤 숫자다.")
        @ParameterizedTest
        @CsvSource("2026_11_2_r1, 1", "2026_11_2_r2, 2", "2026_11_2_r10, 10")
        fun takesRevisionFromSnapshotId(snapshotId: String, revision: Int) {
            assertThat(meta(snapshotId = snapshotId).revision).isEqualTo(revision)
        }

        @DisplayName("건수가 0 이어도, 생성된다.")
        @Test
        fun createsMeta_whenRowCountIsZero() {
            assertThat(meta(rowCount = 0).rowCount).isEqualTo(0L)
        }

        @DisplayName("스냅샷 식별자가 형식에 맞지 않으면, IllegalArgumentException 이 발생한다.")
        @ParameterizedTest
        @ValueSource(strings = ["", "2026_11_2", "2026_11_2_r0", "2026_13_2_r1"])
        fun throws_whenSnapshotIdIsInvalid(snapshotId: String) {
            assertThrows<IllegalArgumentException> { meta(snapshotId = snapshotId) }
        }

        @DisplayName("구간 시작이 끝보다 앞서지 않으면, IllegalArgumentException 이 발생한다.")
        @Test
        fun throws_whenPeriodIsNotOrdered() {
            assertThrows<IllegalArgumentException> { meta(periodStart = start, periodEnd = start) }
        }

        @DisplayName("건수가 음수면, IllegalArgumentException 이 발생한다.")
        @Test
        fun throws_whenRowCountIsNegative() {
            assertThrows<IllegalArgumentException> { meta(rowCount = -1) }
        }

        @DisplayName("checksum 이 소문자 hex 64 자가 아니면, IllegalArgumentException 이 발생한다.")
        @Test
        fun throws_whenChecksumIsNotSha256Hex() {
            listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64)).forEach {
                assertThrows<IllegalArgumentException> { meta(checksum = it) }
            }
        }
    }
}
