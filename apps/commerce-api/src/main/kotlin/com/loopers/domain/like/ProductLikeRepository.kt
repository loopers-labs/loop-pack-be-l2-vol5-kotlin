package com.loopers.domain.like

interface ProductLikeRepository {
    fun countByProductId(productId: Long): Long

    fun countByProductIds(productIds: Collection<Long>): Map<Long, Long>

    fun findByUserAndProduct(userId: Long, productId: Long): ProductLike?

    fun save(like: ProductLike)

    fun deleteByUserAndProduct(userId: Long, productId: Long)

    fun findActivePageByUser(userId: Long, offset: Int, limit: Int): List<ProductLike>

    fun countActiveByUser(userId: Long): Long
}
