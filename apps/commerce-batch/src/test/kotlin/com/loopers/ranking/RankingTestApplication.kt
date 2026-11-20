package com.loopers.ranking

import com.loopers.application.ranking.WeeklyRankingService
import com.loopers.config.jpa.DataSourceConfig
import com.loopers.infrastructure.ranking.JdbcRankingStore
import com.loopers.infrastructure.ranking.MySqlRankingPeriodLock
import com.loopers.interfaces.ranking.WeeklyRankingJobConfig
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import

@TestConfiguration
@EnableAutoConfiguration
@Import(
    DataSourceConfig::class,
    JdbcRankingStore::class,
    MySqlRankingPeriodLock::class,
    WeeklyRankingService::class,
    WeeklyRankingJobConfig::class,
)
class RankingTestApplication
