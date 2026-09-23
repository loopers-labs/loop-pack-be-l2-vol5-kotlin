package com.loopers.infrastructure.user

import com.loopers.domain.user.User
import com.loopers.domain.user.UserRepository
import com.loopers.domain.commerce.EntityState
import org.springframework.stereotype.Repository

@Repository
class UserRepositoryImpl(private val repository: UserEntityRepository) : UserRepository {
    override fun find(id: Long): User? = repository.find(id)?.toDomain()

    override fun save(user: User): User {
        val state = EntityState(user.id, user.createdAt, user.updatedAt)
        return repository.save(UserEntity(user.name, state)).toDomain()
    }

    private fun UserEntity.toDomain() = User.reconstitute(name, state)
}
