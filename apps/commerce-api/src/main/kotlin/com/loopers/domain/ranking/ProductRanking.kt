package com.loopers.domain.ranking

import com.loopers.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.DayOfWeek
import java.time.LocalDate

@Entity
@Table(
    name = "product_ranking",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_product_ranking_snapshot_product",
            columnNames = ["snapshot_id", "product_id"],
        ),
    ],
)
class ProductRanking(
    snapshotId: String,
    periodStart: LocalDate,
    productId: Long,
    score: Long,
    ranking: Int,
) : BaseEntity() {
    @Column(name = "snapshot_id", nullable = false, updatable = false, length = 30)
    var snapshotId: String = snapshotId
        protected set

    @Column(name = "period_start", nullable = false, updatable = false)
    var periodStart: LocalDate = periodStart
        protected set

    @Column(name = "product_id", nullable = false, updatable = false)
    var productId: Long = productId
        protected set

    @Column(name = "score", nullable = false, updatable = false)
    var score: Long = score
        protected set

    @Column(name = "ranking", nullable = false, updatable = false)
    var ranking: Int = ranking
        protected set

    init {
        require(SNAPSHOT_ID_FORMAT.matches(snapshotId)) { "랭킹 스냅샷 식별자 형식이 아닙니다. [스냅샷 식별자 = $snapshotId]" }
        require(periodStart.dayOfWeek == DayOfWeek.MONDAY) { "주 시작일은 월요일이어야 합니다. [시작일 = $periodStart]" }
        require(score > 0) { "점수는 1 이상이어야 합니다. [점수 = $score]" }
        require(ranking > 0) { "랭킹은 1 이상이어야 합니다. [랭킹 = $ranking]" }
    }

    companion object {
        private val SNAPSHOT_ID_FORMAT = Regex("""\d{4}_(0[1-9]|1[0-2])_[1-5]""")
    }
}
