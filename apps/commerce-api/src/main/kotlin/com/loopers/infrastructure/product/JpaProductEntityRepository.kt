package com.loopers.infrastructure.product

import com.loopers.domain.product.ProductSort
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class JpaProductEntityRepository(private val jpa: ProductJpaRepository) : ProductEntityRepository {
    override fun save(entity: ProductEntity): ProductEntity {
        val target = if (entity.state.id == 0L) {
            ProductJpaEntity(entity.brandId, entity.name, entity.price, entity.stock)
        } else {
            jpa.findByIdOrNull(entity.state.id)!!
        }
        target.updateFrom(entity)
        return jpa.saveAndFlush(target).toEntity()
    }

    override fun saveAll(entities: Collection<ProductEntity>) {
        if (entities.isEmpty()) return
        val targets = jpa.findAllById(entities.map { it.state.id }).associateBy { it.id }
        val updated = entities.map { entity ->
            targets[entity.state.id]!!.also { it.updateFrom(entity) }
        }
        jpa.saveAll(updated)
    }

    override fun findActive(id: Long): ProductEntity? = jpa.findByIdAndDeletedAtIsNull(id)?.toEntity()

    override fun findAny(id: Long): ProductEntity? = jpa.findByIdOrNull(id)?.toEntity()

    override fun findActiveByIds(ids: Collection<Long>): List<ProductEntity> =
        jpa.findByIdInAndDeletedAtIsNull(ids).map(ProductJpaEntity::toEntity)

    override fun existsActiveByBrandId(brandId: Long): Boolean = jpa.existsByBrandIdAndDeletedAtIsNull(brandId)

    override fun findActivePage(brandId: Long?, offset: Int, limit: Int, sort: ProductSort): List<ProductEntity> {
        if (sort == ProductSort.LIKES_DESC) {
            return jpa.findActiveOrderByLikes(brandId, offset, limit).map(ProductJpaEntity::toEntity)
        }
        val ordering = if (sort == ProductSort.PRICE_ASC) {
            Sort.by(Sort.Order.asc("price"), Sort.Order.desc("id"))
        } else {
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        }
        val pageable = PageRequest.of(offset / limit, limit, ordering)
        val page = if (brandId == null) {
            jpa.findByDeletedAtIsNull(pageable)
        } else {
            jpa.findByBrandIdAndDeletedAtIsNull(brandId, pageable)
        }
        return page.content.map(ProductJpaEntity::toEntity)
    }

    override fun countActive(brandId: Long?): Long =
        if (brandId == null) jpa.countByDeletedAtIsNull() else jpa.countByBrandIdAndDeletedAtIsNull(brandId)
}
