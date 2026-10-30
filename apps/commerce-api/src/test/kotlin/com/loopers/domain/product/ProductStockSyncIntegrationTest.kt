package com.loopers.domain.product

import com.loopers.domain.brand.Brand
import com.loopers.domain.shared.Money
import com.loopers.infrastructure.brand.BrandJpaRepository
import com.loopers.utils.DatabaseCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class ProductStockSyncIntegrationTest @Autowired constructor(
    private val productService: ProductService,
    private val productStockService: ProductStockService,
    private val brandJpaRepository: BrandJpaRepository,
    private val databaseCleanUp: DatabaseCleanUp,
) {
    @AfterEach
    fun tearDown() = databaseCleanUp.truncateAllTables()

    private fun createdProduct(): Product {
        val brand = brandJpaRepository.save(Brand(name = "테스트브랜드"))

        return productService.create(brandId = brand.id, name = "상품", price = Money(1_000))
    }

    private fun quantityOf(productId: Long): Int? = productStockService.find(productId)?.stock?.quantity

    @DisplayName("상품을 만들면 재고 행이 함께 생긴다.")
    @Test
    fun createsStockRowOnProductCreate() {
        val product = createdProduct()

        assertAll(
            { assertThat(quantityOf(product.id)).isEqualTo(ProductService.INITIAL_STOCK) },
            { assertThat(quantityOf(product.id)).isEqualTo(product.stock.quantity) },
        )
    }

    @DisplayName("재고를 입고하면 재고 행도 같은 값이 된다.")
    @Test
    fun syncsOnChangeStock() {
        val product = createdProduct()

        productService.changeStock(product.id, 30)

        assertThat(quantityOf(product.id)).isEqualTo(30)
    }

    @DisplayName("재고를 차감하면 재고 행도 같은 값이 된다.")
    @Test
    fun syncsOnDecrease() {
        val product = createdProduct()
        productService.changeStock(product.id, 30)

        productService.decreaseStock(productService.get(product.id), 12)

        assertThat(quantityOf(product.id)).isEqualTo(18)
    }

    @DisplayName("재고를 되돌리면 재고 행도 같은 값이 된다.")
    @Test
    fun syncsOnRestore() {
        val product = createdProduct()
        productService.changeStock(product.id, 30)
        productService.decreaseStock(productService.get(product.id), 12)

        productService.restoreStock(product.id, 5)

        assertThat(quantityOf(product.id)).isEqualTo(23)
    }

    @DisplayName("재고 행은 상품마다 하나만 유지된다.")
    @Test
    fun keepsSingleRowPerProduct() {
        val product = createdProduct()

        productService.changeStock(product.id, 10)
        productService.changeStock(product.id, 7)

        assertAll(
            { assertThat(productStockService.findAll(listOf(product.id))).hasSize(1) },
            { assertThat(quantityOf(product.id)).isEqualTo(17) },
        )
    }
}
