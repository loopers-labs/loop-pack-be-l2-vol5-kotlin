package com.loopers.domain.payment

import java.time.ZonedDateTime

interface PaymentRepository {
    fun save(payment: Payment): Payment

    fun find(id: Long): Payment?

    fun findByPaymentKey(paymentKey: String): Payment?

    fun findAllByOrderId(orderId: Long): List<Payment>

    fun countPendingCreatedBefore(threshold: ZonedDateTime): Long
}
