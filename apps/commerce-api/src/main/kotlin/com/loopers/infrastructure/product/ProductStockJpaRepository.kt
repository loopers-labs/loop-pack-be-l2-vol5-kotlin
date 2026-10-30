package com.loopers.infrastructure.product

import com.loopers.domain.product.ProductStock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProductStockJpaRepository : JpaRepository<ProductStock, Long> {
    fun findByProductId(productId: Long): ProductStock?

    fun findAllByProductIdIn(productIds: List<Long>): List<ProductStock>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            insert into product_stock (product_id, quantity, created_at, updated_at)
            values (:productId, :quantity, now(6), now(6))
            on duplicate key update quantity = :quantity, updated_at = now(6)
        """,
        nativeQuery = true,
    )
    fun upsert(@Param("productId") productId: Long, @Param("quantity") quantity: Int)
}
