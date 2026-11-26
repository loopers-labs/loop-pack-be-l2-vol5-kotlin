package com.loopers.application.payment

import com.loopers.application.order.OrderFacade
import com.loopers.application.payment.PaymentReconciliation.Outcome
import com.loopers.domain.brand.Brand
import com.loopers.domain.order.OrderCommand
import com.loopers.domain.payment.CardInfo
import com.loopers.domain.payment.PaymentGateway
import com.loopers.domain.payment.PaymentMethod
import com.loopers.domain.payment.PaymentRepository
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.payment.PaymentTransaction
import com.loopers.domain.product.Product
import com.loopers.domain.product.Stock
import com.loopers.domain.product.StockReservationRepository
import com.loopers.domain.product.StockReservationStatus
import com.loopers.domain.shared.Money
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.support.error.CoreException
import com.loopers.utils.DatabaseCleanUp
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.client.ResourceAccessException

@SpringBootTest
class PaymentReconciliationIntegrationTest @Autowired constructor(
    private val orderFacade: OrderFacade,
    private val paymentRepository: PaymentRepository,
    private val stockReservationRepository: StockReservationRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @MockkBean
    private lateinit var paymentGateway: PaymentGateway

    private val userId = 1L
    private val card = CardInfo(type = "SAMSUNG", number = "1234-5678-9814-1451")

    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    private fun savedProduct(stock: Int = 5): Product {
        val brand = brandJpaRepository.save(Brand(name = "테스트브랜드"))

        return productJpaRepository.save(
            Product(brandId = brand.id, name = "상품", price = Money(PRICE), stock = Stock(stock)),
        )
    }

    private fun stockOf(productId: Long): Int = productJpaRepository.findById(productId).get().stock.quantity

    private fun pendingOrder(quantity: Int = 2, stock: Int = 5): Pair<Long, Long> {
        val product = savedProduct(stock = stock)
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(product.id, quantity))).id

        every { paymentGateway.request(any(), any(), any()) } answers {
            transaction(orderId = firstArg(), amount = secondArg(), status = PaymentStatus.PENDING)
        }
        orderFacade.confirm(userId, orderId, PaymentMethod.CARD, card)

        return orderId to product.id
    }

    private fun transaction(orderId: Long, amount: Money, status: PaymentStatus) = PaymentTransaction(
        orderId = orderId,
        amount = amount,
        maskedCardNo = "****-****-****-1451",
        status = status,
        transactionKey = "pgtx_$orderId",
    )

    private fun pgReturns(orderId: Long, status: PaymentStatus, amount: Money = Money(PRICE * 2)) {
        every { paymentGateway.findByOrderId(orderId) } returns transaction(orderId, amount, status)
    }

    private fun statusOf(orderId: Long) = paymentRepository.findAllByOrderId(orderId).single().status

    private fun reservationOf(orderId: Long) = stockReservationRepository.findAllByOrderId(orderId).single().status

    @DisplayName("PG 가 SUCCESS 면 결제 생성을 다시 부르지 않고 같은 결제를 SUCCESS 로 반영하며, 잡아둔 재고를 그대로 굳힌다.")
    @Test
    fun appliesSuccessWithoutCreate() {
        val (orderId, productId) = pendingOrder(quantity = 2, stock = 5)
        pgReturns(orderId, PaymentStatus.SUCCESS)

        val reconciled = orderFacade.reconcile(userId, orderId)

        assertAll(
            { assertThat(reconciled.outcome).isEqualTo(Outcome.APPLIED) },
            { assertThat(reconciled.paymentStatus).isEqualTo(PaymentStatus.SUCCESS) },
            { assertThat(reconciled.transactionKey).isEqualTo("pgtx_$orderId") },
            { assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.SUCCESS) },
            { assertThat(reservationOf(orderId)).isEqualTo(StockReservationStatus.COMMITTED) },
            { assertThat(stockOf(productId)).isEqualTo(3) },
            { verify(exactly = 1) { paymentGateway.request(any(), any(), any()) } },
        )
    }

    @DisplayName("두 번 불러도 결제는 하나이고 재고가 다시 줄지 않으며, 두 번째는 바꿀 것이 없다고 답한다.")
    @Test
    fun isIdempotent() {
        val (orderId, productId) = pendingOrder(quantity = 2, stock = 5)
        pgReturns(orderId, PaymentStatus.SUCCESS)

        orderFacade.reconcile(userId, orderId)
        val second = orderFacade.reconcile(userId, orderId)

        assertAll(
            { assertThat(second.outcome).isEqualTo(Outcome.NOTHING_PENDING) },
            { assertThat(second.paymentStatus).isEqualTo(PaymentStatus.SUCCESS) },
            { assertThat(paymentRepository.findAllByOrderId(orderId)).hasSize(1) },
            { assertThat(stockOf(productId)).isEqualTo(3) },
            { verify(exactly = 1) { paymentGateway.findByOrderId(orderId) } },
            { verify(exactly = 1) { paymentGateway.request(any(), any(), any()) } },
        )
    }

    @DisplayName("PG 가 최종 FAILED 면 결제를 FAILED 로 반영하고 잡아둔 재고를 돌려준다.")
    @Test
    fun appliesFailure() {
        val (orderId, productId) = pendingOrder(quantity = 2, stock = 5)
        pgReturns(orderId, PaymentStatus.FAILED)

        val reconciled = orderFacade.reconcile(userId, orderId)

        assertAll(
            { assertThat(reconciled.outcome).isEqualTo(Outcome.APPLIED) },
            { assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.FAILED) },
            { assertThat(reservationOf(orderId)).isEqualTo(StockReservationStatus.RELEASED) },
            { assertThat(stockOf(productId)).isEqualTo(5) },
        )
    }

    @DisplayName("PG 도 아직 PENDING 이면 한 번만 조회하고 상태를 그대로 둔다.")
    @Test
    fun keepsWhenStillPending() {
        val (orderId, productId) = pendingOrder(quantity = 2, stock = 5)
        pgReturns(orderId, PaymentStatus.PENDING)

        val reconciled = orderFacade.reconcile(userId, orderId)

        assertAll(
            { assertThat(reconciled.outcome).isEqualTo(Outcome.STILL_PENDING) },
            { assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.PENDING) },
            { assertThat(reservationOf(orderId)).isEqualTo(StockReservationStatus.RESERVED) },
            { assertThat(stockOf(productId)).isEqualTo(3) },
            { verify(exactly = 1) { paymentGateway.findByOrderId(orderId) } },
        )
    }

    @DisplayName("PG 에 거래가 없으면 상태를 그대로 둔다.")
    @Test
    fun keepsWhenTransactionMissing() {
        val (orderId, _) = pendingOrder()
        every { paymentGateway.findByOrderId(orderId) } returns null

        val reconciled = orderFacade.reconcile(userId, orderId)

        assertAll(
            { assertThat(reconciled.outcome).isEqualTo(Outcome.TRANSACTION_NOT_FOUND) },
            { assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.PENDING) },
            { assertThat(reservationOf(orderId)).isEqualTo(StockReservationStatus.RESERVED) },
        )
    }

    @DisplayName("PG 거래 금액이 우리 결제와 다르면 SUCCESS 여도 반영하지 않는다.")
    @Test
    fun keepsWhenAmountDiffers() {
        val (orderId, _) = pendingOrder()
        pgReturns(orderId, PaymentStatus.SUCCESS, amount = Money(PRICE * 2 + 1))

        val reconciled = orderFacade.reconcile(userId, orderId)

        assertAll(
            { assertThat(reconciled.outcome).isEqualTo(Outcome.MISMATCH) },
            { assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.PENDING) },
            { assertThat(reservationOf(orderId)).isEqualTo(StockReservationStatus.RESERVED) },
        )
    }

    @DisplayName("PG 조회가 실패하면 예외로 끝나고 상태를 그대로 둔다.")
    @Test
    fun keepsWhenLookupFails() {
        val (orderId, _) = pendingOrder()
        every { paymentGateway.findByOrderId(orderId) } throws ResourceAccessException("Read timed out")

        assertThatThrownBy { orderFacade.reconcile(userId, orderId) }
            .isInstanceOf(ResourceAccessException::class.java)

        assertAll(
            { assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.PENDING) },
            { assertThat(reservationOf(orderId)).isEqualTo(StockReservationStatus.RESERVED) },
        )
    }

    @DisplayName("다른 사용자의 주문은 조회도 하지 않고 거절한다.")
    @Test
    fun rejectsOtherUsersOrder() {
        val (orderId, _) = pendingOrder()
        pgReturns(orderId, PaymentStatus.SUCCESS)

        assertThatThrownBy { orderFacade.reconcile(userId + 1, orderId) }
            .isInstanceOf(CoreException::class.java)

        assertAll(
            { assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.PENDING) },
            { verify(exactly = 0) { paymentGateway.findByOrderId(any()) } },
        )
    }

    companion object {
        private const val PRICE = 1_000L
    }
}
