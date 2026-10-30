package com.loopers.application.order

import com.loopers.domain.brand.Brand
import com.loopers.domain.order.OrderCommand
import com.loopers.domain.order.OrderRepository
import com.loopers.domain.payment.PaymentRepository
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.point.PointService
import com.loopers.domain.product.Product
import com.loopers.domain.product.Stock
import com.loopers.domain.shared.Money
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.util.Properties

@SpringBootTest
@TestPropertySource(
    properties = [
        "order-event.topic=order.events.unpublished",
        "spring.kafka.producer.bootstrap-servers=localhost:1",
        "spring.kafka.producer.properties.max.block.ms=500",
        "spring.kafka.producer.properties.request.timeout.ms=500",
        "spring.kafka.producer.properties.delivery.timeout.ms=1000",
    ],
)
class OrderEventUnpublishedIntegrationTest @Autowired constructor(
    private val orderFacade: OrderFacade,
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val pointService: PointService,
    private val productJpaRepository: ProductJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    private fun savedProduct(stock: Int = 5): Product {
        val brand = brandJpaRepository.save(Brand(name = "테스트브랜드"))

        return productJpaRepository.save(
            Product(brandId = brand.id, name = "상품", price = Money(1_000), stock = Stock(stock)),
        )
    }

    private fun recordsIn(topic: String): Long {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, REACHABLE_BROKERS)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java)
            put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
            put(ConsumerConfig.GROUP_ID_CONFIG, "unpublished-probe")
        }

        KafkaConsumer<String, ByteArray>(props).use { consumer ->
            val partitions = consumer.partitionsFor(topic)
                ?.map { org.apache.kafka.common.TopicPartition(it.topic(), it.partition()) }
                ?: return 0

            if (partitions.isEmpty()) return 0

            return consumer.endOffsets(partitions).values.sum()
        }
    }

    @DisplayName("브로커에 닿지 못해 발행이 실패해도 주문 확정은 되돌아가지 않는다.")
    @Test
    fun keepsOrderConfirmedWhenPublishFails() {
        pointService.charge(USER, Money(10_000))
        val product = savedProduct()
        val orderId = orderFacade.create(USER, listOf(OrderCommand.Line(product.id, 2))).id

        orderFacade.confirm(USER, orderId)

        assertAll(
            { assertThat(orderRepository.find(orderId)?.isConfirmed()).isTrue() },
            { assertThat(paymentRepository.findAllByOrderId(orderId).first().status).isEqualTo(PaymentStatus.SUCCESS) },
            { assertThat(productJpaRepository.findById(product.id).get().stock.quantity).isEqualTo(3) },
        )
    }

    @DisplayName("발행에 실패한 그 한 건은 토픽에 남지 않는다.")
    @Test
    fun leavesNothingInTheTopic() {
        pointService.charge(USER, Money(10_000))
        val product = savedProduct()
        val orderId = orderFacade.create(USER, listOf(OrderCommand.Line(product.id, 1))).id

        orderFacade.confirm(USER, orderId)

        assertAll(
            { assertThat(paymentRepository.findAllByOrderId(orderId).first().status).isEqualTo(PaymentStatus.SUCCESS) },
            { assertThat(recordsIn("order.events.unpublished")).isZero() },
        )
    }

    companion object {
        private const val USER = 1L
        private const val REACHABLE_BROKERS = "localhost:19092,localhost:19093,localhost:19094"
    }
}
