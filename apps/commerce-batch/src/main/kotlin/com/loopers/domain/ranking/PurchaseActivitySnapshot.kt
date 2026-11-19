package com.loopers.domain.ranking

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.ZonedDateTime

@Entity
@Table(
    name = "purchase_activity_snapshot",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_purchase_activity_snapshot_order_product",
            columnNames = ["snapshot_id", "order_id", "product_id"],
        ),
    ],
)
class PurchaseActivitySnapshot(
    snapshotId: String,
    periodStart: ZonedDateTime,
    periodEnd: ZonedDateTime,
    orderId: Long,
    productId: Long,
    quantity: Int,
    confirmedAt: ZonedDateTime,
) : BaseEntity() {
    @Column(name = "snapshot_id", nullable = false, updatable = false, length = 30)
    var snapshotId: String = snapshotId
        protected set

    @Column(name = "period_start", nullable = false, updatable = false)
    var periodStart: ZonedDateTime = periodStart
        protected set

    @Column(name = "period_end", nullable = false, updatable = false)
    var periodEnd: ZonedDateTime = periodEnd
        protected set

    @Column(name = "order_id", nullable = false, updatable = false)
    var orderId: Long = orderId
        protected set

    @Column(name = "product_id", nullable = false, updatable = false)
    var productId: Long = productId
        protected set

    @Column(name = "quantity", nullable = false, updatable = false)
    var quantity: Int = quantity
        protected set

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    var confirmedAt: ZonedDateTime = confirmedAt
        protected set

    init {
        require(SnapshotIdFormat.matches(snapshotId)) { "스냅샷 식별자 형식이 아닙니다. [스냅샷 식별자 = $snapshotId]" }
        require(periodStart.isBefore(periodEnd)) { "구간 시작이 끝보다 앞서야 합니다. [시작 = $periodStart, 끝 = $periodEnd]" }
        require(quantity > 0) { "수량은 1 이상이어야 합니다. [수량 = $quantity]" }
        require(!confirmedAt.isBefore(periodStart) && confirmedAt.isBefore(periodEnd)) {
            "확정 시각이 구간 밖입니다. [확정 = $confirmedAt, 시작 = $periodStart, 끝 = $periodEnd]"
        }
    }
}
