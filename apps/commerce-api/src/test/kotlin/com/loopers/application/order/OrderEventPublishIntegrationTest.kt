package com.loopers.application.order

import com.loopers.application.payment.PaymentCallback
import com.loopers.application.payment.PaymentCallbackHandler
import com.loopers.domain.brand.Brand
import com.loopers.domain.order.OrderCommand
import com.loopers.domain.order.OrderConfirmedEvent
import com.loopers.domain.order.OrderEventPublisher
import com.loopers.domain.payment.CardInfo
import com.loopers.domain.payment.PaymentGateway
import com.loopers.domain.payment.PaymentMethod
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.payment.PaymentTransaction
import com.loopers.domain.point.PointService
import com.loopers.domain.product.Product
import com.loopers.domain.product.Stock
import com.loopers.domain.shared.Money
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.utils.DatabaseCleanUp
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class OrderEventPublishIntegrationTest @Autowired constructor(
    private val orderFacade: OrderFacade,
    private val paymentCallbackHandler: PaymentCallbackHandler,
    private val pointService: PointService,
    private val productJpaRepository: ProductJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @MockkBean(relaxed = true)
    private lateinit var orderEventPublisher: OrderEventPublisher

    @MockkBean
    private lateinit var paymentGateway: PaymentGateway

    private val userId = 1L
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

    private fun savedProduct(price: Long = 1_000, stock: Int = 5): Product {
        val brand = brandJpaRepository.save(Brand(name = "테스트브랜드"))

        return productJpaRepository.save(
            Product(brandId = brand.id, name = "상품", price = Money(price), stock = Stock(stock)),
        )
    }

    private fun callback(orderId: Long, status: PaymentStatus) = PaymentCallback(
        transactionKey = "pgtx_$orderId",
        orderId = orderId,
        status = status,
        declineCode = if (status == PaymentStatus.FAILED) "LIMIT_EXCEEDED" else null,
        declineReason = if (status == PaymentStatus.FAILED) "한도초과" else null,
    )

    @DisplayName("포인트 결제는 그 자리에서 성공하므로 확정과 동시에 발행된다.")
    @Test
    fun publishesWhenPointPaymentSucceeds() {
        pointService.charge(userId, Money(10_000))
        val product = savedProduct(price = 1_000)
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(product.id, 2))).id
        val event = slot<OrderConfirmedEvent>()
        every { orderEventPublisher.publish(capture(event)) } returns Unit

        orderFacade.confirm(userId, orderId)

        assertAll(
            { verify(exactly = 1) { orderEventPublisher.publish(any()) } },
            { assertThat(event.captured.orderId).isEqualTo(orderId) },
            { assertThat(event.captured.userId).isEqualTo(userId) },
            { assertThat(event.captured.eventType).isEqualTo(OrderConfirmedEvent.TYPE) },
            { assertThat(event.captured.eventId).isEqualTo(OrderConfirmedEvent.idOf(orderId)) },
            { assertThat(event.captured.confirmedAt).isNotBlank() },
            { assertThat(event.captured.items).hasSize(1) },
            { assertThat(event.captured.items.first().productId).isEqualTo(product.id) },
            { assertThat(event.captured.items.first().quantity).isEqualTo(2) },
        )
    }

    @DisplayName("카드 결제가 미확정이면 확정만으로는 발행하지 않는다.")
    @Test
    fun doesNotPublishWhilePaymentIsPending() {
        val product = savedProduct(price = 1_000)
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(product.id, 1))).id

        orderFacade.confirm(userId, orderId, PaymentMethod.CARD, card)

        verify(exactly = 0) { orderEventPublisher.publish(any()) }
    }

    @DisplayName("승인 콜백이 와야 발행된다.")
    @Test
    fun publishesOnApprovalCallback() {
        val product = savedProduct(price = 1_000)
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(product.id, 3))).id
        orderFacade.confirm(userId, orderId, PaymentMethod.CARD, card)
        val event = slot<OrderConfirmedEvent>()
        every { orderEventPublisher.publish(capture(event)) } returns Unit

        paymentCallbackHandler.handle(callback(orderId, PaymentStatus.SUCCESS))

        assertAll(
            { verify(exactly = 1) { orderEventPublisher.publish(any()) } },
            { assertThat(event.captured.orderId).isEqualTo(orderId) },
            { assertThat(event.captured.items.first().quantity).isEqualTo(3) },
        )
    }

    @DisplayName("거절 콜백에는 발행하지 않는다.")
    @Test
    fun doesNotPublishOnDecline() {
        val product = savedProduct(price = 1_000)
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(product.id, 1))).id
        orderFacade.confirm(userId, orderId, PaymentMethod.CARD, card)

        paymentCallbackHandler.handle(callback(orderId, PaymentStatus.FAILED))

        verify(exactly = 0) { orderEventPublisher.publish(any()) }
    }

    @DisplayName("같은 승인 콜백이 두 번 와도 한 번만 발행한다.")
    @Test
    fun publishesOnlyOnceOnDuplicateCallback() {
        val product = savedProduct(price = 1_000)
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(product.id, 1))).id
        orderFacade.confirm(userId, orderId, PaymentMethod.CARD, card)

        paymentCallbackHandler.handle(callback(orderId, PaymentStatus.SUCCESS))
        paymentCallbackHandler.handle(callback(orderId, PaymentStatus.SUCCESS))

        verify(exactly = 1) { orderEventPublisher.publish(any()) }
    }

    @DisplayName("재고가 모자라 확정 트랜잭션이 롤백되면 발행하지 않는다.")
    @Test
    fun doesNotPublishWhenTransactionRollsBack() {
        pointService.charge(userId, Money(10_000))
        val product = savedProduct(price = 1_000, stock = 1)
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(product.id, 2))).id

        runCatching { orderFacade.confirm(userId, orderId) }

        verify(exactly = 0) { orderEventPublisher.publish(any()) }
    }

    @DisplayName("포인트가 모자라 확정이 되돌아가면 발행하지 않는다.")
    @Test
    fun doesNotPublishWhenConfirmRollsBack() {
        val product = savedProduct(price = 10_000)
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(product.id, 1))).id
        pointService.charge(userId, Money(1_000))

        runCatching { orderFacade.confirm(userId, orderId) }

        verify(exactly = 0) { orderEventPublisher.publish(any()) }
    }
}
