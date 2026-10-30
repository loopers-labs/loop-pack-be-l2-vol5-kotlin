package com.loopers.application.order

import com.loopers.application.payment.PaymentCallback
import com.loopers.application.payment.PaymentCallbackHandler
import com.loopers.domain.brand.Brand
import com.loopers.domain.order.OrderCommand
import com.loopers.domain.payment.CardInfo
import com.loopers.domain.payment.PaymentGateway
import com.loopers.domain.payment.PaymentMethod
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class StockReservationIntegrationTest @Autowired constructor(
    private val orderFacade: OrderFacade,
    private val paymentCallbackHandler: PaymentCallbackHandler,
    private val stockReservationRepository: StockReservationRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @MockkBean
    private lateinit var paymentGateway: PaymentGateway

    private val buyer = 1L
    private val otherBuyer = 2L
    private val card = CardInfo(type = "SAMSUNG", number = "1234-5678-9814-1451")

    @BeforeEach
    fun stubPendingGateway() {
        every { paymentGateway.request(any(), any(), any()) } answers {
            PaymentTransaction(
                orderId = firstArg(),
                amount = secondArg(),
                maskedCardNo = "****-****-****-1451",
                status = PaymentStatus.PENDING,
            )
        }
    }

    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    private fun savedProduct(stock: Int): Product {
        val brand = brandJpaRepository.save(Brand(name = "테스트브랜드"))

        return productJpaRepository.save(
            Product(brandId = brand.id, name = "상품", price = Money(1_000), stock = Stock(stock)),
        )
    }

    private fun stockOf(productId: Long): Int = productJpaRepository.findById(productId).get().stock.quantity

    private fun confirmedOrder(userId: Long, productId: Long, quantity: Int): Long {
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(productId, quantity))).id
        orderFacade.confirm(userId, orderId, PaymentMethod.CARD, card)

        return orderId
    }

    private fun callback(orderId: Long, status: PaymentStatus) = PaymentCallback(
        transactionKey = "pgtx_$orderId",
        orderId = orderId,
        status = status,
        declineCode = if (status == PaymentStatus.FAILED) "LIMIT_EXCEEDED" else null,
        declineReason = if (status == PaymentStatus.FAILED) "한도초과" else null,
    )

    @DisplayName("앞선 주문이 결제 결과를 기다리는 동안 남은 재고를 넘겨 주문할 수 없다.")
    @Test
    fun blocksOversellWhilePaymentIsPending() {
        val product = savedProduct(stock = 2)
        confirmedOrder(buyer, product.id, quantity = 2)

        val laterOrderId = orderFacade.create(otherBuyer, listOf(OrderCommand.Line(product.id, 1))).id
        val result = assertThrows<CoreException> {
            orderFacade.confirm(otherBuyer, laterOrderId, PaymentMethod.CARD, card)
        }

        assertAll(
            { assertThat(result.message).contains("재고가 부족합니다") },
            { assertThat(stockOf(product.id)).isEqualTo(0) },
            { assertThat(orderFacade.get(otherBuyer, laterOrderId).status).isEqualTo("DRAFT") },
        )
    }

    @DisplayName("승인 콜백이 오면 예약이 확정되고 재고는 더 움직이지 않는다.")
    @Test
    fun commitsReservationOnApproval() {
        val product = savedProduct(stock = 5)
        val orderId = confirmedOrder(buyer, product.id, quantity = 2)

        paymentCallbackHandler.handle(callback(orderId, PaymentStatus.SUCCESS))

        assertAll(
            { assertThat(stockOf(product.id)).isEqualTo(3) },
            {
                assertThat(stockReservationRepository.findAllByOrderId(orderId))
                    .allMatch { it.status == StockReservationStatus.COMMITTED }
            },
        )
    }

    @DisplayName("거절 콜백이 오면 예약이 풀리고 다음 주문이 그 재고를 가져갈 수 있다.")
    @Test
    fun releasesReservationOnDecline() {
        val product = savedProduct(stock = 2)
        val orderId = confirmedOrder(buyer, product.id, quantity = 2)

        paymentCallbackHandler.handle(callback(orderId, PaymentStatus.FAILED))
        val laterOrderId = confirmedOrder(otherBuyer, product.id, quantity = 2)

        assertAll(
            { assertThat(stockOf(product.id)).isEqualTo(0) },
            {
                assertThat(stockReservationRepository.findAllByOrderId(orderId))
                    .allMatch { it.status == StockReservationStatus.RELEASED }
            },
            { assertThat(orderFacade.get(otherBuyer, laterOrderId).status).isEqualTo("CONFIRMED") },
        )
    }

    @DisplayName("확정된 주문을 취소하면 예약이 풀려 재고가 돌아온다.")
    @Test
    fun releasesReservationOnCancel() {
        val product = savedProduct(stock = 5)
        val orderId = confirmedOrder(buyer, product.id, quantity = 2)
        paymentCallbackHandler.handle(callback(orderId, PaymentStatus.SUCCESS))

        orderFacade.cancel(buyer, orderId)

        assertAll(
            { assertThat(stockOf(product.id)).isEqualTo(5) },
            {
                assertThat(stockReservationRepository.findAllByOrderId(orderId))
                    .allMatch { it.status == StockReservationStatus.RELEASED }
            },
        )
    }

    @DisplayName("결제가 거절되어 이미 풀린 예약은 주문을 취소해도 재고를 두 번 돌려주지 않는다.")
    @Test
    fun doesNotRestoreTwice() {
        val product = savedProduct(stock = 5)
        val orderId = confirmedOrder(buyer, product.id, quantity = 2)
        paymentCallbackHandler.handle(callback(orderId, PaymentStatus.FAILED))

        orderFacade.cancel(buyer, orderId)

        assertThat(stockOf(product.id)).isEqualTo(5)
    }
}
