package com.loopers.infrastructure.user

import com.loopers.domain.commerce.EntityState

data class UserEntity(val name: String, val state: EntityState)

interface UserEntityRepository {
    fun find(id: Long): UserEntity?

    fun save(entity: UserEntity): UserEntity
}
