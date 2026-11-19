package com.loopers.domain.ranking

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.DayOfWeek
import java.time.LocalDate

@Entity
@Table(
    name = "product_ranking_pointer",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_product_ranking_pointer_snapshot",
            columnNames = ["snapshot_id"],
        ),
    ],
)
class ProductRankingPointer(
    snapshotId: String,
    status: ProductRankingPointerStatus,
    productCount: Long?,
) : BaseEntity() {
    @Column(name = "snapshot_id", nullable = false, updatable = false, length = 30)
    var snapshotId: String = snapshotId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: ProductRankingPointerStatus = status
        protected set

    @Column(name = "product_count")
    var productCount: Long? = productCount
        protected set

    val isPublished: Boolean get() = status == ProductRankingPointerStatus.PUBLISHED

    init {
        require(SNAPSHOT_ID_FORMAT.matches(snapshotId)) { "랭킹 스냅샷 식별자 형식이 아닙니다. [스냅샷 식별자 = $snapshotId]" }
        require(productCount == null || productCount >= 0) { "상품 수는 0 이상이어야 합니다. [상품 수 = $productCount]" }
        require(isPublished == (productCount != null)) {
            "상품 수는 공개된 포인터에만 있어야 합니다. [상태 = $status, 상품 수 = $productCount]"
        }
    }

    companion object {
        private val SNAPSHOT_ID_FORMAT = Regex("""\d{4}_(0[1-9]|1[0-2])_[1-5]""")

        fun snapshotIdOf(monday: LocalDate): String {
            require(monday.dayOfWeek == DayOfWeek.MONDAY) { "주 시작일은 월요일이어야 합니다. [시작일 = $monday]" }
            return "%04d_%02d_%d".format(monday.year, monday.monthValue, (monday.dayOfMonth - 1) / 7 + 1)
        }
    }
}
