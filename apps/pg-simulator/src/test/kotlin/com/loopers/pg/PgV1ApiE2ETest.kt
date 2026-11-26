package com.loopers.pg

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import java.math.BigInteger

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "pg.callback-delay=1h",
        "pg.response-lost-rate=0",
        "pg.callback-url=http://localhost:1/callback",
    ],
)
class PgV1ApiE2ETest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
) {
    private val amount: BigInteger = BigInteger.valueOf(50_000)

    private fun request(
        orderId: Long,
        amount: BigInteger = this.amount,
        cardNo: String = "1234-5678-9814-1451",
    ) = testRestTemplate.postForEntity(
        "/api/v1/payments",
        PgV1Dto.PaymentRequest(
            orderId = orderId,
            amount = amount,
            cardType = CardType.SAMSUNG,
            cardNo = cardNo,
        ),
        PgV1Dto.PaymentResponse::class.java,
    )

    @DisplayName("POST /api/v1/payments")
    @Nested
    inner class Request {
        @DisplayName("접수하면 202 와 PENDING 거래를 돌려준다.")
        @Test
        fun acceptsPayment() {
            val response = request(orderId = 1001L)

            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED) },
                { assertThat(response.body?.status).isEqualTo(PgStatus.PENDING) },
                { assertThat(response.body?.orderId).isEqualTo(1001L) },
                { assertThat(response.body?.amount).isEqualTo(amount) },
                { assertThat(response.body?.transactionKey).isNotBlank() },
                { assertThat(response.body?.settledAt).isNull() },
                { assertThat(response.body?.cardType).isEqualTo(CardType.SAMSUNG) },
                { assertThat(response.body?.maskedCardNo).isEqualTo("****-****-****-1451") },
            )
        }

        @DisplayName("같은 주문으로 다시 접수하면 같은 거래키를 돌려준다.")
        @Test
        fun isIdempotentPerOrder() {
            val first = request(orderId = 1002L)
            val second = request(orderId = 1002L)

            assertAll(
                { assertThat(second.statusCode).isEqualTo(HttpStatus.ACCEPTED) },
                { assertThat(second.body?.transactionKey).isEqualTo(first.body?.transactionKey) },
            )
        }

        @DisplayName("같은 주문에 다른 금액으로 접수하면 409 다.")
        @Test
        fun rejectsAmountMismatch() {
            request(orderId = 1006L, amount = BigInteger.valueOf(50_000))

            val response = testRestTemplate.postForEntity(
                "/api/v1/payments",
                PgV1Dto.PaymentRequest(
                    orderId = 1006L,
                    amount = BigInteger.valueOf(99_999),
                    cardType = CardType.SAMSUNG,
                    cardNo = "1234-5678-9814-1451",
                ),
                String::class.java,
            )

            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT) },
                {
                    assertThat(
                        testRestTemplate.getForEntity(
                            "/api/v1/payments?orderId=1006",
                            PgV1Dto.PaymentResponse::class.java,
                        ).body?.amount,
                    ).isEqualTo(BigInteger.valueOf(50_000))
                },
            )
        }

        @DisplayName("카드번호가 짧으면 거절한다.")
        @Test
        fun rejectsMalformedCardNo() {
            val response = testRestTemplate.postForEntity(
                "/api/v1/payments",
                PgV1Dto.PaymentRequest(
                    orderId = 1004L,
                    amount = amount,
                    cardType = CardType.SAMSUNG,
                    cardNo = "1234",
                ),
                String::class.java,
            )

            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }

        @DisplayName("응답에 카드번호 원본이 들어가지 않는다.")
        @Test
        fun neverEchoesFullCardNo() {
            val body = testRestTemplate.postForEntity(
                "/api/v1/payments",
                PgV1Dto.PaymentRequest(
                    orderId = 1005L,
                    amount = amount,
                    cardType = CardType.SAMSUNG,
                    cardNo = "1234-5678-9814-1451",
                ),
                String::class.java,
            ).body

            assertAll(
                { assertThat(body).doesNotContain("1234-5678") },
                { assertThat(body).contains("****-****-****-1451") },
            )
        }

        @DisplayName("확정되지 않은 거래의 응답에는 settledAt 키가 들어가지 않는다.")
        @Test
        fun omitsNullFields() {
            val body = testRestTemplate.postForEntity(
                "/api/v1/payments",
                PgV1Dto.PaymentRequest(
                    orderId = 1003L,
                    amount = amount,
                    cardType = CardType.SAMSUNG,
                    cardNo = "1234-5678-9814-1451",
                ),
                String::class.java,
            ).body

            assertAll(
                { assertThat(body).contains("\"status\":\"PENDING\"") },
                { assertThat(body).doesNotContain("settledAt") },
            )
        }
    }

    @DisplayName("GET /api/v1/payments/{transactionKey}")
    @Nested
    inner class Get {
        @DisplayName("접수한 거래를 거래키로 조회한다.")
        @Test
        fun findsByKey() {
            val key = request(orderId = 2001L).body?.transactionKey

            val response = testRestTemplate.getForEntity(
                "/api/v1/payments/$key",
                PgV1Dto.PaymentResponse::class.java,
            )

            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body?.transactionKey).isEqualTo(key) },
                { assertThat(response.body?.status).isEqualTo(PgStatus.PENDING) },
            )
        }

        @DisplayName("없는 거래키는 404 다.")
        @Test
        fun rejectsUnknownKey() {
            val response = testRestTemplate.getForEntity(
                "/api/v1/payments/pgtx_unknown",
                String::class.java,
            )

            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @DisplayName("GET /api/v1/payments?orderId=")
    @Nested
    inner class GetByOrderId {
        @DisplayName("주문으로 거래를 조회한다.")
        @Test
        fun findsByOrderId() {
            val key = request(orderId = 3001L).body?.transactionKey

            val response = testRestTemplate.getForEntity(
                "/api/v1/payments?orderId=3001",
                PgV1Dto.PaymentResponse::class.java,
            )

            assertAll(
                { assertThat(response.statusCode).isEqualTo(HttpStatus.OK) },
                { assertThat(response.body?.transactionKey).isEqualTo(key) },
            )
        }

        @DisplayName("접수되지 않은 주문은 404 다.")
        @Test
        fun rejectsUnknownOrder() {
            val response = testRestTemplate.getForEntity(
                "/api/v1/payments?orderId=999999",
                String::class.java,
            )

            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }
}
