package com.loopers.pg

import org.springframework.stereotype.Component
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class PgTransactionStore {
    private val byKey = ConcurrentHashMap<String, PgTransaction>()
    private val keyByOrderId = ConcurrentHashMap<Long, String>()

    fun accept(command: Accept): Accepted {
        var createdKey: String? = null

        val key = keyByOrderId.computeIfAbsent(command.orderId) {
            val generated = "pgtx_" + UUID.randomUUID().toString().replace("-", "").take(20)
            byKey[generated] = PgTransaction(
                transactionKey = generated,
                orderId = command.orderId,
                amount = command.amount,
                cardType = command.cardType,
                maskedCardNo = CardNo.mask(command.cardNo),
                status = PgStatus.PENDING,
                requestedAt = command.at,
                testCard = TestCard.of(command.cardNo),
                responseLost = command.responseLost,
            )
            createdKey = generated
            generated
        }

        val transaction = byKey.getValue(key)

        return Accepted(
            transaction = transaction,
            isNew = createdKey != null,
            amountMismatch = createdKey == null && transaction.amount != command.amount,
        )
    }

    fun find(transactionKey: String): PgTransaction? = byKey[transactionKey]

    fun findByOrderId(orderId: Long): PgTransaction? = keyByOrderId[orderId]?.let { byKey[it] }

    fun settle(transactionKey: String, next: PgStatus, at: Instant, reason: DeclineReason? = null): PgTransaction? =
        byKey.compute(transactionKey) { _, current -> current?.settle(next, at, reason) }

    data class Accept(
        val orderId: Long,
        val amount: BigInteger,
        val cardType: CardType,
        val cardNo: String,
        val at: Instant,
        val responseLost: Boolean = false,
    )

    data class Accepted(
        val transaction: PgTransaction,
        val isNew: Boolean,
        val amountMismatch: Boolean = false,
    )
}
