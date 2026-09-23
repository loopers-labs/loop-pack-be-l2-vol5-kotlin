package com.loopers.infrastructure.brand

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class JpaBrandEntityRepository(private val jpa: BrandJpaRepository) : BrandEntityRepository {
    override fun save(entity: BrandEntity): BrandEntity {
        val target = if (entity.state.id == 0L) BrandJpaEntity(entity.name) else jpa.findByIdOrNull(entity.state.id)!!
        target.updateFrom(entity)
        return jpa.saveAndFlush(target).toEntity()
    }

    override fun findActive(id: Long): BrandEntity? = jpa.findByIdAndDeletedAtIsNull(id)?.toEntity()

    override fun findActiveByIds(ids: Collection<Long>): List<BrandEntity> =
        if (ids.isEmpty()) emptyList() else jpa.findByIdInAndDeletedAtIsNull(ids).map(BrandJpaEntity::toEntity)

    override fun findActivePage(offset: Int, limit: Int): List<BrandEntity> =
        jpa.findByDeletedAtIsNull(
            PageRequest.of(offset / limit, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
        ).content.map(BrandJpaEntity::toEntity)

    override fun countActive(): Long = jpa.countByDeletedAtIsNull()
}
