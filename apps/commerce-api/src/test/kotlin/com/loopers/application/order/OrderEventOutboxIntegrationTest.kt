package com.loopers.application.order

import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.payment.PaymentCallback
import com.loopers.application.payment.PaymentCallbackHandler
import com.loopers.domain.brand.Brand
import com.loopers.domain.order.OrderCommand
import com.loopers.domain.order.OrderConfirmedEvent
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.order.OrderStatus
import com.loopers.domain.outbox.OutboxEventRepository
import com.loopers.domain.outbox.OutboxStatus
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
import com.loopers.infrastructure.outbox.OutboxEventJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.utils.DatabaseCleanUp
import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.SpykBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.kafka.producer.bootstrap-servers=localhost:1",
        "spring.kafka.producer.properties.max.block.ms=500",
        "spring.kafka.producer.properties.request.timeout.ms=500",
        "spring.kafka.producer.properties.delivery.timeout.ms=1000",
    ],
)
class OrderEventOutboxIntegrationTest @Autowired constructor(
    private val orderFacade: OrderFacade,
    private val orderRepository: OrderRepository,
    private val paymentCallbackHandler: PaymentCallbackHandler,
    private val pointService: PointService,
    private val productJpaRepository: ProductJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
    @Value("\${order-event.topic}") private val topic: String,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @SpykBean
    private lateinit var outboxEventRepository: OutboxEventRepository

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

    private fun approval(orderId: Long) = PaymentCallback(
        transactionKey = "pgtx_$orderId",
        orderId = orderId,
        status = PaymentStatus.SUCCESS,
        declineCode = null,
        declineReason = null,
    )

    @DisplayName("포인트 결제가 성공하면 확정 이벤트가 아웃박스에 PENDING 으로 쌓인다.")
    @Test
    fun appendsConfirmedEventWhenPointPaymentSucceeds() {
        pointService.charge(userId, Money(10_000))
        val product = savedProduct()
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(product.id, 2))).id

        orderFacade.confirm(userId, orderId)

        val rows = outboxEventJpaRepository.findAll()
        val row = rows.single()
        val payload = objectMapper.readValue(row.payload, OrderConfirmedEvent::class.java)
        assertAll(
            { assertThat(row.eventId).isEqualTo(OrderConfirmedEvent.idOf(orderId)) },
            { assertThat(row.topic).isEqualTo(topic) },
            { assertThat(row.eventType).isEqualTo(OrderConfirmedEvent.TYPE) },
            { assertThat(row.schemaVersion).isEqualTo(1) },
            { assertThat(row.key).isEqualTo(orderId.toString()) },
            { assertThat(row.status).isEqualTo(OutboxStatus.PENDING) },
            { assertThat(payload.eventId).isEqualTo(row.eventId) },
            { assertThat(payload.schemaVersion).isEqualTo(row.schemaVersion) },
            { assertThat(payload.orderId).isEqualTo(orderId) },
            { assertThat(payload.items.single().quantity).isEqualTo(2) },
        )
    }

    @DisplayName("카드 결제가 미확정이면 쌓지 않고, 승인 콜백이 오면 쌓는다.")
    @Test
    fun appendsOnlyAfterApproval() {
        val product = savedProduct()
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(product.id, 1))).id

        orderFacade.confirm(userId, orderId, PaymentMethod.CARD, card)
        val beforeApproval = outboxEventJpaRepository.count()
        paymentCallbackHandler.handle(approval(orderId))

        assertAll(
            { assertThat(beforeApproval).isZero() },
            { assertThat(outboxEventJpaRepository.findAll().single().key).isEqualTo(orderId.toString()) },
        )
    }

    @DisplayName("포인트가 모자라 확정이 되돌아가면 쌓지 않는다.")
    @Test
    fun doesNotAppendWhenConfirmRollsBack() {
        val product = savedProduct(price = 10_000)
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(product.id, 1))).id
        pointService.charge(userId, Money(1_000))

        runCatching { orderFacade.confirm(userId, orderId) }

        assertAll(
            { assertThat(orderRepository.find(orderId)?.status).isEqualTo(OrderStatus.DRAFT) },
            { assertThat(outboxEventJpaRepository.count()).isZero() },
        )
    }

    @DisplayName("아웃박스에 쓰지 못하면 확정도 되돌아가 재고가 그대로다.")
    @Test
    fun rollsBackConfirmWhenAppendFails() {
        pointService.charge(userId, Money(10_000))
        val product = savedProduct(stock = 5)
        val orderId = orderFacade.create(userId, listOf(OrderCommand.Line(product.id, 2))).id
        every { outboxEventRepository.save(any()) } throws IllegalStateException("아웃박스 저장 실패")

        val result = runCatching { orderFacade.confirm(userId, orderId) }

        assertAll(
            { assertThat(result.isFailure).isTrue() },
            { assertThat(orderRepository.find(orderId)?.status).isEqualTo(OrderStatus.DRAFT) },
            { assertThat(productJpaRepository.findById(product.id).get().stock.quantity).isEqualTo(5) },
            { assertThat(outboxEventJpaRepository.count()).isZero() },
        )
    }
}
