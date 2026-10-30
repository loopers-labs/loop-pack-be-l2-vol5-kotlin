package com.loopers.domain.purchase

import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.TestPropertySource
import java.time.ZonedDateTime

@SpringBootTest
@TestPropertySource(properties = ["spring.kafka.listener.auto-startup=false"])
class PurchaseActivityIdempotencyIntegrationTest @Autowired constructor(
    private val purchaseActivityService: PurchaseActivityService,
    private val purchaseActivityRepository: PurchaseActivityRepository,
    private val processedConfirmationRepository: ProcessedConfirmationRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    private fun commandOf(orderId: Long, vararg items: Pair<Long, Int>) = PurchaseActivityCommand(
        eventId = "order-confirmed:v1:$orderId",
        orderId = orderId,
        userId = BUYER,
        confirmedAt = CONFIRMED_AT,
        items = items.map { PurchaseActivityCommand.Item(it.first, it.second) },
    )

    @DisplayName("품목마다 한 행을 남기고 완료 기록을 함께 쓴다.")
    @Test
    fun recordsOncePerItem() {
        val command = commandOf(1L, 7L to 2, 8L to 1)

        purchaseActivityService.record(command)

        assertAll(
            { assertThat(purchaseActivityRepository.findAllByOrderId(1L)).hasSize(2) },
            { assertThat(processedConfirmationRepository.findByEventId(command.eventId)).isNotNull() },
        )
    }

    @DisplayName("같은 eventId 가 다시 오면 충돌로 거절한다.")
    @Test
    fun rejectsSameEventId() {
        val command = commandOf(1L, 7L to 2, 8L to 1)
        purchaseActivityService.record(command)

        assertThatThrownBy { purchaseActivityService.record(command) }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(purchaseActivityRepository.findAllByOrderId(1L)).hasSize(2)
    }

    @DisplayName("거절된 두 번째 시도는 구매 활동을 더 남기지 않는다.")
    @Test
    fun addsNothingOnConflict() {
        val command = commandOf(1L, 7L to 2)
        purchaseActivityService.record(command)
        val before = purchaseActivityRepository.findAllByOrderId(1L).size

        runCatching { purchaseActivityService.record(commandOf(1L, 7L to 99)) }

        assertAll(
            { assertThat(purchaseActivityRepository.findAllByOrderId(1L)).hasSize(before) },
            { assertThat(purchaseActivityRepository.findAllByOrderId(1L).first().quantity).isEqualTo(2) },
        )
    }

    @DisplayName("같은 사실이 다시 오면 충돌이 아니다.")
    @Test
    fun sameFactIsNotConflict() {
        val command = commandOf(1L, 7L to 2, 8L to 1)
        purchaseActivityService.record(command)

        assertThat(purchaseActivityService.conflictsWith(command)).isFalse()
    }

    @DisplayName("품목 순서만 다른 것은 충돌이 아니다.")
    @Test
    fun orderOfItemsIsNotConflict() {
        purchaseActivityService.record(commandOf(1L, 7L to 2, 8L to 1))

        assertThat(purchaseActivityService.conflictsWith(commandOf(1L, 8L to 1, 7L to 2))).isFalse()
    }

    @DisplayName("수량이 다르면 충돌이다.")
    @Test
    fun differentQuantityIsConflict() {
        purchaseActivityService.record(commandOf(1L, 7L to 2))

        assertThat(purchaseActivityService.conflictsWith(commandOf(1L, 7L to 99))).isTrue()
    }

    @DisplayName("품목이 늘거나 줄면 충돌이다.")
    @Test
    fun differentItemsIsConflict() {
        purchaseActivityService.record(commandOf(1L, 7L to 2))

        assertAll(
            { assertThat(purchaseActivityService.conflictsWith(commandOf(1L, 7L to 2, 8L to 1))).isTrue() },
            { assertThat(purchaseActivityService.conflictsWith(commandOf(1L, 9L to 2))).isTrue() },
        )
    }

    @DisplayName("구매자나 확정 시각이 다르면 충돌이다.")
    @Test
    fun differentBuyerOrTimeIsConflict() {
        purchaseActivityService.record(commandOf(1L, 7L to 2))

        val otherBuyer = commandOf(1L, 7L to 2).copy(userId = BUYER + 1)
        val otherTime = commandOf(1L, 7L to 2).copy(confirmedAt = CONFIRMED_AT.plusDays(1))

        assertAll(
            { assertThat(purchaseActivityService.conflictsWith(otherBuyer)).isTrue() },
            { assertThat(purchaseActivityService.conflictsWith(otherTime)).isTrue() },
        )
    }

    @DisplayName("같은 eventId 로 다른 주문이 오면 충돌이다.")
    @Test
    fun differentOrderIsConflict() {
        purchaseActivityService.record(commandOf(1L, 7L to 2))

        assertThat(purchaseActivityService.conflictsWith(commandOf(2L, 7L to 2))).isTrue()
    }

    companion object {
        private const val BUYER = 42L
        private val CONFIRMED_AT: ZonedDateTime = ZonedDateTime.parse("2026-09-07T01:00:00Z")
    }
}
