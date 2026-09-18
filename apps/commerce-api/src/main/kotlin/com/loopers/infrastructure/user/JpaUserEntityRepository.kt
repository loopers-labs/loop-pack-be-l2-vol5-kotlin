package com.loopers.infrastructure.user

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class JpaUserEntityRepository(private val jpa: UserJpaRepository) : UserEntityRepository {
    override fun find(id: Long): UserEntity? = jpa.findByIdOrNull(id)?.toEntity()

    override fun save(entity: UserEntity): UserEntity {
        val target = if (entity.state.id == 0L) UserJpaEntity(entity.name) else jpa.findByIdOrNull(entity.state.id)!!
        return jpa.saveAndFlush(target).toEntity()
    }
}
