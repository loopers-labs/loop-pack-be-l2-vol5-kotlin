package com.loopers.infrastructure.like

import org.springframework.stereotype.Repository

@Repository
class JpaProductLikeEntityRepository(private val jpa: ProductLikeJpaRepository) : ProductLikeEntityRepository {
    override fun countByProductId(productId: Long): Long = jpa.countByProductId(productId)

    override fun countByProductIds(productIds: Collection<Long>): Map<Long, Long> =
        if (productIds.isEmpty()) emptyMap() else jpa.countByProductIds(productIds).associate { it.productId to it.likeCount }

    override fun findByUserAndProduct(userId: Long, productId: Long): ProductLikeEntity? =
        jpa.findByUserIdAndProductId(userId, productId)?.toEntity()

    override fun save(entity: ProductLikeEntity) {
        jpa.save(ProductLikeJpaEntity(entity.userId, entity.productId, entity.likedAt))
    }

    override fun deleteByUserAndProduct(userId: Long, productId: Long) {
        jpa.deleteByUserIdAndProductId(userId, productId)
    }

    override fun findActivePageByUser(userId: Long, offset: Int, limit: Int): List<ProductLikeEntity> =
        jpa.findActivePageByUser(userId, offset, limit).map(ProductLikeJpaEntity::toEntity)

    override fun countActiveByUser(userId: Long): Long = jpa.countActiveByUser(userId)
}
