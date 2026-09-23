package com.loopers.infrastructure.product

import com.loopers.domain.product.Product
import com.loopers.domain.product.ProductRepository
import com.loopers.domain.product.ProductSort
import com.loopers.domain.commerce.EntityState
import org.springframework.stereotype.Repository

@Repository
class ProductRepositoryImpl(private val repository: ProductEntityRepository) : ProductRepository {
    override fun save(product: Product): Product = repository.save(product.toEntity()).toDomain()

    override fun saveAll(products: Collection<Product>) {
        repository.saveAll(products.map { it.toEntity() })
    }

    override fun findActive(id: Long): Product? = repository.findActive(id)?.toDomain()

    override fun findAny(id: Long): Product? = repository.findAny(id)?.toDomain()

    override fun findActiveByIds(ids: Collection<Long>): List<Product> =
        repository.findActiveByIds(ids).map { it.toDomain() }

    override fun existsActiveByBrandId(brandId: Long): Boolean = repository.existsActiveByBrandId(brandId)

    override fun findActivePage(brandId: Long?, offset: Int, limit: Int): List<Product> =
        repository.findActivePage(brandId, offset, limit, ProductSort.LATEST).map { it.toDomain() }

    override fun countActive(brandId: Long?): Long = repository.countActive(brandId)

    override fun findActivePage(brandId: Long?, offset: Int, limit: Int, sort: ProductSort): List<Product> =
        repository.findActivePage(brandId, offset, limit, sort).map { it.toDomain() }

    private fun Product.toEntity() = ProductEntity(
        brandId,
        name,
        price.amount,
        stock.remaining,
        EntityState(id, createdAt, updatedAt, deletedAt),
    )

    private fun ProductEntity.toDomain() = Product.reconstitute(brandId, name, price, stock, state)
}
