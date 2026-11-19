package com.loopers.job.ranking

import com.loopers.batch.job.ranking.SnapshotWeek
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate
import java.time.LocalDateTime

class SnapshotWeekTest {
    @DisplayName("실행일이 월요일이면, 전날 끝난 주를 고른다.")
    @Test
    fun picksWeekEndedYesterday_whenRequestDateIsMonday() {
        val week = SnapshotWeek.endedBefore(LocalDate.parse("2026-11-16"))

        assertThat(week.monday).isEqualTo(LocalDate.parse("2026-11-09"))
        assertThat(week.startUtc).isEqualTo(LocalDateTime.parse("2026-11-09T00:00:00"))
        assertThat(week.endUtc).isEqualTo(LocalDateTime.parse("2026-11-16T00:00:00"))
    }

    @DisplayName("실행일이 주 중간이면, 직전에 끝난 주를 고른다.")
    @Test
    fun picksLastEndedWeek_whenRequestDateIsMidWeek() {
        val week = SnapshotWeek.endedBefore(LocalDate.parse("2026-11-22"))

        assertThat(week.monday).isEqualTo(LocalDate.parse("2026-11-09"))
    }

    @DisplayName("주차는 월요일이 속한 달의 몇 번째 월요일인지로 정한다.")
    @ParameterizedTest
    @CsvSource(
        "2026-11-02, 2026_11_1_r1",
        "2026-11-09, 2026_11_2_r1",
        "2026-11-30, 2026_11_5_r1",
        "2026-12-07, 2026_12_1_r1",
        "2026-10-26, 2026_10_4_r1",
        "2026-12-28, 2026_12_4_r1",
        "2027-03-01, 2027_03_1_r1",
    )
    fun namesWeekByMonthOfMonday(monday: LocalDate, snapshotId: String) {
        assertThat(SnapshotWeek(monday).snapshotId).isEqualTo(snapshotId)
    }

    @DisplayName("연도를 넘는 주는 월요일이 속한 해로 센다.")
    @Test
    fun namesWeekByYearOfMonday_whenWeekCrossesYear() {
        val week = SnapshotWeek.endedBefore(LocalDate.parse("2027-01-04"))

        assertThat(week.snapshotId).isEqualTo("2026_12_4_r1")
        assertThat(week.endUtc).isEqualTo(LocalDateTime.parse("2027-01-04T00:00:00"))
    }

    @DisplayName("늦은 구매 스냅샷 식별자는 같은 주에 리비전 r2 를 붙인다.")
    @Test
    fun namesLateSnapshotWithSecondRevision() {
        assertThat(SnapshotWeek(LocalDate.parse("2026-11-09")).lateSnapshotId).isEqualTo("2026_11_2_r2")
    }

    @DisplayName("월요일이 아닌 날로 주를 만들면, IllegalArgumentException 이 발생한다.")
    @Test
    fun throwsIllegalArgumentException_whenStartIsNotMonday() {
        assertThrows<IllegalArgumentException> { SnapshotWeek(LocalDate.parse("2026-11-10")) }
    }
}
