package com.loopers.pg

import java.math.BigInteger
import java.time.Instant

enum class PgStatus {
    PENDING,
    SUCCESS,
    FAILED,
}

data class PgTransaction(
    val transactionKey: String,
    val orderId: Long,
    val amount: BigInteger,
    val cardType: CardType,
    val maskedCardNo: String,
    val status: PgStatus,
    val requestedAt: Instant,
    val settledAt: Instant? = null,
    val declineReason: DeclineReason? = null,
    val testCard: TestCard? = null,
    val responseLost: Boolean = false,
) {
    val dropsCallback: Boolean
        get() = responseLost || testCard?.dropsCallback == true

    val isFinal: Boolean
        get() = status != PgStatus.PENDING

    fun settle(next: PgStatus, at: Instant, reason: DeclineReason? = null): PgTransaction =
        if (isFinal) this else copy(status = next, settledAt = at, declineReason = reason)
}
