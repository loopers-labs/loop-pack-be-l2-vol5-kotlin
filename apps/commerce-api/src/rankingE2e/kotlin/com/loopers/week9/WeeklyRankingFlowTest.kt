package com.loopers.week9

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.loopers.application.brand.BrandApplicationService
import com.loopers.application.outbox.OutboxRelay
import com.loopers.application.product.ProductApplicationService
import com.loopers.application.user.UserInitializationService
import com.loopers.domain.order.event.OrderConfirmed
import com.loopers.domain.outbox.OutboxRepository
import com.loopers.domain.outbox.OutboxSender
import com.loopers.domain.ranking.RankedProduct
import com.loopers.domain.ranking.RankingPeriod
import com.loopers.domain.ranking.RankingStore
import com.loopers.interfaces.consumer.PurchaseActivityConsumer
import com.loopers.payment.PgHttpFixture
import com.loopers.week7.OutboxE2eApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.explore.JobExplorer
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.listener.AcknowledgingMessageListener
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@Timeout(180)
class WeeklyRankingFlowTest {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

    private fun request(base: String, path: String, body: String? = null, user: Long? = null): JsonNode {
        val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        if (user != null) builder.header("X-USER-ID", user.toString())
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == if (path == "/api/v1/orders") 201 else 200) { "HTTP status=${response.statusCode()} body=${response.body()}" }

        return mapper.readTree(response.body())["data"]
    }

    private fun await(label: String, condition: () -> Boolean) {
        val until = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (!condition()) {
            check(System.nanoTime() < until) { "$label timed out" }
            Thread.sleep(25)
        }
    }

    // Actual PG HTTP -> committed Outbox -> broker offsets -> real consumer -> frozen Job -> public HTTP API.
    @Test
    fun `purchase duplicate late arrival restart new revision and atomic API publication`() {
        val mysql = MySQLContainer("mysql:8.0")
        val redis = GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)
        val topic = "ranking-${UUID.randomUUID()}"
        val broker = EmbeddedKafkaKraftBroker(1, 2, topic)
        val contexts = mutableListOf<ConfigurableApplicationContext>()
        var consumer: ConcurrentMessageListenerContainer<String, ByteArray>? = null
        var pg: PgHttpFixture? = null
        try {
            mysql.start()
            redis.start()
            broker.afterPropertiesSet()
            val payment = PgHttpFixture().also { it.createMode = "SUCCESS" }
            pg = payment
            val args = arrayOf(
                "--spring.profiles.active=test", "--server.port=0", "--spring.jpa.show-sql=false",
                "--datasource.mysql-jpa.main.jdbc-url=${mysql.jdbcUrl}", "--datasource.mysql-jpa.main.username=${mysql.username}",
                "--datasource.mysql-jpa.main.password=${mysql.password}", "--datasource.mysql-jpa.main.minimum-idle=1",
                "--datasource.mysql-jpa.main.maximum-pool-size=8", "--spring.kafka.bootstrap-servers=${broker.brokersAsString}",
                "--datasource.redis.master.host=${redis.host}", "--datasource.redis.master.port=${redis.firstMappedPort}",
                "--datasource.redis.replicas[0].host=${redis.host}", "--datasource.redis.replicas[0].port=${redis.firstMappedPort}",
                "--payment.pg.base-url=${payment.properties.baseUrl}", "--purchase.kafka.topic=$topic",
                "--purchase.kafka.group=ranking-flow", "--purchase.consumer.auto-startup=false", "--outbox.relay.enabled=false",
                "--demo-kafka.test.topic-name=$topic", "--logging.level.org.apache.kafka=WARN", "--spring.batch.job.enabled=false",
            )
            val api = SpringApplicationBuilder(OutboxE2eApplication::class.java)
                .run(*args, "--spring.jpa.hibernate.ddl-auto=create").also { contexts.add(it) }
            val base = "http://127.0.0.1:${(api as ServletWebServerApplicationContext).webServer.port}"
            val dataSource = DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            val jdbc = JdbcTemplate(dataSource)
            dataSource.connection.use { ScriptUtils.executeSqlScript(it, ClassPathResource("ranking-schema.sql")) }
            val delivered = CopyOnWriteArrayList<String>()
            val listener = api.getBean(PurchaseActivityConsumer::class.java)

            @Suppress("UNCHECKED_CAST")
            val factory = api.getBean("purchaseListenerFactory") as ConcurrentKafkaListenerContainerFactory<String, ByteArray>
            consumer = factory.createContainer(topic).apply {
                containerProperties.groupId = "ranking-flow"
                setupMessageListener(
                    AcknowledgingMessageListener<String, ByteArray> { record, ack ->
                    listener.consume(record, checkNotNull(ack))
                    delivered.add(api.getBean(ObjectMapper::class.java).readValue(record.value(), OrderConfirmed::class.java).eventId)
                },
                )
                start()
            }
            ContainerTestUtils.waitForAssignment(consumer, 2)
            val user = api.getBean(UserInitializationService::class.java).create("W9 buyer").id
            val brand = api.getBean(BrandApplicationService::class.java).create("W9 brand").id
            val products = api.getBean(ProductApplicationService::class.java)
            val first = products.create(brand, "first", 100, 100).id
            val second = products.create(brand, "second", 100, 100).id
            val third = products.create(brand, "third", 100, 100).id
            fun order(vararg lines: Pair<Long, Int>): Long {
                val items = lines.joinToString(",") { """{"productId":${it.first},"quantity":${it.second}}""" }
                val id = request(base, "/api/v1/orders", """{"items":[$items]}""", user)["id"].asLong()
                request(base, "/api/v1/orders/$id/confirm", """{"paymentAttemptKey":"w9-$id","cardType":"TEST","cardNo":"0000-0000-0000-0000"}""", user)

                return id
            }
            val orders = listOf(order(first to 2, second to 1), order(first to 3), order(third to 5))
            val relay = api.getBean(OutboxRelay::class.java)
            orders.forEach { assertThat(relay.runOnce(OrderConfirmed.stableEventId(it))).isEqualTo(1) }
            await("four committed activities") { jdbc.queryForObject("SELECT count(*) FROM purchase_activities", Long::class.java) == 4L }
            val event = api.getBean(OutboxRepository::class.java).find(OrderConfirmed.stableEventId(orders.first()))!!.fact
            val duplicateReceipt = api.getBean(OutboxSender::class.java).send(event)
            await("actual duplicate delivery") { delivered.count { it == event.eventId } == 2 }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_activities", Long::class.java)!!).isEqualTo(4L)
            val confirmed = jdbc.queryForObject("SELECT confirmed_at FROM orders WHERE id=?", { rs, _ ->
                rs.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant()
            }, orders.first())!!
            val period = confirmed.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            assertThat(
                orders.all { id ->
                jdbc.queryForObject("SELECT confirmed_at FROM orders WHERE id=?", { rs, _ ->
                RankingPeriod(period).contains(rs.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant())
            }, id) == true
            },
            ).isTrue()
            val batch = SpringApplicationBuilder(RankingFlowBatchApplication::class.java).web(WebApplicationType.NONE)
                .run(
                    *args,
                    "--spring.main.web-application-type=none",
                    "--spring.jpa.hibernate.ddl-auto=none",
                    "--spring.batch.job.name=weeklyRanking",
                    "--spring.batch.jdbc.initialize-schema=always",
                ).also { contexts.add(it) }
            val launcher = batch.getBean(JobLauncher::class.java)
            val job = batch.getBean("weeklyRanking", Job::class.java)
            val observation = batch.getBean(FlowObservation::class.java)
            val store = batch.getBean(RankingStore::class.java)
            fun parameters(snapshot: String, revision: Long) = JobParametersBuilder().addString("periodStart", period.toString())
                .addString("snapshotId", snapshot).addLong("revision", revision).toJobParameters()
            val r1 = "flow-r1"
            observation.failProduct = third
            val failed = launcher.run(job, parameters(r1, 1))
            assertThat(failed.status).isEqualTo(BatchStatus.FAILED)
            val failedStep = failed.stepExecutions.single { it.stepName == "scoreRankingProducts" }
            assertThat(
                batch.getBean(JobExplorer::class.java).getStepExecution(failed.id!!, failedStep.id!!)!!
                .executionContext.getInt("weeklyRankingProducts.read.count"),
            ).isEqualTo(2)
            assertThat(request(base, "/api/v1/rankings/weekly?periodStart=$period")["status"].asText()).isEqualTo("NOT_PUBLISHED")
            val original = store.findSnapshot(r1)!!
            val late = order(first to 10)
            relay.runOnce(OrderConfirmed.stableEventId(late))
            await("late purchase consumer commit") { jdbc.queryForObject("SELECT count(*) FROM purchase_activities", Long::class.java) == 5L }
            observation.failProduct = null
            observation.calls.clear()

            val resumed = launcher.run(job, parameters(r1, 1))

            assertThat(resumed.status).isEqualTo(BatchStatus.COMPLETED)
            assertThat(resumed.jobInstance.instanceId).isEqualTo(failed.jobInstance.instanceId)
            assertThat(observation.calls).containsExactly(third)
            assertThat(store.results(r1)).containsExactly(RankedProduct(first, 5, 1), RankedProduct(third, 5, 2), RankedProduct(second, 1, 3))
            assertThat(store.findSnapshot(r1)!!.inputChecksum).isEqualTo(original.inputChecksum)
            val publishedR1 = request(base, "/api/v1/rankings/weekly?periodStart=$period")
            assertThat(publishedR1["snapshotId"].asText()).isEqualTo(r1)
            assertThat(publishedR1["items"].map { it["score"].asLong() }).containsExactly(5, 5, 1)
            val r2 = "flow-r2"
            observation.failProduct = third
            assertThat(launcher.run(job, parameters(r2, 2)).status).isEqualTo(BatchStatus.FAILED)
            assertThat(request(base, "/api/v1/rankings/weekly?periodStart=$period")).isEqualTo(publishedR1)
            observation.failProduct = null
            val completed = launcher.run(job, parameters(r2, 2))
            assertThat(completed.status).isEqualTo(BatchStatus.COMPLETED)
            val publishedR2 = request(base, "/api/v1/rankings/weekly?periodStart=$period")
            assertThat(publishedR2["snapshotId"].asText()).isEqualTo(r2)
            assertThat(publishedR2["items"].map { it["score"].asLong() }).containsExactly(15, 5, 1)
            assertThat(store.findSnapshot(r2)!!.inputCount).isEqualTo(5)
            assertThat(store.findSnapshot(r2)!!.inputChecksum).isNotEqualTo(original.inputChecksum)
            assertThat(payment.creates.get()).isEqualTo(4)
            assertThat(payment.errors).isEmpty()
            println(
                "WEEK9_FLOW week=$period orders=$orders late=$late duplicateOffset=${duplicateReceipt.offset} activities=5 " +
                    "failedInstance=${failed.jobInstance.instanceId} failedExecution=${failed.id} resumedExecution=${resumed.id} " +
                    "r1=${store.findSnapshot(r1)} r2=${store.findSnapshot(r2)} pgCreates=${payment.creates.get()} API=$publishedR2",
            )
        } finally {
            consumer?.stop()
            contexts.asReversed().forEach { it.close() }
            pg?.close()
            broker.destroy()
            redis.stop()
            mysql.stop()
        }
    }
}
