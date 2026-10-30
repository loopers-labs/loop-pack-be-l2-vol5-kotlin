package com.loopers.domain.product

import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StockReservationTest {
    private fun reservation(quantity: Int = 2) =
        StockReservation(orderId = 1L, productId = 2L, quantity = quantity)

    @DisplayName("예약을 생성할 때, ")
    @Nested
    inner class Create {
        @DisplayName("수량이 0 이하면, BAD_REQUEST 예외가 발생한다.")
        @Test
        fun throwsBadRequestException_whenQuantityIsNotPositive() {
            val result = assertThrows<CoreException> { reservation(quantity = 0) }

            assertThat(result.errorType).isEqualTo(ErrorType.BAD_REQUEST)
        }

        @DisplayName("상태는 RESERVED 로 시작한다.")
        @Test
        fun startsAsReserved() {
            assertThat(reservation().status).isEqualTo(StockReservationStatus.RESERVED)
        }
    }

    @DisplayName("예약을 확정할 때, ")
    @Nested
    inner class Commit {
        @DisplayName("RESERVED 면, COMMITTED 가 된다.")
        @Test
        fun commitsReserved() {
            val reservation = reservation().also { it.commit() }

            assertThat(reservation.status).isEqualTo(StockReservationStatus.COMMITTED)
        }

        @DisplayName("이미 COMMITTED 면, 다시 확정해도 그대로다.")
        @Test
        fun isIdempotent() {
            val reservation = reservation().also { it.commit() }

            reservation.commit()

            assertThat(reservation.status).isEqualTo(StockReservationStatus.COMMITTED)
        }

        @DisplayName("이미 풀린 예약이면, CONFLICT 예외가 발생한다.")
        @Test
        fun throwsConflictException_whenAlreadyReleased() {
            val reservation = reservation().also { it.release() }

            val result = assertThrows<CoreException> { reservation.commit() }

            assertThat(result.errorType).isEqualTo(ErrorType.CONFLICT)
        }
    }

    @DisplayName("예약을 풀 때, ")
    @Nested
    inner class Release {
        @DisplayName("잡고 있던 예약이면, 되돌릴 재고가 있다고 알린다.")
        @Test
        fun reportsRestorable_whenHeld() {
            val reservation = reservation()

            assertThat(reservation.release()).isTrue()
        }

        @DisplayName("확정된 예약도 풀 수 있다. 주문 취소가 쓴다.")
        @Test
        fun releasesCommitted() {
            val reservation = reservation().also { it.commit() }

            assertThat(reservation.release()).isTrue()
            assertThat(reservation.status).isEqualTo(StockReservationStatus.RELEASED)
        }

        @DisplayName("이미 풀린 예약이면, 되돌릴 재고가 없다고 알린다.")
        @Test
        fun reportsNothingToRestore_whenAlreadyReleased() {
            val reservation = reservation().also { it.release() }

            assertThat(reservation.release()).isFalse()
        }
    }
}
