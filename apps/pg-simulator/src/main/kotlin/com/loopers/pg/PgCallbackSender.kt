package com.loopers.pg

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Component
class PgCallbackSender(
    private val properties: PgSimulatorProperties,
    private val store: PgTransactionStore,
    private val declineRoulette: DeclineRoulette,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.create()
    private val scheduler = Executors.newSingleThreadScheduledExecutor(
        ThreadFactory { runnable -> Thread(runnable, "pg-callback").apply { isDaemon = true } },
    )

    fun scheduleSettlement(transactionKey: String) {
        scheduler.schedule(
            { settleAndNotify(transactionKey) },
            properties.callbackDelay.toMillis(),
            TimeUnit.MILLISECONDS,
        )
    }

    private fun settleAndNotify(transactionKey: String) {
        val pending = store.find(transactionKey) ?: return
        val outcome = decide(pending)
        val settled = store.settle(transactionKey, outcome.status, Instant.now(), outcome.reason) ?: return

        if (shouldDropCallback(settled)) {
            log.warn("콜백 드롭. key={} status={}", settled.transactionKey, settled.status)
            return
        }

        runCatching {
            restClient.post()
                .uri(properties.callbackUrl)
                .body(
                    PgCallback(
                        transactionKey = settled.transactionKey,
                        orderId = settled.orderId,
                        status = settled.status,
                        amount = settled.amount.toString(),
                        declineCode = settled.declineReason?.name,
                        declineReason = settled.declineReason?.message,
                        settledAt = settled.settledAt,
                    ),
                )
                .retrieve()
                .toBodilessEntity()
        }.onFailure {
            log.warn("콜백 발신 실패. key={} cause={}", settled.transactionKey, it.message)
        }
    }

    private fun decide(pending: PgTransaction): Outcome {
        if (pending.testCard == TestCard.DECLINED) {
            return Outcome(PgStatus.FAILED, DeclineReason.LIMIT_EXCEEDED)
        }

        return declineRoulette.spin()
            ?.let { Outcome(PgStatus.FAILED, it) }
            ?: Outcome(PgStatus.SUCCESS, null)
    }

    private fun shouldDropCallback(settled: PgTransaction): Boolean =
        settled.dropsCallback || Random.nextDouble() < properties.callbackDropRate

    private data class Outcome(
        val status: PgStatus,
        val reason: DeclineReason?,
    )

    data class PgCallback(
        val transactionKey: String,
        val orderId: Long,
        val status: PgStatus,
        val amount: String,
        val declineCode: String?,
        val declineReason: String?,
        val settledAt: Instant?,
    )
}
