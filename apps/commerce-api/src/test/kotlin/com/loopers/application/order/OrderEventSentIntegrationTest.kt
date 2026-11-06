package com.loopers.application.order

import com.loopers.domain.brand.Brand
import com.loopers.domain.order.OrderCommand
import com.loopers.domain.outbox.OutboxStatus
import com.loopers.domain.point.PointService
import com.loopers.domain.product.Product
import com.loopers.domain.product.Stock
import com.loopers.domain.shared.Money
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.outbox.OutboxEventJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.apache.kafka.clients.admin.NewTopic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.kafka.config.TopicBuilder
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(properties = ["order-event.topic=$TOPIC"])
class OrderEventSentIntegrationTest @Autowired constructor(
    private val orderFacade: OrderFacade,
    private val pointService: PointService,
    private val productJpaRepository: ProductJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    private fun savedProduct(): Product {
        val brand = brandJpaRepository.save(Brand(name = "테스트브랜드"))

        return productJpaRepository.save(
            Product(brandId = brand.id, name = "상품", price = Money(1_000), stock = Stock(5)),
        )
    }

    private fun statusEventually(expected: OutboxStatus): OutboxStatus? {
        val deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS
        var status = outboxEventJpaRepository.findAll().singleOrNull()?.status

        while (status != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            status = outboxEventJpaRepository.findAll().singleOrNull()?.status
        }

        return status
    }

    @DisplayName("주문을 확정하고 브로커가 받았다고 답하면 아웃박스 행이 SENT 가 된다.")
    @Test
    fun marksSentAfterBrokerAck() {
        pointService.charge(USER, Money(10_000))
        val product = savedProduct()
        val orderId = orderFacade.create(USER, listOf(OrderCommand.Line(product.id, 1))).id

        orderFacade.confirm(USER, orderId)

        assertThat(statusEventually(OutboxStatus.SENT)).isEqualTo(OutboxStatus.SENT)
    }

    @TestConfiguration
    class ProbeTopic {
        @Bean
        fun sentProbeTopic(): NewTopic = TopicBuilder.name(TOPIC).partitions(3).replicas(3).build()
    }

    companion object {
        private const val USER = 1L
        private const val ACK_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 200L
    }
}

private const val TOPIC = "order.events.sent-probe"
