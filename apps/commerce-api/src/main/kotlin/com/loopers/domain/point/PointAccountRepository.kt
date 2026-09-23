package com.loopers.domain.point

interface PointAccountRepository {
    fun findByUserId(userId: Long): PointAccount?

    fun save(account: PointAccount): PointAccount
}
