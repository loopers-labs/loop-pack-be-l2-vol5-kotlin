package com.loopers.infrastructure.like

import java.time.ZonedDateTime

data class ProductLikeEntity(
    val userId: Long,
    val productId: Long,
    val id: Long,
    val likedAt: ZonedDateTime,
)

interface ProductLikeEntityRepository {
    fun countByProductId(productId: Long): Long

    fun countByProductIds(productIds: Collection<Long>): Map<Long, Long>

    fun findByUserAndProduct(userId: Long, productId: Long): ProductLikeEntity?

    fun save(entity: ProductLikeEntity)

    fun deleteByUserAndProduct(userId: Long, productId: Long)

    fun findActivePageByUser(userId: Long, offset: Int, limit: Int): List<ProductLikeEntity>

    fun countActiveByUser(userId: Long): Long
}
