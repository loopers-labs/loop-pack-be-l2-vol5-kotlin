package com.loopers.application.payment

import com.loopers.application.order.OrderFacade
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
import com.loopers.domain.shared.Money
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.utils.DatabaseCleanUp
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class PaymentCallbackHandlerIntegrationTest @Autowired constructor(
    private val paymentCallbackHandler: PaymentCallbackHandler,
    private val paymentRepository: PaymentRepository,
    private val orderFacade: OrderFacade,
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
            Product(brandId = brand.id, name = "상품", price = Money(1_000), stock = Stock(stock)),
        )
    }

    private fun stockOf(productId: Long): Int = productJpaRepository.findById(productId).get().stock.quantity

    private fun pendingOrder(quantity: Int = 2, stock: Int = 5): Pair<Long, Long> {
        val product = savedProduct(stock = stock)
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(product.id, quantity))).id

        every { paymentGateway.request(any(), any(), any()) } answers {
            PaymentTransaction(
                orderId = firstArg(),
                amount = secondArg(),
                maskedCardNo = "****-****-****-1451",
                status = PaymentStatus.PENDING,
            )
        }
        orderFacade.confirm(userId, orderId, PaymentMethod.CARD, card)

        return orderId to product.id
    }

    private fun callback(
        status: PaymentStatus,
        orderId: Long,
        transactionKey: String = "pgtx_1",
    ) = PaymentCallback(
        transactionKey = transactionKey,
        orderId = orderId,
        status = status,
        declineCode = if (status == PaymentStatus.FAILED) "LIMIT_EXCEEDED" else null,
        declineReason = if (status == PaymentStatus.FAILED) "한도초과" else null,
    )

    private fun statusOf(orderId: Long) = paymentRepository.findAllByOrderId(orderId).first().status

    @DisplayName("승인 콜백이 오면 미확정 결제가 SUCCESS 가 되고, 확정 때 잡아둔 재고가 그대로 굳는다.")
    @Test
    fun approvesPendingAndKeepsReservedStock() {
        val (orderId, productId) = pendingOrder(quantity = 2, stock = 5)
        val reservedStock = stockOf(productId)

        paymentCallbackHandler.handle(callback(PaymentStatus.SUCCESS, orderId))

        assertAll(
            { assertThat(reservedStock).isEqualTo(3) },
            { assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.SUCCESS) },
            { assertThat(stockOf(productId)).isEqualTo(3) },
        )
    }

    @DisplayName("같은 승인 콜백이 두 번 와도 결과가 같고, 재고가 두 번 줄지 않는다.")
    @Test
    fun isIdempotent() {
        val (orderId, productId) = pendingOrder(quantity = 2, stock = 5)

        paymentCallbackHandler.handle(callback(PaymentStatus.SUCCESS, orderId))
        paymentCallbackHandler.handle(callback(PaymentStatus.SUCCESS, orderId))

        assertAll(
            { assertThat(paymentRepository.findAllByOrderId(orderId)).hasSize(1) },
            { assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.SUCCESS) },
            { assertThat(stockOf(productId)).isEqualTo(3) },
        )
    }

    @DisplayName("다른 주문의 결제는 건드리지 않는다.")
    @Test
    fun touchesOnlyItsOwnOrder() {
        val (mine, _) = pendingOrder()
        val (others, _) = pendingOrder()

        paymentCallbackHandler.handle(callback(PaymentStatus.SUCCESS, mine))

        assertAll(
            { assertThat(statusOf(mine)).isEqualTo(PaymentStatus.SUCCESS) },
            { assertThat(statusOf(others)).isEqualTo(PaymentStatus.PENDING) },
        )
    }

    @DisplayName("바꿀 결제가 없어도 터지지 않는다.")
    @Test
    fun survivesMissingPayment() {
        paymentCallbackHandler.handle(callback(PaymentStatus.SUCCESS, orderId = 9999L))

        assertThat(paymentRepository.findAllByOrderId(9999L)).isEmpty()
    }

    @DisplayName("거절 콜백이 오면 미확정 결제가 FAILED 가 되고, 잡아둔 재고가 풀린다.")
    @Test
    fun declinesPendingAndReleasesStock() {
        val (orderId, productId) = pendingOrder(quantity = 2, stock = 5)
        val reservedStock = stockOf(productId)

        paymentCallbackHandler.handle(callback(PaymentStatus.FAILED, orderId))

        assertAll(
            { assertThat(reservedStock).isEqualTo(3) },
            { assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.FAILED) },
            { assertThat(stockOf(productId)).isEqualTo(5) },
        )
    }

    @DisplayName("같은 거절 콜백이 두 번 와도 재고가 한 번만 풀린다.")
    @Test
    fun releasesStockOnlyOnce() {
        val (orderId, productId) = pendingOrder(quantity = 2, stock = 5)

        paymentCallbackHandler.handle(callback(PaymentStatus.FAILED, orderId))
        paymentCallbackHandler.handle(callback(PaymentStatus.FAILED, orderId))

        assertThat(stockOf(productId)).isEqualTo(5)
    }

    @DisplayName("이미 SUCCESS 인 결제는 거절 콜백이 와도 되돌아가지 않는다.")
    @Test
    fun doesNotRevertSuccess() {
        val (orderId, _) = pendingOrder()
        paymentCallbackHandler.handle(callback(PaymentStatus.SUCCESS, orderId))

        paymentCallbackHandler.handle(callback(PaymentStatus.FAILED, orderId))

        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.SUCCESS)
    }

    @DisplayName("이미 SUCCESS 인 결제는 미확정 콜백이 와도 되돌아가지 않는다.")
    @Test
    fun doesNotRevertSettled() {
        val (orderId, _) = pendingOrder()
        paymentCallbackHandler.handle(callback(PaymentStatus.SUCCESS, orderId))

        paymentCallbackHandler.handle(callback(PaymentStatus.PENDING, orderId))

        assertThat(statusOf(orderId)).isEqualTo(PaymentStatus.SUCCESS)
    }
}
