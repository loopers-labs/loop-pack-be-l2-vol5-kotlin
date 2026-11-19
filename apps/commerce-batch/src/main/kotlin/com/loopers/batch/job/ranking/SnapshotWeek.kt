package com.loopers.batch.job.ranking

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

data class SnapshotWeek(
    val monday: LocalDate,
) {
    init {
        require(monday.dayOfWeek == DayOfWeek.MONDAY) { "주는 월요일로 시작해야 합니다. [시작일 = $monday]" }
    }

    val startUtc: LocalDateTime get() = monday.atStartOfDay()

    val endUtc: LocalDateTime get() = monday.plusWeeks(1).atStartOfDay()

    val snapshotId: String get() = snapshotIdOf(1)

    val lateSnapshotId: String get() = snapshotIdOf(LATE_REVISION)

    private fun snapshotIdOf(revision: Int): String =
        "%04d_%02d_%d_r%d".format(monday.year, monday.monthValue, (monday.dayOfMonth - 1) / 7 + 1, revision)

    companion object {
        private const val LATE_REVISION = 2

        fun endedBefore(requestDate: LocalDate): SnapshotWeek =
            SnapshotWeek(requestDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1))
    }
}
