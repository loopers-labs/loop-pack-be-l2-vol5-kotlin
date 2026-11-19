package com.loopers.domain.ranking

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate

class ProductRankingPointerTest {
    @DisplayName("포인터를 생성할 때, ")
    @Nested
    inner class Create {
        @DisplayName("공개된 포인터에 0 이상의 상품 수가 있으면, 생성된다.")
        @ParameterizedTest
        @ValueSource(longs = [0, 1, 300])
        fun createsPublishedPointer_whenCountIsZeroOrMore(count: Long) {
            val pointer = ProductRankingPointer("2026_11_2", ProductRankingPointerStatus.PUBLISHED, count)

            assertThat(pointer.isPublished).isTrue()
            assertThat(pointer.productCount).isEqualTo(count)
        }

        @DisplayName("공개되지 않은 포인터에 상품 수가 없으면, 생성된다.")
        @ParameterizedTest
        @EnumSource(value = ProductRankingPointerStatus::class, names = ["IN_PROGRESS", "FAILED"])
        fun createsUnpublishedPointer_whenCountIsNull(status: ProductRankingPointerStatus) {
            val pointer = ProductRankingPointer("2026_11_2", status, null)

            assertThat(pointer.isPublished).isFalse()
            assertThat(pointer.productCount).isNull()
        }

        @DisplayName("스냅샷 식별자가 연도_월_주차 형식이 아니면, 예외가 발생한다.")
        @ParameterizedTest
        @ValueSource(strings = ["2026_11_2_r1", "2026_13_2", "2026_11_6", "2026-11-2", ""])
        fun throws_whenSnapshotIdIsInvalid(snapshotId: String) {
            assertThrows<IllegalArgumentException> {
                ProductRankingPointer(snapshotId, ProductRankingPointerStatus.IN_PROGRESS, null)
            }
        }

        @DisplayName("상품 수가 음수면, 예외가 발생한다.")
        @Test
        fun throws_whenCountIsNegative() {
            assertThrows<IllegalArgumentException> {
                ProductRankingPointer("2026_11_2", ProductRankingPointerStatus.PUBLISHED, -1)
            }
        }

        @DisplayName("공개된 포인터에 상품 수가 없으면, 예외가 발생한다.")
        @Test
        fun throws_whenPublishedWithoutCount() {
            assertThrows<IllegalArgumentException> {
                ProductRankingPointer("2026_11_2", ProductRankingPointerStatus.PUBLISHED, null)
            }
        }

        @DisplayName("공개되지 않은 포인터에 상품 수가 있으면, 예외가 발생한다.")
        @ParameterizedTest
        @EnumSource(value = ProductRankingPointerStatus::class, names = ["IN_PROGRESS", "FAILED"])
        fun throws_whenUnpublishedWithCount(status: ProductRankingPointerStatus) {
            assertThrows<IllegalArgumentException> { ProductRankingPointer("2026_11_2", status, 3) }
        }
    }

    @DisplayName("주 시작일로 스냅샷 식별자를 만들 때, ")
    @Nested
    inner class SnapshotIdOf {
        @DisplayName("월요일이면, 그 달의 몇 번째 주인지로 만든다.")
        @ParameterizedTest
        @CsvSource(
            "2026-11-02, 2026_11_1",
            "2026-11-09, 2026_11_2",
            "2026-11-30, 2026_11_5",
            "2026-12-07, 2026_12_1",
        )
        fun buildsSnapshotId_whenMonday(monday: String, expected: String) {
            assertThat(ProductRankingPointer.snapshotIdOf(LocalDate.parse(monday))).isEqualTo(expected)
        }

        @DisplayName("월요일이 아니면, 예외가 발생한다.")
        @Test
        fun throws_whenNotMonday() {
            assertThrows<IllegalArgumentException> { ProductRankingPointer.snapshotIdOf(LocalDate.parse("2026-11-10")) }
        }
    }
}
