package com.loopers.infrastructure.product

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ProductJpaRepository : JpaRepository<ProductJpaEntity, Long> {
    fun findByIdAndDeletedAtIsNull(id: Long): ProductJpaEntity?

    fun findByIdInAndDeletedAtIsNull(ids: Collection<Long>): List<ProductJpaEntity>

    fun existsByBrandIdAndDeletedAtIsNull(brandId: Long): Boolean

    fun findByDeletedAtIsNull(pageable: Pageable): Page<ProductJpaEntity>

    fun findByBrandIdAndDeletedAtIsNull(brandId: Long, pageable: Pageable): Page<ProductJpaEntity>

    fun countByDeletedAtIsNull(): Long

    fun countByBrandIdAndDeletedAtIsNull(brandId: Long): Long

    @Query(
        value = "select p.* from products p left join product_likes l on l.product_id = p.id " +
            "where p.deleted_at is null and (:brandId is null or p.brand_id = :brandId) " +
            "group by p.id order by count(l.id) desc, p.id desc limit :limit offset :offset",
        nativeQuery = true,
    )
    fun findActiveOrderByLikes(
        @Param("brandId") brandId: Long?,
        @Param("offset") offset: Int,
        @Param("limit") limit: Int,
    ): List<ProductJpaEntity>
}
