package com.loopers.pg

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
@RequestMapping("/api/v1/payments")
class PgV1Controller(
    private val store: PgTransactionStore,
    private val callbackSender: PgCallbackSender,
    private val properties: PgSimulatorProperties,
    private val responseLossRoulette: ResponseLossRoulette,
) {
    @PostMapping
    fun request(
        @RequestBody request: PgV1Dto.PaymentRequest,
    ): ResponseEntity<PgV1Dto.PaymentResponse> {
        if (request.cardNo.filter { it.isDigit() }.length < 12) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "카드번호 형식이 올바르지 않습니다.")
        }

        val testCard = TestCard.of(request.cardNo)
        val responseLost = testCard == null && responseLossRoulette.spin()
        val delay = maxOf(
            properties.responseDelay.toMillis(),
            if (responseLost || testCard?.delaysResponse == true) properties.slowCardDelay.toMillis() else 0,
        )
        if (delay > 0) {
            Thread.sleep(delay)
        }

        val accepted = store.accept(
            PgTransactionStore.Accept(
                orderId = request.orderId,
                amount = request.amount,
                cardType = request.cardType,
                cardNo = request.cardNo,
                at = Instant.now(),
                responseLost = responseLost,
            ),
        )

        if (accepted.amountMismatch) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "이미 다른 금액으로 접수된 주문입니다. " +
                    "[orderId = ${request.orderId}, 접수된 금액 = ${accepted.transaction.amount}, 요청 금액 = ${request.amount}]",
            )
        }

        if (accepted.isNew) {
            callbackSender.scheduleSettlement(accepted.transaction.transactionKey)
        }

        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(PgV1Dto.PaymentResponse.from(accepted.transaction))
    }

    @GetMapping("/{transactionKey}")
    fun get(
        @PathVariable(value = "transactionKey") transactionKey: String,
    ): PgV1Dto.PaymentResponse =
        store.find(transactionKey)
            ?.let { PgV1Dto.PaymentResponse.from(it) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "거래를 찾을 수 없습니다. [transactionKey = $transactionKey]")

    @GetMapping
    fun getByOrderId(
        @RequestParam(value = "orderId") orderId: Long,
    ): PgV1Dto.PaymentResponse =
        store.findByOrderId(orderId)
            ?.let { PgV1Dto.PaymentResponse.from(it) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "거래를 찾을 수 없습니다. [orderId = $orderId]")
}
