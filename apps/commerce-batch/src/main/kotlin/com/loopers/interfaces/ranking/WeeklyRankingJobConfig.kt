package com.loopers.interfaces.ranking

import com.loopers.application.ranking.WeeklyRankingService
import com.loopers.domain.ranking.ProductScore
import com.loopers.domain.ranking.RankingPeriodLock
import com.loopers.domain.ranking.RankingRequest
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersInvalidException
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.ItemProcessor
import org.springframework.batch.item.ItemWriter
import org.springframework.batch.item.database.JdbcCursorItemReader
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.interceptor.DefaultTransactionAttribute
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

@Configuration
@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = "weeklyRanking")
class WeeklyRankingJobConfig {
    companion object {
        fun request(parameters: JobParameters): RankingRequest = RankingRequest(
            LocalDate.parse(requireNotNull(parameters.getString("periodStart"))),
            requireNotNull(parameters.getString("snapshotId")),
            requireNotNull(parameters.getLong("revision")),
        )
    }

    // Acquire the period lease before any step, including restarts that skip freeze, and always release it after execution.
    @Bean
    fun weeklyRanking(
        repository: JobRepository,
        @Qualifier("freezeRankingInput") freeze: Step,
        @Qualifier("scoreRankingProducts") scores: Step,
        @Qualifier("publishWeeklyRanking") publish: Step,
        periodLock: RankingPeriodLock,
    ): Job {
        val leases = ConcurrentHashMap<Long, AutoCloseable>()

        return JobBuilder("weeklyRanking", repository)
            .validator { parameters ->
                try {
                    val values = requireNotNull(parameters)
                    request(values)
                    check(values.parameters.keys == setOf("periodStart", "snapshotId", "revision")) { "unexpected parameters" }
                    check(values.parameters.values.all { it.isIdentifying }) { "business parameters must identify the instance" }
                } catch (failure: RuntimeException) {
                    throw JobParametersInvalidException(failure.message ?: "invalid ranking parameters")
                }
            }
            .listener(object : JobExecutionListener {
                override fun beforeJob(jobExecution: JobExecution) {
                    leases[jobExecution.id!!] = periodLock.acquire(request(jobExecution.jobParameters).periodStart)
                }

                override fun afterJob(jobExecution: JobExecution) {
                    leases.remove(jobExecution.id!!)?.close()
                }
            })
            .start(freeze).next(scores).next(publish).build()
    }

    @Bean
    fun freezeRankingInput(
        repository: JobRepository,
        transactionManager: PlatformTransactionManager,
        service: WeeklyRankingService,
    ): Step =
        StepBuilder("freezeRankingInput", repository)
            .tasklet({ _, context ->
                service.freeze(request(context.stepContext.stepExecution.jobExecution.jobParameters))
                RepeatStatus.FINISHED
            }, transactionManager)
            .transactionAttribute(
                DefaultTransactionAttribute().apply { isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ },
            )
            .build()

    @Bean
    fun scoreRankingProducts(
        repository: JobRepository,
        transactionManager: PlatformTransactionManager,
        weeklyRankingProducts: JdbcCursorItemReader<Long>,
        rankingScoreProcessor: ItemProcessor<Long, ProductScore>,
        rankingScoreWriter: ItemWriter<ProductScore>,
    ): Step = StepBuilder("scoreRankingProducts", repository)
        .chunk<Long, ProductScore>(2, transactionManager)
        .reader(weeklyRankingProducts).processor(rankingScoreProcessor).writer(rankingScoreWriter).build()

    @Bean
    @StepScope
    fun weeklyRankingProducts(
        dataSource: DataSource,
        @Value("#{jobParameters['snapshotId']}") snapshotId: String,
    ): JdbcCursorItemReader<Long> = JdbcCursorItemReaderBuilder<Long>()
        .name("weeklyRankingProducts").saveState(true).dataSource(dataSource)
        .sql("SELECT DISTINCT product_id FROM ranking_snapshot_inputs WHERE snapshot_id=? ORDER BY product_id")
        .preparedStatementSetter { it.setString(1, snapshotId) }
        .rowMapper { rs, _ -> rs.getLong(1) }.build()

    @Bean
    @StepScope
    fun rankingScoreProcessor(
        service: WeeklyRankingService,
        @Value("#{jobParameters['snapshotId']}") snapshotId: String,
    ): ItemProcessor<Long, ProductScore> = ItemProcessor { service.score(snapshotId, it) }

    @Bean
    @StepScope
    fun rankingScoreWriter(
        service: WeeklyRankingService,
        @Value("#{jobParameters['snapshotId']}") snapshotId: String,
    ): ItemWriter<ProductScore> = ItemWriter { service.write(snapshotId, it.items) }

    @Bean
    fun publishWeeklyRanking(
        repository: JobRepository,
        transactionManager: PlatformTransactionManager,
        service: WeeklyRankingService,
    ): Step =
        StepBuilder("publishWeeklyRanking", repository)
            .tasklet({ _, context ->
                service.publish(request(context.stepContext.stepExecution.jobExecution.jobParameters))
                RepeatStatus.FINISHED
            }, transactionManager).build()
}
