package com.loopers.incident

import com.loopers.domain.payment.CardInfo
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.shared.Money
import com.loopers.infrastructure.payment.PgClient
import com.loopers.infrastructure.payment.PgProperties
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.web.client.RestClientException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class LocalPgFixtureTest {
    private lateinit var pg: LocalPgFixture
    private lateinit var callbackReceiver: HttpServer
    private val receivedCallbacks = CopyOnWriteArrayList<String>()

    private val card = CardInfo(type = "SAMSUNG", number = "1234-5678-9814-1451")

    @BeforeEach
    fun setUp() {
        receivedCallbacks.clear()
        callbackReceiver = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/api/v1/payments/callback") { exchange ->
                receivedCallbacks.add(String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8))
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        pg = LocalPgFixture(dropHold = Duration.ofMillis(800))
        pg.callbackUrl = "http://localhost:${callbackReceiver.address.port}/api/v1/payments/callback"
    }

    @AfterEach
    fun tearDown() {
        pg.close()
        callbackReceiver.stop(0)
    }

    private fun client(readTimeout: Duration = Duration.ofSeconds(2)) = PgClient(
        PgProperties(
            baseUrl = pg.baseUrl,
            connectTimeout = Duration.ofSeconds(1),
            readTimeout = readTimeout,
        ),
    )

    @DisplayName("접수하면 미확정 거래를 남기고 생성 호출을 한 번 센다.")
    @Test
    fun acceptsAsPending() {
        val transaction = client().request(1L, Money(1_000), card)

        assertAll(
            { assertThat(transaction.status).isEqualTo(PaymentStatus.PENDING) },
            { assertThat(pg.transactionOf(1L)?.status).isEqualTo(LocalPgFixture.PENDING) },
            { assertThat(pg.createCountOf(1L)).isEqualTo(1) },
            { assertThat(pg.duplicateCreates()).isEmpty() },
        )
    }

    @DisplayName("같은 주문으로 다시 접수하면 거래는 하나지만 두 번째 생성 시도도 기록된다.")
    @Test
    fun recordsDuplicateCreate() {
        client().request(1L, Money(1_000), card)
        client().request(1L, Money(1_000), card)

        assertAll(
            { assertThat(pg.transactions()).hasSize(1) },
            { assertThat(pg.createCountOf(1L)).isEqualTo(2) },
            { assertThat(pg.duplicateCreates()).extracting("orderId").containsExactly(1L) },
        )
    }

    @DisplayName("응답 유실 대상은 성공 거래를 저장한 채 클라이언트가 읽기 제한 시간을 넘긴다.")
    @Test
    fun storesSuccessThenDropsResponse() {
        pg.dropResponseFor(listOf(1L))

        assertThatThrownBy { client(readTimeout = Duration.ofMillis(200)).request(1L, Money(1_000), card) }
            .isInstanceOf(RestClientException::class.java)
            .hasRootCauseInstanceOf(SocketTimeoutException::class.java)

        assertAll(
            { assertThat(pg.transactionOf(1L)?.status).isEqualTo(LocalPgFixture.SUCCESS) },
            { assertThat(client().findByOrderId(1L)?.status).isEqualTo(PaymentStatus.SUCCESS) },
            { assertThat(pg.createCountOf(1L)).isEqualTo(1) },
        )
    }

    @DisplayName("확정하면 성공 콜백을 보낸다.")
    @Test
    fun settlesWithCallback() {
        client().request(1L, Money(1_000), card)

        val status = pg.settle(1L)

        assertAll(
            { assertThat(status).isEqualTo(200) },
            { assertThat(pg.transactionOf(1L)?.status).isEqualTo(LocalPgFixture.SUCCESS) },
            { assertThat(receivedCallbacks).singleElement().asString().contains("\"orderId\":1", "\"status\":\"SUCCESS\"") },
        )
    }

    @DisplayName("콜백 보류 대상은 확정해도 콜백을 보내지 않고, 보류를 풀면 다시 보낼 수 있다.")
    @Test
    fun holdsCallback() {
        client().request(1L, Money(1_000), card)
        pg.holdCallbackFor(listOf(1L))

        val held = pg.settle(1L)
        pg.clearFaults()
        val released = pg.settle(1L)

        assertAll(
            { assertThat(held).isNull() },
            { assertThat(released).isEqualTo(200) },
            { assertThat(receivedCallbacks).hasSize(1) },
            { assertThat(pg.faultsCleared()).isTrue() },
        )
    }
}
