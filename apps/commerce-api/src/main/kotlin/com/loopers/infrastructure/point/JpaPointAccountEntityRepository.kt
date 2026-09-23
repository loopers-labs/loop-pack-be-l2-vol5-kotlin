package com.loopers.infrastructure.point

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class JpaPointAccountEntityRepository(private val jpa: PointAccountJpaRepository) : PointAccountEntityRepository {
    override fun findByUserId(userId: Long): PointAccountEntity? = jpa.findByIdOrNull(userId)?.toEntity()

    override fun save(entity: PointAccountEntity): PointAccountEntity {
        val target = jpa.findByIdOrNull(entity.userId)
            ?: PointAccountJpaEntity(entity.userId, entity.balance)
        target.balance = entity.balance
        return jpa.saveAndFlush(target).toEntity()
    }
}
