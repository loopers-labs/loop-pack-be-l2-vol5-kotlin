package com.loopers.incident

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.order.OrderFacade
import com.loopers.domain.brand.Brand
import com.loopers.domain.payment.PaymentRepository
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.point.PointService
import com.loopers.domain.product.Product
import com.loopers.domain.product.Stock
import com.loopers.domain.product.StockReservationRepository
import com.loopers.domain.product.StockReservationStatus
import com.loopers.domain.shared.Money
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.outbox.OutboxEventJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Clock
import java.time.Duration
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension::class)
@AutoConfigureObservability(tracing = false)
class CheckoutIncidentTest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val objectMapper: ObjectMapper,
    private val paymentRepository: PaymentRepository,
    private val stockReservationRepository: StockReservationRepository,
    private val pointService: PointService,
    private val productJpaRepository: ProductJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @LocalServerPort
    private var serverPort: Int = 0

    @LocalManagementPort
    private var managementPort: Int = 0

    @BeforeEach
    fun setUp() {
        databaseCleanUp.truncateAllTables()
        clock.set(BASELINE_SETTLED_AT)
        pg.callbackUrl = "http://localhost:$serverPort/api/v1/payments/callback"
    }

    @AfterEach
    fun tearDown() {
        pg.reset()
        databaseCleanUp.truncateAllTables()
    }

    @DisplayName("정상 주문 10건은 PG 거래 10건으로 한 번씩 결제되고, 콜백을 받아 모두 성공으로 끝난다.")
    @Test
    fun baseline() {
        val product = savedProduct(price = PRICE, stock = INITIAL_STOCK)

        runBaseline(product)
    }

    @DisplayName("정상 기준선 뒤 장애 집합 10건 중 고정한 3건만 미확인으로 남고, 같은 3건을 대사하면 새 결제 없이 장애 집합 10건이 모두 성공하고 Outbox 에 한 번씩 기록된다.")
    @Test
    fun recoversIncidentWithoutNewPayment(output: CapturedOutput) {
        val product = savedProduct(price = PRICE, stock = INITIAL_STOCK)
        val baselineOrderIds = runBaseline(product)

        val users = (1L..ORDER_COUNT).map { FAULT_USER_OFFSET + it }
        users.forEach { pointService.charge(it, Money(INITIAL_POINT)) }
        val orderIds = users.map { userId -> userId to placeOrder(userId, product.id) }
        val faultOrderIds = FAULT_POSITIONS.map { orderIds[it].second }
        val normalOrderIds = orderIds.map { it.second } - faultOrderIds.toSet()

        val confirmed: Map<Long, HttpStatus>
        val callbacks: Map<Long, Int?>
        try {
            pg.dropResponseFor(faultOrderIds)
            pg.holdCallbackFor(faultOrderIds)
            confirmed = orderIds.associate { (userId, orderId) -> orderId to confirm(userId, orderId).first }
            clock.set(FAULT_SETTLED_AT)
            callbacks = orderIds.associate { (_, orderId) -> orderId to pg.settle(orderId) }
        } finally {
            pg.clearFaults()
        }

        val faultSet = orderIds.map { it.second }
        val payments = faultSet.flatMap { paymentRepository.findAllByOrderId(it) }
        val pending = payments.filter { it.status == PaymentStatus.PENDING }
        val reservations = faultSet.flatMap { stockReservationRepository.findAllByOrderId(it) }
        val stalePending = awaitStalePending(expected = FAULT_POSITIONS.size.toDouble())
        val health = managementGet("/actuator/health")

        assertAll(
            { assertThat(faultSet).doesNotHaveDuplicates().hasSize(ORDER_COUNT.toInt()).doesNotContainAnyElementsOf(baselineOrderIds) },
            { assertThat(faultOrderIds.map { confirmed[it] }).containsOnly(HttpStatus.INTERNAL_SERVER_ERROR) },
            { assertThat(normalOrderIds.map { confirmed[it] }).containsOnly(HttpStatus.OK) },
            { assertThat(faultOrderIds.map { callbacks[it] }).containsOnlyNulls() },
            { assertThat(normalOrderIds.map { callbacks[it] }).containsOnly(200) },
            { assertThat(payments).hasSize(ORDER_COUNT.toInt()) },
            { assertThat(payments.count { it.status == PaymentStatus.SUCCESS }).isEqualTo(ORDER_COUNT.toInt() - FAULT_POSITIONS.size) },
            { assertThat(pending.map { it.orderId }).containsExactlyInAnyOrderElementsOf(faultOrderIds) },
            { assertThat(faultSet.map { pg.transactionOf(it)?.status }).containsOnly(LocalPgFixture.SUCCESS) },
            { assertThat(faultSet.map { pg.transactionOf(it)?.amount }).containsOnly(PRICE) },
            { assertThat(pg.transactions()).hasSize(2 * ORDER_COUNT.toInt()) },
            { assertThat(pg.createAttempts()).hasSize(2 * ORDER_COUNT.toInt()) },
            { assertThat(pg.duplicateCreates()).isEmpty() },
            { assertThat(reservations.filter { it.orderId in faultOrderIds }).hasSize(FAULT_POSITIONS.size).allSatisfy { assertThat(it.status).isEqualTo(StockReservationStatus.RESERVED) } },
            { assertThat(reservations.filter { it.orderId in normalOrderIds }).allSatisfy { assertThat(it.status).isEqualTo(StockReservationStatus.COMMITTED) } },
            { assertThat(productJpaRepository.findById(product.id).get().stock).isEqualTo(Stock(INITIAL_STOCK - 2 * ORDER_COUNT.toInt())) },
            { assertThat(users.map { pointService.getBalance(it) }).containsOnly(Money(INITIAL_POINT)) },
            {
                pending.forEach {
                    assertThat(output.out).contains(
                        "PG 결제 응답을 받지 못했다. orderId=${it.orderId} paymentKey=${it.paymentKey} failure=${OrderFacade.PG_TIMEOUT}",
                    )
                }
            },
            { assertThat(stalePending).isEqualTo(FAULT_POSITIONS.size.toDouble()) },
            { assertThat(health.get("status").asText()).isEqualTo("UP") },
            { assertThat(pg.faultsCleared()).isTrue() },
        )

        val faultOwners = orderIds.filter { it.second in faultOrderIds }
        val transactionKeysBefore = faultSet.map { pg.transactionOf(it)?.transactionKey }
        val createsBefore = pg.createAttempts().size

        clock.set(RECOVERED_AT)
        val reconciled = faultOwners.map { (userId, orderId) -> post("/api/v1/orders/$orderId/reconcile", userId, emptyMap<String, Any>()) }
        val outboxAfterRecovery = outboxOf(faultSet)
        val repeated = faultOwners.map { (userId, orderId) -> post("/api/v1/orders/$orderId/reconcile", userId, emptyMap<String, Any>()) }
        val lateCallbacks = faultOrderIds.map { pg.settle(it) }

        val recoveredPayments = faultSet.flatMap { paymentRepository.findAllByOrderId(it) }
        val recoveredReservations = faultSet.flatMap { stockReservationRepository.findAllByOrderId(it) }
        val outboxAfterRepeat = outboxOf(faultSet)
        val incidentOutbox = outboxOf(baselineOrderIds + faultSet)
        val stalePendingAfterRecovery = awaitStalePending(expected = 0.0)
        val healthAfterRecovery = managementGet("/actuator/health")

        assertAll(
            { assertThat(reconciled.map { it.first }).containsOnly(HttpStatus.OK) },
            { assertThat(reconciled.map { it.second.at("/data/outcome").asText() }).containsOnly("APPLIED") },
            { assertThat(repeated.map { it.second.at("/data/outcome").asText() }).containsOnly("NOTHING_PENDING") },
            { assertThat(lateCallbacks).containsOnly(200) },
            { assertThat(recoveredPayments).hasSize(ORDER_COUNT.toInt()) },
            { assertThat(recoveredPayments).allSatisfy { assertThat(it.status).isEqualTo(PaymentStatus.SUCCESS) } },
            { assertThat(faultSet.map { pg.transactionOf(it)?.transactionKey }).isEqualTo(transactionKeysBefore) },
            { assertThat(faultSet.map { pg.transactionOf(it)?.status }).containsOnly(LocalPgFixture.SUCCESS) },
            { assertThat(pg.createAttempts()).hasSize(createsBefore) },
            { assertThat(faultSet.map { pg.createCountOf(it) }).containsOnly(1) },
            { assertThat(pg.duplicateCreates()).isEmpty() },
            { assertThat(recoveredReservations).hasSize(ORDER_COUNT.toInt()) },
            { assertThat(recoveredReservations).allSatisfy { assertThat(it.status).isEqualTo(StockReservationStatus.COMMITTED) } },
            { assertThat(productJpaRepository.findById(product.id).get().stock).isEqualTo(Stock(INITIAL_STOCK - 2 * ORDER_COUNT.toInt())) },
            { assertThat(users.map { pointService.getBalance(it) }).containsOnly(Money(INITIAL_POINT)) },
            { assertThat(outboxAfterRecovery.map { it.key }).containsExactlyInAnyOrderElementsOf(faultSet.map { it.toString() }) },
            { assertThat(outboxAfterRepeat.map { it.eventId }).isEqualTo(outboxAfterRecovery.map { it.eventId }) },
            { assertThat(incidentOutbox.sortedBy { it.key.toLong() }.map { objectMapper.readTree(it.payload) }).containsExactlyElementsOf(incidentContract()) },
            {
                faultOrderIds.forEach {
                    assertThat(output.out).contains("결제 대사. orderId=$it ").contains("outcome=APPLIED")
                }
            },
            { assertThat(stalePendingAfterRecovery).isEqualTo(0.0) },
            { assertThat(healthAfterRecovery.get("status").asText()).isEqualTo("UP") },
            { assertThat(pg.faultsCleared()).isTrue() },
        )
    }

    @DisplayName("PG 응답을 잃은 주문은 경계 로그에 주문과 timeout 원인이 남고, 기준 시간이 지나면 미확인 결제 지표가 1이 된다. health 는 계속 UP 이다.")
    @Test
    fun observesLostResponse(output: CapturedOutput) {
        val product = savedProduct(price = PRICE, stock = INITIAL_STOCK)
        val userId = OBSERVATION_USER_OFFSET + 1
        pointService.charge(userId, Money(INITIAL_POINT))
        val orderId = placeOrder(userId, product.id)
        pg.dropResponseFor(listOf(orderId))

        val confirmed = confirm(userId, orderId)
        val beforeThreshold = stalePending()
        val afterThreshold = awaitStalePending(expected = 1.0)
        val payment = paymentRepository.findAllByOrderId(orderId).single()
        val health = managementGet("/actuator/health")

        assertAll(
            { assertThat(confirmed.first).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR) },
            { assertThat(payment.status).isEqualTo(PaymentStatus.PENDING) },
            { assertThat(pg.transactionOf(orderId)?.status).isEqualTo(LocalPgFixture.SUCCESS) },
            { assertThat(output.out).contains("PG 결제 요청. orderId=$orderId paymentKey=${payment.paymentKey}") },
            {
                assertThat(output.out).contains(
                    "PG 결제 응답을 받지 못했다. orderId=$orderId paymentKey=${payment.paymentKey} failure=${OrderFacade.PG_TIMEOUT}",
                )
            },
            { assertThat(beforeThreshold).isEqualTo(0.0) },
            { assertThat(afterThreshold).isEqualTo(1.0) },
            { assertThat(health.get("status").asText()).isEqualTo("UP") },
        )
    }

    private fun runBaseline(product: Product): List<Long> {
        val users = (1L..ORDER_COUNT).map { BASELINE_USER_OFFSET + it }
        users.forEach { pointService.charge(it, Money(INITIAL_POINT)) }

        assertThat(pg.faultsCleared()).isTrue()
        val orderIds = users.map { userId -> placeAndConfirm(userId, product.id) }
        orderIds.forEach { assertThat(pg.settle(it)).isEqualTo(200) }

        val payments = orderIds.flatMap { paymentRepository.findAllByOrderId(it) }
        val reservations = orderIds.flatMap { stockReservationRepository.findAllByOrderId(it) }
        val health = managementGet("/actuator/health")

        assertAll(
            { assertThat(orderIds).doesNotHaveDuplicates().hasSize(ORDER_COUNT.toInt()) },
            { assertThat(payments).hasSize(ORDER_COUNT.toInt()) },
            { assertThat(payments).allSatisfy { assertThat(it.status).isEqualTo(PaymentStatus.SUCCESS) } },
            { assertThat(payments).allSatisfy { assertThat(it.amount).isEqualTo(Money(PRICE)) } },
            { assertThat(orderIds.map { pg.transactionOf(it)?.status }).containsOnly(LocalPgFixture.SUCCESS) },
            { assertThat(orderIds.map { pg.transactionOf(it)?.amount }).containsOnly(PRICE) },
            { assertThat(orderIds.map { pg.createCountOf(it) }).containsOnly(1) },
            { assertThat(pg.createAttempts()).hasSize(ORDER_COUNT.toInt()) },
            { assertThat(pg.duplicateCreates()).isEmpty() },
            { assertThat(reservations).hasSize(ORDER_COUNT.toInt()) },
            { assertThat(reservations).allSatisfy { assertThat(it.status).isEqualTo(StockReservationStatus.COMMITTED) } },
            { assertThat(productJpaRepository.findById(product.id).get().stock).isEqualTo(Stock(INITIAL_STOCK - ORDER_COUNT.toInt())) },
            { assertThat(users.map { pointService.getBalance(it) }).containsOnly(Money(INITIAL_POINT)) },
            { assertThat(health.get("status").asText()).isEqualTo("UP") },
        )

        return orderIds
    }

    private fun savedProduct(price: Long, stock: Int): Product {
        val brand = brandJpaRepository.save(Brand(name = "사고실험브랜드"))

        return productJpaRepository.save(
            Product(brandId = brand.id, name = "사고실험상품", price = Money(price), stock = Stock(stock)),
        )
    }

    private fun placeAndConfirm(userId: Long, productId: Long): Long {
        val orderId = placeOrder(userId, productId)
        assertThat(confirm(userId, orderId).first).isEqualTo(HttpStatus.OK)

        return orderId
    }

    private fun placeOrder(userId: Long, productId: Long): Long {
        val created = post(
            "/api/v1/orders",
            userId,
            mapOf("lines" to listOf(mapOf("productId" to productId, "quantity" to 1))),
        )
        assertThat(created.first).isEqualTo(HttpStatus.OK)

        return created.second.get("data").get("id").asLong()
    }

    private fun confirm(userId: Long, orderId: Long): Pair<HttpStatus, JsonNode> =
        post(
            "/api/v1/orders/$orderId/confirm",
            userId,
            mapOf("method" to "CARD", "cardType" to CARD_TYPE, "cardNo" to CARD_NO),
        )

    private fun incidentContract() =
        requireNotNull(javaClass.getResource(INCIDENT_CONTRACT)).readText().lines().filter { it.isNotBlank() }.map { objectMapper.readTree(it) }

    private fun outboxOf(orderIds: List<Long>) =
        orderIds.map { it.toString() }.toSet().let { keys -> outboxEventJpaRepository.findAll().filter { it.key in keys }.sortedBy { it.id } }

    private fun stalePending(): Double? {
        val body = testRestTemplate.getForEntity("http://localhost:$managementPort/actuator/prometheus", String::class.java).body

        return STALE_PENDING_LINE.find(body.orEmpty())?.groupValues?.get(1)?.toDouble()
    }

    private fun awaitStalePending(expected: Double): Double? {
        val deadline = System.nanoTime() + STALE_PENDING_WAIT.toNanos()
        var value = stalePending()
        while (value != expected && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL.toMillis())
            value = stalePending()
        }

        return value
    }

    private fun post(path: String, userId: Long, body: Any): Pair<HttpStatus, JsonNode> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-USER-ID", userId.toString())
        }
        val response = testRestTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, headers), String::class.java)

        return HttpStatus.valueOf(response.statusCode.value()) to objectMapper.readTree(response.body)
    }

    private fun managementGet(path: String): JsonNode {
        val response = testRestTemplate.getForEntity("http://localhost:$managementPort$path", String::class.java)

        return objectMapper.readTree(response.body)
    }

    @TestConfiguration
    class FixedClockConfig {
        @Bean
        @Primary
        fun incidentClock(): Clock = clock
    }

    companion object {
        private const val ORDER_COUNT = 10L
        private const val BASELINE_USER_OFFSET = 1_000L
        private const val PRICE = 39_000L
        private const val INITIAL_STOCK = 30
        private const val INITIAL_POINT = 50_000L
        private const val CARD_TYPE = "SAMSUNG"
        private const val CARD_NO = "1234-5678-9814-1451"
        private const val OBSERVATION_USER_OFFSET = 2_000L
        private const val FAULT_USER_OFFSET = 3_000L
        private val FAULT_POSITIONS = listOf(1, 4, 7)

        private val STALE_PENDING_AFTER = Duration.ofSeconds(2)
        private val STALE_PENDING_WAIT = Duration.ofSeconds(5)
        private val POLL_INTERVAL = Duration.ofMillis(200)
        private val STALE_PENDING_LINE = Regex("""(?m)^payment_pending_stale(?:\{[^}]*})? (\S+)$""")

        private const val INCIDENT_CONTRACT = "/contract/order-confirmed-incident-2026-08-17.jsonl"
        private val BASELINE_SETTLED_AT = Instant.parse("2026-08-18T10:00:00Z")
        private val FAULT_SETTLED_AT = Instant.parse("2026-08-19T10:00:00Z")
        private val RECOVERED_AT = Instant.parse("2026-08-20T10:00:00Z")

        private val pg = LocalPgFixture(dropHold = Duration.ofMillis(1_500))
        private val clock = MutableClock(BASELINE_SETTLED_AT)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("pg.base-url") { pg.baseUrl }
            registry.add("pg.read-timeout") { "500ms" }
            registry.add("management.server.port") { "0" }
            registry.add("payment.metrics.stale-pending-after") { STALE_PENDING_AFTER.toString() }
        }

        @JvmStatic
        @AfterAll
        fun closePg() = pg.close()
    }
}
