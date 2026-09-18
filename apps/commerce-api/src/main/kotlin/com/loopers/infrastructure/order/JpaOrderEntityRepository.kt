package com.loopers.infrastructure.order

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JpaOrderEntityRepository(private val jpa: OrderJpaRepository) : OrderEntityRepository {
    override fun save(entity: OrderEntity): OrderEntity {
        val target = if (entity.state.id == 0L) {
            OrderJpaEntity(entity.userId, entity.totalAmount).also { order ->
                entity.items.forEach(order::addLine)
            }
        } else {
            jpa.findByIdOrNull(entity.state.id)!!
        }
        target.updateFrom(entity)
        return jpa.saveAndFlush(target).toEntity()
    }

    @Transactional(readOnly = true)
    override fun find(id: Long): OrderEntity? = jpa.findByIdOrNull(id)?.toEntity()

    @Transactional(readOnly = true)
    override fun findPage(userId: Long?, offset: Int, limit: Int): List<OrderEntity> {
        val pageable = PageRequest.of(offset / limit, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
        val page = if (userId == null) jpa.findAll(pageable) else jpa.findByUserId(userId, pageable)
        return page.content.map(OrderJpaEntity::toEntity)
    }

    override fun count(userId: Long?): Long = if (userId == null) jpa.count() else jpa.countByUserId(userId)
}
