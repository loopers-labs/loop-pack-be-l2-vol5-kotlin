package com.loopers.connection

import com.loopers.application.ranking.WeeklyRankingStatus
import com.loopers.domain.brand.Brand
import com.loopers.domain.product.Product
import com.loopers.domain.product.Stock
import com.loopers.domain.ranking.ProductRanking
import com.loopers.domain.ranking.ProductRankingPointer
import com.loopers.domain.ranking.ProductRankingPointerStatus
import com.loopers.domain.shared.Money
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.infrastructure.product.ProductJpaRepository
import com.loopers.infrastructure.ranking.ProductRankingJpaRepository
import com.loopers.infrastructure.ranking.ProductRankingPointerJpaRepository
import com.loopers.interfaces.api.ApiResponse
import com.loopers.interfaces.api.ranking.RankingV1Dto
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import java.time.LocalDate

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WeeklyRankingApiConnectionTest @Autowired constructor(
    private val testRestTemplate: TestRestTemplate,
    private val productJpaRepository: ProductJpaRepository,
    private val brandJpaRepository: BrandJpaRepository,
    private val productRankingJpaRepository: ProductRankingJpaRepository,
    private val productRankingPointerJpaRepository: ProductRankingPointerJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    private val responseType = object : ParameterizedTypeReference<ApiResponse<RankingV1Dto.WeeklyResponse>>() {}

    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    @DisplayName("연결 5구간: 배치가 쓴 랭킹과 포인터를 조회하면, 계약 fixture 의 점수·순위가 그대로 나온다.")
    @Test
    fun returnsRankingWrittenByBatch() {
        val brand = brandJpaRepository.save(Brand(name = "나이키코리아"))
        val products = (1..9).map {
            productJpaRepository.save(Product(brandId = brand.id, name = "상품$it", price = Money(1_000), stock = Stock(5)))
        }
        val byId = products.associateBy { it.id }
        listOf(Triple(7L, 5L, 1), Triple(9L, 5L, 2), Triple(8L, 1L, 3)).forEach { (productId, score, ranking) ->
            productRankingJpaRepository.save(
                ProductRanking(
                    snapshotId = WEEK,
                    periodStart = MONDAY,
                    productId = requireNotNull(byId[productId]).id,
                    score = score,
                    ranking = ranking,
                ),
            )
        }
        productRankingPointerJpaRepository.save(ProductRankingPointer(WEEK, ProductRankingPointerStatus.PUBLISHED, 3L))

        val data = testRestTemplate.exchange(
            "/api/v1/rankings/weekly?periodStart=2026-08-17&limit=10",
            HttpMethod.GET,
            HttpEntity<Any>(Unit),
            responseType,
        ).body?.data

        assertThat(data?.status).isEqualTo(WeeklyRankingStatus.PUBLISHED)
        assertThat(data?.snapshotId).isEqualTo(WEEK)
        assertThat(data?.items?.map { Triple(it.productId, it.score, it.ranking) })
            .containsExactly(Triple(7L, 5L, 1), Triple(9L, 5L, 2), Triple(8L, 1L, 3))
    }

    @DisplayName("사고 연결 5구간: 복구한 장애 집합까지 배치가 집계한 랭킹을 조회하면, 상품 점수 20 으로 1위가 나온다.")
    @Test
    fun returnsIncidentRankingWrittenByBatch() {
        val brand = brandJpaRepository.save(Brand(name = "사고실험브랜드"))
        val product = productJpaRepository.save(Product(brandId = brand.id, name = "사고실험상품", price = Money(39_000), stock = Stock(30)))
        productRankingJpaRepository.save(
            ProductRanking(snapshotId = WEEK, periodStart = MONDAY, productId = product.id, score = 20L, ranking = 1),
        )
        productRankingPointerJpaRepository.save(ProductRankingPointer(WEEK, ProductRankingPointerStatus.PUBLISHED, 1L))

        val data = testRestTemplate.exchange(
            "/api/v1/rankings/weekly?periodStart=2026-08-17&limit=10",
            HttpMethod.GET,
            HttpEntity<Any>(Unit),
            responseType,
        ).body?.data

        assertThat(product.id).isEqualTo(INCIDENT_PRODUCT_ID)
        assertThat(data?.status).isEqualTo(WeeklyRankingStatus.PUBLISHED)
        assertThat(data?.snapshotId).isEqualTo(WEEK)
        assertThat(data?.items?.map { Triple(it.productId, it.score, it.ranking) }).containsExactly(Triple(INCIDENT_PRODUCT_ID, 20L, 1))
    }

    companion object {
        private const val INCIDENT_PRODUCT_ID = 1L
        private const val WEEK = "2026_08_3_r1"
        private val MONDAY = LocalDate.parse("2026-08-17")
    }
}
