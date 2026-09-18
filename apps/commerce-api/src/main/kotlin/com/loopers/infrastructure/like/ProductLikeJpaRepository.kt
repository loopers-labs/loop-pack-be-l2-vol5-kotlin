package com.loopers.infrastructure.like

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProductLikeJpaRepository : JpaRepository<ProductLikeJpaEntity, Long> {
    fun countByProductId(productId: Long): Long

    @Query(
        "select l.productId as productId, count(l) as likeCount from ProductLikeJpaEntity l " +
            "where l.productId in :productIds group by l.productId",
    )
    fun countByProductIds(@Param("productIds") productIds: Collection<Long>): List<ProductLikeCount>

    fun findByUserIdAndProductId(userId: Long, productId: Long): ProductLikeJpaEntity?

    @Query(
        value = "select l.* from product_likes l join products p on p.id = l.product_id " +
            "where l.user_id = :userId and p.deleted_at is null " +
            "order by l.liked_at desc, l.id desc limit :limit offset :offset",
        nativeQuery = true,
    )
    fun findActivePageByUser(
        @Param("userId") userId: Long,
        @Param("offset") offset: Int,
        @Param("limit") limit: Int,
    ): List<ProductLikeJpaEntity>

    @Query(
        value = "select count(*) from product_likes l join products p on p.id = l.product_id " +
            "where l.user_id = :userId and p.deleted_at is null",
        nativeQuery = true,
    )
    fun countActiveByUser(@Param("userId") userId: Long): Long

    fun deleteByUserIdAndProductId(userId: Long, productId: Long): Long
}

interface ProductLikeCount {
    val productId: Long
    val likeCount: Long
}
