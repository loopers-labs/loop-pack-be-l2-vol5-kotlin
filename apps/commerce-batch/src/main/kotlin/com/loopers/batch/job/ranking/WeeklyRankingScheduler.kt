package com.loopers.batch.job.ranking

import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

@ConditionalOnProperty(name = [WeeklyRankingScheduler.ENABLED_PROPERTY], havingValue = "true")
@EnableScheduling
@Component
class WeeklyRankingScheduler(
    private val jobLauncher: JobLauncher,
    @param:Qualifier(WeeklySalesSnapshotJobConfig.JOB_NAME) private val job: Job,
) {
    @Scheduled(cron = CRON, zone = ZONE)
    fun launchForLastWeek() {
        launch(LocalDate.now(ZoneOffset.UTC))
    }

    fun launch(requestDate: LocalDate): JobExecution =
        jobLauncher.run(
            job,
            JobParametersBuilder()
                .addLocalDate("requestDate", requestDate, false)
                .addString("snapshotId", SnapshotWeek.endedBefore(requestDate).snapshotId)
                .toJobParameters(),
        )

    companion object {
        const val ENABLED_PROPERTY = "ranking.schedule.enabled"
        const val CRON = "0 30 1 * * MON"
        const val ZONE = "UTC"
    }
}
