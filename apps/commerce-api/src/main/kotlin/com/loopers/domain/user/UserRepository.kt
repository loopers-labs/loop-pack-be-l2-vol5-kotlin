package com.loopers.domain.user

interface UserRepository {
    fun find(id: Long): User?

    fun save(user: User): User
}
