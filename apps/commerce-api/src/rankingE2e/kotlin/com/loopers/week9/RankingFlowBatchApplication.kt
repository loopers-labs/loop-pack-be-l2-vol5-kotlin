package com.loopers.week9

import com.loopers.application.ranking.WeeklyRankingService
import com.loopers.config.jpa.DataSourceConfig
import com.loopers.domain.ranking.ProductScore
import com.loopers.infrastructure.ranking.JdbcRankingStore
import com.loopers.infrastructure.ranking.MySqlRankingPeriodLock
import com.loopers.interfaces.ranking.WeeklyRankingJobConfig
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.item.ItemProcessor
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList

@TestConfiguration
@EnableAutoConfiguration
@Import(
    DataSourceConfig::class,
    JdbcRankingStore::class,
    MySqlRankingPeriodLock::class,
    WeeklyRankingService::class,
    WeeklyRankingJobConfig::class,
)
class RankingFlowBatchApplication {
    @Bean
    fun flowObservation() = FlowObservation()

    @Bean
    @Primary
    @StepScope
    fun flowProcessor(
        service: WeeklyRankingService,
        observation: FlowObservation,
        @Value("#{jobParameters['snapshotId']}") snapshotId: String,
    ): ItemProcessor<Long, ProductScore> = ItemProcessor { id ->
        observation.calls.add(id)
        if (observation.failProduct == id) error("E2E controlled failure before third product")
        service.score(snapshotId, id)
    }
}

class FlowObservation {
    @Volatile var failProduct: Long? = null
    val calls = CopyOnWriteArrayList<Long>()
}
