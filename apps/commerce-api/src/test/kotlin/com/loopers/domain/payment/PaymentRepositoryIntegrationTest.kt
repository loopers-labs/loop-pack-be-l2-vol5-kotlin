package com.loopers.domain.payment

import com.loopers.domain.shared.Money
import com.loopers.utils.DatabaseCleanUp
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException

@SpringBootTest
class PaymentRepositoryIntegrationTest @Autowired constructor(
    private val paymentRepository: PaymentRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    @DisplayName("주문과 금액을 저장한다.")
    @Test
    fun saves() {
        val saved = paymentRepository.save(Payment(userId = 1L, orderId = 1L, amount = Money(50_000)))

        assertAll(
            { assertThat(saved.id).isPositive() },
            { assertThat(saved.orderId).isEqualTo(1L) },
            { assertThat(saved.amount).isEqualTo(Money(50_000)) },
            { assertThat(saved.status).isEqualTo(PaymentStatus.PENDING) },
            { assertThat(saved.createdAt).isNotNull() },
        )
    }

    @DisplayName("주문으로 찾는다.")
    @Test
    fun findsByOrderId() {
        paymentRepository.save(Payment(userId = 1L, orderId = 1L, amount = Money(50_000)))
        paymentRepository.save(Payment(userId = 1L, orderId = 2L, amount = Money(30_000)))

        assertAll(
            { assertThat(paymentRepository.findAllByOrderId(1L)).hasSize(1) },
            { assertThat(paymentRepository.findAllByOrderId(1L).first().amount).isEqualTo(Money(50_000)) },
            { assertThat(paymentRepository.findAllByOrderId(99L)).isEmpty() },
        )
    }

    @DisplayName("상태를 바꾼다.")
    @Test
    fun changesStatus() {
        val saved = paymentRepository.save(Payment(userId = 1L, orderId = 1L, amount = Money(50_000)))

        saved.markSuccess()
        paymentRepository.save(saved)

        assertThat(paymentRepository.findAllByOrderId(1L).first().status).isEqualTo(PaymentStatus.SUCCESS)
    }

    @DisplayName("한 주문에 여러 건을 남길 수 있다.")
    @Test
    fun allowsManyPerOrder() {
        paymentRepository.save(Payment(userId = 1L, orderId = 1L, amount = Money(50_000)))
        paymentRepository.save(Payment(userId = 1L, orderId = 1L, amount = Money(50_000)))

        assertThat(paymentRepository.findAllByOrderId(1L)).hasSize(2)
    }

    @DisplayName("저장하면 멱등키가 함께 남는다.")
    @Test
    fun issuesPaymentKey() {
        val saved = paymentRepository.save(Payment(userId = 1L, orderId = 1L, amount = Money(50_000)))

        assertAll(
            { assertThat(saved.paymentKey).isNotBlank() },
            { assertThat(saved.paymentKey).hasSize(36) },
        )
    }

    @DisplayName("한 주문의 두 시도는 서로 다른 멱등키를 받는다.")
    @Test
    fun issuesDistinctKeysPerAttempt() {
        val first = paymentRepository.save(Payment(userId = 1L, orderId = 1L, amount = Money(50_000)))
        val second = paymentRepository.save(Payment(userId = 1L, orderId = 1L, amount = Money(50_000)))

        assertThat(first.paymentKey).isNotEqualTo(second.paymentKey)
    }

    @DisplayName("멱등키로 찾는다.")
    @Test
    fun findsByPaymentKey() {
        val saved = paymentRepository.save(Payment(userId = 1L, orderId = 1L, amount = Money(50_000)))

        assertAll(
            { assertThat(paymentRepository.findByPaymentKey(saved.paymentKey)?.id).isEqualTo(saved.id) },
            { assertThat(paymentRepository.findByPaymentKey("없는-키")).isNull() },
        )
    }

    @DisplayName("같은 멱등키로는 두 번 남길 수 없다.")
    @Test
    fun rejectsDuplicateKey() {
        val key = "11111111-2222-3333-4444-555555555555"
        paymentRepository.save(Payment(userId = 1L, orderId = 1L, amount = Money(50_000), paymentKey = key))

        assertThatThrownBy {
            paymentRepository.save(Payment(userId = 1L, orderId = 2L, amount = Money(30_000), paymentKey = key))
            entityManager.flush()
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
