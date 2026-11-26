package com.loopers.infrastructure.payment

import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.payment.PaymentTransaction
import com.loopers.domain.shared.Money

object PgDto {
    data class PaymentRequest(
        val orderId: Long,
        val amount: Long,
        val cardType: String,
        val cardNo: String,
    )

    data class PaymentResponse(
        val orderId: Long,
        val amount: Long,
        val maskedCardNo: String,
        val status: PaymentStatus,
        val declineReason: String? = null,
        val transactionKey: String? = null,
    ) {
        fun toTransaction() = PaymentTransaction(
            orderId = orderId,
            amount = Money(amount),
            maskedCardNo = maskedCardNo,
            status = status,
            declineReason = declineReason,
            transactionKey = transactionKey,
        )
    }
}
