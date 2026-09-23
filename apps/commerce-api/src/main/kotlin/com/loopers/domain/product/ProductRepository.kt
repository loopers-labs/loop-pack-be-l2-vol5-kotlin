package com.loopers.domain.product

interface ProductRepository {
    fun save(product: Product): Product

    fun saveAll(products: Collection<Product>)

    fun findActive(id: Long): Product?

    fun findAny(id: Long): Product?

    fun findActiveByIds(ids: Collection<Long>): List<Product>

    fun existsActiveByBrandId(brandId: Long): Boolean

    fun findActivePage(brandId: Long?, offset: Int, limit: Int): List<Product>

    fun countActive(brandId: Long?): Long

    fun findActivePage(brandId: Long?, offset: Int, limit: Int, sort: ProductSort): List<Product>
}

enum class ProductSort { LATEST, PRICE_ASC, LIKES_DESC }
