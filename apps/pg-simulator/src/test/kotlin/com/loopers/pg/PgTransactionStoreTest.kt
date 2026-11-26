package com.loopers.pg

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.math.BigInteger
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PgTransactionStoreTest {
    private val store = PgTransactionStore()
    private val now: Instant = Instant.parse("2026-10-18T00:00:00Z")
    private val amount: BigInteger = BigInteger.valueOf(50_000)

    private fun accept(
        orderId: Long,
        at: Instant = now,
        cardNo: String = "1234-5678-9814-1451",
        amount: BigInteger = this.amount,
        responseLost: Boolean = false,
    ) = store.accept(
        PgTransactionStore.Accept(
            orderId = orderId,
            amount = amount,
            cardType = CardType.SAMSUNG,
            cardNo = cardNo,
            at = at,
            responseLost = responseLost,
        ),
    )

    @DisplayName("접수할 때,")
    @Nested
    inner class Accept {
        @DisplayName("처음 접수한 주문은 PENDING 거래로 새로 생긴다.")
        @Test
        fun createsPendingTransaction() {
            val accepted = accept(orderId = 1L)

            assertAll(
                { assertThat(accepted.isNew).isTrue() },
                { assertThat(accepted.transaction.status).isEqualTo(PgStatus.PENDING) },
                { assertThat(accepted.transaction.orderId).isEqualTo(1L) },
                { assertThat(accepted.transaction.amount).isEqualTo(amount) },
                { assertThat(accepted.transaction.requestedAt).isEqualTo(now) },
                { assertThat(accepted.transaction.settledAt).isNull() },
                { assertThat(accepted.transaction.transactionKey).isNotBlank() },
                { assertThat(accepted.transaction.cardType).isEqualTo(CardType.SAMSUNG) },
            )
        }

        @DisplayName("같은 주문을 다시 접수하면 새 거래를 만들지 않고 같은 거래키를 돌려준다.")
        @Test
        fun isIdempotentPerOrder() {
            val first = accept(orderId = 1L)
            val second = accept(orderId = 1L, at = now.plusSeconds(10))

            assertAll(
                { assertThat(second.isNew).isFalse() },
                { assertThat(second.transaction.transactionKey).isEqualTo(first.transaction.transactionKey) },
                { assertThat(second.transaction.requestedAt).isEqualTo(now) },
            )
        }

        @DisplayName("같은 주문에 다른 금액으로 접수하면 금액 불일치로 표시된다.")
        @Test
        fun flagsAmountMismatch() {
            accept(orderId = 1L, amount = BigInteger.valueOf(50_000))

            val again = accept(orderId = 1L, amount = BigInteger.valueOf(99_999))

            assertAll(
                { assertThat(again.amountMismatch).isTrue() },
                { assertThat(again.isNew).isFalse() },
                { assertThat(again.transaction.amount).isEqualTo(BigInteger.valueOf(50_000)) },
                { assertThat(store.findByOrderId(1L)?.amount).isEqualTo(BigInteger.valueOf(50_000)) },
            )
        }

        @DisplayName("같은 주문에 같은 금액이면 금액 불일치가 아니다.")
        @Test
        fun allowsSameAmount() {
            accept(orderId = 1L)

            assertThat(accept(orderId = 1L).amountMismatch).isFalse()
        }

        @DisplayName("다른 주문은 서로 다른 거래키를 받는다.")
        @Test
        fun separatesOrders() {
            val first = accept(orderId = 1L)
            val second = accept(orderId = 2L)

            assertAll(
                { assertThat(second.isNew).isTrue() },
                { assertThat(second.transaction.transactionKey).isNotEqualTo(first.transaction.transactionKey) },
            )
        }

        @DisplayName("같은 주문을 동시에 접수해도 거래는 하나만 생긴다.")
        @Test
        fun createsOneTransactionUnderConcurrency() {
            val count = 32
            val start = CountDownLatch(1)
            val done = CountDownLatch(count)
            val keys = Collections.synchronizedList(mutableListOf<String>())
            val newFlags = Collections.synchronizedList(mutableListOf<Boolean>())
            val pool = Executors.newFixedThreadPool(count)

            repeat(count) {
                pool.submit {
                    try {
                        start.await()
                        val accepted = accept(orderId = 1L)
                        keys.add(accepted.transaction.transactionKey)
                        newFlags.add(accepted.isNew)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            done.await(10, TimeUnit.SECONDS)
            pool.shutdown()

            assertAll(
                { assertThat(keys).hasSize(count) },
                { assertThat(keys.distinct()).hasSize(1) },
                { assertThat(newFlags.count { it }).isEqualTo(1) },
            )
        }

        @DisplayName("카드번호는 뒤 4자리만 남기고 가린다.")
        @Test
        fun masksCardNo() {
            val accepted = accept(orderId = 1L, cardNo = "1234-5678-9814-1451")

            assertAll(
                { assertThat(accepted.transaction.maskedCardNo).isEqualTo("****-****-****-1451") },
                { assertThat(accepted.transaction.maskedCardNo).doesNotContain("1234") },
            )
        }

        @DisplayName("테스트 카드는 접수 시점에 어떤 시나리오인지 정해진다.")
        @Test
        fun resolvesTestCard() {
            assertAll(
                { assertThat(accept(1L, cardNo = "1234-5678-9814-0000").transaction.testCard).isEqualTo(TestCard.DECLINED) },
                { assertThat(accept(2L, cardNo = "1234-5678-9814-1111").transaction.testCard).isEqualTo(TestCard.SLOW) },
                { assertThat(accept(3L, cardNo = "1234-5678-9814-2222").transaction.testCard).isEqualTo(TestCard.SILENT) },
                { assertThat(accept(4L, cardNo = "1234-5678-9814-3333").transaction.testCard).isEqualTo(TestCard.RESPONSE_LOST) },
                { assertThat(accept(5L, cardNo = "1234-5678-9814-1451").transaction.testCard).isNull() },
            )
        }

        @DisplayName("응답 유실 여부는 접수 시점에 거래에 남는다.")
        @Test
        fun keepsResponseLost() {
            assertAll(
                { assertThat(accept(1L, responseLost = true).transaction.dropsCallback).isTrue() },
                { assertThat(accept(2L).transaction.dropsCallback).isFalse() },
            )
        }
    }

    @DisplayName("확정할 때,")
    @Nested
    inner class Settle {
        @DisplayName("PENDING 거래는 확정되고 확정 시각이 남는다.")
        @Test
        fun settlesPendingTransaction() {
            val key = accept(orderId = 1L).transaction.transactionKey
            val settledAt = now.plusSeconds(3)

            val settled = store.settle(key, PgStatus.SUCCESS, settledAt)

            assertAll(
                { assertThat(settled?.status).isEqualTo(PgStatus.SUCCESS) },
                { assertThat(settled?.settledAt).isEqualTo(settledAt) },
                { assertThat(settled?.isFinal).isTrue() },
            )
        }

        @DisplayName("이미 확정된 거래는 다시 확정되지 않는다.")
        @Test
        fun keepsFirstResult() {
            val key = accept(orderId = 1L).transaction.transactionKey
            val firstSettledAt = now.plusSeconds(3)
            store.settle(key, PgStatus.SUCCESS, firstSettledAt)

            val again = store.settle(key, PgStatus.FAILED, now.plusSeconds(9))

            assertAll(
                { assertThat(again?.status).isEqualTo(PgStatus.SUCCESS) },
                { assertThat(again?.settledAt).isEqualTo(firstSettledAt) },
            )
        }

        @DisplayName("없는 거래키를 확정하면 아무것도 돌려주지 않는다.")
        @Test
        fun returnsNullForUnknownKey() {
            assertThat(store.settle("pgtx_unknown", PgStatus.SUCCESS, now)).isNull()
        }
    }

    @DisplayName("조회할 때,")
    @Nested
    inner class Find {
        @DisplayName("거래키로 찾는다.")
        @Test
        fun findsByKey() {
            val key = accept(orderId = 1L).transaction.transactionKey

            assertThat(store.find(key)?.orderId).isEqualTo(1L)
        }

        @DisplayName("주문으로도 찾는다.")
        @Test
        fun findsByOrderId() {
            val key = accept(orderId = 1L).transaction.transactionKey

            assertThat(store.findByOrderId(1L)?.transactionKey).isEqualTo(key)
        }

        @DisplayName("없는 거래는 찾히지 않는다.")
        @Test
        fun findsNothing() {
            assertAll(
                { assertThat(store.find("pgtx_unknown")).isNull() },
                { assertThat(store.findByOrderId(999L)).isNull() },
            )
        }
    }
}
