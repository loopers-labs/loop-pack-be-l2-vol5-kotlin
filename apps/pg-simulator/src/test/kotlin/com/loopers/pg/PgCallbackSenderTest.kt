package com.loopers.pg

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.math.BigInteger
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PgCallbackSenderTest {
    private lateinit var server: HttpServer
    private lateinit var received: MutableList<Map<*, *>>
    private lateinit var arrived: CountDownLatch
    private var responseStatus = 200

    private val store = PgTransactionStore()
    private val objectMapper = ObjectMapper()
    private val amount: BigInteger = BigInteger.valueOf(50_000)

    @BeforeEach
    fun setUp() {
        received = Collections.synchronizedList(mutableListOf())
        arrived = CountDownLatch(1)
        responseStatus = 200

        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/callback") { exchange ->
            received.add(objectMapper.readValue(exchange.requestBody, Map::class.java))
            exchange.sendResponseHeaders(responseStatus, -1)
            exchange.close()
            arrived.countDown()
        }
        server.start()
    }

    @AfterEach
    fun tearDown() = server.stop(0)

    private fun sender(
        decline: Map<DeclineReason, Double> = emptyMap(),
        callbackDropRate: Double = 0.0,
    ): PgCallbackSender {
        val properties = PgSimulatorProperties(
            callbackUrl = "http://localhost:${server.address.port}/callback",
            callbackDelay = Duration.ofMillis(50),
            callbackDropRate = callbackDropRate,
            decline = decline,
        )

        return PgCallbackSender(properties, store, DeclineRoulette(properties))
    }

    private fun acceptedKey(
        orderId: Long,
        cardNo: String = "1234-5678-9814-1451",
        responseLost: Boolean = false,
    ) =
        store.accept(
            PgTransactionStore.Accept(
                orderId = orderId,
                amount = amount,
                cardType = CardType.SAMSUNG,
                cardNo = cardNo,
                at = Instant.now(),
                responseLost = responseLost,
            ),
        ).transaction.transactionKey

    @DisplayName("지연이 지나면 거래를 확정하고 콜백을 보낸다.")
    @Test
    fun sendsCallbackAfterDelay() {
        val key = acceptedKey(1L)

        sender().scheduleSettlement(key)

        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue()
        assertAll(
            { assertThat(received).hasSize(1) },
            { assertThat(received.first()["transactionKey"]).isEqualTo(key) },
            { assertThat(received.first()["orderId"]).isEqualTo(1) },
            { assertThat(received.first()["status"]).isEqualTo("SUCCESS") },
            { assertThat(received.first()["amount"]).isEqualTo("50000") },
            { assertThat(store.find(key)?.status).isEqualTo(PgStatus.SUCCESS) },
        )
    }

    @DisplayName("거절 확률이 1이면 그 사유로 FAILED 가 되고 콜백에 코드와 사유가 실린다.")
    @Test
    fun sendsDeclinedCallback() {
        val key = acceptedKey(2L)

        sender(decline = mapOf(DeclineReason.INSUFFICIENT_BALANCE to 1.0)).scheduleSettlement(key)

        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue()
        assertAll(
            { assertThat(received.first()["status"]).isEqualTo("FAILED") },
            { assertThat(received.first()["declineCode"]).isEqualTo("INSUFFICIENT_BALANCE") },
            { assertThat(received.first()["declineReason"]).isEqualTo("잔액부족") },
            { assertThat(store.find(key)?.status).isEqualTo(PgStatus.FAILED) },
            { assertThat(store.find(key)?.declineReason).isEqualTo(DeclineReason.INSUFFICIENT_BALANCE) },
        )
    }

    @DisplayName("콜백 드롭률이 1이면 콜백은 오지 않지만 거래는 확정된다.")
    @Test
    fun dropsCallbackButStillSettles() {
        val key = acceptedKey(3L)

        sender(callbackDropRate = 1.0).scheduleSettlement(key)

        assertAll(
            { assertThat(arrived.await(1, TimeUnit.SECONDS)).isFalse() },
            { assertThat(received).isEmpty() },
            { assertThat(store.find(key)?.status).isEqualTo(PgStatus.SUCCESS) },
            { assertThat(store.find(key)?.settledAt).isNotNull() },
        )
    }

    @DisplayName("콜백 수신 측이 실패로 응답해도 다시 보내지 않는다.")
    @Test
    fun doesNotRetry() {
        responseStatus = 500
        val key = acceptedKey(4L)

        sender().scheduleSettlement(key)

        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue()
        Thread.sleep(500)
        assertAll(
            { assertThat(received).hasSize(1) },
            { assertThat(store.find(key)?.status).isEqualTo(PgStatus.SUCCESS) },
        )
    }

    @DisplayName("한도초과 카드는 확률과 무관하게 거절된다.")
    @Test
    fun declinesTestCard() {
        val key = acceptedKey(5L, cardNo = "1234-5678-9814-0000")

        sender().scheduleSettlement(key)

        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue()
        assertAll(
            { assertThat(received.first()["status"]).isEqualTo("FAILED") },
            { assertThat(received.first()["declineCode"]).isEqualTo("LIMIT_EXCEEDED") },
            { assertThat(received.first()["declineReason"]).isEqualTo("한도초과") },
            { assertThat(store.find(key)?.status).isEqualTo(PgStatus.FAILED) },
        )
    }

    @DisplayName("콜백 유실 카드는 확정은 되지만 콜백이 오지 않는다.")
    @Test
    fun dropsCallbackForSilentCard() {
        val key = acceptedKey(6L, cardNo = "1234-5678-9814-2222")

        sender().scheduleSettlement(key)

        assertAll(
            { assertThat(arrived.await(1, TimeUnit.SECONDS)).isFalse() },
            { assertThat(received).isEmpty() },
            { assertThat(store.find(key)?.status).isEqualTo(PgStatus.SUCCESS) },
        )
    }

    @DisplayName("응답 유실 카드도 확정은 되지만 콜백이 오지 않는다.")
    @Test
    fun dropsCallbackForResponseLostCard() {
        val key = acceptedKey(8L, cardNo = "1234-5678-9814-3333")

        sender().scheduleSettlement(key)

        assertAll(
            { assertThat(arrived.await(1, TimeUnit.SECONDS)).isFalse() },
            { assertThat(received).isEmpty() },
            { assertThat(store.find(key)?.status).isEqualTo(PgStatus.SUCCESS) },
        )
    }

    @DisplayName("응답 유실로 뽑힌 거래도 확정은 되지만 콜백이 오지 않는다.")
    @Test
    fun dropsCallbackForResponseLostTransaction() {
        val key = acceptedKey(9L, responseLost = true)

        sender().scheduleSettlement(key)

        assertAll(
            { assertThat(arrived.await(1, TimeUnit.SECONDS)).isFalse() },
            { assertThat(received).isEmpty() },
            { assertThat(store.find(key)?.status).isEqualTo(PgStatus.SUCCESS) },
        )
    }

    @DisplayName("거절 확률이 모두 0이면 승인되고 거절 사유가 없다.")
    @Test
    fun approvesWhenNoDeclineConfigured() {
        val key = acceptedKey(7L)

        sender().scheduleSettlement(key)

        assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue()
        assertAll(
            { assertThat(received.first()["status"]).isEqualTo("SUCCESS") },
            { assertThat(received.first()["declineCode"]).isNull() },
            { assertThat(store.find(key)?.declineReason).isNull() },
        )
    }

    @DisplayName("없는 거래를 확정하려 하면 콜백을 보내지 않는다.")
    @Test
    fun ignoresUnknownTransaction() {
        sender().scheduleSettlement("pgtx_unknown")

        assertAll(
            { assertThat(arrived.await(1, TimeUnit.SECONDS)).isFalse() },
            { assertThat(received).isEmpty() },
        )
    }
}
