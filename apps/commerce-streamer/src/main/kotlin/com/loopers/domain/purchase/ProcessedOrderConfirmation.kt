package com.loopers.domain.purchase

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "processed_order_confirmation",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_processed_confirmation_event", columnNames = ["event_id"]),
    ],
    indexes = [
        Index(name = "idx_processed_confirmation_order", columnList = "order_id"),
    ],
)
class ProcessedOrderConfirmation(
    eventId: String,
    orderId: Long,
) : BaseEntity() {
    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    var eventId: String = eventId
        protected set

    @Column(name = "order_id", nullable = false, updatable = false)
    var orderId: Long = orderId
        protected set
}
