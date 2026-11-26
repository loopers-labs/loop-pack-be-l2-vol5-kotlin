package com.loopers.domain.payment

data class PaymentResult(
    val method: PaymentMethod,
    val status: PaymentStatus,
    val transactionKey: String? = null,
) {
    val isFinal: Boolean
        get() = status != PaymentStatus.PENDING
}
