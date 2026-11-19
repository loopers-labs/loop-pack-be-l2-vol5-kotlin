package com.loopers

import com.loopers.batch.job.ranking.WeeklyRankingScheduler
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.util.TimeZone
import kotlin.system.exitProcess

@ConfigurationPropertiesScan
@SpringBootApplication
class CommerceBatchApplication

fun main(args: Array<String>) {
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
    val context = runApplication<CommerceBatchApplication>(*args)
    if (context.environment.getProperty(WeeklyRankingScheduler.ENABLED_PROPERTY, Boolean::class.java, false)) return
    exitProcess(SpringApplication.exit(context))
}
