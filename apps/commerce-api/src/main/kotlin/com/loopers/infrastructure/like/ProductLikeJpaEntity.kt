package com.loopers.infrastructure.like

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.ZonedDateTime

@Entity
@Table(
    name = "product_likes",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "product_id"])],
    indexes = [Index(columnList = "product_id"), Index(columnList = "user_id,liked_at,id")],
)
class ProductLikeJpaEntity(
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "product_id", nullable = false)
    val productId: Long,
    @Column(name = "liked_at", nullable = false)
    val likedAt: ZonedDateTime = ZonedDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    fun toEntity(): ProductLikeEntity = ProductLikeEntity(userId, productId, id, likedAt)
}
