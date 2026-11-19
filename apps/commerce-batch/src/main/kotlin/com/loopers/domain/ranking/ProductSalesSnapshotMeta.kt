package com.loopers.domain.ranking

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.ZonedDateTime

@Entity
@Table(
    name = "product_sales_snapshot_meta",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_product_sales_snapshot_meta_snapshot", columnNames = ["snapshot_id"]),
    ],
)
class ProductSalesSnapshotMeta(
    snapshotId: String,
    periodStart: ZonedDateTime,
    periodEnd: ZonedDateTime,
    status: SnapshotMetaStatus,
    frozenAt: ZonedDateTime,
    rowCount: Long,
    checksum: String,
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

    @Column(name = "revision", nullable = false, updatable = false)
    var revision: Int = SnapshotIdFormat.revisionOf(snapshotId) ?: 0
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: SnapshotMetaStatus = status
        protected set

    @Column(name = "frozen_at", nullable = false, updatable = false)
    var frozenAt: ZonedDateTime = frozenAt
        protected set

    @Column(name = "row_count", nullable = false, updatable = false)
    var rowCount: Long = rowCount
        protected set

    @Column(name = "checksum", nullable = false, updatable = false, length = 64)
    var checksum: String = checksum
        protected set

    init {
        require(SnapshotIdFormat.matches(snapshotId)) { "스냅샷 식별자 형식이 아닙니다. [스냅샷 식별자 = $snapshotId]" }
        require(periodStart.isBefore(periodEnd)) { "구간 시작이 끝보다 앞서야 합니다. [시작 = $periodStart, 끝 = $periodEnd]" }
        require(rowCount >= 0) { "건수는 0 이상이어야 합니다. [건수 = $rowCount]" }
        require(CHECKSUM_FORMAT.matches(checksum)) { "checksum 은 SHA-256 hex 64 자여야 합니다. [checksum = $checksum]" }
    }

    companion object {
        private val CHECKSUM_FORMAT = Regex("[0-9a-f]{64}")
    }
}
