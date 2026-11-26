package com.loopers.infrastructure.payment

import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PaymentStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.time.ZonedDateTime

interface PaymentJpaRepository : JpaRepository<Payment, Long> {
    fun findByPaymentKey(paymentKey: String): Payment?

    fun findAllByOrderIdOrderByIdAsc(orderId: Long): List<Payment>

    fun countByStatusAndCreatedAtBefore(status: PaymentStatus, createdAt: ZonedDateTime): Long
}
