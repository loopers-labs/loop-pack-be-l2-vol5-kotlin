package com.loopers.application.payment

import com.loopers.domain.payment.Payment
import com.loopers.domain.payment.PaymentGateway
import com.loopers.domain.payment.PaymentRepository
import com.loopers.domain.payment.PaymentStatus
import com.loopers.domain.payment.PaymentTransaction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class PaymentReconciliation(
    private val paymentRepository: PaymentRepository,
    private val paymentGateway: PaymentGateway,
    private val paymentSettlement: PaymentSettlement,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun reconcile(orderId: Long): Reconciled {
        val payments = paymentRepository.findAllByOrderId(orderId)
        val pending = payments.filter { it.status == PaymentStatus.PENDING }

        if (pending.isEmpty()) {
            return Reconciled(orderId, Outcome.NOTHING_PENDING, payments.maxByOrNull { it.id }?.status, null)
        }
        if (pending.size > 1) {
            return record(pending.first(), Outcome.MULTIPLE_PENDING, null)
        }

        val payment = pending.single()
        val transaction = paymentGateway.findByOrderId(orderId)

        val outcome = when {
            transaction == null -> Outcome.TRANSACTION_NOT_FOUND
            !transaction.matches(payment) -> Outcome.MISMATCH
            transaction.status == PaymentStatus.PENDING -> Outcome.STILL_PENDING
            else -> apply(payment.id, transaction.status)
        }

        return record(payment, outcome, transaction)
    }

    private fun apply(paymentId: Long, status: PaymentStatus): Outcome =
        transactionTemplate.execute {
            val payment = paymentRepository.find(paymentId)
            if (payment == null || payment.status != PaymentStatus.PENDING) {
                Outcome.NOTHING_PENDING
            } else {
                paymentSettlement.reflect(payment, status)
                Outcome.APPLIED
            }
        } ?: Outcome.NOTHING_PENDING

    private fun PaymentTransaction.matches(payment: Payment): Boolean =
        orderId == payment.orderId && amount == payment.amount

    private fun record(payment: Payment, outcome: Outcome, transaction: PaymentTransaction?): Reconciled {
        val status = paymentRepository.find(payment.id)?.status ?: payment.status
        log.info(
            "결제 대사. orderId={} paymentKey={} transactionKey={} pgStatus={} outcome={} paymentStatus={}",
            payment.orderId,
            payment.paymentKey,
            transaction?.transactionKey,
            transaction?.status,
            outcome,
            status,
        )

        return Reconciled(payment.orderId, outcome, status, transaction?.transactionKey)
    }

    data class Reconciled(
        val orderId: Long,
        val outcome: Outcome,
        val paymentStatus: PaymentStatus?,
        val transactionKey: String?,
    )

    enum class Outcome {
        APPLIED,
        STILL_PENDING,
        TRANSACTION_NOT_FOUND,
        MISMATCH,
        MULTIPLE_PENDING,
        NOTHING_PENDING,
    }
}
