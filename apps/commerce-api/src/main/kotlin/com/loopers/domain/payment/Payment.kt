package com.loopers.domain.payment

import com.loopers.domain.BaseEntity
import com.loopers.domain.shared.Money
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.ZonedDateTime
import java.util.UUID

@Entity
@Table(
    name = "payment",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_payment_key", columnNames = ["payment_key"]),
    ],
    indexes = [
        Index(name = "idx_payment_order", columnList = "order_id"),
        Index(name = "idx_payment_user", columnList = "user_id"),
    ],
)
class Payment(
    userId: Long,
    orderId: Long,
    amount: Money,
    status: PaymentStatus = PaymentStatus.PENDING,
    paymentKey: String = UUID.randomUUID().toString(),
) : BaseEntity() {
    @Column(name = "payment_key", nullable = false, updatable = false, length = 36)
    var paymentKey: String = paymentKey
        protected set

    @Column(name = "user_id", nullable = false, updatable = false)
    var userId: Long = userId
        protected set

    @Column(name = "order_id", nullable = false, updatable = false)
    var orderId: Long = orderId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: PaymentStatus = status
        protected set

    @Embedded
    @AttributeOverride(column = Column(name = "amount", nullable = false), name = "amount")
    var amount: Money = amount
        protected set

    @Column(name = "succeeded_at")
    var succeededAt: ZonedDateTime? = null
        protected set

    /**
     * 최초 성공 시각은 한 번만 적는다. 중복 콜백이 구매 시각을 뒤로 밀면 안 된다.
     */
    fun markSuccess() {
        status = PaymentStatus.SUCCESS
        succeededAt = succeededAt ?: ZonedDateTime.now()
    }

    fun markFailed() {
        status = PaymentStatus.FAILED
    }
}
