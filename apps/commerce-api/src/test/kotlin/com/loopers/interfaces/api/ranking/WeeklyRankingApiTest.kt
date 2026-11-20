package com.loopers.interfaces.api.ranking

import com.loopers.application.brand.BrandApplicationService
import com.loopers.application.product.ProductApplicationService
import com.loopers.domain.ranking.RankingChecksum
import com.loopers.domain.ranking.RankedProduct
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
class WeeklyRankingApiTest @Autowired constructor(
    private val mvc: MockMvc,
    private val jdbc: JdbcTemplate,
    private val dataSource: DataSource,
    private val brands: BrandApplicationService,
    private val products: ProductApplicationService,
) {
    @BeforeEach
    fun schema() {
        dataSource.connection.use { ScriptUtils.executeSqlScript(it, ClassPathResource("ranking-schema.sql")) }
        jdbc.update("DELETE FROM ranking_publications")
        listOf("ranking_results", "ranking_score_stages", "ranking_snapshot_inputs", "ranking_snapshots").forEach {
            jdbc.update("DELETE FROM $it")
        }
    }

    private fun publish(id: String, date: String, rows: List<RankedProduct>) {
        jdbc.update(
            "INSERT INTO ranking_snapshots " +
                "(snapshot_id,period_start,period_end,revision,status,frozen_at,input_count,product_count,input_checksum,result_checksum) " +
                "VALUES (?, ?,DATE_ADD(?,INTERVAL 7 DAY),1,'COMPLETE','2026-08-24 00:00:00',?,?,?,?)",
            id,
            date,
            date,
            rows.size,
            rows.size,
            RankingChecksum.inputs(emptyList()),
            RankingChecksum.results(rows),
        )
        rows.forEach { jdbc.update("INSERT INTO ranking_results VALUES (?,?,?,?)", id, it.productId, it.score, it.rank) }
        jdbc.update("INSERT INTO ranking_publications VALUES (?,?)", date, id)
    }

    @Test
    fun `unpublished exact week and published zero purchases are distinct and public`() {
        mvc.perform(get("/api/v1/rankings/weekly?periodStart=2026-08-17"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").doesNotExist()).andExpect(jsonPath("$.data.items").isEmpty)
        publish("empty", "2026-08-17", emptyList())

        mvc.perform(get("/api/v1/rankings/weekly?periodStart=2026-08-17"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value("empty"))
            .andExpect(jsonPath("$.data.periodStart").value("2026-08-17"))
            .andExpect(jsonPath("$.data.periodEnd").value("2026-08-24"))
            .andExpect(jsonPath("$.data.frozenAt").value("2026-08-24T00:00:00Z"))
            .andExpect(jsonPath("$.data.items").isEmpty)
        mvc.perform(get("/api/v1/rankings/weekly?periodStart=2026-08-24"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.status").value("NOT_PUBLISHED"))
    }

    @Test
    fun `visibility filters before limit preserves calculated rank and uses current product names`() {
        val active = brands.create("ranking active").id
        val hidden = brands.create("ranking hidden").id
        val first = products.create(active, "first", 100, 1).id
        val second = products.create(active, "second", 100, 1).id
        val third = products.create(hidden, "third", 100, 1).id
        val fourth = products.create(active, "fourth", 100, 1).id
        val rows = listOf(RankedProduct(first, 9, 1), RankedProduct(second, 8, 2), RankedProduct(third, 7, 3), RankedProduct(fourth, 6, 4))
        publish("visible", "2026-08-17", rows)
        products.delete(second)
        jdbc.update("UPDATE brands SET deleted_at=UTC_TIMESTAMP(6) WHERE id=?", hidden)
        jdbc.update("UPDATE products SET name='current name' WHERE id=?", fourth)

        mvc.perform(get("/api/v1/rankings/weekly?periodStart=2026-08-17&limit=2"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].productId").value(first))
            .andExpect(jsonPath("$.data.items[0].rank").value(1))
            .andExpect(jsonPath("$.data.items[1].productId").value(fourth))
            .andExpect(jsonPath("$.data.items[1].name").value("current name"))
            .andExpect(jsonPath("$.data.items[1].score").value(6))
            .andExpect(jsonPath("$.data.items[1].rank").value(4))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ranking_results WHERE snapshot_id='visible'", Long::class.java)!!).isEqualTo(4L)
        jdbc.update("UPDATE products SET deleted_at=UTC_TIMESTAMP(6) WHERE id IN (?,?)", first, fourth)
        mvc.perform(get("/api/v1/rankings/weekly?periodStart=2026-08-17"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data.snapshotId").value("visible"))
            .andExpect(jsonPath("$.data.items").isEmpty)
    }

    @Test
    fun `strict ISO Monday and positive bounded limit return existing invalid request contract`() {
        listOf(
            "periodStart=2026-08-18", "periodStart=2026-8-17", "periodStart=2026-02-30", "periodStart=no",
            "periodStart=2026-08-17&limit=0", "periodStart=2026-08-17&limit=-1", "periodStart=2026-08-17&limit=101",
            "periodStart=2026-08-17&limit=no", "limit=10",
        ).forEach { query ->
            mvc.perform(get("/api/v1/rankings/weekly?$query"))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.meta.errorCode").value("INVALID_REQUEST"))
        }
    }
}
