package com.loopers.infrastructure.point

data class PointAccountEntity(val userId: Long, val balance: Long)

interface PointAccountEntityRepository {
    fun findByUserId(userId: Long): PointAccountEntity?

    fun save(entity: PointAccountEntity): PointAccountEntity
}
