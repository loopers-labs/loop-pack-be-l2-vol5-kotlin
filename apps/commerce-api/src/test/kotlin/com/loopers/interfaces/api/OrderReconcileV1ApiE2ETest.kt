package com.loopers.interfaces.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.domain.brand.Brand
import com.loopers.domain.payment.PaymentRepository
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.product.Product
import com.loopers.domain.product.Stock
import com.loopers.domain.shared.Money
import com.loopers.incident.LocalPgFixture
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderReconcileV1ApiE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val objectMapper: ObjectMapper,
    private val paymentRepository: PaymentRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() {
        pg.reset()
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("PG 응답을 잃어 미확인으로 남은 본인 주문을 대사하면, 결제 생성 없이 PG 의 같은 거래로 SUCCESS 가 된다.")
    @Test
    fun reconcilesLostResponse() {
        val orderId = lostResponseOrder()

        val reconciled = post("/api/v1/orders/$orderId/reconcile", USER_ID)

        assertAll(
            { assertThat(reconciled.first).isEqualTo(HttpStatus.OK) },
            { assertThat(reconciled.second.at("/data/outcome").asText()).isEqualTo("APPLIED") },
            { assertThat(reconciled.second.at("/data/paymentStatus").asText()).isEqualTo("SUCCESS") },
            { assertThat(reconciled.second.at("/data/transactionKey").asText()).isEqualTo(pg.transactionOf(orderId)?.transactionKey) },
            { assertThat(paymentRepository.findAllByOrderId(orderId).single().status).isEqualTo(PaymentStatus.SUCCESS) },
            { assertThat(pg.createCountOf(orderId)).isEqualTo(1) },
        )
    }

    @DisplayName("다른 사용자의 주문을 대사하려 하면 404 이고 결제는 그대로 미확인이다.")
    @Test
    fun rejectsOtherUser() {
        val orderId = lostResponseOrder()

        val reconciled = post("/api/v1/orders/$orderId/reconcile", USER_ID + 1)

        assertAll(
            { assertThat(reconciled.first).isEqualTo(HttpStatus.NOT_FOUND) },
            { assertThat(paymentRepository.findAllByOrderId(orderId).single().status).isEqualTo(PaymentStatus.PENDING) },
        )
    }

    private fun lostResponseOrder(): Long {
        val brand = brandJpaRepository.save(Brand(name = "대사브랜드"))
        val product = productJpaRepository.save(
            Product(brandId = brand.id, name = "대사상품", price = Money(PRICE), stock = Stock(5)),
        )
        val created = post(
            "/api/v1/orders",
            USER_ID,
            mapOf("lines" to listOf(mapOf("productId" to product.id, "quantity" to 1))),
        )
        val orderId = created.second.at("/data/id").asLong()
        pg.dropResponseFor(listOf(orderId))

        val confirmed = post(
            "/api/v1/orders/$orderId/confirm",
            USER_ID,
            mapOf("method" to "CARD", "cardType" to "SAMSUNG", "cardNo" to "1234-5678-9814-1451"),
        )
        pg.clearFaults()
        assertThat(confirmed.first).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(paymentRepository.findAllByOrderId(orderId).single().status).isEqualTo(PaymentStatus.PENDING)

        return orderId
    }

    private fun post(path: String, userId: Long, body: Any? = null): Pair<HttpStatus, JsonNode> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-USER-ID", userId.toString())
        }
        val response = testRestTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, headers), String::class.java)

        return HttpStatus.valueOf(response.statusCode.value()) to objectMapper.readTree(response.body)
    }

    companion object {
        private const val USER_ID = 1L
        private const val PRICE = 39_000L

        private val pg = LocalPgFixture(dropHold = Duration.ofMillis(1_500))

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("pg.base-url") { pg.baseUrl }
            registry.add("pg.read-timeout") { "500ms" }
        }

        @JvmStatic
        @AfterAll
        fun closePg() = pg.close()
    }
}
