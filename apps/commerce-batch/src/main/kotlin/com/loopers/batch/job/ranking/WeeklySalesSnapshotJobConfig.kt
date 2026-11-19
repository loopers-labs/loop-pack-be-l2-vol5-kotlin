package com.loopers.batch.job.ranking

import com.loopers.batch.job.ranking.step.ProductSales
import com.loopers.batch.job.ranking.step.WeeklySalesReaderConfig
import com.loopers.batch.job.ranking.step.WeeklyRankingDecider
import com.loopers.batch.job.ranking.step.WeeklyRankingPublishTasklet
import com.loopers.batch.job.ranking.step.WeeklyRankingWriterConfig
import com.loopers.batch.job.ranking.step.WeeklySalesSnapshotTasklet
import com.loopers.batch.listener.JobListener
import com.loopers.batch.listener.StepMonitorListener
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.JobScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.database.JdbcBatchItemWriter
import org.springframework.batch.item.database.JdbcCursorItemReader
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

@ConditionalOnProperty(name = ["spring.batch.job.name"], havingValue = WeeklySalesSnapshotJobConfig.JOB_NAME)
@Configuration
class WeeklySalesSnapshotJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val jobListener: JobListener,
    private val stepMonitorListener: StepMonitorListener,
    private val weeklySalesSnapshotTasklet: WeeklySalesSnapshotTasklet,
    @param:Qualifier(WeeklySalesReaderConfig.READER_NAME) private val weeklySalesReader: JdbcCursorItemReader<ProductSales>,
    @param:Qualifier(WeeklyRankingWriterConfig.WRITER_NAME) private val weeklyRankingWriter: JdbcBatchItemWriter<ProductSales>,
    private val weeklyRankingDecider: WeeklyRankingDecider,
    private val weeklyRankingPublishTasklet: WeeklyRankingPublishTasklet,
    private val weeklyRankingPointerListener: WeeklyRankingPointerListener,
    @param:Value("\${${WeeklySalesSnapshotJobConfig.CHUNK_SIZE_PROPERTY}:100}") private val chunkSize: Int,
) {
    companion object {
        const val JOB_NAME = "weeklySalesSnapshotJob"
        private const val STEP_NAME = "weeklySalesSnapshotStep"
        const val RANKING_STEP_NAME = "weeklySalesRankingStep"
        private const val PUBLISH_STEP_NAME = "weeklyRankingPublishStep"
        const val CHUNK_SIZE_PROPERTY = "ranking.chunk-size"
    }

    @Bean(JOB_NAME)
    fun weeklySalesSnapshotJob(): Job =
        JobBuilder(JOB_NAME, jobRepository)
            .listener(jobListener)
            .listener(weeklyRankingPointerListener)
            .start(weeklySalesSnapshotStep())
            .next(weeklyRankingDecider)
            .on(WeeklyRankingDecider.RANKING_EXISTS).to(weeklyRankingPublishStep())
            .from(weeklyRankingDecider).on("*").to(weeklySalesRankingStep()).next(weeklyRankingPublishStep())
            .end()
            .build()

    @JobScope
    @Bean(STEP_NAME)
    fun weeklySalesSnapshotStep(): Step =
        StepBuilder(STEP_NAME, jobRepository)
            .tasklet(weeklySalesSnapshotTasklet, transactionManager)
            .listener(stepMonitorListener)
            .build()

    @JobScope
    @Bean(RANKING_STEP_NAME)
    fun weeklySalesRankingStep(): Step =
        StepBuilder(RANKING_STEP_NAME, jobRepository)
            .chunk<ProductSales, ProductSales>(chunkSize, transactionManager)
            .reader(weeklySalesReader)
            .writer(weeklyRankingWriter)
            .listener(stepMonitorListener)
            .build()

    @JobScope
    @Bean(PUBLISH_STEP_NAME)
    fun weeklyRankingPublishStep(): Step =
        StepBuilder(PUBLISH_STEP_NAME, jobRepository)
            .tasklet(weeklyRankingPublishTasklet, transactionManager)
            .listener(stepMonitorListener)
            .build()
}
